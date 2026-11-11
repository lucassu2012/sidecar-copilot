package local.sidecar.copilot.uvcadapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import local.sidecar.copilot.uvccontract.CaptureSession;
import static local.sidecar.copilot.uvccontract.UvcContract.*;
import static local.sidecar.copilot.uvcadapter.BackendPort.*;

/** Actual CaptureSession implementation against controlled Java ports only.
 * Permission, explicit wire confirmation, platform FD ownership and publication gate are external.
 * The trusted cleanup executor MUST enqueue without blocking or inline execution. No thread starts
 * at construction. Backend release/join never runs under the ledger lock or on requestStop's stack.
 */
public final class IsolatedCaptureSession implements CaptureSession {
    private final Object lock = new Object();
    private final Binding binding;
    private final ByteBudget budget;
    private final FailureSink failures;
    private final BackendPort backend;
    private final Executor cleanup;
    private final OwnedFrameBridge frames;
    private final Map<String, Transfer> transfers = new LinkedHashMap<>();
    private final Map<String, Integer> calls = new HashMap<>();
    private final List<Reservation> contextReservations = new ArrayList<>();
    private final List<Failure> retainedFailures = new ArrayList<>();
    private final ThreadLocal<Boolean> enqueueStack = new ThreadLocal<>();
    private long businessCalls, callbackRefs, nativeBytes, nextCleanupOperation;
    private long sinkExceptions;
    private boolean openAttempted, openReturned, startAttempted, backendTouched;
    private boolean admissionClosed, cleanupRequested, pumpPossible, pumpJoined = true;
    private boolean deviceClosed, deviceCloseAttempted, pumpJoinAttempted, contextReleaseAttempted;
    private boolean contextReleased, cleanupQueued, cleanupRunning, frozen;
    private WireFormat actualWire;
    private FrameSink sink;
    private StopKey stop;
    private NativeEvidence evidence;

    public IsolatedCaptureSession(Binding binding, ByteBudget budget, FailureSink failures,
                                  BackendPort backend, Executor cleanup) {
        this.binding = Objects.requireNonNull(binding); this.budget = Objects.requireNonNull(budget);
        this.failures = Objects.requireNonNull(failures); this.backend = Objects.requireNonNull(backend);
        this.cleanup = Objects.requireNonNull(cleanup);
        frames = new OwnedFrameBridge(binding, budget, this::recordFailure);
    }

    /** The supplier must itself construct bookkeeping only; it is not a native opener. */
    public static CaptureSession.Factory factory(Supplier<? extends BackendPort> ports, Executor cleanup) {
        Objects.requireNonNull(ports); Objects.requireNonNull(cleanup);
        return (binding, budget, failures) -> new IsolatedCaptureSession(binding, budget, failures, ports.get(), cleanup);
    }

    @Override public Binding binding() { return binding; }

    private void binding(OperationKey op) {
        if (op == null || !binding.equals(op.binding)) throw new IllegalArgumentException("operation binding mismatch");
    }
    private void kind(OperationKey op, CallKind kind) {
        binding(op);
        if (op.kind != kind) throw new IllegalArgumentException("operation kind mismatch");
    }
    private static String slot(OperationKey op) { return op.channel + ":" + op.operationId; }
    private static String callId(OperationKey op) { return op.kind + ":" + slot(op) + ":" + op.submissionCycle; }
    private static boolean same(OperationKey a, OperationKey b) {
        return a != null && b != null && a.binding.equals(b.binding) && a.operationId.equals(b.operationId)
            && a.submissionCycle == b.submissionCycle && a.kind == b.kind && a.channel == b.channel;
    }
    private void enterCall(OperationKey op) {
        String id = callId(op); calls.put(id, calls.getOrDefault(id, 0) + 1); businessCalls++; changed();
    }
    private void leaveCall(OperationKey op) {
        String id = callId(op); Integer n = calls.get(id);
        if (n == null || n == 0) throw new IllegalStateException("call ledger underflow");
        if (n == 1) calls.remove(id); else calls.put(id, n - 1);
        businessCalls--; changed();
    }

    @Override public OpenResult open(OperationKey op, BorrowedFd fd, WireFormat requested) {
        kind(op, CallKind.OPEN); Objects.requireNonNull(requested);
        if (fd == null || !binding.equals(fd.binding)) throw new IllegalArgumentException("FD binding mismatch");
        synchronized (lock) {
            if (openAttempted || admissionClosed) throw new IllegalStateException("open admission closed");
            enterCall(op);
            openAttempted = true; backendTouched = true; pumpPossible = true; pumpJoined = false;
        }
        OpenResult result;
        try {
            result = Objects.requireNonNull(backend.open(op, fd, requested, events));
            if (result.failure != null) requireFailure(op, result.failure);
            if (result.failure == null && result.actualWire == null) throw new IllegalStateException("missing actual wire");
        } catch (Throwable error) { result = OpenResult.failed(failure(op, error)); }
        if (result.failure != null) recordFailure(result.failure);
        synchronized (lock) {
            openReturned = true;
            if (result.failure == null) actualWire = result.actualWire;
            leaveCall(op);
        }
        schedule();
        return result;
    }

    @Override public StartResult start(OperationKey op, WireFormat confirmed, FrameSink target) {
        kind(op, CallKind.START); Objects.requireNonNull(confirmed); Objects.requireNonNull(target);
        Failure mismatch = null;
        synchronized (lock) {
            if (!openReturned || actualWire == null || startAttempted || admissionClosed)
                throw new IllegalStateException("start admission closed");
            if (!actualWire.equals(confirmed)) mismatch = new Failure(FailureKind.FORMAT, 0, op);
            else { enterCall(op); startAttempted = true; sink = target; }
        }
        if (mismatch != null) { recordFailure(mismatch); return StartResult.failed(mismatch); }
        StartResult result;
        try {
            result = Objects.requireNonNull(backend.start(op, confirmed, events));
            if (result.failure != null) requireFailure(op, result.failure);
        } catch (Throwable error) { result = StartResult.failed(failure(op, error)); }
        if (result.failure != null) recordFailure(result.failure);
        synchronized (lock) { leaveCall(op); }
        schedule();
        return result;
    }

    @Override public void requestStop(StopKey key) {
        validateStopBinding(key);
        synchronized (lock) {
            if (stop != null && !stop.equals(key)) throw new IllegalArgumentException("different canonical stop key");
            if (stop != null) return;
            stop = key; admissionClosed = true; cleanupRequested = true; changed();
        }
        frames.closeAdmission();
        schedule();
    }

    private void validateStopBinding(StopKey key) {
        if (key == null || !binding.equals(key.binding)) throw new IllegalArgumentException("stop binding mismatch");
    }
    @Override public NativeEvidence nativeEvidence(StopKey key) {
        validateStopBinding(key);
        synchronized (lock) {
            if (stop == null) throw new IllegalStateException("stop not requested");
            if (!stop.equals(key)) throw new IllegalArgumentException("different canonical stop key");
            return evidence;
        }
    }

    public List<Failure> failures() {
        synchronized (lock) { return Collections.unmodifiableList(new ArrayList<>(retainedFailures)); }
    }
    public long failureSinkExceptions() { synchronized (lock) { return sinkExceptions; } }

    private final SessionEvents events = new SessionEvents() {
        @Override public SubmitResult submit(OperationKey op, long bytes) { return submitTransfer(op, bytes); }
        @Override public Failure control(OperationKey op) { return controlCall(op); }
        @Override public void reserveNative(OperationKey op, long bytes) {
            binding(op);
            synchronized (lock) {
                if (!calls.containsKey(callId(op)) || contextReleased || frozen)
                    throw new IllegalStateException("allocation lacks an active call owner");
                Reservation reservation;
                try { reservation = budget.reserve(bytes); }
                catch (RuntimeException exhausted) { throw new BackendFailure(FailureKind.RESOURCE, 0); }
                boolean accounted = false;
                try {
                    contextReservations.add(reservation); nativeBytes += bytes; accounted = true;
                } finally {
                    // List growth can fail after reserve but before any fake allocation.
                    if (!accounted) reservation.close();
                }
                changed();
            }
        }
        @Override public void failure(Failure failure) { recordFailure(Objects.requireNonNull(failure)); }
    };

    private SubmitResult submitTransfer(OperationKey op, long bytes) {
        kind(op, CallKind.SUBMIT);
        if (op.channel == Channel.DEVICE || bytes <= 0) throw new IllegalArgumentException("invalid transfer allocation");
        Transfer registered = null;
        Failure rejected = null;
        synchronized (lock) {
            if (admissionClosed || !backendTouched || contextReleased)
                rejected = new Failure(FailureKind.PROTOCOL, 0, op);
            else {
                String transferSlot = slot(op), id = callId(op);
                Transfer prior = transfers.get(transferSlot);
                if (prior != null && !prior.released) return SubmitResult.busy();
                if (prior != null && op.submissionCycle <= prior.op.submissionCycle)
                    throw new IllegalArgumentException("stale submission cycle");
                if (calls.containsKey(id)) throw new IllegalStateException("submission call already active");
                try { registered = registerTransfer(op, bytes, transferSlot, id, prior); }
                catch (RuntimeException | Error error) { rejected = failure(op, error); }
            }
        }
        if (rejected != null) { recordFailure(rejected); return SubmitResult.rejected(rejected); }
        final Transfer transfer = registered;
        SubmitResult result;
        boolean acceptanceUnknown = false;
        try {
            result = Objects.requireNonNull(backend.submit(op, bytes, failure -> callbackEnter(transfer, failure)));
            if (result.failure != null) requireFailure(op, result.failure);
        } catch (Throwable error) {
            acceptanceUnknown = true;
            result = SubmitResult.rejected(failure(op, error));
        }
        Failure contradiction = null;
        synchronized (lock) {
            transfer.submitReturned = true; transfer.callRefs--; leaveCall(op);
            if (result.status == SubmitStatus.ACCEPTED || acceptanceUnknown) transfer.accepted = true;
            else if (transfer.terminal || transfer.callbacks != 0) {
                transfer.accepted = true;
                contradiction = new Failure(FailureKind.PROTOCOL, 0, op);
            } else {
                // The port's rejected/BUSY contract guarantees NO resource for this attempt.
                transfer.released = true; nativeBytes -= transfer.bytes; transfer.reservation.close();
            }
            changed();
        }
        if (result.failure != null) recordFailure(result.failure);
        if (contradiction != null) recordFailure(contradiction);
        schedule();
        return result;
    }

    /** Transaction before backend entry: no payload is allocated until all ledger nodes exist. */
    private Transfer registerTransfer(OperationKey op, long bytes, String transferSlot, String id, Transfer prior) {
        Reservation reservation;
        try { reservation = budget.reserve(bytes); }
        catch (RuntimeException exhausted) { throw new BackendFailure(FailureKind.RESOURCE, 0); }
        Transfer candidate = null;
        boolean committed = false;
        try {
            candidate = new Transfer(op, bytes, reservation);
            calls.put(id, 1);
            transfers.put(transferSlot, candidate);
            candidate.callRefs = 1; businessCalls++; nativeBytes += bytes; committed = true;
            changed();
            return candidate;
        } finally {
            if (!committed) {
                calls.remove(id);
                if (candidate != null && transfers.get(transferSlot) == candidate) {
                    if (prior == null) transfers.remove(transferSlot);
                    else transfers.put(transferSlot, prior); // Existing node: restore without new payload.
                }
                reservation.close();
            }
        }
    }

    private Failure controlCall(OperationKey op) {
        kind(op, CallKind.CONTROL);
        if (op.channel != Channel.CONTROL) throw new IllegalArgumentException("control channel mismatch");
        Failure rejected = null;
        synchronized (lock) {
            if (admissionClosed || !backendTouched || contextReleased)
                rejected = new Failure(FailureKind.PROTOCOL, 0, op);
            else enterCall(op);
        }
        if (rejected != null) { recordFailure(rejected); return rejected; }
        Failure result;
        try { result = backend.control(op); if (result != null) requireFailure(op, result); }
        catch (Throwable error) { result = failure(op, error); }
        if (result != null) recordFailure(result);
        synchronized (lock) { leaveCall(op); }
        schedule(); return result;
    }

    private CallbackLease callbackEnter(Transfer transfer, Failure failure) {
        if (failure != null) requireFailure(transfer.op, failure);
        final Lease lease;
        synchronized (lock) {
            if (transfer.released || transfer.terminal || frozen) throw new IllegalStateException("stale/duplicate completion");
            transfer.terminal = true; transfer.callbacks++; callbackRefs++; changed();
            lease = new Lease(transfer);
        }
        if (failure != null) recordFailure(failure);
        schedule(); return lease;
    }

    private final class Lease implements CallbackLease {
        private final Transfer transfer;
        private boolean closed;
        Lease(Transfer transfer) { this.transfer = transfer; }
        @Override public void frame(OwnedFrameBridge.BorrowedFrame borrowed) {
            final FrameSink target;
            synchronized (lock) {
                if (closed) throw new IllegalStateException("callback already exited");
                if (transfer.op.channel != Channel.IMAGE) throw new IllegalArgumentException("non-image callback");
                if (admissionClosed || sink == null) return;
                target = sink;
                // frame() and lease.close() may race. Keep the borrowed source alive until
                // copying/delivery itself has returned, independently of the outer callback lease.
                transfer.callbacks++; callbackRefs++; changed();
            }
            try {
                frames.deliver(transfer.op, borrowed, target, () -> {
                    synchronized (lock) { return !closed && !admissionClosed && sink == target; }
                });
            } finally {
                synchronized (lock) { transfer.callbacks--; callbackRefs--; changed(); }
                schedule();
            }
        }
        @Override public void close() {
            synchronized (lock) {
                if (closed) return;
                closed = true; transfer.callbacks--; callbackRefs--; changed();
            }
            schedule();
        }
    }

    private void requireFailure(OperationKey op, Failure failure) {
        if (!same(op, failure.operation)) throw new IllegalArgumentException("failure operation mismatch");
    }
    private Failure failure(OperationKey op, Throwable error) {
        if (error instanceof BackendFailure) {
            BackendFailure typed = (BackendFailure) error;
            return new Failure(typed.kind, typed.nativeCode, op);
        }
        return new Failure(error instanceof OutOfMemoryError ? FailureKind.RESOURCE : FailureKind.PROTOCOL, 0, op);
    }
    private void recordFailure(Failure failure) {
        binding(failure.operation);
        synchronized (lock) {
            retainedFailures.add(failure);
            if (!frozen) { admissionClosed = true; cleanupRequested = true; changed(); }
        }
        frames.closeAdmission();
        try { failures.accept(failure); }
        catch (Throwable receiverFailure) { synchronized (lock) { sinkExceptions++; } }
        schedule();
    }

    private void schedule() {
        synchronized (lock) {
            if (frozen || cleanupQueued || cleanupRunning) return;
            boolean releasable = false;
            for (Transfer t : transfers.values()) if (!t.released && t.terminal && t.callRefs == 0 && t.callbacks == 0) releasable = true;
            if (!cleanupRequested && !releasable) return;
            cleanupQueued = true;
        }
        enqueueStack.set(Boolean.TRUE);
        try {
            cleanup.execute(() -> {
                if (Boolean.TRUE.equals(enqueueStack.get())) {
                    // Contract-breaking executor: never run native work on the enqueue caller.
                    synchronized (lock) { cleanupQueued = false; }
                    schedulingFailure();
                    return;
                }
                cleanupLoop();
            });
        } catch (Throwable rejected) {
            synchronized (lock) { cleanupQueued = false; }
            schedulingFailure();
        } finally { enqueueStack.remove(); }
    }

    /** No recursive schedule attempt when the injected scheduler itself violates its contract. */
    private void schedulingFailure() {
        Failure failure = new Failure(FailureKind.PROTOCOL, 0,
            new OperationKey(binding, "adapter-cleanup-scheduler", 1, CallKind.CLOSE, Channel.DEVICE));
        synchronized (lock) {
            retainedFailures.add(failure);
            if (!frozen) { admissionClosed = true; cleanupRequested = true; changed(); }
        }
        frames.closeAdmission();
        try { failures.accept(failure); }
        catch (Throwable receiverFailure) { synchronized (lock) { sinkExceptions++; } }
    }

    private void cleanupLoop() {
        synchronized (lock) {
            if (cleanupRunning || frozen) { cleanupQueued = false; return; }
            cleanupQueued = false; cleanupRunning = true;
        }
        for (;;) {
            final Action action;
            synchronized (lock) {
                action = nextAction();
                if (action == null) { cleanupRunning = false; return; }
            }
            Throwable error = null;
            Failure resultFailure = null;
            try {
                switch (action.kind) {
                    case CANCEL:
                        CancelResult result = Objects.requireNonNull(backend.cancel(action.transfer.op, action.op));
                        resultFailure = result.failure;
                        if (resultFailure != null) requireFailure(action.op, resultFailure);
                        break;
                    case RELEASE: backend.releaseTransfer(action.transfer.op); break;
                    case DEVICE: backend.closeDevice(); break;
                    case PUMP: backend.stopPumpAndJoin(); break;
                    case CONTEXT: backend.releaseContext(); break;
                    default: throw new IllegalStateException("unknown cleanup action");
                }
            } catch (Throwable caught) { error = caught; }
            synchronized (lock) {
                if (action.transfer != null) action.transfer.callRefs--;
                leaveCall(action.op);
                boolean success = error == null && resultFailure == null;
                if (success) {
                    switch (action.kind) {
                        case RELEASE:
                            action.transfer.released = true; nativeBytes -= action.transfer.bytes;
                            action.transfer.reservation.close(); break;
                        case DEVICE: deviceClosed = true; break;
                        case PUMP: pumpPossible = false; pumpJoined = true; break;
                        case CONTEXT:
                            for (Reservation r : contextReservations) { nativeBytes -= r.bytes; r.close(); }
                            contextReservations.clear(); contextReleased = true; break;
                        default: break; // A cancel result never completes a transfer.
                    }
                }
                changed();
            }
            if (resultFailure != null) recordFailure(resultFailure);
            if (error != null) recordFailure(failure(action.op, error));
        }
    }

    private Action nextAction() {
        for (Transfer t : transfers.values()) {
            if (!t.released && t.submitReturned && t.accepted && t.terminal
                    && t.callbacks == 0 && t.callRefs == 0 && !t.releaseAttempted) {
                t.releaseAttempted = true; return action(ActionKind.RELEASE, t);
            }
        }
        if (!cleanupRequested) return null;
        for (Transfer t : transfers.values()) {
            if (!t.released && t.submitReturned && t.accepted && !t.terminal && !t.cancelIssued) {
                t.cancelIssued = true; return action(ActionKind.CANCEL, t);
            }
        }
        if (businessCalls != 0 || callbackRefs != 0) return null;
        for (Transfer t : transfers.values()) if (!t.released) return null;
        if (!backendTouched) { pumpJoined = true; contextReleased = true; changed(); return null; }
        if (!deviceClosed) {
            if (deviceCloseAttempted) return null;
            deviceCloseAttempted = true; return action(ActionKind.DEVICE, null);
        }
        if (!pumpJoined) {
            if (pumpJoinAttempted) return null;
            pumpJoinAttempted = true; return action(ActionKind.PUMP, null);
        }
        if (!contextReleased) {
            if (contextReleaseAttempted) return null;
            contextReleaseAttempted = true; return action(ActionKind.CONTEXT, null);
        }
        return null;
    }

    private Action action(ActionKind kind, Transfer transfer) {
        CallKind callKind = kind == ActionKind.CANCEL ? CallKind.CANCEL
            : kind == ActionKind.PUMP ? CallKind.EVENT_PUMP : CallKind.CLOSE;
        OperationKey op = new OperationKey(binding, "adapter-" + kind, ++nextCleanupOperation,
            callKind, transfer == null ? Channel.DEVICE : transfer.op.channel);
        if (transfer != null) transfer.callRefs++;
        enterCall(op); return new Action(kind, op, transfer);
    }

    /** All callers hold lock; no external code or device query occurs in evidence construction. */
    private void changed() {
        if (stop == null || frozen) return;
        long image = 0, status = 0, control = 0;
        for (Transfer t : transfers.values()) {
            if (t.released) continue;
            if (t.op.channel == Channel.IMAGE) image++;
            else if (t.op.channel == Channel.STATUS) status++;
            else if (t.op.channel == Channel.CONTROL) control++;
        }
        long nativeCalls = businessCalls + (pumpPossible ? 1 : 0);
        if (evidence != null && evidence.admissionClosed == admissionClosed && evidence.nativeCalls == nativeCalls
            && evidence.imageTransfers == image && evidence.statusTransfers == status
            && evidence.controlTransfers == control && evidence.callbacks == callbackRefs
            && evidence.nativeOwnedBytes == nativeBytes && evidence.pumpJoined == pumpJoined
            && evidence.contextReleased == contextReleased) return;
        evidence = new NativeEvidence(stop, evidence == null ? 1 : evidence.revision + 1, admissionClosed,
            nativeCalls, image, status, control, callbackRefs, nativeBytes, pumpJoined, contextReleased);
        if (evidence.drained()) frozen = true;
    }

    private static final class Transfer {
        final OperationKey op;
        final long bytes;
        final Reservation reservation;
        long callRefs, callbacks;
        boolean submitReturned, accepted, terminal, cancelIssued, releaseAttempted, released;
        Transfer(OperationKey op, long bytes, Reservation reservation) { this.op = op; this.bytes = bytes; this.reservation = reservation; }
    }
    private enum ActionKind { CANCEL, RELEASE, DEVICE, PUMP, CONTEXT }
    private static final class Action {
        final ActionKind kind;
        final OperationKey op;
        final Transfer transfer;
        Action(ActionKind kind, OperationKey op, Transfer transfer) { this.kind = kind; this.op = op; this.transfer = transfer; }
    }
}

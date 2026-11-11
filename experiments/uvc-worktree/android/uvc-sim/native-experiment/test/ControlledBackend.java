package local.sidecar.copilot.uvcadapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import local.sidecar.copilot.uvccontract.CaptureSession;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/**
 * Independent host backend ledger. It owns actual small arrays, records real Java call
 * lifetimes, and never uses adapter NativeEvidence to decide whether a release was safe.
 * There is no OS descriptor, USB, JNI, native code, or hardware.
 */
final class ControlledBackend implements BackendPort {
    private static final Runnable NOOP = new Runnable() {
        @Override public void run() {}
    };

    volatile Runnable onOpen = NOOP;
    volatile Runnable onStart = NOOP;
    volatile Runnable onSubmit = NOOP;
    volatile Runnable onCancel = NOOP;
    volatile Runnable onControl = NOOP;
    volatile Runnable onClose = NOOP;
    volatile Runnable onJoin = NOOP;
    volatile Runnable onContext = NOOP;
    volatile SubmitResult submitResult = SubmitResult.accepted();
    volatile CancelResult cancelResult = CancelResult.accepted();
    volatile Failure controlFailure;
    volatile SessionEvents events;
    volatile BorrowedFd borrowedFd;

    private final List<Attempt> attempts = new ArrayList<Attempt>();
    private final List<String> trace = new ArrayList<String>();
    private final List<String> violations = new ArrayList<String>();
    private int businessCalls;
    private int openCalls;
    private int startCalls;
    private int controlCalls;
    private int closeCalls;
    private int joinCalls;
    private int contextCalls;
    private boolean deviceClosed;
    private boolean pumpJoined;
    private boolean contextReleased;

    static final class Attempt {
        final OperationKey operation;
        final Completion completion;
        final byte[] bytes;
        int submitCalls;
        int cancelCalls;
        int callbackCalls;
        int releaseCount;
        boolean terminalSeen;
        Attempt(OperationKey operation, long count, Completion completion) {
            this.operation = operation;
            this.completion = completion;
            bytes = new byte[(int) count];
            Arrays.fill(bytes, (byte) 0x5a);
        }
    }

    private static boolean same(OperationKey a, OperationKey b) {
        return a.binding.equals(b.binding) && a.operationId.equals(b.operationId)
            && a.submissionCycle == b.submissionCycle && a.kind == b.kind && a.channel == b.channel;
    }

    private static String label(OperationKey operation) {
        return operation.operationId + "/" + operation.submissionCycle + "/" + operation.channel;
    }

    private synchronized void enter(String name) {
        businessCalls++;
        trace.add(name + ":enter");
    }

    private synchronized void exit(String name) {
        businessCalls--;
        trace.add(name + ":exit");
    }

    synchronized Attempt attempt(OperationKey operation) {
        for (Attempt candidate : attempts) if (same(candidate.operation, operation)) return candidate;
        throw new AssertionError("backend has no accepted transfer " + label(operation));
    }

    synchronized int invocations() { return trace.size(); }
    synchronized int openCalls() { return openCalls; }
    synchronized int startCalls() { return startCalls; }
    synchronized int controlCalls() { return controlCalls; }
    synchronized int closeCalls() { return closeCalls; }
    synchronized int joinCalls() { return joinCalls; }
    synchronized int contextCalls() { return contextCalls; }
    synchronized List<String> trace() { return new ArrayList<String>(trace); }
    synchronized List<String> violations() { return new ArrayList<String>(violations); }

    @Override public CaptureSession.OpenResult open(OperationKey operation, BorrowedFd fd,
            WireFormat requested, SessionEvents callbacks) {
        events = callbacks;
        borrowedFd = fd;
        synchronized (this) { openCalls++; }
        enter("open");
        try {
            onOpen.run();
            return CaptureSession.OpenResult.opened(requested);
        } finally { exit("open"); }
    }

    @Override public CaptureSession.StartResult start(OperationKey operation, WireFormat wire,
            SessionEvents callbacks) {
        events = callbacks;
        synchronized (this) { startCalls++; }
        enter("start");
        try {
            onStart.run();
            return CaptureSession.StartResult.started();
        } finally { exit("start"); }
    }

    @Override public SubmitResult submit(OperationKey operation, long reservedBytes, Completion completion) {
        final SubmitResult result = submitResult;
        final Attempt attempt;
        synchronized (this) {
            attempt = result.status == SubmitStatus.ACCEPTED
                ? new Attempt(operation, reservedBytes, completion) : null;
            if (attempt != null) {
                attempts.add(attempt);
                attempt.submitCalls++;
            }
        }
        enter("submit:" + label(operation));
        try {
            onSubmit.run();
            return result;
        } finally {
            synchronized (this) { if (attempt != null) attempt.submitCalls--; }
            exit("submit:" + label(operation));
        }
    }

    @Override public CancelResult cancel(OperationKey transfer, OperationKey cancelOperation) {
        final Attempt attempt = attempt(transfer);
        synchronized (this) { attempt.cancelCalls++; }
        enter("cancel:" + label(transfer));
        try {
            onCancel.run();
            return cancelResult;
        } finally {
            synchronized (this) { attempt.cancelCalls--; }
            exit("cancel:" + label(transfer));
        }
    }

    @Override public Failure control(OperationKey operation) {
        synchronized (this) { controlCalls++; }
        enter("control:" + label(operation));
        try {
            onControl.run();
            return controlFailure;
        } finally { exit("control:" + label(operation)); }
    }

    void terminal(OperationKey operation, Failure failure, Consumer<CallbackLease> body) {
        Attempt attempt = attempt(operation);
        synchronized (this) {
            attempt.terminalSeen = true;
            attempt.callbackCalls++;
            trace.add("callback:enter:" + label(operation));
        }
        CallbackLease lease = null;
        try {
            lease = attempt.completion.enter(failure);
            if (lease != null && body != null) body.accept(lease);
        } finally {
            synchronized (this) {
                attempt.callbackCalls--;
                trace.add("callback:exit:" + label(operation));
            }
            // This is the final callback action: all borrowed-data access has already ended.
            if (lease != null) lease.close();
        }
    }

    void terminal(OperationKey operation) { terminal(operation, null, null); }

    @Override public synchronized void releaseTransfer(OperationKey operation) {
        Attempt attempt = attempt(operation);
        if (!attempt.terminalSeen || attempt.submitCalls != 0 || attempt.cancelCalls != 0
                || attempt.callbackCalls != 0) {
            violations.add("EARLY_TRANSFER_RELEASE:" + label(operation));
        }
        if (attempt.releaseCount++ != 0) violations.add("DUPLICATE_TRANSFER_RELEASE:" + label(operation));
        Arrays.fill(attempt.bytes, (byte) 0);
        trace.add("release:" + label(operation));
    }

    private synchronized boolean activeTransfers() {
        for (Attempt attempt : attempts) if (attempt.releaseCount == 0 || attempt.submitCalls != 0
                || attempt.cancelCalls != 0 || attempt.callbackCalls != 0) return true;
        return false;
    }

    @Override public void closeDevice() {
        synchronized (this) {
            closeCalls++;
            if (businessCalls != 0 || activeTransfers()) violations.add("EARLY_DEVICE_CLOSE");
            if (closeCalls > 1) violations.add("DUPLICATE_DEVICE_CLOSE");
            trace.add("close:enter");
        }
        onClose.run();
        synchronized (this) {
            deviceClosed = true;
            trace.add("close:exit");
        }
    }

    @Override public void stopPumpAndJoin() {
        synchronized (this) {
            joinCalls++;
            if (businessCalls != 0 || activeTransfers() || !deviceClosed) violations.add("EARLY_PUMP_JOIN");
            if (joinCalls > 1) violations.add("DUPLICATE_PUMP_JOIN");
            trace.add("join:enter");
        }
        onJoin.run();
        synchronized (this) {
            pumpJoined = true;
            trace.add("join:exit");
        }
    }

    @Override public void releaseContext() {
        synchronized (this) {
            contextCalls++;
            if (businessCalls != 0 || activeTransfers() || !deviceClosed || !pumpJoined) {
                violations.add("EARLY_CONTEXT_RELEASE");
            }
            if (contextCalls > 1) violations.add("DUPLICATE_CONTEXT_RELEASE");
            trace.add("context:enter");
        }
        onContext.run();
        synchronized (this) {
            contextReleased = true;
            trace.add("context:exit");
        }
    }
}

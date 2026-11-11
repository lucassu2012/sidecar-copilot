package local.sidecar.copilot.usbowner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import local.sidecar.copilot.uvccontract.CaptureSession;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/**
 * One immutable binding, one permission request, one open attempt, one connection owner.
 * Retry/reconnect requires a new externally admitted owner. This is a Java SPI experiment,
 * not Android USB support. There are no direct Android/OS calls: platform actions are
 * delegated to the injected port, which is implemented only by Java fakes in this experiment.
 *
 * The injected CaptureSession is the trusted, binding-matched native evidence producer;
 * matching fields of an arbitrary DTO are not proof of native drain. The cleanup executor
 * must enqueue without blocking; inline execution is rejected without platform work.
 * FailureSink must obey the public bounded-metadata contract. Its exceptions are isolated.
 * No external platform/session/sink call occurs under the owner lock.
 */
public final class UsbConnectionOwner {
    private final Object lock = new Object();
    private final Binding binding;
    private final PlatformUsbPort port;
    private final CaptureSession session;
    private final CaptureSession.FailureSink failureSink;
    private final Executor cleanup;
    private final Map<BorrowLease, Boolean> leases = new IdentityHashMap<>();
    private final List<Failure> retainedFailures = new ArrayList<>();
    private final ThreadLocal<Boolean> enqueueStack = new ThreadLocal<>();
    private final OperationKey permissionOperation, openOperation, closeOperation, evidenceOperation, schedulerOperation;

    private PermissionRequest permissionRequest;
    private boolean requestInFlight, permissionConsumed, permissionGranted, permissionRequestSucceeded;
    private boolean admissionClosed, openAttempted, fdReady, openUncertain;
    private int fd = -1;
    private PlatformUsbPort.Connection connection;
    private boolean closeAttempted, connectionClosed, closeUncertain;
    private long platformCalls, openOperations, permissionCallbacks, producerCalls, nextBorrow, sinkExceptions;
    private StopKey stop;
    private NativeEvidence lastNative, nativeDrain;
    private OwnerEvidence evidence;
    private boolean nativeStopCompleted, nativeEvidenceInvalid, nativeReading;
    private boolean cleanupQueued, cleanupRunning, frozen;

    /** Bookkeeping only. binding() is a trusted, nonblocking metadata accessor. */
    public UsbConnectionOwner(Binding binding, PlatformUsbPort port, CaptureSession session,
                              CaptureSession.FailureSink failures, Executor cleanup) {
        this.binding = Objects.requireNonNull(binding); this.port = Objects.requireNonNull(port);
        this.session = Objects.requireNonNull(session); failureSink = Objects.requireNonNull(failures);
        this.cleanup = Objects.requireNonNull(cleanup);
        if (!binding.equals(session.binding())) throw new IllegalArgumentException("native producer binding mismatch");
        permissionOperation = operation("usb-owner-permission", CallKind.OPEN);
        openOperation = operation("usb-owner-open", CallKind.OPEN);
        closeOperation = operation("usb-owner-close", CallKind.CLOSE);
        evidenceOperation = operation("usb-owner-native-evidence", CallKind.CLOSE);
        schedulerOperation = operation("usb-owner-scheduler", CallKind.CLOSE);
    }

    private OperationKey operation(String name, CallKind kind) {
        return new OperationKey(binding, name, 1, kind, Channel.DEVICE);
    }
    public Binding binding() { return binding; }

    /** Register before calling the port. A synchronous callback can consume this request once. */
    public void requestPermission(PermissionRequest request) {
        if (request == null || !binding.selection.equals(request.selection))
            throw new IllegalArgumentException("permission selection mismatch");
        synchronized (lock) {
            if (admissionClosed || permissionRequest != null || openAttempted)
                throw new IllegalStateException("permission admission closed");
            permissionRequest = request; requestInFlight = true; platformCalls++; changed();
        }
        Failure failed = null;
        try { port.requestPermission(request, this::permissionResult); }
        catch (Throwable error) { failed = failure(permissionOperation, error); }
        finally {
            synchronized (lock) {
                if (failed != null) { admissionClosed = true; permissionGranted = false; }
                requestInFlight = false; permissionRequestSucceeded = failed == null;
                platformCalls--; changed();
            }
        }
        if (failed != null) recordFailure(failed);
        schedule();
    }

    private void permissionResult(PermissionRequest request, boolean granted) {
        synchronized (lock) {
            if (request == null || permissionRequest == null || permissionConsumed
                    || !permissionRequest.selection.equals(request.selection)
                    || !permissionRequest.nonce.equals(request.nonce)) return;
            permissionConsumed = true;
            // Late/duplicate grants can never reopen this owner, including after terminal evidence.
            if (admissionClosed) return;
            permissionGranted = granted; permissionCallbacks++;
            if (!granted) admissionClosed = true;
            changed();
        }
        try {
            if (!granted) recordFailure(new Failure(FailureKind.PERMISSION, 0, permissionOperation));
        } finally {
            synchronized (lock) { permissionCallbacks--; changed(); }
            schedule();
        }
    }

    public static final class OpenResult {
        public final boolean ready;
        public final Failure failure;
        private OpenResult(boolean ready, Failure failure) { this.ready = ready; this.failure = failure; }
    }

    /**
     * Blocking worker-only operation. Existing system permission may be used without a new
     * request. Grant callbacks never invoke this method. Checks occur before and after open
     * and again after getFD; the entire operation and each platform call keep separate refs.
     */
    public OpenResult open() {
        synchronized (lock) {
            if (admissionClosed || openAttempted) throw new IllegalStateException("open admission closed");
            if (permissionRequest != null && (!permissionRequestSucceeded || !permissionConsumed || !permissionGranted))
                throw new IllegalStateException("requested permission has not completed successfully");
            openAttempted = true; openOperations++; changed();
        }
        boolean ready = false;
        Failure failed = null;
        try {
            checkCurrentPermission();
            beginPlatform();
            PlatformUsbPort.Connection acquired;
            try { acquired = port.openConnection(binding); }
            catch (Throwable error) {
                synchronized (lock) { openUncertain = true; }
                throw error;
            } finally { endPlatform(); }
            if (acquired == null) throw new PlatformUsbPort.PlatformFailure(FailureKind.IO, 0);
            synchronized (lock) { connection = acquired; changed(); }
            // Even a late connection is now in this owner's ledger before any admission check.
            checkCurrentPermission();
            beginPlatform();
            int acquiredFd;
            try { acquiredFd = acquired.getFileDescriptor(); }
            finally { endPlatform(); }
            if (acquiredFd < 0) throw new PlatformUsbPort.PlatformFailure(FailureKind.IO, 0);
            checkCurrentPermission();
            synchronized (lock) {
                if (admissionClosed) throw new Halted();
                fd = acquiredFd; fdReady = true; ready = true;
            }
        } catch (Halted stopped) {
            // Stop already closed admission. Retain a late connection solely for its cleanup.
        } catch (Throwable error) {
            failed = failure(openOperation, error);
            recordFailure(failed);
        } finally {
            synchronized (lock) { openOperations--; changed(); }
            schedule();
        }
        return new OpenResult(ready, failed);
    }

    private void checkCurrentPermission() {
        beginPlatform();
        boolean current;
        try { current = port.isCurrentAttachment(binding); }
        finally { endPlatform(); }
        if (!current) throw new PlatformUsbPort.PlatformFailure(FailureKind.DETACHED, 0);
        beginPlatform();
        boolean permitted;
        try { permitted = port.hasPermission(binding); }
        finally { endPlatform(); }
        if (!permitted) throw new PlatformUsbPort.PlatformFailure(FailureKind.PERMISSION, 0);
        synchronized (lock) { if (admissionClosed) throw new Halted(); }
    }
    private void beginPlatform() {
        synchronized (lock) {
            if (admissionClosed) throw new Halted();
            platformCalls++; changed();
        }
    }
    private void endPlatform() {
        synchronized (lock) { platformCalls--; changed(); }
        schedule();
    }

    /** Capability identity, not the public BorrowedFd fields, controls exactly-once return. */
    public static final class BorrowLease implements AutoCloseable {
        public final BorrowedFd descriptor;
        private final UsbConnectionOwner owner;
        private boolean returned;
        private BorrowLease(UsbConnectionOwner owner, BorrowedFd descriptor) {
            this.owner = owner; this.descriptor = descriptor;
        }
        @Override public void close() { owner.returnBorrow(this); }
    }

    public BorrowLease borrow() {
        synchronized (lock) {
            if (admissionClosed || !fdReady || connection == null || connectionClosed)
                throw new IllegalStateException("FD borrow admission closed");
            long serial = nextBorrow + 1;
            if (serial <= 0) throw new IllegalStateException("borrow identity exhausted");
            BorrowedFd descriptor = new BorrowedFd(binding,
                binding.connectionOwnerToken + ":borrow:" + serial, fd);
            BorrowLease lease = new BorrowLease(this, descriptor);
            leases.put(lease, Boolean.TRUE); nextBorrow = serial; changed();
            return lease;
        }
    }

    public void returnBorrow(BorrowLease lease) {
        if (lease == null || lease.owner != this) throw new IllegalArgumentException("foreign borrow capability");
        synchronized (lock) {
            if (lease.returned) return;
            if (!leases.containsKey(lease)) throw new IllegalArgumentException("unknown borrow capability");
            leases.remove(lease); lease.returned = true; changed();
        }
        schedule();
    }

    /** Explicit external detach/revoke/lifecycle boundary; policy chooses when to invoke it. */
    public void invalidate(Binding eventBinding, FailureKind reason) {
        if (eventBinding == null || !binding.equals(eventBinding)) throw new IllegalArgumentException("event binding mismatch");
        Objects.requireNonNull(reason);
        recordFailure(new Failure(reason, 0, openOperation));
        // No invented StopKey. The root must revoke publication and request its canonical stop.
    }

    /**
     * No platform I/O or join. Calls the trusted session's contractually nonblocking stop,
     * outside our lock, so both owner and native admissions are closed before successful return.
     * Only platform close is deferred. Failure to honor the session contract is a trust breach.
     */
    public void requestStop(StopKey key) {
        validateStopBinding(key);
        boolean callNative;
        synchronized (lock) {
            if (stop != null && !stop.equals(key)) throw new IllegalArgumentException("different canonical stop key");
            if (stop == null) { stop = key; admissionClosed = true; changed(); }
            callNative = !nativeStopCompleted;
            if (callNative) producerCalls++;
        }
        if (callNative) {
            Failure failed = null;
            try { session.requestStop(key); }
            catch (Throwable error) { failed = failure(evidenceOperation, error); }
            synchronized (lock) {
                producerCalls--;
                if (failed == null) nativeStopCompleted = true;
                else nativeEvidenceInvalid = true;
                changed();
            }
            if (failed != null) recordFailure(failed);
        }
        schedule();
    }

    /**
     * Reads only the bound trusted producer. Explicit refresh is needed after a pending
     * producer makes progress; no polling thread or busy loop is started by this owner.
     * Committing close seals reads after a trusted terminal snapshot and zero observer refs.
     * That terminal producer contract remains a trust premise, not a DTO authentication scheme.
     */
    public void refreshNativeDrain(StopKey key) {
        validateStopBinding(key);
        synchronized (lock) {
            requireStop(key);
            if (!nativeStopCompleted) throw new IllegalStateException("native stop has not returned");
            if (frozen || closeAttempted || nativeEvidenceInvalid || nativeReading) return;
            nativeReading = true; producerCalls++;
        }
        NativeEvidence observed = null;
        Failure failed = null;
        try { observed = session.nativeEvidence(key); }
        catch (Throwable error) { failed = failure(evidenceOperation, error); }
        synchronized (lock) {
            if (failed == null && !validNative(observed)) failed = new Failure(FailureKind.PROTOCOL, 0, evidenceOperation);
            if (failed == null) {
                lastNative = observed;
                if (observed.drained()) nativeDrain = observed;
            } else nativeEvidenceInvalid = true;
            producerCalls--; nativeReading = false; changed();
        }
        if (failed != null) recordFailure(failed);
        schedule();
    }

    private boolean validNative(NativeEvidence candidate) {
        if (candidate == null || !stop.equals(candidate.key)) return false;
        if (lastNative == null) return true;
        if (candidate.revision < lastNative.revision) return false;
        if (candidate.revision == lastNative.revision || lastNative.drained()) return sameNative(candidate, lastNative);
        return true;
    }
    private static boolean sameNative(NativeEvidence a, NativeEvidence b) {
        return a.key.equals(b.key) && a.revision == b.revision && a.nativeCalls == b.nativeCalls
            && a.imageTransfers == b.imageTransfers && a.statusTransfers == b.statusTransfers
            && a.controlTransfers == b.controlTransfers && a.callbacks == b.callbacks
            && a.nativeOwnedBytes == b.nativeOwnedBytes && a.admissionClosed == b.admissionClosed
            && a.pumpJoined == b.pumpJoined && a.contextReleased == b.contextReleased;
    }

    public OwnerEvidence ownerEvidence(StopKey key) {
        validateStopBinding(key);
        synchronized (lock) { requireStop(key); return evidence; }
    }
    private void validateStopBinding(StopKey key) {
        if (key == null || !binding.equals(key.binding)) throw new IllegalArgumentException("stop binding mismatch");
    }
    private void requireStop(StopKey key) {
        if (stop == null) throw new IllegalStateException("stop not requested");
        if (!stop.equals(key)) throw new IllegalArgumentException("different canonical stop key");
    }

    public List<Failure> failures() {
        synchronized (lock) { return Collections.unmodifiableList(new ArrayList<>(retainedFailures)); }
    }
    public long failureSinkExceptions() { synchronized (lock) { return sinkExceptions; } }
    private Failure failure(OperationKey operation, Throwable error) {
        if (error instanceof PlatformUsbPort.PlatformFailure) {
            PlatformUsbPort.PlatformFailure typed = (PlatformUsbPort.PlatformFailure) error;
            return new Failure(typed.kind, typed.nativeCode, operation);
        }
        FailureKind kind = error instanceof SecurityException ? FailureKind.PERMISSION
            : error instanceof OutOfMemoryError ? FailureKind.RESOURCE : FailureKind.IO;
        return new Failure(kind, 0, operation);
    }
    private void recordFailure(Failure failure) {
        synchronized (lock) {
            retainedFailures.add(failure);
            if (!frozen) { admissionClosed = true; changed(); }
        }
        try { failureSink.accept(failure); }
        catch (Throwable receiverFailure) { synchronized (lock) { sinkExceptions++; } }
        schedule();
    }

    private void schedule() {
        synchronized (lock) {
            if (stop == null || frozen || cleanupQueued || cleanupRunning || closeAttempted) return;
            cleanupQueued = true;
        }
        enqueueStack.set(Boolean.TRUE);
        try {
            cleanup.execute(() -> {
                if (Boolean.TRUE.equals(enqueueStack.get())) {
                    synchronized (lock) { cleanupQueued = false; }
                    schedulerFailed();
                    return;
                }
                cleanupOnce();
            });
        } catch (Throwable rejected) {
            synchronized (lock) { cleanupQueued = false; }
            schedulerFailed();
        } finally { enqueueStack.remove(); }
    }

    private void schedulerFailed() {
        Failure failure = new Failure(FailureKind.PROTOCOL, 0, schedulerOperation);
        synchronized (lock) { retainedFailures.add(failure); admissionClosed = true; changed(); }
        try { failureSink.accept(failure); }
        catch (Throwable receiverFailure) { synchronized (lock) { sinkExceptions++; } }
        // Do not recursively try the executor that just violated its contract.
    }

    private void cleanupOnce() {
        StopKey key;
        boolean refresh;
        synchronized (lock) {
            if (cleanupRunning || frozen) { cleanupQueued = false; return; }
            cleanupQueued = false; cleanupRunning = true; key = stop;
            refresh = nativeStopCompleted && !nativeEvidenceInvalid;
        }
        if (refresh) refreshNativeDrain(key);
        PlatformUsbPort.Connection closing;
        synchronized (lock) {
            boolean ready = nativeDrain != null && !nativeEvidenceInvalid && nativeStopCompleted
                && platformCalls == 0 && openOperations == 0 && permissionCallbacks == 0
                && producerCalls == 0 && leases.isEmpty() && !openUncertain && !closeAttempted;
            if (!ready) { cleanupRunning = false; return; }
            closing = connection;
            if (closing == null) {
                connectionClosed = true; fdReady = false; changed(); cleanupRunning = false; return;
            }
            closeAttempted = true; platformCalls++; changed();
        }
        Failure failed = null;
        try { closing.close(); }
        catch (Throwable error) { failed = failure(closeOperation, error); }
        synchronized (lock) {
            platformCalls--;
            if (failed == null) { connectionClosed = true; fdReady = false; }
            else closeUncertain = true;
            changed(); cleanupRunning = false;
        }
        if (failed != null) recordFailure(failed);
    }

    /** OwnerEvidence contains only its public fields; platform calls remain diagnostic refs. */
    private void changed() {
        if (stop == null || frozen) return;
        long drainRevision = connectionClosed && nativeDrain != null ? nativeDrain.revision : 0;
        if (evidence != null && evidence.admissionClosed == admissionClosed && evidence.borrows == leases.size()
                && evidence.connectionClosed == connectionClosed && evidence.nativeDrainRevision == drainRevision) return;
        evidence = new OwnerEvidence(stop, evidence == null ? 1 : evidence.revision + 1,
            admissionClosed, leases.size(), connectionClosed, nativeDrain);
        if (evidence.drained()) frozen = true;
    }

    /** Diagnostics are not a replacement evidence producer or new product state machine. */
    public Snapshot snapshot() { synchronized (lock) { return new Snapshot(this); } }
    public static final class Snapshot {
        public final long platformCalls, openOperations, permissionCallbacks, nativeObserverCalls, borrows;
        public final boolean requestInFlight, permissionConsumed, permissionGranted, permissionRequestSucceeded, connectionAcquired;
        public final boolean fdReady, closeAttempted, closeUncertain, admissionClosed, openUncertain;
        public final boolean nativeStopCompleted, nativeEvidenceInvalid;
        private Snapshot(UsbConnectionOwner owner) {
            platformCalls = owner.platformCalls; openOperations = owner.openOperations;
            permissionCallbacks = owner.permissionCallbacks; nativeObserverCalls = owner.producerCalls;
            borrows = owner.leases.size(); requestInFlight = owner.requestInFlight;
            permissionConsumed = owner.permissionConsumed; permissionGranted = owner.permissionGranted;
            permissionRequestSucceeded = owner.permissionRequestSucceeded;
            connectionAcquired = owner.connection != null; fdReady = owner.fdReady;
            closeAttempted = owner.closeAttempted; closeUncertain = owner.closeUncertain;
            admissionClosed = owner.admissionClosed; openUncertain = owner.openUncertain;
            nativeStopCompleted = owner.nativeStopCompleted; nativeEvidenceInvalid = owner.nativeEvidenceInvalid;
        }
    }
    private static final class Halted extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}

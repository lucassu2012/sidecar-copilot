package local.sidecar.copilot.jnihost;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import local.sidecar.copilot.uvcadapter.BackendPort;
import local.sidecar.copilot.uvccontract.CaptureSession;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/**
 * Actual host JNI implementation of the inherited experiment port. No USB, OS FD ownership,
 * image publication, Android ABI, libusb, or libuvc. Construction is bookkeeping only.
 * All driver submissions enter the actual adapter's SessionEvents admission/ledger first.
 * The native worker never resubmits autonomously; cancel/close also close its own admission.
 * Test gates pause real native calls/stacks. Releasing a gate never fabricates a completion.
 */
public final class JniBackendPort implements BackendPort {
    public enum Gate { OPEN_RETURN, SUBMIT_RETURN, CONTROL_RETURN, CALLBACK_ENTER, CALLBACK_TAIL,
        CANCEL_RETURN, CLOSE_RETURN, JOIN_RETURN, CONTEXT_RETURN }
    public interface CallbackHook { void afterEnter(OperationKey operation); }
    private static String loadedPath;

    /** Explicit host setup only. Factory/constructor never loads a library or starts a thread. */
    public static synchronized void load(String absoluteDll) {
        Path path = Paths.get(Objects.requireNonNull(absoluteDll)).normalize();
        if (!path.isAbsolute()) throw new IllegalArgumentException("absolute DLL path required");
        String text = path.toString();
        if (loadedPath != null) {
            if (!loadedPath.equals(text)) throw new IllegalStateException("different JNI library already loaded");
            return;
        }
        System.load(text); loadedPath = text;
    }

    private final Object lock = new Object();
    private final Binding binding;
    private final OperationKey statusOperation;
    private final Map<String, TransferBridge> transfers = new HashMap<>();
    private final EnumSet<Gate> armed = EnumSet.noneOf(Gate.class);
    private SessionEvents events;
    private boolean creating, opened, autoStatus = true, cancelCompletes = true;
    private volatile long context;
    private long nextTransfer;
    private volatile CallbackHook hook = operation -> {};
    private volatile Throwable firstCallbackFailure;
    private volatile Snapshot finalSnapshot;

    public JniBackendPort(Binding binding) {
        this.binding = Objects.requireNonNull(binding);
        statusOperation = new OperationKey(binding, "jni-status", 1, CallKind.SUBMIT, Channel.STATUS);
    }
    public OperationKey statusOperation() { return statusOperation; }
    public void setAutoStatus(boolean enabled) {
        synchronized (lock) {
            if (creating || opened) throw new IllegalStateException("open already begun");
            autoStatus = enabled;
        }
    }
    public void setCancelCompletes(boolean enabled) {
        long id;
        synchronized (lock) { cancelCompletes = enabled; id = context; }
        if (id != 0) nSetCancelCompletes(id, enabled);
    }
    public void setCallbackHook(CallbackHook callback) { hook = Objects.requireNonNull(callback); }
    public Throwable firstCallbackFailure() { return firstCallbackFailure; }

    @Override public CaptureSession.OpenResult open(OperationKey operation, BorrowedFd fd,
                                                    WireFormat requested, SessionEvents callbacks) {
        require(operation, CallKind.OPEN);
        if (fd == null || !binding.equals(fd.binding)) throw new IllegalArgumentException("FD binding mismatch");
        Objects.requireNonNull(requested); Objects.requireNonNull(callbacks);
        long mask = 0;
        boolean status;
        boolean completeCancel;
        synchronized (lock) {
            if (creating || opened) throw new IllegalStateException("open already attempted");
            creating = true; events = callbacks;
            for (Gate gate : armed) mask |= 1L << gate.ordinal();
            status = autoStatus; completeCancel = cancelCompletes;
        }
        long created = nCreate(fd.fd, mask, completeCancel);
        synchronized (lock) { context = created; creating = false; opened = true; lock.notifyAll(); }
        if (status) callbacks.submit(statusOperation, 32);
        nOpen(created); // Real JNI stack can remain here while status callbacks complete or stop wins.
        return CaptureSession.OpenResult.opened(requested);
    }

    @Override public CaptureSession.StartResult start(OperationKey operation, WireFormat confirmed, SessionEvents callbacks) {
        require(operation, CallKind.START); Objects.requireNonNull(confirmed);
        int result = nStart(requireContext());
        return result == 0 ? CaptureSession.StartResult.started()
            : CaptureSession.StartResult.failed(new Failure(FailureKind.PROTOCOL, result, operation));
    }

    private static String key(OperationKey operation) {
        return operation.channel + ":" + operation.operationId + ":" + operation.submissionCycle;
    }
    private void require(OperationKey operation, CallKind kind) {
        if (operation == null || !binding.equals(operation.binding) || operation.kind != kind)
            throw new IllegalArgumentException("operation identity mismatch");
    }
    private long requireContext() {
        long id = context;
        if (id == 0) throw new IllegalStateException("native context not created");
        return id;
    }
    private SessionEvents driverEvents() {
        synchronized (lock) {
            if (events == null) throw new IllegalStateException("driver not opened");
            return events;
        }
    }
    public SubmitResult submitFromDriver(OperationKey operation, long bytes) { return driverEvents().submit(operation, bytes); }
    public Failure controlFromDriver(OperationKey operation) { return driverEvents().control(operation); }
    public void timeout(OperationKey operation) {
        require(operation, CallKind.CONTROL);
        driverEvents().failure(new Failure(FailureKind.TIMEOUT, 0, operation));
    }

    @Override public SubmitResult submit(OperationKey operation, long reservedBytes, Completion completion) {
        require(operation, CallKind.SUBMIT); Objects.requireNonNull(completion);
        TransferBridge bridge;
        synchronized (lock) {
            String key = key(operation);
            TransferBridge prior = transfers.get(key);
            if (prior != null && !prior.released) return SubmitResult.busy();
            bridge = new TransferBridge(++nextTransfer, operation, completion);
            transfers.put(key, bridge); // Publish identity before JNI can invoke completion.
        }
        int result = nSubmit(requireContext(), bridge.id, operation.channel.ordinal(), reservedBytes, bridge);
        if (result == 0) return SubmitResult.accepted();
        synchronized (lock) { bridge.released = true; }
        if (result == 1) return SubmitResult.busy();
        return SubmitResult.rejected(new Failure(result == 3 ? FailureKind.RESOURCE : FailureKind.PROTOCOL, result, operation));
    }
    private TransferBridge transfer(OperationKey operation) {
        require(operation, CallKind.SUBMIT);
        synchronized (lock) {
            TransferBridge bridge = transfers.get(key(operation));
            if (bridge == null || bridge.released) throw new IllegalStateException("unknown/retired transfer");
            return bridge;
        }
    }
    public void queueTerminal(OperationKey operation, int outcome) {
        TransferBridge transfer = transfer(operation);
        if (nQueue(requireContext(), transfer.id, outcome) != 0) throw new IllegalStateException("terminal already queued/observed");
    }
    @Override public CancelResult cancel(OperationKey operation, OperationKey cancelOperation) {
        require(cancelOperation, CallKind.CANCEL);
        int result = nCancel(requireContext(), transfer(operation).id);
        if (result == 0) return CancelResult.accepted();
        if (result == 1) return CancelResult.notFound();
        return CancelResult.failed(new Failure(FailureKind.PROTOCOL, result, cancelOperation));
    }
    @Override public Failure control(OperationKey operation) {
        require(operation, CallKind.CONTROL);
        int result = nControl(requireContext());
        return result == 0 ? null : new Failure(FailureKind.PROTOCOL, result, operation);
    }
    @Override public void releaseTransfer(OperationKey operation) {
        TransferBridge bridge = transfer(operation);
        nReleaseTransfer(requireContext(), bridge.id);
        synchronized (lock) { bridge.released = true; }
    }
    @Override public void closeDevice() { if (context != 0) nCloseDevice(context); }
    @Override public void stopPumpAndJoin() { if (context != 0) nJoin(context); }
    @Override public void releaseContext() {
        if (context != 0) finalSnapshot = new Snapshot(nReleaseContext(context));
    }

    private final class TransferBridge {
        final long id;
        final OperationKey operation;
        final Completion completion;
        boolean released;
        TransferBridge(long id, OperationKey operation, Completion completion) {
            this.id = id; this.operation = operation; this.completion = completion;
        }
        /** Called only by the attached native worker. JNI resolves this method on the Java caller. */
        @SuppressWarnings("unused")
        public void dispatch(int outcome) throws Throwable {
            CallbackLease lease = null;
            try {
                lease = completion.enter(outcome < 0 ? new Failure(FailureKind.IO, outcome, operation) : null);
                hook.afterEnter(operation);
            } catch (Throwable failure) {
                synchronized (lock) { if (firstCallbackFailure == null) firstCallbackFailure = failure; }
                driverEvents().failure(new Failure(FailureKind.PROTOCOL, 0, operation));
                throw failure;
            } finally {
                if (lease != null) lease.close();
            }
        }
    }

    public void arm(Gate gate) {
        Objects.requireNonNull(gate);
        long id;
        synchronized (lock) {
            if (creating && context == 0) throw new IllegalStateException("arm before open or after context publication");
            armed.add(gate); id = context;
        }
        if (id != 0) nArm(id, gate.ordinal());
    }
    public boolean awaitEntered(Gate gate, long timeoutMillis) {
        Objects.requireNonNull(gate);
        long id = awaitContext(timeoutMillis);
        return id != 0 && nAwaitGate(id, gate.ordinal(), timeoutMillis);
    }
    public void release(Gate gate) { nReleaseGate(requireContext(), Objects.requireNonNull(gate).ordinal()); }
    public boolean awaitReleaseWaiting(long timeoutMillis) {
        long id = awaitContext(timeoutMillis);
        return id != 0 && nAwaitReleaseWaiting(id, timeoutMillis);
    }
    private long awaitContext(long timeoutMillis) {
        if (timeoutMillis < 0 || timeoutMillis > 60000) throw new IllegalArgumentException("bounded wait required");
        long end = System.nanoTime() + timeoutMillis * 1000000L;
        synchronized (lock) {
            while (context == 0) {
                long remaining = end - System.nanoTime();
                if (remaining <= 0) return 0;
                try { lock.wait(Math.max(1L, remaining / 1000000L)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException("interrupted", interrupted);
                }
            }
            return context;
        }
    }

    public Snapshot snapshot() {
        Snapshot finished = finalSnapshot;
        if (finished != null) return finished;
        return new Snapshot(context == 0 ? new long[18] : nSnapshot(context));
    }
    public static final class Snapshot {
        public final long contextId, liveTransfers, nativeBufferBytes, globalRefs, nativeCalls, callbackRefs;
        public final boolean workerAlive;
        public final long attachedThreads, attachCount, detachCount, callbackExceptions, completedCallbacks, freedTransfers;
        public final boolean pumpJoined, contextReleased;
        public final int borrowedFd;
        public final long releaseWaiters, zeroedBuffers;
        private Snapshot(long[] value) {
            if (value == null || value.length != 18) throw new IllegalStateException("native snapshot shape");
            contextId=value[0]; liveTransfers=value[1]; nativeBufferBytes=value[2]; globalRefs=value[3]; nativeCalls=value[4]; callbackRefs=value[5];
            workerAlive=value[6]!=0; attachedThreads=value[7]; attachCount=value[8]; detachCount=value[9]; callbackExceptions=value[10];
            completedCallbacks=value[11]; freedTransfers=value[12]; pumpJoined=value[13]!=0; contextReleased=value[14]!=0;
            borrowedFd=(int)value[15]; releaseWaiters=value[16]; zeroedBuffers=value[17];
        }
    }
    public static GlobalSnapshot globalSnapshot() { return new GlobalSnapshot(nGlobalSnapshot()); }
    public static final class GlobalSnapshot {
        public final long liveContexts, globalRefs, nativeBufferBytes, attachedThreads;
        private GlobalSnapshot(long[] value) {
            if (value == null || value.length != 4) throw new IllegalStateException("global snapshot shape");
            liveContexts=value[0]; globalRefs=value[1]; nativeBufferBytes=value[2]; attachedThreads=value[3];
        }
    }

    private static native long nCreate(int borrowedFd, long gateMask, boolean cancelCompletes);
    private static native void nOpen(long context);
    private static native int nStart(long context);
    private static native int nSubmit(long context, long transfer, int channel, long bytes, Object callback);
    private static native int nQueue(long context, long transfer, int outcome);
    private static native int nCancel(long context, long transfer);
    private static native int nControl(long context);
    private static native void nReleaseTransfer(long context, long transfer);
    private static native void nCloseDevice(long context);
    private static native void nJoin(long context);
    private static native long[] nReleaseContext(long context);
    private static native void nSetCancelCompletes(long context, boolean enabled);
    private static native void nArm(long context, int gate);
    private static native boolean nAwaitGate(long context, int gate, long timeoutMillis);
    private static native void nReleaseGate(long context, int gate);
    private static native boolean nAwaitReleaseWaiting(long context, long timeoutMillis);
    private static native long[] nSnapshot(long context);
    private static native long[] nGlobalSnapshot();
}

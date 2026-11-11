package local.sidecar.copilot.usbowner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import local.sidecar.copilot.uvcadapter.BackendPort;
import local.sidecar.copilot.uvcadapter.OwnedFrameBridge;
import local.sidecar.copilot.uvccontract.CaptureSession;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/** Small test-owned backend used only by this owner integration suite, not the old adapter suite. */
final class OwnerBackend implements BackendPort {
    private final ControlledPlatform platform;
    volatile SessionEvents events;
    volatile Runnable onControl = () -> {};
    volatile Runnable onContext = () -> {};
    private int calls, callbacks, closeCalls, joinCalls, contextCalls;
    private boolean deviceClosed, pumpJoined, terminal, released;
    private Completion completion;
    private OperationKey transfer;
    private byte[] transferBytes;
    private final List<String> violations = new ArrayList<String>();

    OwnerBackend(ControlledPlatform platform) { this.platform = platform; }
    synchronized int closeCalls() { return closeCalls; }
    synchronized int contextCalls() { return contextCalls; }
    synchronized List<String> violations() { return new ArrayList<String>(violations); }
    private synchronized void enter() { calls++; }
    private synchronized void exit() { calls--; }

    @Override public CaptureSession.OpenResult open(OperationKey operation, BorrowedFd fd,
            WireFormat requested, SessionEvents receiver) {
        events = receiver;
        enter(); platform.nativeStarted();
        try { return CaptureSession.OpenResult.opened(requested); } finally { exit(); }
    }
    @Override public CaptureSession.StartResult start(OperationKey operation, WireFormat wire, SessionEvents receiver) {
        events = receiver; enter();
        try { return CaptureSession.StartResult.started(); } finally { exit(); }
    }
    @Override public SubmitResult submit(OperationKey operation, long bytes, Completion receiver) {
        enter();
        try {
            synchronized (this) {
                if (transfer != null && !released) return SubmitResult.busy();
                transfer = operation; completion = receiver; terminal = false; released = false;
                transferBytes = new byte[(int) bytes];
                Arrays.fill(transferBytes, (byte) 0x71);
            }
            return SubmitResult.accepted();
        } finally { exit(); }
    }
    void emit(OwnedFrameBridge.BorrowedFrame frame) {
        Completion receiver;
        synchronized (this) { receiver = completion; terminal = true; callbacks++; }
        CallbackLease lease = null;
        try {
            lease = receiver.enter(null);
            lease.frame(frame);
        } finally {
            synchronized (this) { callbacks--; }
            if (lease != null) lease.close();
        }
    }
    @Override public CancelResult cancel(OperationKey operation, OperationKey cancel) {
        enter();
        try { return CancelResult.notFound(); } finally { exit(); }
    }
    @Override public Failure control(OperationKey operation) {
        enter();
        try { onControl.run(); return null; } finally { exit(); }
    }
    @Override public synchronized void releaseTransfer(OperationKey operation) {
        if (calls != 0 || callbacks != 0 || !terminal) violations.add("EARLY_NATIVE_TRANSFER_RELEASE");
        if (released) violations.add("DUPLICATE_NATIVE_TRANSFER_RELEASE");
        Arrays.fill(transferBytes, (byte) 0); released = true;
    }
    private synchronized boolean liveTransfer() { return transfer != null && !released; }
    @Override public synchronized void closeDevice() {
        closeCalls++;
        if (calls != 0 || callbacks != 0 || liveTransfer()) violations.add("EARLY_NATIVE_CLOSE");
        if (closeCalls > 1) violations.add("DUPLICATE_NATIVE_CLOSE");
        deviceClosed = true;
    }
    @Override public synchronized void stopPumpAndJoin() {
        joinCalls++;
        if (!deviceClosed || calls != 0 || callbacks != 0 || liveTransfer()) violations.add("EARLY_NATIVE_JOIN");
        if (joinCalls > 1) violations.add("DUPLICATE_NATIVE_JOIN");
        pumpJoined = true;
    }
    @Override public void releaseContext() {
        synchronized (this) {
            contextCalls++;
            if (!deviceClosed || !pumpJoined || calls != 0 || callbacks != 0 || liveTransfer()) {
                violations.add("EARLY_NATIVE_CONTEXT_RELEASE");
            }
            if (contextCalls > 1) violations.add("DUPLICATE_NATIVE_CONTEXT_RELEASE");
        }
        onContext.run();
        platform.nativeDrained(); // Controlled cleanup body is done; adapter must still await call return.
    }
}

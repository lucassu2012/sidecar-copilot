package local.sidecar.copilot.usbbridge;

import java.util.Objects;
import local.sidecar.copilot.usbowner.UsbConnectionOwner;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/**
 * Explicit host hooks, not an Activity/Service lifecycle policy or native capture factory.
 * Construction, attach and grant do not open a device or start capture. The application must
 * deliberately call the worker-only user actions, and decide which lifecycle event stops it.
 * PublicationGate and the actual owner's native stop obey bounded, nonblocking contracts.
 */
public final class AndroidUsbLifecycleBridge {
    public interface PublicationGate {
        /** Revoke publication synchronously, without I/O or waiting for native/callback work. */
        void revoke(StopKey key);
    }

    private final Object lock = new Object();
    private final AndroidPlatformUsbPort port;
    private final UsbConnectionOwner owner;
    private final StopKey stop;
    private final PublicationGate publication;
    private boolean closed, stopDispatchCompleted;
    private long callbackFailures;

    public AndroidUsbLifecycleBridge(AndroidPlatformUsbPort port, UsbConnectionOwner owner,
                                     StopKey canonicalStop, PublicationGate publication) {
        this.port = Objects.requireNonNull(port); this.owner = Objects.requireNonNull(owner);
        stop = Objects.requireNonNull(canonicalStop); this.publication = Objects.requireNonNull(publication);
        if (!port.binding().equals(owner.binding()) || !stop.binding.equals(owner.binding()))
            throw new IllegalArgumentException("lifecycle binding mismatch");
        port.setPermissionFailureListener(() -> stopWithReason(null));
    }

    /** Worker-only monitor registration. The caller chooses the actual host lifecycle point. */
    public void attach() {
        requireAdmission();
        try { port.attachDetachMonitoring(() -> stopWithReason(FailureKind.DETACHED)); }
        catch (RuntimeException | Error failure) { stopWithReason(FailureKind.IO); throw failure; }
    }

    /** Explicit user action only. A grant never calls openFromUser or starts native capture. */
    public void requestPermissionFromUser(PermissionRequest request) {
        if (request == null || !owner.binding().selection.equals(request.selection))
            throw new IllegalArgumentException("permission selection mismatch");
        requireAdmission();
        owner.requestPermission(request);
        // The package-private listener also closes an async denial after this call returns.
        // Owner already retained the original typed permission failure; do not replace it.
        if (owner.snapshot().admissionClosed) stopWithReason(null);
    }

    /** Explicit worker action. Returns owner status, not permission to publish or record. */
    public UsbConnectionOwner.OpenResult openFromUser() {
        requireAdmission();
        UsbConnectionOwner.OpenResult result = owner.open();
        if (!result.ready) stopWithReason(null);
        return result;
    }

    public void stopFromUser() { stopWithReason(null); }

    /** Host policy decides when to call this; no onPause/onDestroy policy is invented here. */
    public void onLifecycleStop() { stopWithReason(null); }

    public void onPermissionRevoked(Binding eventBinding) {
        if (eventBinding == null || !owner.binding().equals(eventBinding))
            throw new IllegalArgumentException("revocation binding mismatch");
        stopWithReason(FailureKind.PERMISSION);
    }

    private void requireAdmission() {
        synchronized (lock) { if (closed) throw new IllegalStateException("lifecycle admission closed"); }
    }

    private void failedCallback() { synchronized (lock) { callbackFailures++; } }

    private void stopWithReason(FailureKind reason) {
        synchronized (lock) {
            // Reentrant gate/failure hooks must not recursively enter external callbacks.
            // A repeated return observes a claimed stop sequence, not native/full drain.
            if (closed) return;
            closed = true;
        }
        boolean publicationFailed = false;
        try { publication.revoke(stop); }
        catch (RuntimeException | Error failure) { publicationFailed = true; failedCallback(); }
        try {
            if (reason != null || publicationFailed) {
                try { owner.invalidate(stop.binding, reason != null ? reason : FailureKind.IO); }
                catch (RuntimeException | Error failure) { failedCallback(); }
            }
        } finally {
            try { owner.requestStop(stop); }
            catch (RuntimeException | Error failure) { failedCallback(); }
            finally {
                try { port.revoke(); }
                finally { synchronized (lock) { stopDispatchCompleted = true; } }
            }
        }
        // No BufferEvidence or successful full-stop claim is manufactured if a gate failed.
    }

    public Snapshot snapshot() { synchronized (lock) { return new Snapshot(closed, stopDispatchCompleted, callbackFailures); } }
    public static final class Snapshot {
        public final boolean closed, stopDispatchCompleted;
        public final long callbackFailures;
        private Snapshot(boolean closed, boolean stopDispatchCompleted, long callbackFailures) {
            this.closed = closed; this.stopDispatchCompleted = stopDispatchCompleted; this.callbackFailures = callbackFailures;
        }
    }
}

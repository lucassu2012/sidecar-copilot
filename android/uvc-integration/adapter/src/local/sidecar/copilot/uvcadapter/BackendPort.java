package local.sidecar.copilot.uvcadapter;

import java.util.Objects;
import local.sidecar.copilot.uvccontract.CaptureSession;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/** Controlled Java experiment port. No JNI, native loading, USB or OS FD operations.
 * Backend methods may block or callback before returning; callers must hold ledger references.
 * A rejected/BUSY submit has acquired no NEW resource and must not issue a callback for that attempt.
 * Existing cycles remain owned. Resource-release methods return only after their fake work ends.
 * A thrown submit has UNKNOWN acceptance; the adapter retains its obligation until an actual
 * terminal callback. A backend knowing that nothing was accepted must return explicit REJECTED.
 */
public interface BackendPort {
    CaptureSession.OpenResult open(OperationKey operation, BorrowedFd fd, WireFormat requested, SessionEvents events);
    CaptureSession.StartResult start(OperationKey operation, WireFormat confirmed, SessionEvents events);
    SubmitResult submit(OperationKey operation, long reservedBytes, Completion completion);
    CancelResult cancel(OperationKey transfer, OperationKey cancelOperation);
    Failure control(OperationKey operation);
    void releaseTransfer(OperationKey operation);
    void closeDevice();
    void stopPumpAndJoin();
    void releaseContext();

    interface SessionEvents {
        SubmitResult submit(OperationKey operation, long bytes);
        Failure control(OperationKey operation);
        /** Reserve BEFORE fake allocation. Context-owned bytes are returned only after releaseContext. */
        void reserveNative(OperationKey activeCall, long bytes);
        /** Metadata only; never implies a transfer or native call has returned. */
        void failure(Failure failure);
    }
    interface Completion {
        /** Terminal observation plus callback-stack entry, bound to this exact submitted cycle. */
        CallbackLease enter(Failure failure);
    }
    interface CallbackLease extends AutoCloseable {
        void frame(OwnedFrameBridge.BorrowedFrame frame);
        @Override void close();
    }
    enum SubmitStatus { ACCEPTED, BUSY, REJECTED }
    final class SubmitResult {
        public final SubmitStatus status;
        public final Failure failure;
        private SubmitResult(SubmitStatus status, Failure failure) { this.status = status; this.failure = failure; }
        public static SubmitResult accepted() { return new SubmitResult(SubmitStatus.ACCEPTED, null); }
        public static SubmitResult busy() { return new SubmitResult(SubmitStatus.BUSY, null); }
        public static SubmitResult rejected(Failure failure) { return new SubmitResult(SubmitStatus.REJECTED, Objects.requireNonNull(failure)); }
    }
    enum CancelStatus { ACCEPTED, NOT_FOUND, FAILED }
    final class CancelResult {
        public final CancelStatus status;
        public final Failure failure;
        private CancelResult(CancelStatus status, Failure failure) { this.status = status; this.failure = failure; }
        public static CancelResult accepted() { return new CancelResult(CancelStatus.ACCEPTED, null); }
        public static CancelResult notFound() { return new CancelResult(CancelStatus.NOT_FOUND, null); }
        public static CancelResult failed(Failure failure) { return new CancelResult(CancelStatus.FAILED, Objects.requireNonNull(failure)); }
    }
    final class BackendFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final FailureKind kind;
        public final int nativeCode;
        public BackendFailure(FailureKind kind, int nativeCode) {
            super("controlled backend failure"); this.kind = Objects.requireNonNull(kind); this.nativeCode = nativeCode;
        }
    }
}

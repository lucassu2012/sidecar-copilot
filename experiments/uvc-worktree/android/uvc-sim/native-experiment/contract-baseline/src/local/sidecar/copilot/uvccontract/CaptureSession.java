package local.sidecar.copilot.uvccontract;

import java.util.Objects;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/**
 * Interface for the isolated adapter experiment, NOT wired into the Android application.
 * Constructing a session allocates bookkeeping only. It must not open USB or start a pump.
 * Implementations must register all native call/transfer/callback references BEFORE each native call.
 */
public interface CaptureSession {
    Binding binding();

    /**
     * Blocking worker-only call. Requires matching OPEN operation and FD binding.
     * A result may be late; it always belongs to this session, never a replacement connection.
     * The platform owner holds its FD borrow until native drain, including partial-open failures.
     * Open may create status traffic; it never admits images for publication or starts recording.
     */
    OpenResult open(OperationKey operation, BorrowedFd fd, WireFormat requested);

    /**
     * Blocking worker-only call after explicit confirmation of the exact actual wire format.
     * Requires matching START operation. Sink is installed before calling native code.
     * Sink owns every delivered frame even before this call returns or when it rejects/throws.
     * Root admission must reject pre-ack frames; this return does not itself grant publication.
     */
    StartResult start(OperationKey operation, WireFormat confirmedWire, FrameSink sink);

    /**
     * Same-key idempotent. Synchronously closes native resubmission admission; schedules cleanup.
     * No blocking native call, I/O or join on this path. Root revokes publication before this call.
     * Stop can race open/start; late resources stay in this session's cleanup ledger.
     * A wrong binding or different canonical key throws IllegalArgumentException without mutation.
     * No forced free on timeout.
     */
    void requestStop(StopKey key);

    /**
     * Atomic nonblocking bookkeeping snapshot for the canonical stop key; no device calls.
     * Revisions increase only when the snapshot changes. A drained snapshot is terminal and frozen.
     * Draining includes image/status/control, callbacks, all native stacks, pump join and contexts.
     * The adapter NEVER closes the platform connection or its borrowed raw FD.
     * A wrong binding/key throws IllegalArgumentException; requesting evidence before stop throws
     * IllegalStateException. Neither error opens, stops or replaces a session.
     */
    NativeEvidence nativeEvidence(StopKey key);

    /**
     * May run on any native/event/control thread, before call return; bounded metadata delivery only.
     * No I/O, waiting for native work or reentrant device calls. Adapter isolates receiver exceptions:
     * they must not cross JNI/native boundaries or discard the original failure/cleanup obligations.
     */
    interface FailureSink { void accept(Failure failure); }

    interface Factory {
        /** Bookkeeping only. One shared byte budget covers transfers, copies, JNI and app frames. */
        CaptureSession create(Binding binding, ByteBudget budget, FailureSink failures);
    }

    final class OpenResult {
        public final WireFormat actualWire;
        public final Failure failure;
        private OpenResult(WireFormat actualWire, Failure failure) {
            this.actualWire = actualWire; this.failure = failure;
        }
        public static OpenResult opened(WireFormat actualWire) {
            return new OpenResult(Objects.requireNonNull(actualWire), null);
        }
        public static OpenResult failed(Failure failure) {
            return new OpenResult(null, Objects.requireNonNull(failure));
        }
    }
    final class StartResult {
        public final Failure failure;
        private StartResult(Failure failure) { this.failure = failure; }
        public static StartResult started() { return new StartResult(null); }
        public static StartResult failed(Failure failure) {
            return new StartResult(Objects.requireNonNull(failure));
        }
    }
}

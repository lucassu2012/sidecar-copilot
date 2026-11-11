package local.sidecar.copilot.uvcadapter;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import local.sidecar.copilot.uvccontract.CaptureSession.FailureSink;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/**
 * Copies a borrowed synthetic tight RGBA frame into the actual contract's owned frame.
 * This class has no native, Android, format-conversion, masking or publication capability.
 *
 * The caller holds its registered callback lease throughout deliver(), and keeps the
 * borrowed bytes stable until deliver() returns. Source bytes remain the caller's
 * responsibility and must be charged separately to the same ByteBudget. Only the
 * destination allocation is charged here, by OwnedRgba.allocate before allocation.
 *
 * One nonblocking slot bounds copying and sink invocation. No bridge lock is held
 * during allocation, copying, admission callbacks, sink calls or failure delivery.
 * A sink invocation admitted just before stop may enter after stop: the application's
 * publication gate must still reject/close that already registered late owned frame.
 * This bridge does not create BufferEvidence or claim that sink-owned bytes are clear.
 */
public final class OwnedFrameBridge {
    public static final String ENCODING = "RGBA";

    /** A view valid only for the duration of the enclosing borrowed callback. */
    public static final class BorrowedFrame {
        public final byte[] pixels;
        public final int offset, length, width, height, stride;
        public final String encoding;
        public final long sequence, hostReceiveMonoNs;

        /** Metadata is validated by deliver(), so malformed backend input is rejectable. */
        public BorrowedFrame(byte[] pixels, int offset, int length, int width, int height,
                             int stride, String encoding, long sequence, long hostReceiveMonoNs) {
            this.pixels = pixels;
            this.offset = offset;
            this.length = length;
            this.width = width;
            this.height = height;
            this.stride = stride;
            this.encoding = encoding;
            this.sequence = sequence;
            this.hostReceiveMonoNs = hostReceiveMonoNs;
        }
    }

    /** Package-private experiment seam, never part of the public capture contract. */
    interface CopyHook {
        void afterAllocation(OwnedRgba privateFrame);
        void afterCopy(OwnedRgba privateFrame);
    }

    private static final CopyHook NO_HOOK = new CopyHook() {
        @Override public void afterAllocation(OwnedRgba frame) {}
        @Override public void afterCopy(OwnedRgba frame) {}
    };

    /** Diagnostic bookkeeping only, not a buffer-drain or native-drain producer. */
    public static final class Snapshot {
        public final boolean admissionClosed;
        public final int activeDeliveries;
        public final long privateOwnedBytes, handoffAttempts, droppedFrames;
        public final long failureCount, failureSinkErrors, lastSequence, lastHostReceiveMonoNs;
        public final Failure firstFailure;
        public final Throwable firstFailureCause, firstFailureSinkError;

        private Snapshot(OwnedFrameBridge bridge) {
            admissionClosed = bridge.closed;
            activeDeliveries = bridge.active ? 1 : 0;
            privateOwnedBytes = bridge.privateOwnedBytes;
            handoffAttempts = bridge.handoffAttempts;
            droppedFrames = bridge.droppedFrames;
            failureCount = bridge.failureCount;
            failureSinkErrors = bridge.failureSinkErrors;
            lastSequence = bridge.lastSequence;
            lastHostReceiveMonoNs = bridge.lastTime;
            firstFailure = bridge.firstFailure;
            firstFailureCause = bridge.firstFailureCause;
            firstFailureSinkError = bridge.firstFailureSinkError;
        }
    }

    private final Object lock = new Object();
    private final Binding binding;
    private final ByteBudget budget;
    private final FailureSink failures;
    private final CopyHook hook;
    private boolean closed, active;
    private long privateOwnedBytes, handoffAttempts, droppedFrames, failureCount, failureSinkErrors;
    private long lastSequence = -1, lastTime = -1;
    private Failure firstFailure;
    private Throwable firstFailureCause, firstFailureSinkError;

    public OwnedFrameBridge(Binding binding, ByteBudget budget, FailureSink failures) {
        this(binding, budget, failures, NO_HOOK);
    }

    /** Hooks must not close or retain frames; tests may instrument private cleanup deliberately. */
    OwnedFrameBridge(Binding binding, ByteBudget budget, FailureSink failures, CopyHook hook) {
        this.binding = Objects.requireNonNull(binding);
        this.budget = Objects.requireNonNull(budget);
        this.failures = Objects.requireNonNull(failures);
        this.hook = Objects.requireNonNull(hook);
    }

    /** Closes local admission without waiting for a copy, sink, native work or callback. */
    public void closeAdmission() {
        synchronized (lock) { closed = true; }
    }

    public Snapshot snapshot() {
        synchronized (lock) { return new Snapshot(this); }
    }

    /**
     * The operation must identify this session's IMAGE/SUBMIT callback. Invalid keys
     * are caller misuse: reject without mutating this bridge or reporting a failure
     * against a fabricated/wrong operation. The session validates keys before entry.
     * All processing failures for a valid key are typed and isolated below.
     */
    public void deliver(OperationKey operation, BorrowedFrame source, FrameSink sink,
                        BooleanSupplier admission) {
        if (operation == null || !binding.equals(operation.binding)
                || operation.kind != CallKind.SUBMIT || operation.channel != Channel.IMAGE) {
            throw new IllegalArgumentException("frame operation must match IMAGE/SUBMIT binding");
        }
        if (source == null || !validLayout(source)) {
            reject(operation, FailureKind.FORMAT, null);
            return;
        }
        if (source.sequence < 0 || source.hostReceiveMonoNs < 0 || sink == null || admission == null) {
            reject(operation, FailureKind.PROTOCOL, null);
            return;
        }

        FailureKind rejected = null;
        synchronized (lock) {
            if (closed) { droppedFrames++; return; }
            if (source.sequence <= lastSequence || source.hostReceiveMonoNs < lastTime) {
                rejected = FailureKind.PROTOCOL;
            } else if (active) {
                rejected = FailureKind.RESOURCE;
            } else {
                active = true;
            }
        }
        if (rejected != null) {
            reject(operation, rejected, null);
            return;
        }

        OwnedRgba privateFrame = null;
        boolean handedOff = false;
        try {
            if (!admission.getAsBoolean() || admissionClosed()) {
                dropped();
                return;
            }
            try {
                // The actual contract reserves LENGTH before allocating its byte array.
                privateFrame = OwnedRgba.allocate(budget, binding, source.sequence, source.hostReceiveMonoNs);
            } catch (IllegalStateException exhausted) {
                reject(operation, FailureKind.RESOURCE, exhausted);
                return;
            }
            synchronized (lock) { privateOwnedBytes = OwnedRgba.LENGTH; }
            hook.afterAllocation(privateFrame);
            System.arraycopy(source.pixels, source.offset, privateFrame.pixelsForOwner(), 0, OwnedRgba.LENGTH);
            hook.afterCopy(privateFrame);

            // The admission callback may acquire the core's bookkeeping lock, so it
            // must execute without our lock. It does not grant publication authority.
            if (!admission.getAsBoolean()) {
                dropped();
                return;
            }
            synchronized (lock) {
                if (closed) { droppedFrames++; return; }
                lastSequence = source.sequence;
                lastTime = source.hostReceiveMonoNs;
                privateOwnedBytes = 0;
                handoffAttempts++;
            }

            OwnedRgba transferred = privateFrame;
            privateFrame = null;
            handedOff = true;
            // No throwing hook/check is inserted between relinquishment and entry.
            // accept() owns the frame on entry, including rejection or exception.
            sink.accept(transferred);
        } catch (OutOfMemoryError failure) {
            if (!handedOff) dropped();
            report(operation, FailureKind.RESOURCE, failure);
        } catch (RuntimeException | Error failure) {
            if (!handedOff) dropped();
            report(operation, handedOff ? FailureKind.IO : FailureKind.PROTOCOL, failure);
        } finally {
            try {
                if (privateFrame != null) {
                    // A rejected or failed private copy is still our exclusive property.
                    privateFrame.close();
                    synchronized (lock) { privateOwnedBytes = 0; }
                }
            } finally {
                synchronized (lock) { active = false; }
            }
        }
    }

    private static boolean validLayout(BorrowedFrame source) {
        return source.pixels != null && ENCODING.equals(source.encoding)
            && source.width == OwnedRgba.WIDTH && source.height == OwnedRgba.HEIGHT
            && source.stride == OwnedRgba.STRIDE && source.length == OwnedRgba.LENGTH
            && source.offset >= 0 && source.offset <= source.pixels.length - OwnedRgba.LENGTH;
    }

    private boolean admissionClosed() {
        synchronized (lock) { return closed; }
    }

    private void dropped() {
        synchronized (lock) { droppedFrames++; }
    }

    private void reject(OperationKey operation, FailureKind kind, Throwable cause) {
        dropped();
        report(operation, kind, cause);
    }

    private void report(OperationKey operation, FailureKind kind, Throwable cause) {
        // No native call occurred: zero is deliberately not an invented libusb code.
        Failure failure = new Failure(kind, 0, operation);
        synchronized (lock) {
            failureCount++;
            if (firstFailure == null) {
                firstFailure = failure;
                firstFailureCause = cause;
            }
        }
        try {
            failures.accept(failure);
        } catch (RuntimeException | Error notificationFailure) {
            synchronized (lock) {
                failureSinkErrors++;
                if (firstFailureSinkError == null) firstFailureSinkError = notificationFailure;
            }
        }
    }
}

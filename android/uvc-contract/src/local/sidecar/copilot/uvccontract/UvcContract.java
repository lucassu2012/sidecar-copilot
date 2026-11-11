package local.sidecar.copilot.uvccontract;

import java.util.Arrays;
import java.util.Objects;

/** Experimental boundary only. No Android, JNI, driver or permission implementation. */
public final class UvcContract {
    private UvcContract() {}

    private static String token(String value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("empty identity");
        return value;
    }
    private static long positive(long value) {
        if (value <= 0) throw new IllegalArgumentException("non-positive identity");
        return value;
    }
    static long count(long value) {
        if (value < 0) throw new IllegalArgumentException("negative count");
        return value;
    }

    /** Platform permission is an observation; this identity is not an authorization grant. */
    public static final class Selection {
        public final String sessionId, selectionToken, bootId;
        public final long attachmentEpoch, permissionRevision;
        public Selection(String sessionId, String selectionToken, String bootId,
                         long attachmentEpoch, long permissionRevision) {
            this.sessionId = token(sessionId); this.selectionToken = token(selectionToken);
            this.bootId = token(bootId); this.attachmentEpoch = positive(attachmentEpoch);
            this.permissionRevision = count(permissionRevision);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Selection)) return false;
            Selection s = (Selection) other;
            return sessionId.equals(s.sessionId) && selectionToken.equals(s.selectionToken)
                && bootId.equals(s.bootId) && attachmentEpoch == s.attachmentEpoch
                && permissionRevision == s.permissionRevision;
        }
        @Override public int hashCode() {
            return Objects.hash(sessionId, selectionToken, bootId, attachmentEpoch, permissionRevision);
        }
    }

    /** Register before requestPermission; consume at most once under the platform admission lock. */
    public static final class PermissionRequest {
        public final Selection selection;
        public final String nonce;
        public PermissionRequest(Selection selection, String nonce) {
            this.selection = Objects.requireNonNull(selection); this.nonce = token(nonce);
        }
    }

    /** Allocate before opening. A late open retains this binding solely for its own cleanup. */
    public static final class Binding {
        public final Selection selection;
        public final String connectionOwnerToken;
        public final long connectionGeneration, runGeneration;
        public Binding(Selection selection, String connectionOwnerToken,
                       long connectionGeneration, long runGeneration) {
            this.selection = Objects.requireNonNull(selection);
            this.connectionOwnerToken = token(connectionOwnerToken);
            this.connectionGeneration = positive(connectionGeneration);
            this.runGeneration = positive(runGeneration);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Binding)) return false;
            Binding b = (Binding) other;
            return selection.equals(b.selection) && connectionOwnerToken.equals(b.connectionOwnerToken)
                && connectionGeneration == b.connectionGeneration && runGeneration == b.runGeneration;
        }
        @Override public int hashCode() {
            return Objects.hash(selection, connectionOwnerToken, connectionGeneration, runGeneration);
        }
    }

    public enum CallKind { OPEN, START, SUBMIT, CANCEL, CONTROL, CLOSE, EVENT_PUMP }
    public enum Channel { DEVICE, IMAGE, STATUS, CONTROL }
    public static final class OperationKey {
        public final Binding binding;
        public final String operationId;
        public final long submissionCycle;
        public final CallKind kind;
        public final Channel channel;
        public OperationKey(Binding binding, String operationId, long submissionCycle,
                            CallKind kind, Channel channel) {
            this.binding = Objects.requireNonNull(binding); this.operationId = token(operationId);
            this.submissionCycle = positive(submissionCycle);
            this.kind = Objects.requireNonNull(kind); this.channel = Objects.requireNonNull(channel);
        }
    }

    /** Native borrows only. No close API: the platform connection remains the sole FD owner. */
    public static final class BorrowedFd {
        public final Binding binding;
        public final String borrowToken;
        public final int fd;
        public BorrowedFd(Binding binding, String borrowToken, int fd) {
            this.binding = Objects.requireNonNull(binding); this.borrowToken = token(borrowToken);
            if (fd < 0) throw new IllegalArgumentException("invalid FD");
            this.fd = fd;
        }
    }

    /** Exact negotiated wire mode; distinct from the experimental owned RGBA sink. */
    public static final class WireFormat {
        public final String encoding;
        public final int width, height;
        public final long interval100ns;
        public WireFormat(String encoding, int width, int height, long interval100ns) {
            this.encoding = token(encoding);
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("invalid dimensions");
            this.width = width; this.height = height; this.interval100ns = positive(interval100ns);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof WireFormat)) return false;
            WireFormat f = (WireFormat) other;
            return encoding.equals(f.encoding) && width == f.width && height == f.height
                && interval100ns == f.interval100ns;
        }
        @Override public int hashCode() { return Objects.hash(encoding, width, height, interval100ns); }
    }

    public enum FailureKind { PERMISSION, DETACHED, FORMAT, RESOURCE, IO, PROTOCOL, TIMEOUT }
    public enum ExistingReason {
        PERMISSION_REVOKED, SOURCE_LOST, FORMAT_CHANGED, RESOURCE_PRESSURE, SOURCE_FAILED
    }
    public static final class Failure {
        public final FailureKind kind;
        public final int nativeCode;
        public final OperationKey operation;
        public Failure(FailureKind kind, int nativeCode, OperationKey operation) {
            this.kind = Objects.requireNonNull(kind); this.nativeCode = nativeCode;
            this.operation = Objects.requireNonNull(operation);
        }
        public ExistingReason existingReason() {
            switch (kind) {
                case PERMISSION: return ExistingReason.PERMISSION_REVOKED;
                case DETACHED: return ExistingReason.SOURCE_LOST;
                case FORMAT: return ExistingReason.FORMAT_CHANGED;
                case RESOURCE: return ExistingReason.RESOURCE_PRESSURE;
                default: return ExistingReason.SOURCE_FAILED;
            }
        }
    }

    /** Shared experiment accounting, also for transfer/source/JNI bytes outside OwnedRgba. */
    public static final class ByteBudget {
        public final long limit;
        private long used;
        public ByteBudget(long limit) { this.limit = positive(limit); }
        public synchronized Reservation reserve(long bytes) {
            positive(bytes);
            if (bytes > limit - used) throw new IllegalStateException("experiment byte budget exhausted");
            Reservation reservation = new Reservation(this, bytes);
            used += bytes;
            return reservation;
        }
        public synchronized long used() { return used; }
        private synchronized void release(long bytes) { used -= bytes; }
    }
    public static final class Reservation implements AutoCloseable {
        public final long bytes;
        private ByteBudget owner;
        private Reservation(ByteBudget owner, long bytes) { this.owner = owner; this.bytes = bytes; }
        @Override public synchronized void close() {
            if (owner != null) { owner.release(bytes); owner = null; }
        }
    }

    /** Exclusive ownership, not a shared view. No copies or references may survive handoff/close. */
    public static final class OwnedRgba implements AutoCloseable {
        public static final int WIDTH = 640, HEIGHT = 360, STRIDE = WIDTH * 4, LENGTH = STRIDE * HEIGHT;
        public final Binding binding;
        public final long sequence, hostReceiveMonoNs;
        private final Reservation reservation;
        private byte[] pixels;
        private OwnedRgba(Binding binding, long sequence, long time, Reservation reservation, byte[] pixels) {
            this.binding = binding; this.sequence = sequence; hostReceiveMonoNs = time;
            this.reservation = reservation; this.pixels = pixels;
        }
        public static OwnedRgba allocate(ByteBudget budget, Binding binding, long sequence, long time) {
            Objects.requireNonNull(binding); count(sequence); count(time);
            Reservation reservation = Objects.requireNonNull(budget).reserve(LENGTH);
            try { return new OwnedRgba(binding, sequence, time, reservation, new byte[LENGTH]); }
            catch (RuntimeException | Error failure) { reservation.close(); throw failure; }
        }
        /** Only the current exclusive owner may call; it must never race close or retain aliases. */
        public synchronized byte[] pixelsForOwner() {
            if (pixels == null) throw new IllegalStateException("frame already released");
            return pixels;
        }
        @Override public synchronized void close() {
            if (pixels != null) { Arrays.fill(pixels, (byte) 0); pixels = null; reservation.close(); }
        }
    }
    public interface FrameSink {
        /** Ownership passes on entry, even for rejection/exception. Sink must eventually close frame. */
        void accept(OwnedRgba frame);
    }

    public static final class StopKey {
        public final Binding binding;
        public final String requestId;
        public StopKey(Binding binding, String requestId) {
            this.binding = Objects.requireNonNull(binding); this.requestId = token(requestId);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof StopKey)) return false;
            StopKey s = (StopKey) other;
            return binding.equals(s.binding) && requestId.equals(s.requestId);
        }
        @Override public int hashCode() { return Objects.hash(binding, requestId); }
    }

    /** One producer's atomic snapshot; all counters are per binding, not global totals. */
    public static final class NativeEvidence {
        public final StopKey key;
        public final long revision, nativeCalls, imageTransfers, statusTransfers, controlTransfers, callbacks, nativeOwnedBytes;
        public final boolean admissionClosed, pumpJoined, contextReleased;
        public NativeEvidence(StopKey key, long revision, boolean admissionClosed, long nativeCalls,
                              long imageTransfers, long statusTransfers, long controlTransfers, long callbacks, long nativeOwnedBytes,
                              boolean pumpJoined, boolean contextReleased) {
            this.key = Objects.requireNonNull(key); this.revision = positive(revision);
            this.admissionClosed = admissionClosed; this.nativeCalls = count(nativeCalls);
            this.imageTransfers = count(imageTransfers); this.statusTransfers = count(statusTransfers);
            this.controlTransfers = count(controlTransfers); this.callbacks = count(callbacks);
            this.nativeOwnedBytes = count(nativeOwnedBytes);
            this.pumpJoined = pumpJoined; this.contextReleased = contextReleased;
            if (contextReleased && !drained()) throw new IllegalArgumentException("context released before drain");
        }
        public boolean drained() {
            return admissionClosed && nativeCalls == 0 && imageTransfers == 0 && statusTransfers == 0
                && controlTransfers == 0 && callbacks == 0 && nativeOwnedBytes == 0 && pumpJoined && contextReleased;
        }
        boolean sameSnapshot(NativeEvidence other) {
            return key.equals(other.key) && revision == other.revision && admissionClosed == other.admissionClosed
                && nativeCalls == other.nativeCalls && imageTransfers == other.imageTransfers
                && statusTransfers == other.statusTransfers && controlTransfers == other.controlTransfers
                && callbacks == other.callbacks && nativeOwnedBytes == other.nativeOwnedBytes
                && pumpJoined == other.pumpJoined && contextReleased == other.contextReleased;
        }
    }
    public static final class OwnerEvidence {
        public final StopKey key;
        public final long revision, borrows, nativeDrainRevision;
        public final boolean admissionClosed, connectionClosed;
        public OwnerEvidence(StopKey key, long revision, boolean admissionClosed, long borrows,
                             boolean connectionClosed, NativeEvidence nativeDrain) {
            this.key = Objects.requireNonNull(key); this.revision = positive(revision);
            this.admissionClosed = admissionClosed; this.borrows = count(borrows);
            this.connectionClosed = connectionClosed;
            if (connectionClosed && (!admissionClosed || borrows != 0 || nativeDrain == null
                    || !nativeDrain.key.equals(key) || !nativeDrain.drained()))
                throw new IllegalArgumentException("owner close without matching native drain");
            nativeDrainRevision = connectionClosed ? nativeDrain.revision : 0;
        }
        public boolean drained() { return admissionClosed && borrows == 0 && connectionClosed; }
        boolean sameSnapshot(OwnerEvidence other) {
            return key.equals(other.key) && revision == other.revision && admissionClosed == other.admissionClosed
                && borrows == other.borrows && connectionClosed == other.connectionClosed
                && nativeDrainRevision == other.nativeDrainRevision;
        }
    }
    public static final class BufferEvidence {
        public final StopKey key;
        public final long revision, queuedBytes, inFlightBytes, previewBytes;
        public final boolean publicationRevoked, admissionClosed;
        public BufferEvidence(StopKey key, long revision, boolean publicationRevoked, boolean admissionClosed,
                              long queuedBytes, long inFlightBytes, long previewBytes) {
            this.key = Objects.requireNonNull(key); this.revision = positive(revision);
            this.publicationRevoked = publicationRevoked; this.admissionClosed = admissionClosed;
            this.queuedBytes = count(queuedBytes); this.inFlightBytes = count(inFlightBytes);
            this.previewBytes = count(previewBytes);
        }
        public boolean drained() {
            return publicationRevoked && admissionClosed && queuedBytes == 0 && inFlightBytes == 0 && previewBytes == 0;
        }
        boolean sameSnapshot(BufferEvidence other) {
            return key.equals(other.key) && revision == other.revision && publicationRevoked == other.publicationRevoked
                && admissionClosed == other.admissionClosed && queuedBytes == other.queuedBytes
                && inFlightBytes == other.inFlightBytes && previewBytes == other.previewBytes;
        }
    }
}

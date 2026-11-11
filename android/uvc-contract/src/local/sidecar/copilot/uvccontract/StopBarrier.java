package local.sidecar.copilot.uvccontract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/** Joins trusted producer evidence, not a substitute for a native lifetime ledger or publication gate. */
public final class StopBarrier {
    public final StopKey key;
    private NativeEvidence nativeEvidence;
    private OwnerEvidence ownerEvidence;
    private BufferEvidence bufferEvidence;
    private boolean protocolViolation;
    private final List<Failure> failures = new ArrayList<>();
    public StopBarrier(StopKey key) { this.key = Objects.requireNonNull(key); }

    public synchronized boolean update(NativeEvidence evidence) {
        Objects.requireNonNull(evidence);
        if (!key.equals(evidence.key)) return false;
        if (nativeEvidence != null && evidence.revision <= nativeEvidence.revision) {
            if (evidence.revision == nativeEvidence.revision && !nativeEvidence.sameSnapshot(evidence))
                protocolViolation = true;
            return false;
        }
        if (nativeEvidence != null && nativeEvidence.drained()) {
            // A terminal producer freezes its evidence: no reopening or later final revision.
            protocolViolation = true; return false;
        }
        nativeEvidence = evidence; return true;
    }
    public synchronized boolean update(OwnerEvidence evidence) {
        Objects.requireNonNull(evidence);
        if (!key.equals(evidence.key)) return false;
        if (ownerEvidence != null && evidence.revision <= ownerEvidence.revision) {
            if (evidence.revision == ownerEvidence.revision && !ownerEvidence.sameSnapshot(evidence))
                protocolViolation = true;
            return false;
        }
        if (ownerEvidence != null && ownerEvidence.drained()) { protocolViolation = true; return false; }
        ownerEvidence = evidence; return true;
    }
    public synchronized boolean update(BufferEvidence evidence) {
        Objects.requireNonNull(evidence);
        if (!key.equals(evidence.key)) return false;
        if (bufferEvidence != null && evidence.revision <= bufferEvidence.revision) {
            if (evidence.revision == bufferEvidence.revision && !bufferEvidence.sameSnapshot(evidence))
                protocolViolation = true;
            return false;
        }
        if (bufferEvidence != null && bufferEvidence.drained()) { protocolViolation = true; return false; }
        bufferEvidence = evidence; return true;
    }
    /** Preserve failures separately from safe drain; timeout never supplies cleanup evidence. */
    public synchronized boolean recordFailure(Failure failure) {
        Objects.requireNonNull(failure);
        if (!key.binding.equals(failure.operation.binding)) return false;
        failures.add(failure); return true;
    }
    public synchronized Receipt snapshot() {
        boolean drained = !protocolViolation && nativeEvidence != null && nativeEvidence.drained()
            && ownerEvidence != null && ownerEvidence.drained()
            && ownerEvidence.nativeDrainRevision == nativeEvidence.revision
            && bufferEvidence != null && bufferEvidence.drained();
        return new Receipt(key, drained, protocolViolation, failures);
    }
    public static final class Receipt {
        public final StopKey key;
        /** Safe disposal evidence only; errors remain visible and this does not reopen capture. */
        public final boolean drained, protocolViolation;
        public final List<Failure> failures;
        private Receipt(StopKey key, boolean drained, boolean violation, List<Failure> failures) {
            this.key = key; this.drained = drained; protocolViolation = violation;
            this.failures = Collections.unmodifiableList(new ArrayList<>(failures));
        }
    }
}

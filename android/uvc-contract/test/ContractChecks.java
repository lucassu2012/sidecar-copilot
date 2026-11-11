package local.sidecar.copilot.uvccontract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/** Offline contract tests. Evidence objects are trusted-producer fixtures, not native or Android proof. */
public final class ContractChecks {
    private static int checks;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++;
    }
    private static void rejected(Class<? extends Throwable> type, Checked action, String label) throws Exception {
        try { action.run(); }
        catch (Throwable failure) {
            if (!type.isInstance(failure)) throw new AssertionError(label + ": wrong failure", failure);
            checks++; return;
        }
        throw new AssertionError(label + ": unexpectedly accepted");
    }
    private static Selection selection() { return new Selection("session", "selection", "boot", 1, 2); }
    private static Binding binding() { return new Binding(selection(), "owner", 3, 4); }
    private static StopKey key() { return new StopKey(binding(), "stop"); }
    private static NativeEvidence nativeDone(StopKey key, long revision) {
        return new NativeEvidence(key, revision, true, 0, 0, 0, 0, 0, 0, true, true);
    }
    private static OwnerEvidence ownerDone(StopKey key, long revision, NativeEvidence nativeDrain) {
        return new OwnerEvidence(key, revision, true, 0, true, nativeDrain);
    }
    private static BufferEvidence buffersDone(StopKey key, long revision) {
        return new BufferEvidence(key, revision, true, true, 0, 0, 0);
    }
    private static Failure failure(Binding binding, FailureKind kind, int code) {
        return new Failure(kind, code, new OperationKey(binding, "operation", 1, CallKind.CANCEL, Channel.STATUS));
    }
    private static void completed(StopBarrier barrier, NativeEvidence nativeDrain) {
        check(barrier.update(nativeDrain), "native terminal accepted");
        check(barrier.update(ownerDone(barrier.key, 1, nativeDrain)), "owner terminal accepted");
        check(barrier.update(buffersDone(barrier.key, 1)), "buffer terminal accepted");
        check(barrier.snapshot().drained, "all matching terminal branches combine");
    }

    private static void identitiesAndFdReuse() throws Exception {
        Binding original = binding();
        Binding equivalent = new Binding(new Selection("session", "selection", "boot", 1, 2), "owner", 3, 4);
        check(original.equals(equivalent), "independent equal binding values match");
        check(original.hashCode() == equivalent.hashCode(), "equal bindings hash equally");
        List<Binding> other = Arrays.asList(
            new Binding(selection(), "different-owner", 3, 4),
            new Binding(new Selection("session", "selection", "different-boot", 1, 2), "owner", 3, 4),
            new Binding(selection(), "owner", 3, 5),
            new Binding(selection(), "owner", 4, 4),
            new Binding(new Selection("session", "selection", "boot", 2, 2), "owner", 3, 4),
            new Binding(new Selection("session", "selection", "boot", 1, 3), "owner", 3, 4),
            new Binding(new Selection("session", "different-selection", "boot", 1, 2), "owner", 3, 4),
            new Binding(new Selection("different-session", "selection", "boot", 1, 2), "owner", 3, 4));
        List<StopKey> wrongKeys = new ArrayList<>();
        Set<Binding> distinct = new HashSet<>(); distinct.add(original); distinct.add(equivalent);
        BorrowedFd firstFd = new BorrowedFd(original, "borrow", 0);
        check(firstFd.fd == 0, "FD zero is accepted");
        for (Binding different : other) {
            distinct.add(different);
            BorrowedFd reused = new BorrowedFd(different, "borrow", 0);
            check(reused.fd == firstFd.fd && !reused.binding.equals(firstFd.binding), "reused FD cannot replace owner identity");
            wrongKeys.add(new StopKey(different, "stop"));
        }
        check(distinct.size() == 9, "all binding components distinguish generations");
        wrongKeys.add(new StopKey(original, "different-stop"));
        StopKey target = new StopKey(original, "stop");
        check(target.equals(new StopKey(equivalent, "stop")), "stop key compares full binding values");
        for (StopKey wrong : wrongKeys) {
            StopBarrier barrier = new StopBarrier(target);
            NativeEvidence alien = nativeDone(wrong, 1);
            check(!barrier.update(alien), "foreign native evidence rejected");
            check(!barrier.update(ownerDone(wrong, 1, alien)), "foreign owner evidence rejected");
            check(!barrier.update(buffersDone(wrong, 1)), "foreign buffer evidence rejected");
            check(!barrier.snapshot().drained, "foreign evidence cannot complete stop");
            check(!barrier.snapshot().protocolViolation, "foreign evidence does not poison current stop");
            if (!wrong.binding.equals(original))
                check(!barrier.recordFailure(failure(wrong.binding, FailureKind.IO, -1)), "foreign run failure rejected");
            completed(barrier, nativeDone(target, 1));
        }
        rejected(IllegalArgumentException.class, () -> new BorrowedFd(original, "borrow", -1), "negative FD rejected");
        rejected(IllegalArgumentException.class, () -> new Selection("session", "", "boot", 1, 0), "missing selection identity rejected");
        rejected(IllegalArgumentException.class, () -> new Binding(selection(), "owner", 0, 1), "zero connection generation rejected");
    }

    private static void incompleteBranches() throws Exception {
        StopKey key = key(); NativeEvidence finalNative = nativeDone(key, 2);
        NativeEvidence[] pending = {
            new NativeEvidence(key, 1, false, 0, 0, 0, 0, 0, 0, true, false),
            new NativeEvidence(key, 1, true, 1, 0, 0, 0, 0, 0, true, false),
            new NativeEvidence(key, 1, true, 0, 1, 0, 0, 0, 0, true, false),
            new NativeEvidence(key, 1, true, 0, 0, 1, 0, 0, 0, true, false),
            new NativeEvidence(key, 1, true, 0, 0, 0, 1, 0, 0, true, false),
            new NativeEvidence(key, 1, true, 0, 0, 0, 0, 1, 0, true, false),
            new NativeEvidence(key, 1, true, 0, 0, 0, 0, 0, 1, true, false),
            new NativeEvidence(key, 1, true, 0, 0, 0, 0, 0, 0, false, false),
            new NativeEvidence(key, 1, true, 0, 0, 0, 0, 0, 0, true, false)
        };
        for (NativeEvidence evidence : pending) {
            StopBarrier barrier = new StopBarrier(key);
            check(barrier.update(ownerDone(key, 1, finalNative)), "out-of-delivery-order owner snapshot retained");
            check(barrier.update(buffersDone(key, 1)), "buffer branch terminal");
            check(barrier.update(evidence), "pending native branch accepted");
            check(!barrier.snapshot().drained, "each native obligation keeps composite pending");
            check(barrier.update(finalNative), "native completion advances pending branch");
            check(barrier.snapshot().drained, "later matching native completion joins owner");
        }
        OwnerEvidence[] owners = {
            new OwnerEvidence(key, 1, false, 0, false, null),
            new OwnerEvidence(key, 1, true, 1, false, null),
            new OwnerEvidence(key, 1, true, 0, false, null)
        };
        for (OwnerEvidence owner : owners) {
            StopBarrier barrier = new StopBarrier(key);
            check(barrier.update(finalNative) && barrier.update(buffersDone(key, 1)), "other branches terminal for owner test");
            check(barrier.update(owner), "pending owner accepted");
            check(!barrier.snapshot().drained, "owner not closed cannot confirm");
            check(barrier.update(ownerDone(key, 2, finalNative)), "owner terminal update accepted");
            check(barrier.snapshot().drained, "owner completion combines");
        }
        BufferEvidence[] buffers = {
            new BufferEvidence(key, 1, false, true, 0, 0, 0),
            new BufferEvidence(key, 1, true, false, 0, 0, 0),
            new BufferEvidence(key, 1, true, true, 1, 0, 0),
            new BufferEvidence(key, 1, true, true, 0, 1, 0),
            new BufferEvidence(key, 1, true, true, 0, 0, 1)
        };
        for (BufferEvidence buffer : buffers) {
            StopBarrier barrier = new StopBarrier(key);
            check(barrier.update(finalNative) && barrier.update(ownerDone(key, 1, finalNative)), "native and owner closed");
            check(barrier.update(buffer), "pending buffer snapshot accepted");
            check(!barrier.snapshot().drained, "queued in-flight preview or open admission prevents completion");
            check(barrier.update(buffersDone(key, 2)), "buffer release observed");
            check(barrier.snapshot().drained, "buffer completion combines");
        }
        StopBarrier missing = new StopBarrier(key);
        check(!missing.snapshot().drained, "no producer is not a success");
        missing.update(finalNative); check(!missing.snapshot().drained, "native only is not a success");
        missing.update(ownerDone(key, 1, finalNative)); check(!missing.snapshot().drained, "missing buffer report is not a success");
        rejected(IllegalArgumentException.class,
            () -> new NativeEvidence(key, 1, true, 1, 0, 0, 0, 0, 0, true, true), "context cannot close with native call remaining");
        rejected(IllegalArgumentException.class,
            () -> new NativeEvidence(key, 1, true, 0, 0, 0, 0, 0, 1, true, true), "context cannot close with native-owned bytes remaining");
        rejected(IllegalArgumentException.class,
            () -> new NativeEvidence(key, 1, true, 0, 0, 0, 0, 0, 0, false, true), "context cannot precede pump join");
        rejected(IllegalArgumentException.class,
            () -> new OwnerEvidence(key, 1, true, 0, true, null), "owner close requires native evidence");
        rejected(IllegalArgumentException.class,
            () -> new OwnerEvidence(key, 1, true, 1, true, finalNative), "owner cannot close with outstanding borrow");
        rejected(IllegalArgumentException.class,
            () -> new OwnerEvidence(key, 1, true, 0, true, pending[0]), "owner cannot use pending native evidence");
        rejected(IllegalArgumentException.class,
            () -> new OwnerEvidence(new StopKey(key.binding, "other-stop"), 1, true, 0, true, finalNative), "owner cannot use another stop native evidence");
    }

    private static void revisionsAndTerminalFreeze() {
        StopKey key = key(); StopBarrier barrier = new StopBarrier(key);
        NativeEvidence pending = new NativeEvidence(key, 3, true, 1, 0, 0, 0, 0, 0, false, false);
        check(barrier.update(pending), "new native revision accepted");
        check(!barrier.update(pending), "exact duplicate native snapshot ignored");
        check(!barrier.update(nativeDone(key, 2)), "older native completion cannot override pending revision");
        check(!barrier.snapshot().drained && !barrier.snapshot().protocolViolation, "old duplicate data neither confirms nor poisons");
        NativeEvidence finalNative = nativeDone(key, 4);
        check(barrier.update(finalNative), "higher terminal native revision accepted");
        check(barrier.update(ownerDone(key, 1, nativeDone(key, 5))), "owner report may arrive before the claimed native revision");
        check(barrier.update(buffersDone(key, 1)), "buffers terminal");
        check(!barrier.snapshot().drained, "owner native revision mismatch blocks confirmation");
        // A terminal owner cannot silently be rewritten; this invalid producer stream must remain invalid.
        check(!barrier.update(ownerDone(key, 2, finalNative)), "terminal owner cannot rewrite drain binding");
        check(barrier.snapshot().protocolViolation && !barrier.snapshot().drained, "terminal contradiction latches protocol violation");

        for (int branch = 0; branch < 3; branch++) {
            StopBarrier current = new StopBarrier(key); NativeEvidence done = nativeDone(key, 7);
            completed(current, done); StopBarrier.Receipt before = current.snapshot();
            check(!current.update(done) && !current.update(ownerDone(key, 1, done))
                    && !current.update(buffersDone(key, 1)), "terminal duplicate reports ignored");
            check(current.snapshot().drained && !current.snapshot().protocolViolation, "duplicates retain terminal evidence");
            boolean accepted;
            if (branch == 0) accepted = current.update(new NativeEvidence(key, 8, true, 1, 0, 0, 0, 0, 0, false, false));
            else if (branch == 1) accepted = current.update(new OwnerEvidence(key, 2, false, 1, false, null));
            else accepted = current.update(new BufferEvidence(key, 2, false, false, 0, 1, 0));
            check(!accepted, "newer reopening snapshot refused");
            check(current.snapshot().protocolViolation && !current.snapshot().drained, "reopening revokes current composite confirmation");
            check(before.drained && !before.protocolViolation, "previous receipt remains a detached historical snapshot");
        }
        StopBarrier frozen = new StopBarrier(key); NativeEvidence done = nativeDone(key, 1);
        completed(frozen, done);
        check(!frozen.update(nativeDone(key, 2)), "higher terminal revision also violates frozen producer protocol");
        check(frozen.snapshot().protocolViolation, "no silent terminal stream continuation");
    }

    private static void sameRevisionConflicts() {
        StopKey key = key(); NativeEvidence done = nativeDone(key, 4);
        for (int branch = 0; branch < 3; branch++) {
            StopBarrier pending = new StopBarrier(key);
            if (branch == 0) {
                check(pending.update(new NativeEvidence(key, 1, true, 1, 0, 0, 0, 0, 0, false, false)), "pending native seed");
                check(!pending.update(new NativeEvidence(key, 1, true, 1, 0, 0, 0, 0, 0, false, false)), "equal distinct native report is duplicate");
            } else if (branch == 1) {
                check(pending.update(new OwnerEvidence(key, 1, true, 1, false, null)), "pending owner seed");
                check(!pending.update(new OwnerEvidence(key, 1, true, 1, false, null)), "equal distinct owner report is duplicate");
            } else {
                check(pending.update(new BufferEvidence(key, 1, true, true, 1, 0, 0)), "pending buffer seed");
                check(!pending.update(new BufferEvidence(key, 1, true, true, 1, 0, 0)), "equal distinct buffer report is duplicate");
            }
            check(!pending.snapshot().protocolViolation, "equal repeated pending report does not violate protocol");
            boolean accepted;
            if (branch == 0) accepted = pending.update(new NativeEvidence(key, 1, true, 0, 0, 0, 0, 0, 1, false, false));
            else if (branch == 1) accepted = pending.update(new OwnerEvidence(key, 1, true, 0, false, null));
            else accepted = pending.update(new BufferEvidence(key, 1, true, true, 0, 1, 0));
            check(!accepted, "same revision changed pending report rejected");
            check(pending.snapshot().protocolViolation && !pending.snapshot().drained, "same revision conflict latches failure while pending");

            StopBarrier terminal = new StopBarrier(key); completed(terminal, done);
            StopBarrier.Receipt historical = terminal.snapshot();
            if (branch == 0) accepted = terminal.update(new NativeEvidence(key, 4, true, 1, 0, 0, 0, 0, 0, false, false));
            else if (branch == 1) accepted = terminal.update(new OwnerEvidence(key, 1, false, 1, false, null));
            else accepted = terminal.update(new BufferEvidence(key, 1, false, false, 0, 1, 0));
            check(!accepted, "same revision terminal reopening rejected");
            check(terminal.snapshot().protocolViolation && !terminal.snapshot().drained, "same revision contradiction invalidates current terminal result");
            check(historical.drained && !historical.protocolViolation, "earlier receipt remains historical after conflict");
        }
        StopBarrier revisionBinding = new StopBarrier(key);
        revisionBinding.update(ownerDone(key, 1, done));
        check(!revisionBinding.update(ownerDone(key, 1, nativeDone(key, 5))), "same owner revision cannot change native-drain revision");
        check(revisionBinding.snapshot().protocolViolation, "native revision binding participates in owner equality");

        // Each changed resource amount must remain visible even when every other native field matches.
        for (int counter = 0; counter < 6; counter++) {
            StopBarrier nativeCounter = new StopBarrier(key);
            nativeCounter.update(new NativeEvidence(key, 1, true, 0, 0, 0, 0, 0, 0, false, false));
            long[] n = new long[6]; n[counter] = 1;
            check(!nativeCounter.update(new NativeEvidence(key, 1, true, n[0], n[1], n[2], n[3], n[4], n[5], false, false)),
                "changed native count cannot masquerade as identical duplicate");
            check(nativeCounter.snapshot().protocolViolation, "each native resource count is included in equality");
        }
        for (int counter = 0; counter < 3; counter++) {
            StopBarrier bufferCounter = new StopBarrier(key);
            bufferCounter.update(new BufferEvidence(key, 1, true, false, 0, 0, 0));
            long[] n = new long[3]; n[counter] = 1;
            check(!bufferCounter.update(new BufferEvidence(key, 1, true, false, n[0], n[1], n[2])),
                "changed buffer count cannot masquerade as identical duplicate");
            check(bufferCounter.snapshot().protocolViolation, "each application ownership count is included in equality");
        }
    }

    private static void failureRetention() throws Exception {
        StopKey key = key(); StopBarrier barrier = new StopBarrier(key);
        Failure timeout = failure(key.binding, FailureKind.TIMEOUT, -7);
        Failure notFound = failure(key.binding, FailureKind.IO, -5); // opaque cancellation return code, never a drain witness
        check(barrier.recordFailure(timeout) && barrier.recordFailure(notFound), "timeout and cancel result retained");
        StopBarrier.Receipt incomplete = barrier.snapshot();
        check(!incomplete.drained && incomplete.failures.size() == 2, "timeout and NOT_FOUND-like result do not supply cleanup evidence");
        check(incomplete.failures.get(0) == timeout && incomplete.failures.get(1) == notFound, "original typed failures and codes retained");
        completed(barrier, nativeDone(key, 1));
        StopBarrier.Receipt disposed = barrier.snapshot();
        check(disposed.drained && disposed.failures.size() == 2, "safe disposal does not erase earlier failure");
        check(!incomplete.drained && incomplete.failures.size() == 2, "pending historical receipt is immutable");
        check(barrier.recordFailure(failure(key.binding, FailureKind.PROTOCOL, -99)), "later same-run failure recorded");
        check(disposed.failures.size() == 2 && barrier.snapshot().failures.size() == 3, "failure list snapshots do not share mutable list");
        rejected(UnsupportedOperationException.class, () -> disposed.failures.clear(), "receipt failure list is unmodifiable");
        ExistingReason[] expected = {ExistingReason.PERMISSION_REVOKED, ExistingReason.SOURCE_LOST,
            ExistingReason.FORMAT_CHANGED, ExistingReason.RESOURCE_PRESSURE, ExistingReason.SOURCE_FAILED,
            ExistingReason.SOURCE_FAILED, ExistingReason.SOURCE_FAILED};
        for (int i = 0; i < FailureKind.values().length; i++)
            check(failure(key.binding, FailureKind.values()[i], -42).existingReason() == expected[i], "typed failure maps to an existing reason");
    }

    private static void budgetsAndActualBuffers() throws Exception {
        ByteBudget budget = new ByteBudget(OwnedRgba.LENGTH * 2L);
        Reservation external = budget.reserve(OwnedRgba.LENGTH);
        OwnedRgba frame = OwnedRgba.allocate(budget, binding(), 0, 0);
        byte[] aliasForZeroVerification = frame.pixelsForOwner(); // Test observer only; not a valid consumer after close.
        Arrays.fill(aliasForZeroVerification, (byte) 0x5a);
        check(aliasForZeroVerification.length == OwnedRgba.LENGTH, "actual fixed RGBA storage allocated");
        check(budget.used() == OwnedRgba.LENGTH * 2L, "frame and other layer share budget");
        rejected(IllegalStateException.class, () -> OwnedRgba.allocate(budget, binding(), 1, 1), "allocation beyond experiment limit refused");
        check(budget.used() == OwnedRgba.LENGTH * 2L, "rejected allocation reserves nothing");
        frame.close();
        check(budget.used() == OwnedRgba.LENGTH, "frame close returns exactly its reservation");
        check(allZero(aliasForZeroVerification), "actual pixel bytes cleared before return");
        rejected(IllegalStateException.class, () -> frame.pixelsForOwner(), "released frame cannot be reopened");
        frame.close(); check(budget.used() == OwnedRgba.LENGTH, "repeated frame close does not return another layer reservation");
        external.close(); external.close(); check(budget.used() == 0, "repeated reservation close is idempotent");
        rejected(IllegalArgumentException.class, () -> budget.reserve(0), "zero reservation refused");
        rejected(IllegalArgumentException.class, () -> budget.reserve(-1), "negative reservation refused");
        rejected(IllegalArgumentException.class, () -> OwnedRgba.allocate(budget, binding(), -1, 0), "invalid sequence fails without reservation");
        check(budget.used() == 0, "invalid metadata leaves budget intact");

        ByteBudget wide = new ByteBudget(Long.MAX_VALUE);
        Reservation large = wide.reserve(Long.MAX_VALUE - 1);
        rejected(IllegalStateException.class, () -> wide.reserve(2), "long addition overflow cannot bypass budget");
        check(wide.used() == Long.MAX_VALUE - 1, "failed overflow attempt does not change ledger");
        Reservation last = wide.reserve(1); check(wide.used() == Long.MAX_VALUE, "exact long limit supported without wrap");
        rejected(IllegalStateException.class, () -> wide.reserve(Long.MAX_VALUE), "exhausted long budget rejects a large request");
        last.close(); large.close(); check(wide.used() == 0, "large reservations fully return");
    }
    private static boolean allZero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    private static void concurrentClose() throws Exception {
        ByteBudget budget = new ByteBudget(OwnedRgba.LENGTH * 3L);
        Reservation independent = budget.reserve(OwnedRgba.LENGTH);
        Reservation raced = budget.reserve(OwnedRgba.LENGTH);
        OwnedRgba frame = OwnedRgba.allocate(budget, binding(), 10, 100);
        byte[] observed = frame.pixelsForOwner(); Arrays.fill(observed, (byte) 0x7f);
        int threads = 12; CountDownLatch ready = new CountDownLatch(threads), go = new CountDownLatch(1), ended = new CountDownLatch(threads);
        AtomicReference<Throwable> problem = new AtomicReference<>(); List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread worker = new Thread(() -> {
                ready.countDown();
                try { go.await(); for (int j = 0; j < 100; j++) { frame.close(); raced.close(); } }
                catch (Throwable error) { problem.compareAndSet(null, error); }
                finally { ended.countDown(); }
            }, "contract-close-" + i);
            worker.setDaemon(true); workers.add(worker); worker.start();
        }
        check(ready.await(5, TimeUnit.SECONDS), "concurrent close workers ready"); go.countDown();
        check(ended.await(5, TimeUnit.SECONDS), "concurrent close workers terminate");
        if (problem.get() != null) throw new AssertionError("concurrent close failed", problem.get());
        for (Thread worker : workers) worker.join(1000);
        check(budget.used() == OwnedRgba.LENGTH, "concurrent closes return each allocation once and preserve independent reservation");
        check(allZero(observed), "concurrent frame close clears actual pixels");
        independent.close(); check(budget.used() == 0, "independent final reservation still owned and releasable");
        Reservation reuse = budget.reserve(OwnedRgba.LENGTH * 3L); check(budget.used() == budget.limit, "budget reusable after concurrent cleanup");
        reuse.close(); check(budget.used() == 0, "reused budget returns to zero");
    }

    public static void main(String[] args) throws Exception {
        identitiesAndFdReuse(); incompleteBranches(); revisionsAndTerminalFreeze(); sameRevisionConflicts(); failureRetention();
        budgetsAndActualBuffers(); concurrentClose();
        System.out.println("PASS UVC contract: " + checks + " assertions; identities, branch drain, immutable receipts, actual zeroing and concurrent close");
        System.out.println("Trusted producer fixtures and host JVM only; no native/Android/USB, authorization, physical timing or PRD stage acceptance.");
    }
}

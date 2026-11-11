package local.sidecar.copilot.uvcadapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.sidecar.copilot.uvccontract.CaptureSession;
import local.sidecar.copilot.uvccontract.StopBarrier;
import static local.sidecar.copilot.uvccontract.UvcContract.*;
import static local.sidecar.copilot.uvcadapter.BackendPort.*;
import static local.sidecar.copilot.uvcadapter.TestSupport.*;

/** Deterministic Java host checks. No native library, OS descriptor, device or simulator. */
public final class AdapterChecks {
    private static final WireFormat WIRE = new WireFormat("YUY2", 640, 360, 333333);
    private static final BooleanSupplier YES = () -> true;
    private static int cases;
    private static int negativeControls;

    private AdapterChecks() {}

    public static void main(String[] args) {
        run("identity_fd_zero_and_terminal_revision", AdapterChecks::identityAndFd);
        run("late_open_stop", AdapterChecks::lateOpen);
        run("callback_before_open_and_start_return", AdapterChecks::preReturnCallbacks);
        run("terminal_before_submit_return", AdapterChecks::earlySubmitTerminal);
        run("busy_preserves_old_attempt", AdapterChecks::busyAttempt);
        run("image_status_control_and_missing_completion", AdapterChecks::channelObligations);
        run("terminal_before_cancel_not_found_return", AdapterChecks::cancelStack);
        run("control_outer_timeout_keeps_stack", AdapterChecks::controlTimeout);
        run("failure_sink_exception_and_unknown_submit", AdapterChecks::failureIsolation);
        run("close_join_context_return_barriers", AdapterChecks::cleanupStages);
        run("close_failure_stays_pending", AdapterChecks::closeFailure);
        run("owned_copy_native_vs_composed_stop", AdapterChecks::copyAndComposedStop);
        run("blocked_sink_callback_stays_pending", AdapterChecks::blockedSink);
        run("frame_borrow_survives_concurrent_lease_close", AdapterChecks::concurrentLeaseClose);
        run("stale_completion_cannot_retire_new_cycle", AdapterChecks::staleCompletion);
        run("inline_cleanup_executor_rejected", AdapterChecks::inlineExecutor);
        run("rejected_cleanup_executor_stays_pending", AdapterChecks::rejectedExecutor);
        run("bridge_layout_order_and_budget", AdapterChecks::bridgeValidation);
        run("bridge_busy_stop_copy_and_zero", AdapterChecks::bridgeCopyStop);
        run("bridge_sink_throw_preserves_owner", AdapterChecks::bridgeSinkThrow);
        run("bridge_copy_exception_private_release", AdapterChecks::bridgeCopyFailure);
        run("independent_early_release_negative_controls", AdapterChecks::negativeControls);
        System.out.println("ADAPTER_CHECKS_OK cases=" + cases + " assertions=" + assertions()
            + " negative_controls=" + negativeControls);
    }

    private static void run(String name, Runnable body) {
        body.run();
        cases++;
        System.out.println("PASS " + name);
    }

    private static Binding binding(long generation) {
        return new Binding(new Selection("session", "selection", "boot", 7, 3),
                           "owner-" + generation, generation, generation);
    }

    private static OperationKey op(Binding binding, String id, long cycle, CallKind kind, Channel channel) {
        return new OperationKey(binding, id, cycle, kind, channel);
    }

    private static final class FailureLog implements CaptureSession.FailureSink {
        private final List<Failure> received = new ArrayList<Failure>();
        volatile boolean throwing;
        @Override public synchronized void accept(Failure failure) {
            received.add(failure);
            if (throwing) throw new IllegalStateException("controlled failure receiver");
        }
        synchronized int size() { return received.size(); }
        synchronized boolean has(FailureKind kind) {
            for (Failure failure : received) if (failure.kind == kind) return true;
            return false;
        }
        synchronized Failure last() { return received.get(received.size() - 1); }
    }

    private static final class Fixture {
        final Binding binding = AdapterChecks.binding(1);
        final ByteBudget budget = new ByteBudget(8L * 1024 * 1024);
        final ControlledBackend backend = new ControlledBackend();
        final ManualExecutor executor = new ManualExecutor();
        final FailureLog failures = new FailureLog();
        final IsolatedCaptureSession session = new IsolatedCaptureSession(binding, budget, failures, backend, executor);
        final OperationKey open = op(binding, "open", 1, CallKind.OPEN, Channel.DEVICE);
        final OperationKey start = op(binding, "start", 1, CallKind.START, Channel.DEVICE);
        final StopKey stop = new StopKey(binding, "stop");
        final BorrowedFd fd = new BorrowedFd(binding, "borrow", 0);
        OperationKey transfer(String name, Channel channel) {
            return op(binding, name, 1, CallKind.SUBMIT, channel);
        }
        void open() {
            CaptureSession.OpenResult result = session.open(open, fd, WIRE);
            check(result.failure == null && WIRE.equals(result.actualWire), "open returns exact wire");
        }
        void start(FrameSink sink) { check(session.start(start, WIRE, sink).failure == null, "start accepted"); }
        void submit(OperationKey operation, long bytes) {
            check(backend.events.submit(operation, bytes).status == SubmitStatus.ACCEPTED, "submit accepted");
        }
        void stop() { session.requestStop(stop); }
        NativeEvidence evidence() { return session.nativeEvidence(stop); }
        void drain() { executor.runAll(); }
        void healthy() { check(backend.violations().isEmpty(), "independent backend ledger: " + backend.violations()); }
        void finished() {
            drain();
            check(evidence().drained(), "native drain complete");
            check(budget.used() == 0, "all experiment reservations returned");
            healthy();
        }
    }

    private static void identityAndFd() {
        Fixture f = new Fixture();
        check(f.backend.invocations() == 0, "construction is bookkeeping only");
        check(f.session.binding().equals(f.binding), "binding preserved");
        rejects(IllegalStateException.class, () -> f.session.nativeEvidence(f.stop), "evidence before stop");
        BorrowedFd wrong = new BorrowedFd(binding(2), "wrong-borrow", 0);
        rejects(IllegalArgumentException.class, () -> f.session.open(f.open, wrong, WIRE), "FD binding");
        check(f.backend.openCalls() == 0, "wrong FD has no backend side effect");
        f.open();
        check(f.backend.borrowedFd == f.fd && f.backend.borrowedFd.fd == 0, "FD zero borrowed intact");
        CaptureSession.StartResult mismatch = f.session.start(f.start,
            new WireFormat("MJPEG", 640, 360, 333333), frame -> frame.close());
        check(mismatch.failure != null && mismatch.failure.kind == FailureKind.FORMAT, "actual wire confirmation exact");
        check(f.backend.startCalls() == 0, "format mismatch never enters backend");
        f.stop();
        NativeEvidence pending = f.evidence();
        check(f.backend.closeCalls() == 0 && f.executor.executed() == 0, "requestStop only enqueues");
        StopKey wrongStop = new StopKey(binding(2), "stop");
        rejects(IllegalArgumentException.class, () -> f.session.requestStop(wrongStop), "wrong binding stop");
        rejects(IllegalArgumentException.class, () -> f.session.nativeEvidence(new StopKey(f.binding, "other")), "wrong canonical evidence");
        check(f.evidence().revision == pending.revision, "wrong keys do not change evidence");
        f.finished();
        long terminal = f.evidence().revision;
        int calls = f.backend.invocations();
        f.stop();
        f.drain();
        rejects(IllegalArgumentException.class, () -> f.session.requestStop(new StopKey(f.binding, "other")), "canonical stop immutable");
        check(f.evidence().revision == terminal && f.backend.invocations() == calls, "terminal revision frozen");
        check(f.fd.fd == 0, "adapter has no platform FD close capability");
    }

    private static void lateOpen() {
        Fixture f = new Fixture();
        Gate gate = new Gate("open-return");
        f.backend.onOpen = gate::enter;
        Task<CaptureSession.OpenResult> task = new Task<CaptureSession.OpenResult>("open", () -> f.session.open(f.open, f.fd, WIRE));
        try {
            gate.awaitEntered();
            new Task<Void>("stop-during-open", () -> { f.stop(); return null; }).finish();
            f.drain();
            check(f.evidence().nativeCalls >= 2 && !f.evidence().drained(), "open stack and potential pump retained");
            check(f.backend.closeCalls() == 0, "late open resource not closed while open is live");
        } finally { gate.release(); }
        check(task.finish().failure == null, "late open still belongs to original session");
        rejects(IllegalStateException.class, () -> f.session.start(f.start, WIRE, frame -> frame.close()), "late success cannot restart");
        f.finished();
    }

    private static void preReturnCallbacks() {
        Fixture f = new Fixture();
        OperationKey status = f.transfer("open-status", Channel.STATUS);
        OperationKey image = f.transfer("start-image", Channel.IMAGE);
        final AtomicInteger statusCallbacks = new AtomicInteger();
        final AtomicInteger preAckRejected = new AtomicInteger();
        final AtomicInteger acknowledgements = new AtomicInteger();
        try (Reservation sourceBytes = f.budget.reserve(OwnedRgba.LENGTH)) {
            check(sourceBytes.bytes == OwnedRgba.LENGTH, "borrowed source is budgeted");
            byte[] source = pattern();
            f.backend.onOpen = () -> {
                f.submit(status, 32);
                f.backend.terminal(status, null, lease -> statusCallbacks.incrementAndGet());
            };
            f.open();
            check(statusCallbacks.get() == 1, "status callback before open return");
            f.backend.onStart = () -> {
                f.submit(image, 64);
                f.backend.terminal(image, null, lease -> lease.frame(frame(source, 1, 10)));
            };
            f.start(owned -> {
                check(acknowledgements.get() == 0, "image sink installed before start returned");
                preAckRejected.incrementAndGet();
                owned.close(); // Independent root publication gate rejects pre-ack delivery.
            });
            acknowledgements.incrementAndGet();
            check(preAckRejected.get() == 1, "root rejected exactly one pre-ack frame");
            f.drain();
            check(f.budget.used() == OwnedRgba.LENGTH, "pre-ack owned copy and completed transfers released");
        }
        f.stop();
        f.finished();
    }

    private static void earlySubmitTerminal() {
        Fixture f = new Fixture();
        f.open();
        OperationKey image = f.transfer("early", Channel.IMAGE);
        Gate gate = new Gate("submit-return");
        f.backend.onSubmit = () -> { f.backend.terminal(image); gate.enter(); };
        Task<SubmitResult> submit = new Task<SubmitResult>("submit", () -> f.backend.events.submit(image, 128));
        try {
            gate.awaitEntered();
            f.stop();
            f.drain();
            check(f.evidence().imageTransfers == 1 && f.evidence().nativeOwnedBytes == 128, "terminal did not erase live submit attempt");
            check(f.backend.attempt(image).releaseCount == 0, "no transfer free before submit return");
        } finally { gate.release(); }
        check(submit.finish().status == SubmitStatus.ACCEPTED, "submit return accepted");
        f.finished();
        check(f.backend.attempt(image).releaseCount == 1, "transfer uniquely released");
    }

    private static void busyAttempt() {
        Fixture f = new Fixture();
        f.open();
        OperationKey first = f.transfer("slot", Channel.IMAGE);
        f.submit(first, 128);
        OperationKey next = op(f.binding, "slot", 2, CallKind.SUBMIT, Channel.IMAGE);
        check(f.backend.events.submit(next, 256).status == SubmitStatus.BUSY, "busy preserves active slot");
        f.backend.submitResult = SubmitResult.busy();
        check(f.backend.events.submit(f.transfer("different-slot", Channel.IMAGE), 256).status == SubmitStatus.BUSY,
              "backend BUSY adds no new resource");
        f.stop();
        f.drain();
        check(f.evidence().imageTransfers == 1 && f.evidence().nativeOwnedBytes == 128, "only old accepted cycle remains");
        f.backend.terminal(first);
        f.finished();
    }

    private static void channelObligations() {
        Fixture f = new Fixture();
        f.open();
        OperationKey image = f.transfer("image", Channel.IMAGE);
        OperationKey status = f.transfer("status", Channel.STATUS);
        OperationKey control = f.transfer("control", Channel.CONTROL);
        f.submit(image, 32); f.submit(status, 48); f.submit(control, 64);
        f.backend.cancelResult = CancelResult.notFound();
        f.stop(); f.drain();
        NativeEvidence pending = f.evidence();
        check(pending.imageTransfers == 1 && pending.statusTransfers == 1 && pending.controlTransfers == 1,
              "all three channels retained after NOT_FOUND");
        check(pending.nativeOwnedBytes == 144 && !pending.drained(), "missing callbacks remain pending");
        check(f.backend.events.submit(op(f.binding, "status", 2, CallKind.SUBMIT, Channel.STATUS), 48).status == SubmitStatus.REJECTED,
              "status resubmit rejected after stop");
        f.backend.terminal(image); f.drain();
        Gate gate = new Gate("status-callback-exit");
        Task<Void> callback = new Task<Void>("status-callback", () -> {
            f.backend.terminal(status, null, lease -> gate.enter()); return null;
        });
        try {
            gate.awaitEntered();
            f.backend.terminal(control); f.drain();
            check(f.evidence().callbacks == 1 && f.evidence().statusTransfers == 1, "active callback keeps status transfer");
            check(f.backend.closeCalls() == 0, "other channels finishing cannot close live status callback");
        } finally { gate.release(); }
        callback.finish(); f.finished();
    }

    private static void cancelStack() {
        Fixture f = new Fixture(); f.open();
        OperationKey image = f.transfer("cancel-stack", Channel.IMAGE); f.submit(image, 64);
        Gate gate = new Gate("cancel-return");
        f.backend.cancelResult = CancelResult.notFound();
        f.backend.onCancel = gate::enter;
        f.stop();
        Task<Void> cleanup = new Task<Void>("cleanup-cancel", () -> { f.drain(); return null; });
        try {
            gate.awaitEntered();
            f.backend.terminal(image);
            check(f.evidence().imageTransfers == 1 && f.evidence().nativeCalls >= 2, "terminal callback cannot clear cancel stack");
            check(f.backend.attempt(image).releaseCount == 0, "no free before cancel returned");
        } finally { gate.release(); }
        cleanup.finish(); f.finished();
    }

    private static void controlTimeout() {
        Fixture f = new Fixture(); f.open();
        OperationKey control = op(f.binding, "sync-control", 1, CallKind.CONTROL, Channel.CONTROL);
        Gate gate = new Gate("control-return"); f.backend.onControl = gate::enter;
        Task<Failure> task = new Task<Failure>("control", () -> f.backend.events.control(control));
        try {
            gate.awaitEntered();
            f.backend.events.failure(new Failure(FailureKind.TIMEOUT, -7, control));
            f.stop(); f.drain();
            check(f.failures.has(FailureKind.TIMEOUT), "outer timeout remains an explicit failure");
            check(f.evidence().nativeCalls >= 2 && !f.evidence().drained(), "outer timeout does not return synchronous stack");
            check(f.backend.closeCalls() == 0, "control still borrows context");
        } finally { gate.release(); }
        check(task.finish() == null, "actual control may return later without inventing timeout completion");
        f.finished();
    }

    private static void failureIsolation() {
        Fixture f = new Fixture(); f.open();
        f.failures.throwing = true;
        OperationKey image = f.transfer("unknown-submit", Channel.IMAGE);
        f.backend.onSubmit = () -> { throw new BackendFailure(FailureKind.IO, -91); };
        SubmitResult result = f.backend.events.submit(image, 80);
        check(result.failure != null && result.failure.kind == FailureKind.IO, "original typed submit failure retained");
        check(f.session.failureSinkExceptions() == 1 && f.session.failures().get(0).nativeCode == -91, "sink exception cannot replace original failure");
        f.backend.cancelResult = CancelResult.notFound();
        f.stop(); f.drain();
        check(f.evidence().imageTransfers == 1 && f.evidence().nativeOwnedBytes == 80, "unknown submit acceptance conservatively retains allocation");
        f.backend.terminal(image); f.finished();
    }

    private static void cleanupStages() {
        Fixture f = new Fixture();
        f.backend.onOpen = () -> f.backend.events.reserveNative(f.open, 256);
        f.open();
        Gate close = new Gate("device-close"), join = new Gate("pump-join"), context = new Gate("context-release");
        f.backend.onClose = close::enter; f.backend.onJoin = join::enter; f.backend.onContext = context::enter;
        f.stop();
        Task<Void> cleanup = new Task<Void>("cleanup-stages", () -> { f.drain(); return null; });
        try {
            close.awaitEntered();
            check(!f.evidence().contextReleased && f.evidence().nativeOwnedBytes == 256, "close entry is not cleanup completion");
            close.release(); join.awaitEntered();
            check(!f.evidence().pumpJoined && !f.evidence().drained(), "pump join entry is not join completion");
            join.release(); context.awaitEntered();
            check(f.evidence().pumpJoined && !f.evidence().contextReleased && f.budget.used() == 256,
                  "context bytes retained through context release stack");
        } finally { close.release(); join.release(); context.release(); }
        cleanup.finish(); f.finished();
        check(f.backend.closeCalls() == 1 && f.backend.joinCalls() == 1 && f.backend.contextCalls() == 1, "unique ordered teardown");
    }

    private static void closeFailure() {
        Fixture f = new Fixture();
        f.backend.onOpen = () -> f.backend.events.reserveNative(f.open, 96);
        f.open();
        f.backend.onClose = () -> { throw new BackendFailure(FailureKind.IO, -92); };
        f.stop(); f.drain();
        check(f.failures.has(FailureKind.IO), "close error reported");
        check(!f.evidence().drained() && f.evidence().nativeOwnedBytes == 96 && f.budget.used() == 96,
              "failed close retains context reservation, no forced disposal");
        check(f.backend.joinCalls() == 0 && f.backend.contextCalls() == 0, "failed close does not advance teardown");
        long revision = f.evidence().revision;
        f.stop(); f.drain();
        check(f.evidence().revision == revision && f.backend.closeCalls() == 1, "idempotent stop does not pretend retry success");
        f.healthy(); // Deliberately pending synthetic context, no actual OS/native resource.
    }

    private static final class FakeOwner {
        final StopKey key;
        long revision = 1, borrows = 1;
        int closes;
        boolean closedAdmission;
        NativeEvidence nativeDrain;
        FakeOwner(StopKey key) { this.key = key; }
        void revoke() { closedAdmission = true; revision++; }
        void returnBorrow(NativeEvidence evidence) {
            check(evidence.key.equals(key) && evidence.drained(), "owner waits for matching actual native drain");
            nativeDrain = evidence; borrows = 0; revision++;
        }
        void close() {
            check(closedAdmission && borrows == 0 && nativeDrain != null && nativeDrain.drained(), "owner close preconditions");
            closes++; revision++;
        }
        OwnerEvidence snapshot() {
            return new OwnerEvidence(key, revision, closedAdmission, borrows, closes != 0, nativeDrain);
        }
    }

    private static final class FakeBuffers implements FrameSink {
        final StopKey key;
        OwnedRgba held;
        long revision = 1;
        boolean revoked;
        FakeBuffers(StopKey key) { this.key = key; }
        @Override public void accept(OwnedRgba frame) {
            check(held == null, "independent sink has one exclusive owned slot");
            held = frame; revision++;
        }
        void revoke() { revoked = true; revision++; }
        void release() { held.close(); held = null; revision++; }
        BufferEvidence snapshot() {
            return new BufferEvidence(key, revision, revoked, revoked, 0, 0, held == null ? 0 : OwnedRgba.LENGTH);
        }
    }

    private static void copyAndComposedStop() {
        Fixture f = new Fixture(); f.open();
        FakeOwner owner = new FakeOwner(f.stop);
        FakeBuffers buffers = new FakeBuffers(f.stop);
        f.start(owned -> { buffers.accept(owned); throw new IllegalStateException("controlled retaining sink"); });
        try (Reservation sourceBytes = f.budget.reserve(OwnedRgba.LENGTH)) {
            check(sourceBytes.bytes == OwnedRgba.LENGTH, "shared budget charges callback source");
            byte[] source = pattern();
            OperationKey image = f.transfer("copy", Channel.IMAGE); f.submit(image, 64);
            f.backend.terminal(image, null, lease -> lease.frame(frame(source, 2, 200)));
            check(f.failures.has(FailureKind.IO), "adapter isolates retaining frame sink failure");
            check(buffers.held != null && buffers.held.pixelsForOwner() != source, "owned destination is an independent array");
            Arrays.fill(source, (byte) 0);
            checkPattern(buffers.held.pixelsForOwner(), "source reused after callback");
        }
        owner.revoke(); buffers.revoke(); f.stop(); f.drain();
        NativeEvidence nativeDrain = f.evidence();
        check(nativeDrain.drained() && f.budget.used() == OwnedRgba.LENGTH, "native drain coexists with sink-owned bytes");
        check(owner.closes == 0 && owner.borrows == 1, "adapter never closes platform owner");
        StopBarrier barrier = new StopBarrier(f.stop);
        barrier.update(nativeDrain); barrier.update(owner.snapshot()); barrier.update(buffers.snapshot());
        check(!barrier.snapshot().drained, "native evidence alone is not composed stop");
        owner.returnBorrow(nativeDrain); owner.close(); barrier.update(owner.snapshot());
        check(!barrier.snapshot().drained, "held application frame still prevents composed stop");
        buffers.release(); barrier.update(buffers.snapshot());
        check(barrier.snapshot().drained && !barrier.snapshot().protocolViolation, "all three independent producers complete");
        check(f.evidence().revision == nativeDrain.revision && f.budget.used() == 0, "native terminal frozen while app later releases");
        f.healthy();
    }

    private static void blockedSink() {
        Fixture f = new Fixture(); f.open();
        Gate gate = new Gate("sink-return");
        f.start(frame -> { try { gate.enter(); } finally { frame.close(); } });
        try (Reservation sourceBytes = f.budget.reserve(OwnedRgba.LENGTH)) {
            check(sourceBytes.bytes == OwnedRgba.LENGTH, "blocked source charged");
            byte[] source = pattern();
            OperationKey image = f.transfer("blocked-sink", Channel.IMAGE); f.submit(image, 64);
            Task<Void> callback = new Task<Void>("image-callback", () -> {
                f.backend.terminal(image, null, lease -> lease.frame(frame(source, 1, 1))); return null;
            });
            try {
                gate.awaitEntered();
                new Task<Void>("stop-during-sink", () -> { f.stop(); return null; }).finish();
                f.drain();
                check(f.evidence().callbacks >= 1 && f.backend.closeCalls() == 0, "blocked callback cannot fake drain");
                check(f.budget.used() == 2L * OwnedRgba.LENGTH + 64, "source, owned frame and transfer remain charged");
            } finally { gate.release(); }
            callback.finish();
        }
        f.finished();
    }

    private static void staleCompletion() {
        Fixture f = new Fixture(); f.open();
        OperationKey first = f.transfer("cycle", Channel.STATUS); f.submit(first, 32);
        Completion stale = f.backend.attempt(first).completion;
        f.backend.terminal(first); f.drain();
        OperationKey second = op(f.binding, "cycle", 2, CallKind.SUBMIT, Channel.STATUS); f.submit(second, 48);
        rejects(IllegalStateException.class, () -> stale.enter(null), "retired terminal callback rejected");
        f.stop(); f.drain();
        check(f.evidence().statusTransfers == 1 && f.evidence().nativeOwnedBytes == 48, "old callback cannot retire new cycle");
        f.backend.terminal(second); f.finished();
    }

    private static void concurrentLeaseClose() {
        Fixture f = new Fixture(); f.open();
        Gate gate = new Gate("frame-borrow-return");
        AtomicReference<CallbackLease> outerLease = new AtomicReference<CallbackLease>();
        f.start(owned -> { try { gate.enter(); } finally { owned.close(); } });
        try (Reservation sourceBytes = f.budget.reserve(OwnedRgba.LENGTH)) {
            check(sourceBytes.bytes == OwnedRgba.LENGTH, "concurrent-frame source counted");
            byte[] source = pattern();
            OperationKey image = f.transfer("frame-borrow", Channel.IMAGE); f.submit(image, 64);
            Task<Void> callback = new Task<Void>("frame-borrow", () -> {
                f.backend.terminal(image, null, lease -> {
                    outerLease.set(lease);
                    lease.frame(frame(source, 1, 1));
                });
                return null;
            });
            try {
                gate.awaitEntered();
                new Task<Void>("outer-lease-close", () -> { outerLease.get().close(); return null; }).finish();
                f.stop(); f.drain();
                check(f.evidence().callbacks == 1 && f.evidence().imageTransfers == 1,
                      "frame borrow remains after concurrent outer lease close");
                check(f.backend.attempt(image).releaseCount == 0 && f.backend.closeCalls() == 0,
                      "live frame call prevents transfer or native disposal");
                check(f.budget.used() == 2L * OwnedRgba.LENGTH + 64, "concurrent close cannot hide live source/copy/transfer");
            } finally { gate.release(); }
            callback.finish();
        }
        f.finished();
    }

    private static void inlineExecutor() {
        Binding binding = binding(1);
        ControlledBackend backend = new ControlledBackend();
        ByteBudget budget = new ByteBudget(1024);
        FailureLog failures = new FailureLog();
        Executor inline = Runnable::run;
        IsolatedCaptureSession session = new IsolatedCaptureSession(binding, budget, failures, backend, inline);
        session.open(op(binding, "open", 1, CallKind.OPEN, Channel.DEVICE), new BorrowedFd(binding, "borrow", 0), WIRE);
        StopKey stop = new StopKey(binding, "stop"); session.requestStop(stop);
        check(backend.closeCalls() == 0 && !session.nativeEvidence(stop).drained(), "inline executor cannot run blocking cleanup on stop");
        checkSchedulerFailure(failures.last(), binding);
        check(session.failures().size() == 1, "contract-breaking executor failure retained");
        check(backend.violations().isEmpty(), "rejected inline execution did not reach backend");
    }

    private static void rejectedExecutor() {
        Binding binding = binding(1);
        ControlledBackend backend = new ControlledBackend();
        ByteBudget budget = new ByteBudget(1024);
        FailureLog failures = new FailureLog();
        Executor rejected = command -> { throw new RejectedExecutionException("controlled scheduler rejection"); };
        IsolatedCaptureSession session = new IsolatedCaptureSession(binding, budget, failures, backend, rejected);
        OperationKey open = op(binding, "open", 1, CallKind.OPEN, Channel.DEVICE);
        backend.onOpen = () -> backend.events.reserveNative(open, 64);
        session.open(open, new BorrowedFd(binding, "borrow", 0), WIRE);
        StopKey stop = new StopKey(binding, "stop"); session.requestStop(stop);
        NativeEvidence pending = session.nativeEvidence(stop);
        checkSchedulerFailure(failures.last(), binding);
        check(session.failures().size() == 1, "rejected scheduler failure retained once");
        check(!pending.drained() && pending.nativeOwnedBytes == 64 && budget.used() == 64,
              "scheduler rejection preserves pending context allocation");
        check(backend.closeCalls() == 0 && backend.joinCalls() == 0 && backend.contextCalls() == 0,
              "no cleanup falsely attributed to rejected executor");
        session.requestStop(stop);
        check(session.nativeEvidence(stop).revision == pending.revision, "repeat stop cannot manufacture executor completion");
    }

    private static void checkSchedulerFailure(Failure failure, Binding binding) {
        check(failure.kind == FailureKind.PROTOCOL && failure.nativeCode == 0
              && failure.operation.binding.equals(binding)
              && failure.operation.operationId.equals("adapter-cleanup-scheduler")
              && failure.operation.submissionCycle == 1 && failure.operation.kind == CallKind.CLOSE
              && failure.operation.channel == Channel.DEVICE, "exact scheduler failure identity and kind");
    }

    private static byte[] pattern() {
        byte[] bytes = new byte[OwnedRgba.LENGTH];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) ((i * 31) ^ 0xa5);
        return bytes;
    }

    private static void checkPattern(byte[] bytes, String phase) {
        boolean equal = bytes.length == OwnedRgba.LENGTH;
        for (int i = 0; equal && i < bytes.length; i++) equal = bytes[i] == (byte) ((i * 31) ^ 0xa5);
        check(equal, phase + ": all owned bytes match independent pattern formula");
    }

    private static OwnedFrameBridge.BorrowedFrame frame(byte[] bytes, long sequence, long time) {
        return new OwnedFrameBridge.BorrowedFrame(bytes, 0, OwnedRgba.LENGTH, OwnedRgba.WIDTH,
            OwnedRgba.HEIGHT, OwnedRgba.STRIDE, "RGBA", sequence, time);
    }

    private static void bridgeValidation() {
        Binding binding = binding(1);
        OperationKey image = op(binding, "bridge", 1, CallKind.SUBMIT, Channel.IMAGE);
        ByteBudget budget = new ByteBudget(3L * OwnedRgba.LENGTH);
        FailureLog failures = new FailureLog();
        OwnedFrameBridge bridge = new OwnedFrameBridge(binding, budget, failures);
        AtomicInteger accepted = new AtomicInteger();
        FrameSink sink = owned -> { accepted.incrementAndGet(); owned.close(); };
        try (Reservation sourceBytes = budget.reserve(OwnedRgba.LENGTH)) {
            check(sourceBytes.bytes == OwnedRgba.LENGTH, "validation fixture source counted");
            byte[] source = pattern();
            List<OwnedFrameBridge.BorrowedFrame> malformed = Arrays.asList(
                new OwnedFrameBridge.BorrowedFrame(source, 0, OwnedRgba.LENGTH, 639, 360, 2560, "RGBA", 1, 1),
                new OwnedFrameBridge.BorrowedFrame(source, 0, OwnedRgba.LENGTH, 640, 360, 2564, "RGBA", 1, 1),
                new OwnedFrameBridge.BorrowedFrame(source, 0, OwnedRgba.LENGTH - 1, 640, 360, 2560, "RGBA", 1, 1),
                new OwnedFrameBridge.BorrowedFrame(source, Integer.MAX_VALUE, OwnedRgba.LENGTH, 640, 360, 2560, "RGBA", 1, 1),
                new OwnedFrameBridge.BorrowedFrame(source, 0, OwnedRgba.LENGTH, 640, 360, 2560, "RGBX", 1, 1));
            for (OwnedFrameBridge.BorrowedFrame bad : malformed) {
                bridge.deliver(image, bad, sink, YES);
                check(failures.last().kind == FailureKind.FORMAT && budget.used() == OwnedRgba.LENGTH, "malformed layout rejected before owned allocation");
            }
            bridge.deliver(image, frame(source, 1, 100), sink, YES);
            check(accepted.get() == 1, "valid fixed RGBA delivered");
            bridge.deliver(image, frame(source, 1, 101), sink, YES);
            check(failures.last().kind == FailureKind.PROTOCOL && accepted.get() == 1, "sequence equality rejected");
            bridge.deliver(image, frame(source, 2, 99), sink, YES);
            check(failures.last().kind == FailureKind.PROTOCOL && accepted.get() == 1, "host time regression rejected");
            bridge.deliver(image, frame(source, -1, 100), sink, YES);
            check(failures.last().kind == FailureKind.PROTOCOL, "negative sequence rejected");
            long count = bridge.snapshot().failureCount;
            rejects(IllegalArgumentException.class, () -> bridge.deliver(
                op(binding(2), "bridge", 1, CallKind.SUBMIT, Channel.IMAGE), frame(source, 2, 101), sink, YES), "wrong frame identity");
            check(bridge.snapshot().failureCount == count, "wrong identity no bridge mutation");
        }
        check(budget.used() == 0, "validation reservations all returned");
        ByteBudget tiny = new ByteBudget(2L * OwnedRgba.LENGTH - 1);
        FailureLog pressure = new FailureLog();
        AtomicInteger allocations = new AtomicInteger();
        OwnedFrameBridge limited = new OwnedFrameBridge(binding, tiny, pressure, new OwnedFrameBridge.CopyHook() {
            @Override public void afterAllocation(OwnedRgba frame) { allocations.incrementAndGet(); }
            @Override public void afterCopy(OwnedRgba frame) {}
        });
        try (Reservation sourceBytes = tiny.reserve(OwnedRgba.LENGTH)) {
            check(sourceBytes.bytes == OwnedRgba.LENGTH, "small-budget source counted");
            limited.deliver(image, frame(pattern(), 1, 1), sink, YES);
            check(pressure.last().kind == FailureKind.RESOURCE && allocations.get() == 0, "budget rejected before destination allocation");
            check(tiny.used() == OwnedRgba.LENGTH, "failed allocation did not consume budget");
        }
    }

    private static void bridgeCopyStop() {
        Binding binding = binding(1);
        OperationKey image = op(binding, "copy-stop", 1, CallKind.SUBMIT, Channel.IMAGE);
        ByteBudget budget = new ByteBudget(3L * OwnedRgba.LENGTH);
        FailureLog failures = new FailureLog();
        Gate gate = new Gate("private-copy");
        AtomicReference<byte[]> forensicArray = new AtomicReference<byte[]>();
        AtomicInteger deliveries = new AtomicInteger();
        OwnedFrameBridge bridge = new OwnedFrameBridge(binding, budget, failures, new OwnedFrameBridge.CopyHook() {
            @Override public void afterAllocation(OwnedRgba frame) {}
            @Override public void afterCopy(OwnedRgba frame) {
                // Deliberate test-only alias, never read concurrently; inspect after private owner closes.
                forensicArray.set(frame.pixelsForOwner()); gate.enter();
            }
        });
        try (Reservation sourceBytes = budget.reserve(OwnedRgba.LENGTH)) {
            check(sourceBytes.bytes == OwnedRgba.LENGTH, "copy-stop source counted");
            byte[] source = pattern();
            FrameSink sink = owned -> { deliveries.incrementAndGet(); owned.close(); };
            Task<Void> copy = new Task<Void>("copy-stop", () -> { bridge.deliver(image, frame(source, 1, 1), sink, YES); return null; });
            try {
                gate.awaitEntered();
                bridge.deliver(image, frame(source, 2, 2), sink, YES);
                check(failures.last().kind == FailureKind.RESOURCE, "second delivery rejected while slot busy");
                check(bridge.snapshot().privateOwnedBytes == OwnedRgba.LENGTH, "private copy remains live while held");
                new Task<Void>("bridge-stop", () -> { bridge.closeAdmission(); return null; }).finish();
                check(budget.used() == 2L * OwnedRgba.LENGTH, "stop cannot fake private copy release");
            } finally { gate.release(); }
            copy.finish();
            boolean zero = true;
            for (byte value : forensicArray.get()) if (value != 0) zero = false;
            check(zero && deliveries.get() == 0, "stop-rejected private frame zeroed without handoff");
            check(budget.used() == OwnedRgba.LENGTH && bridge.snapshot().activeDeliveries == 0, "private budget returned after copy exits");
            forensicArray.set(null);
        }
        check(budget.used() == 0, "copy-stop source returned");
    }

    private static void bridgeSinkThrow() {
        Binding binding = binding(1);
        ByteBudget budget = new ByteBudget(3L * OwnedRgba.LENGTH);
        FailureLog failures = new FailureLog(); failures.throwing = true;
        OwnedFrameBridge bridge = new OwnedFrameBridge(binding, budget, failures);
        AtomicReference<OwnedRgba> owned = new AtomicReference<OwnedRgba>();
        try (Reservation sourceBytes = budget.reserve(OwnedRgba.LENGTH)) {
            check(sourceBytes.bytes == OwnedRgba.LENGTH, "sink-throw source counted");
            byte[] source = pattern();
            bridge.deliver(op(binding, "throw", 1, CallKind.SUBMIT, Channel.IMAGE), frame(source, 1, 1),
                value -> { owned.set(value); throw new IllegalStateException("controlled frame sink"); }, YES);
            check(failures.has(FailureKind.IO) && bridge.snapshot().failureSinkErrors == 1, "both receiver exceptions isolated with original IO");
            check(budget.used() == 2L * OwnedRgba.LENGTH && owned.get() != null, "sink owns on entry despite throwing");
            Arrays.fill(source, (byte) 0);
            checkPattern(owned.get().pixelsForOwner(), "throwing sink still owns independent bytes");
            owned.get().close(); owned.set(null);
            check(budget.used() == OwnedRgba.LENGTH, "only sink returns handed-off destination budget");
        }
        check(budget.used() == 0, "throw fixture source returned");
    }

    private static void bridgeCopyFailure() {
        Binding binding = binding(1);
        ByteBudget budget = new ByteBudget(3L * OwnedRgba.LENGTH);
        FailureLog failures = new FailureLog();
        AtomicInteger accepts = new AtomicInteger();
        OwnedFrameBridge bridge = new OwnedFrameBridge(binding, budget, failures, new OwnedFrameBridge.CopyHook() {
            @Override public void afterAllocation(OwnedRgba frame) { throw new IllegalStateException("copy hook failure"); }
            @Override public void afterCopy(OwnedRgba frame) {}
        });
        try (Reservation sourceBytes = budget.reserve(OwnedRgba.LENGTH)) {
            check(sourceBytes.bytes == OwnedRgba.LENGTH, "failed-copy source counted");
            bridge.deliver(op(binding, "copy-fail", 1, CallKind.SUBMIT, Channel.IMAGE), frame(pattern(), 1, 1),
                value -> { accepts.incrementAndGet(); value.close(); }, YES);
            check(failures.last().kind == FailureKind.PROTOCOL && accepts.get() == 0, "private-copy failure is typed without transfer");
            check(budget.used() == OwnedRgba.LENGTH && bridge.snapshot().privateOwnedBytes == 0, "private allocation reclaimed on exception");
        }
    }

    private static void negativeControls() {
        ControlledBackend early = new ControlledBackend();
        Binding binding = binding(1);
        OperationKey operation = op(binding, "negative-early", 1, CallKind.SUBMIT, Channel.IMAGE);
        Gate gate = new Gate("negative-submit-return");
        early.onSubmit = gate::enter;
        Task<SubmitResult> submit = new Task<SubmitResult>("negative-submit", () ->
            early.submit(operation, 16, failure -> null));
        try {
            gate.awaitEntered();
            early.terminal(operation); // Terminal and callback have ended; only submit's stack remains.
            early.releaseTransfer(operation); // Deliberately wrong backend lifecycle, while its submit stack lives.
            check(early.violations().contains("EARLY_TRANSFER_RELEASE:negative-early/1/IMAGE"),
                  "negative control must detect the exact early-transfer-free condition");
            negativeControls++;
        } finally { gate.release(); }
        submit.finish();
        ControlledBackend prematureContext = new ControlledBackend();
        prematureContext.closeDevice();
        prematureContext.releaseContext(); // Deliberately wrong: device closed, but pump has not joined.
        check(prematureContext.violations().contains("EARLY_CONTEXT_RELEASE"),
              "negative control must detect context release before close/join");
        negativeControls++;
    }
}

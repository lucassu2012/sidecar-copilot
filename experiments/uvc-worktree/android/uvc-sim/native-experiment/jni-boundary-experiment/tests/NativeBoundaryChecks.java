package local.sidecar.copilot.jnihost;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import local.sidecar.copilot.usbowner.PlatformUsbPort;
import local.sidecar.copilot.usbowner.UsbConnectionOwner;
import local.sidecar.copilot.uvcadapter.BackendPort;
import local.sidecar.copilot.uvcadapter.IsolatedCaptureSession;
import local.sidecar.copilot.uvccontract.CaptureSession;
import local.sidecar.copilot.uvccontract.StopBarrier;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/** Actual host JNI calls and native gates; no Android, USB, OS FD, or upstream native code. */
public final class NativeBoundaryChecks {
    private static final long WAIT_MS = 8000;
    private static final WireFormat WIRE = new WireFormat("YUY2", 640, 360, 333333);
    private static final AtomicInteger ASSERTIONS = new AtomicInteger();
    private static int cases, negativeControls;
    private NativeBoundaryChecks() {}

    public static void main(String[] args) {
        check(args.length == 1, "exactly one absolute host JNI library argument");
        JniBackendPort.load(args[0]);
        run("early_terminal_before_submit_returns", NativeBoundaryChecks::earlySubmit);
        run("java_callback_exit_still_has_native_tail", NativeBoundaryChecks::callbackTail);
        run("callback_exception_is_cleared_at_jni_boundary", NativeBoundaryChecks::callbackException);
        run("outer_control_timeout_keeps_native_stack", NativeBoundaryChecks::controlTimeout);
        run("late_terminal_cannot_revive_old_fd_binding", NativeBoundaryChecks::lateCompletion);
        run("cancel_stack_and_terminal_overlap", NativeBoundaryChecks::cancelOverlap);
        run("actual_native_owner_and_application_barrier", NativeBoundaryChecks::composedStop);
        run("independent_close_oracle_negative_controls", NativeBoundaryChecks::negativeControls);
        System.out.println("JNI_BOUNDARY_OK cases=" + cases + " assertions=" + ASSERTIONS.get()
            + " negative_controls=" + negativeControls);
    }

    private static void run(String name, Runnable action) {
        action.run();
        JniBackendPort.GlobalSnapshot global = JniBackendPort.globalSnapshot();
        check(global.liveContexts == 0 && global.globalRefs == 0 && global.nativeBufferBytes == 0
              && global.attachedThreads == 0, "no live JNI resources after " + name);
        cases++;
        System.out.println("PASS " + name);
    }

    private static void check(boolean condition, String message) {
        ASSERTIONS.incrementAndGet();
        if (!condition) throw new AssertionError(message);
    }

    private static void await(CountDownLatch latch, String name) {
        try { check(latch.await(WAIT_MS, TimeUnit.MILLISECONDS), "deadlock guard: " + name); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new AssertionError(name, interrupted);
        }
    }

    /** The deadline is a failed-test deadlock guard, never a passing latency assertion. */
    private static final class Task<T> {
        private final CountDownLatch done = new CountDownLatch(1);
        private T result;
        private Throwable failure;
        Task(String name, Callable<T> action) {
            Thread thread = new Thread(() -> {
                try { result = action.call(); }
                catch (Throwable caught) { failure = caught; }
                finally { done.countDown(); }
            }, name);
            thread.setDaemon(true); thread.start();
        }
        T finish() {
            await(done, "task return");
            if (failure != null) throw new AssertionError("controlled task failed", failure);
            return result;
        }
    }

    /** Executes only explicitly queued adapter/owner work, with no polling or sleeps. */
    private static final class Deferred implements Executor {
        private final Queue<Runnable> queue = new ArrayDeque<Runnable>();
        @Override public synchronized void execute(Runnable task) { queue.add(task); notifyAll(); }
        private synchronized Runnable poll() { return queue.poll(); }
        void runAll() {
            int count = 0;
            for (Runnable task = poll(); task != null; task = poll()) {
                check(++count < 1000, "bounded cleanup task count"); task.run();
            }
        }
        void runUntil(BooleanSupplier finished) {
            int count = 0;
            while (!finished.getAsBoolean()) {
                Runnable task = take();
                check(++count < 1000, "bounded native cleanup task count"); task.run();
            }
        }
        private synchronized Runnable take() {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
            while (queue.isEmpty()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new AssertionError("deadlock guard: no scheduled cleanup progress");
                try { TimeUnit.NANOSECONDS.timedWait(this, remaining); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError("cleanup wait", interrupted);
                }
            }
            return queue.remove();
        }
    }

    private static final class Failures implements CaptureSession.FailureSink {
        private final List<Failure> values = new ArrayList<Failure>();
        @Override public synchronized void accept(Failure failure) { values.add(failure); }
        synchronized boolean has(FailureKind kind, OperationKey operation) {
            for (Failure value : values) if (value.kind == kind && value.operation == operation) return true;
            return false;
        }
    }

    /** An independent close precondition oracle, not a replacement NativeEvidence producer. */
    private static List<String> closeViolations(boolean nativeActive, boolean nativeDrained,
                                                long borrows, int priorCloses) {
        List<String> result = new ArrayList<String>();
        if (nativeActive) result.add("CLOSE_WITH_NATIVE_STACK");
        else if (!nativeDrained) result.add("CLOSE_BEFORE_NATIVE_DRAIN");
        if (borrows != 0) result.add("CLOSE_WITH_OWNER_BORROW");
        if (priorCloses != 0) result.add("DUPLICATE_OWNER_CLOSE");
        return result;
    }

    private static final class Platform implements PlatformUsbPort, PlatformUsbPort.Connection {
        final Binding binding;
        final JniBackendPort backend;
        final List<String> violations = new ArrayList<String>();
        IsolatedCaptureSession session;
        StopKey stop;
        long borrows;
        int closeCalls;
        boolean closed;
        Platform(Binding binding, JniBackendPort backend) { this.binding = binding; this.backend = backend; }
        @Override public void requestPermission(PermissionRequest request, PermissionCallback callback) {
            throw new AssertionError("this host JNI fixture uses existing synthetic permission only");
        }
        @Override public boolean isCurrentAttachment(Binding value) { return binding.equals(value) && !closed; }
        @Override public boolean hasPermission(Binding value) { return isCurrentAttachment(value); }
        @Override public Connection openConnection(Binding value) {
            check(binding.equals(value), "platform open binding"); return this;
        }
        @Override public int getFileDescriptor() { return 0; }
        @Override public synchronized void close() {
            JniBackendPort.Snapshot state = backend.snapshot();
            boolean active = state.nativeCalls != 0 || state.callbackRefs != 0 || state.workerAlive
                || state.attachedThreads != 0;
            boolean drained = state.contextReleased && state.pumpJoined && state.liveTransfers == 0
                && state.globalRefs == 0 && state.nativeBufferBytes == 0 && session.nativeEvidence(stop).drained();
            violations.addAll(closeViolations(active, drained, borrows, closeCalls));
            closeCalls++;
            if (!violations.isEmpty()) throw new AssertionError("independent close ledger: " + violations);
            closed = true;
        }
    }

    /** A real separately allocated application owner, never represented as a JNI-produced frame. */
    private static final class AppFrames implements FrameSink {
        final StopKey stop;
        OwnedRgba held;
        boolean revoked;
        long revision = 1;
        int deliveries;
        AppFrames(StopKey stop) { this.stop = stop; }
        @Override public synchronized void accept(OwnedRgba frame) {
            deliveries++;
            if (revoked) { frame.close(); return; }
            check(held == null, "one separately owned application frame"); held = frame; revision++;
        }
        synchronized void revoke() { if (!revoked) { revoked = true; revision++; } }
        synchronized void release() { if (held != null) { held.close(); held = null; revision++; } }
        synchronized BufferEvidence evidence() {
            return new BufferEvidence(stop, revision, revoked, revoked, 0, held == null ? 0 : OwnedRgba.LENGTH, 0);
        }
    }

    private static final class Fixture {
        final Binding binding;
        final StopKey stop;
        final ByteBudget budget = new ByteBudget(4L * 1024 * 1024);
        final Deferred nativeQueue = new Deferred(), ownerQueue = new Deferred();
        final Failures failures = new Failures();
        final JniBackendPort backend;
        final IsolatedCaptureSession session;
        final Platform platform;
        final UsbConnectionOwner owner;
        final AppFrames app;
        UsbConnectionOwner.BorrowLease borrow;
        Fixture(long generation, boolean autoStatus) {
            binding = new Binding(new Selection("jni-session", "selection", "host-boot", generation, 7),
                "jni-owner-" + generation, generation, generation);
            stop = new StopKey(binding, "canonical-stop");
            backend = new JniBackendPort(binding); backend.setAutoStatus(autoStatus);
            session = new IsolatedCaptureSession(binding, budget, failures, backend, nativeQueue);
            platform = new Platform(binding, backend); platform.session = session; platform.stop = stop;
            owner = new UsbConnectionOwner(binding, platform, session, failures, ownerQueue);
            app = new AppFrames(stop);
        }
        OperationKey op(String name, CallKind kind, Channel channel) {
            return new OperationKey(binding, name, 1, kind, channel);
        }
        void open() {
            check(owner.open().ready, "synthetic platform owner opens explicitly");
            borrow = owner.borrow(); platform.borrows++;
            check(borrow.descriptor.fd == 0, "FD zero is a borrowed identity, not an OS handle");
            check(session.open(op("native-open", CallKind.OPEN, Channel.DEVICE), borrow.descriptor, WIRE).failure == null,
                  "actual parent adapter enters native open");
        }
        void start() {
            check(session.start(op("native-start", CallKind.START, Channel.DEVICE), WIRE, app).failure == null,
                  "actual adapter starts only by explicit fixture action");
        }
        void stop() { app.revoke(); owner.requestStop(stop); }
        void stopOnThread() { new Task<Void>("bounded-stop", () -> { stop(); return null; }).finish(); }
        void gate(JniBackendPort.Gate gate) { check(backend.awaitEntered(gate, WAIT_MS), "native gate entered: " + gate); }
        Task<Void> drainTask() {
            return new Task<Void>("native-cleanup", () -> {
                nativeQueue.runUntil(() -> session.nativeEvidence(stop).drained()); return null;
            });
        }
        void observeOwnerPending() {
            owner.refreshNativeDrain(stop); ownerQueue.runAll();
            check(!owner.ownerEvidence(stop).drained() && platform.closeCalls == 0,
                  "actual owner retains its issued borrow or pending native obligation");
        }
        void finishNative() { nativeQueue.runUntil(() -> session.nativeEvidence(stop).drained()); }
        void finishOwner(long expectedAppBytes) {
            NativeEvidence evidence = session.nativeEvidence(stop);
            check(evidence.drained(), "actual adapter has completed native drain");
            JniBackendPort.Snapshot state = backend.snapshot();
            check(state.contextReleased && state.pumpJoined && !state.workerAlive
                  && state.nativeCalls == 0 && state.callbackRefs == 0 && state.attachedThreads == 0,
                  "native stacks, callbacks, attached worker and context all ended");
            check(state.liveTransfers == 0 && state.nativeBufferBytes == 0 && state.globalRefs == 0,
                  "native transfer buffers and global references are actually released");
            check(state.attachCount == state.detachCount && state.attachCount == 1,
                  "one actual native worker attachment is matched by detach");
            observeOwnerPending();
            check(platform.borrows == 1, "independent connection ledger still owns one issued borrow");
            owner.returnBorrow(borrow); platform.borrows--; borrow = null;
            owner.refreshNativeDrain(stop); ownerQueue.runAll();
            check(owner.ownerEvidence(stop).drained() && platform.closeCalls == 1 && platform.closed,
                  "platform owner alone closes once after true native drain and borrow return");
            check(platform.violations.isEmpty(), "independent connection ledger has no early or duplicate close");
            check(budget.used() == expectedAppBytes, "native reservations returned independently of application bytes");
            long revision = session.nativeEvidence(stop).revision;
            owner.requestStop(stop); ownerQueue.runAll();
            check(session.nativeEvidence(stop).revision == revision && platform.closeCalls == 1,
                  "terminal native evidence freezes and repeated stop cannot close twice");
        }
        void finish() { finishNative(); finishOwner(0); }
    }

    private static void earlySubmit() {
        Fixture f = new Fixture(1, false); f.open();
        OperationKey operation = f.op("early-status", CallKind.SUBMIT, Channel.STATUS);
        AtomicReference<Thread> callbackThread = new AtomicReference<Thread>();
        Thread caller = Thread.currentThread();
        f.backend.setCallbackHook(value -> callbackThread.set(Thread.currentThread()));
        f.backend.arm(JniBackendPort.Gate.SUBMIT_RETURN); f.backend.arm(JniBackendPort.Gate.CALLBACK_TAIL);
        Task<BackendPort.SubmitResult> submitting = new Task<BackendPort.SubmitResult>("submit-caller",
            () -> f.backend.submitFromDriver(operation, 64));
        try {
            f.gate(JniBackendPort.Gate.SUBMIT_RETURN);
            f.backend.queueTerminal(operation, 0); f.gate(JniBackendPort.Gate.CALLBACK_TAIL);
            f.stopOnThread(); f.nativeQueue.runAll(); f.observeOwnerPending();
            JniBackendPort.Snapshot state = f.backend.snapshot();
            check(callbackThread.get() != null && callbackThread.get() != caller,
                  "actual std::thread invoked Java on its attached thread");
            check(state.liveTransfers == 1 && state.freedTransfers == 0 && state.nativeBufferBytes >= 64,
                  "early terminal cannot release an in-flight submit or native callback tail");
            check(f.session.nativeEvidence(f.stop).callbacks == 0 && !f.session.nativeEvidence(f.stop).drained(),
                  "Java lease ended but submit/native obligations still block drain");
        } finally {
            f.backend.release(JniBackendPort.Gate.CALLBACK_TAIL); f.backend.release(JniBackendPort.Gate.SUBMIT_RETURN);
        }
        check(submitting.finish().status == BackendPort.SubmitStatus.ACCEPTED, "early terminal preserves accepted submit result");
        f.finish();
        check(f.backend.snapshot().completedCallbacks == 1, "one real callback completed");
    }

    private static void callbackTail() {
        Fixture f = new Fixture(2, true); f.open();
        f.backend.arm(JniBackendPort.Gate.CALLBACK_TAIL);
        f.backend.queueTerminal(f.backend.statusOperation(), 0); f.gate(JniBackendPort.Gate.CALLBACK_TAIL);
        f.stopOnThread();
        Task<Void> draining = f.drainTask();
        try {
            check(f.backend.awaitReleaseWaiting(WAIT_MS), "actual releaseTransfer reached native callback-tail wait");
            JniBackendPort.Snapshot state = f.backend.snapshot();
            check(state.releaseWaiters == 1 && state.callbackRefs > 0 && state.liveTransfers == 1 && state.freedTransfers == 0,
                  "native release waits after Java callback has exited");
            check(f.session.nativeEvidence(f.stop).callbacks == 0 && !f.session.nativeEvidence(f.stop).drained(),
                  "actual adapter counts release call while independent C++ callback still borrows");
            f.observeOwnerPending();
        } finally { f.backend.release(JniBackendPort.Gate.CALLBACK_TAIL); }
        draining.finish(); f.finishOwner(0);
    }

    private static final class CallbackSentinel extends RuntimeException {
        private static final long serialVersionUID = 1L;
        CallbackSentinel() { super("expected-callback-sentinel"); }
    }

    private static void callbackException() {
        Fixture f = new Fixture(3, true); f.open();
        CallbackSentinel sentinel = new CallbackSentinel();
        f.backend.setCallbackHook(operation -> { throw sentinel; });
        f.backend.arm(JniBackendPort.Gate.CALLBACK_TAIL);
        f.backend.queueTerminal(f.backend.statusOperation(), 0);
        try {
            f.gate(JniBackendPort.Gate.CALLBACK_TAIL);
            check(f.backend.firstCallbackFailure() == sentinel, "exact original Java callback throwable is retained");
            check(f.backend.snapshot().callbackExceptions == 1, "JNI detected and cleared the expected pending callback exception");
            check(f.backend.snapshot().contextId > 0, "independent subsequent JNI call succeeds after exception boundary");
            f.stopOnThread(); f.observeOwnerPending();
        } finally { f.backend.release(JniBackendPort.Gate.CALLBACK_TAIL); }
        f.finish();
        check(f.backend.snapshot().completedCallbacks == 1 && f.backend.snapshot().callbackExceptions == 1,
              "throwing callback still retired exactly once and native cleanup completed");
    }

    private static void controlTimeout() {
        Fixture f = new Fixture(4, false); f.open();
        OperationKey control = f.op("blocking-control", CallKind.CONTROL, Channel.CONTROL);
        f.backend.arm(JniBackendPort.Gate.CONTROL_RETURN);
        Task<Failure> running = new Task<Failure>("blocking-native-control", () -> f.backend.controlFromDriver(control));
        try {
            f.gate(JniBackendPort.Gate.CONTROL_RETURN);
            f.backend.timeout(control); f.stopOnThread(); f.nativeQueue.runAll(); f.observeOwnerPending();
            check(f.failures.has(FailureKind.TIMEOUT, control), "exact outer timeout remains a typed failure on its operation");
            JniBackendPort.Snapshot state = f.backend.snapshot();
            check(state.nativeCalls > 0 && !state.contextReleased && !state.pumpJoined && state.workerAlive,
                  "outer timeout does not delete the still-active native control stack or pump");
            check(f.session.nativeEvidence(f.stop).nativeCalls > 0 && !f.session.nativeEvidence(f.stop).drained(),
                  "actual adapter preserves blocking call obligation after timeout");
        } finally { f.backend.release(JniBackendPort.Gate.CONTROL_RETURN); }
        check(running.finish() == null, "successful eventual native return is distinct from retained outer timeout");
        f.finish();
        check(f.failures.has(FailureKind.TIMEOUT, control), "safe final drain does not erase timeout diagnosis");
    }

    private static void lateCompletion() {
        Fixture old = new Fixture(5, true); old.backend.setCancelCompletes(false); old.open(); old.start();
        old.stopOnThread(); old.nativeQueue.runAll(); old.observeOwnerPending();
        check(old.backend.snapshot().liveTransfers == 1 && !old.session.nativeEvidence(old.stop).drained(),
              "missing native terminal keeps cancellation pending without forced free");
        Fixture fresh = new Fixture(6, false); fresh.open();
        long freshId = fresh.backend.snapshot().contextId;
        check(old.backend.snapshot().borrowedFd == 0 && fresh.backend.snapshot().borrowedFd == 0
              && freshId != old.backend.snapshot().contextId, "numeric FD reuse never merges native context identity");
        old.backend.arm(JniBackendPort.Gate.CALLBACK_TAIL);
        old.backend.queueTerminal(old.backend.statusOperation(), 0);
        try {
            old.gate(JniBackendPort.Gate.CALLBACK_TAIL);
            check(old.app.deliveries == 0 && old.session.nativeEvidence(old.stop).admissionClosed,
                  "late status terminal settles only old work and cannot reopen publication");
        } finally { old.backend.release(JniBackendPort.Gate.CALLBACK_TAIL); }
        old.finish();
        check(fresh.platform.closeCalls == 0 && fresh.backend.snapshot().contextId == freshId
              && !fresh.backend.snapshot().contextReleased, "old native drain/owner close cannot dispose replacement context");
        fresh.stop(); fresh.finish();
    }

    private static void cancelOverlap() {
        Fixture f = new Fixture(7, true); f.backend.setCancelCompletes(false); f.open();
        f.backend.arm(JniBackendPort.Gate.CANCEL_RETURN); f.backend.arm(JniBackendPort.Gate.CALLBACK_TAIL);
        f.stopOnThread(); Task<Void> draining = f.drainTask();
        try {
            f.gate(JniBackendPort.Gate.CANCEL_RETURN);
            f.backend.queueTerminal(f.backend.statusOperation(), 0); f.gate(JniBackendPort.Gate.CALLBACK_TAIL);
            check(f.backend.snapshot().liveTransfers == 1 && f.backend.snapshot().freedTransfers == 0
                  && f.backend.snapshot().nativeCalls > 0, "terminal does not free transfer while native cancel call remains in stack");
            f.observeOwnerPending();
        } finally {
            f.backend.release(JniBackendPort.Gate.CALLBACK_TAIL); f.backend.release(JniBackendPort.Gate.CANCEL_RETURN);
        }
        draining.finish(); f.finishOwner(0);
    }

    private static void composedStop() {
        Fixture f = new Fixture(8, true); f.open(); f.start();
        OwnedRgba frame = OwnedRgba.allocate(f.budget, f.binding, 1, 100);
        Arrays.fill(frame.pixelsForOwner(), (byte) 0x5a);
        f.app.accept(frame); // Independent application fixture; JNI backend has no pixel production API.
        f.stopOnThread(); f.finishNative(); f.finishOwner(OwnedRgba.LENGTH);
        StopBarrier barrier = new StopBarrier(f.stop);
        barrier.update(f.session.nativeEvidence(f.stop)); barrier.update(f.owner.ownerEvidence(f.stop));
        barrier.update(f.app.evidence());
        check(!barrier.snapshot().drained && f.platform.closeCalls == 1 && f.budget.used() == OwnedRgba.LENGTH,
              "real native/owner drain remains separate from still-owned application bytes");
        f.app.release(); barrier.update(f.app.evidence());
        check(barrier.snapshot().drained && !barrier.snapshot().protocolViolation && f.budget.used() == 0,
              "actual three-producer stop closes only after independent application ownership ends");
    }

    private static void negativeControls() {
        check(closeViolations(true, true, 0, 0).equals(Arrays.asList("CLOSE_WITH_NATIVE_STACK")),
              "precise oracle detects native stack alone despite claimed drain and zero borrows");
        negativeControls++;
        check(closeViolations(false, true, 1, 0).equals(Arrays.asList("CLOSE_WITH_OWNER_BORROW")),
              "precise oracle detects sole outstanding owner borrow after native stack/drain completion");
        negativeControls++;
        check(closeViolations(false, true, 0, 0).isEmpty(), "same independent oracle accepts complete prerequisites");
    }
}

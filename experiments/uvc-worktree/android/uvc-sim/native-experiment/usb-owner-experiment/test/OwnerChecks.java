package local.sidecar.copilot.usbowner;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import local.sidecar.copilot.uvcadapter.BackendPort.SubmitStatus;
import local.sidecar.copilot.uvcadapter.IsolatedCaptureSession;
import local.sidecar.copilot.uvcadapter.OwnedFrameBridge;
import local.sidecar.copilot.uvccontract.CaptureSession;
import local.sidecar.copilot.uvccontract.StopBarrier;
import static local.sidecar.copilot.uvccontract.UvcContract.*;
import static local.sidecar.copilot.usbowner.OwnerTestSupport.*;

/** Isolated, deterministic Java platform-owner checks; no Android, JNI, OS FD or hardware. */
public final class OwnerChecks {
    private static final WireFormat WIRE = new WireFormat("YUY2", 640, 360, 333333);
    private static int cases, negativeControls;
    private OwnerChecks() {}

    public static void main(String[] args) {
        run("construction_and_wrong_producer_identity", OwnerChecks::construction);
        run("permission_denial_is_permanent", OwnerChecks::denial);
        run("nonce_selection_attachment_revision_and_pending_request", OwnerChecks::nonceIdentity);
        run("early_grant_request_stack_and_late_throw", OwnerChecks::earlyGrant);
        run("grant_is_only_hint_system_permission_rechecked", OwnerChecks::grantHint);
        run("late_permission_after_terminal_stop", OwnerChecks::latePermission);
        run("stop_during_system_permission_check", OwnerChecks::stopPermissionCheck);
        run("late_open_connection_remains_old_owner", OwnerChecks::lateOpen);
        run("stop_during_getfd", OwnerChecks::stopGetFd);
        run("post_open_attachment_and_permission_rechecks", OwnerChecks::postOpenRechecks);
        run("fd_zero_and_negative_rejection", OwnerChecks::fdValues);
        run("borrow_capability_duplicate_return_and_fd_reuse", OwnerChecks::borrowCapabilities);
        run("detach_revoke_and_native_admission_stop", OwnerChecks::invalidation);
        run("native_stack_pending_after_borrow_return", OwnerChecks::nativeStack);
        run("actual_adapter_owner_and_application_buffer_stop", OwnerChecks::composedStop);
        run("close_stack_wrong_keys_and_terminal_freeze", OwnerChecks::closeStack);
        run("close_exception_pending_and_failure_sink_isolated", OwnerChecks::closeFailure);
        run("open_exception_acquisition_unknown_stays_pending", OwnerChecks::openFailure);
        run("bound_producer_contradictions_block_close", OwnerChecks::producerContradictions);
        run("native_stop_exception_cannot_certify_success", OwnerChecks::nativeStopException);
        run("owner_executor_inline_and_rejection_stay_pending", OwnerChecks::badExecutors);
        run("independent_precise_early_close_negative_controls", OwnerChecks::negativeControls);
        System.out.println("OWNER_CHECKS_OK cases=" + cases + " assertions=" + assertions()
            + " negative_controls=" + negativeControls);
    }

    private static void run(String name, Runnable test) {
        test.run(); cases++; System.out.println("PASS " + name);
    }
    private static Binding binding(long generation) {
        return new Binding(new Selection("session", "selected", "boot", 4, 9),
            "owner-" + generation, generation, generation);
    }
    private static OperationKey op(Binding binding, String id, CallKind kind, Channel channel) {
        return new OperationKey(binding, id, 1, kind, channel);
    }

    private static final class Failures implements CaptureSession.FailureSink {
        final List<Failure> received = new ArrayList<Failure>();
        volatile boolean throwing;
        @Override public synchronized void accept(Failure failure) {
            received.add(failure);
            if (throwing) throw new IllegalStateException("controlled failure receiver");
        }
        synchronized boolean has(FailureKind kind) {
            for (Failure failure : received) if (failure.kind == kind) return true;
            return false;
        }
        synchronized Failure last() { return received.get(received.size() - 1); }
    }

    /** Instrumentation delegates every operation to the actual parent adapter. No forged evidence. */
    private static final class CountingSession implements CaptureSession {
        private final CaptureSession actual;
        final AtomicInteger reads = new AtomicInteger();
        CountingSession(CaptureSession actual) { this.actual = actual; }
        @Override public Binding binding() { return actual.binding(); }
        @Override public OpenResult open(OperationKey operation, BorrowedFd fd, WireFormat wire) {
            return actual.open(operation, fd, wire);
        }
        @Override public StartResult start(OperationKey operation, WireFormat wire, FrameSink sink) {
            return actual.start(operation, wire, sink);
        }
        @Override public void requestStop(StopKey key) { actual.requestStop(key); }
        @Override public NativeEvidence nativeEvidence(StopKey key) {
            reads.incrementAndGet(); return actual.nativeEvidence(key);
        }
    }

    private static final class Fixture {
        final Binding binding;
        final StopKey stop;
        final ControlledPlatform platform = new ControlledPlatform();
        final OwnerBackend backend = new OwnerBackend(platform);
        final ByteBudget budget = new ByteBudget(8L * 1024 * 1024);
        final ManualExecutor nativeExecutor = new ManualExecutor();
        final ManualExecutor ownerExecutor = new ManualExecutor();
        final Failures nativeFailures = new Failures(), ownerFailures = new Failures();
        final IsolatedCaptureSession session;
        final CountingSession producer;
        final UsbConnectionOwner owner;
        final Map<UsbConnectionOwner.BorrowLease, Boolean> live = new IdentityHashMap<UsbConnectionOwner.BorrowLease, Boolean>();
        Fixture() { this(1); }
        Fixture(long generation) {
            binding = OwnerChecks.binding(generation);
            stop = new StopKey(binding, "canonical-stop");
            session = new IsolatedCaptureSession(binding, budget, nativeFailures, backend, nativeExecutor);
            producer = new CountingSession(session);
            owner = new UsbConnectionOwner(binding, platform, producer, ownerFailures, ownerExecutor);
        }
        PermissionRequest request(String nonce) { return new PermissionRequest(binding.selection, nonce); }
        void open() { check(owner.open().ready, "explicit owner open ready"); }
        UsbConnectionOwner.BorrowLease borrow() {
            UsbConnectionOwner.BorrowLease lease = owner.borrow();
            live.put(lease, Boolean.TRUE); platform.borrowEntered(); return lease;
        }
        void giveBack(UsbConnectionOwner.BorrowLease lease) {
            owner.returnBorrow(lease);
            if (live.remove(lease) != null) platform.borrowReturned();
        }
        void nativeOpen(UsbConnectionOwner.BorrowLease lease) {
            CaptureSession.OpenResult result = session.open(op(binding, "native-open", CallKind.OPEN, Channel.DEVICE),
                lease.descriptor, WIRE);
            check(result.failure == null && WIRE.equals(result.actualWire), "actual adapter borrows owner FD");
        }
        void stop() { owner.requestStop(stop); }
        OwnerEvidence evidence() { return owner.ownerEvidence(stop); }
        void settle() {
            ownerExecutor.runAll();
            nativeExecutor.runAll();
            owner.refreshNativeDrain(stop);
            ownerExecutor.runAll();
        }
        void healthy() {
            check(platform.violations().isEmpty(), "independent platform ledger: " + platform.violations());
            check(backend.violations().isEmpty(), "independent native backend ledger: " + backend.violations());
        }
        void finished(boolean acquired) {
            settle();
            check(evidence().drained(), "owner completed safe disposal");
            check(platform.closeCalls() == (acquired ? 1 : 0), "exact platform connection close count");
            check(!acquired || platform.closed(), "close actually returned");
            check(budget.used() == 0, "all test pixel/transfer reservations returned");
            healthy();
        }
    }

    private static void construction() {
        Fixture f = new Fixture();
        check(f.platform.calls() == 0 && f.backend.closeCalls() == 0, "constructor has no platform/native effects");
        check(f.owner.binding().equals(f.binding), "owner immutable binding");
        rejects(IllegalStateException.class, () -> f.owner.ownerEvidence(f.stop), "evidence before stop");
        rejects(IllegalArgumentException.class, () -> new UsbConnectionOwner(binding(2), f.platform,
            f.session, f.ownerFailures, f.ownerExecutor), "trusted producer binding mismatch");
        PermissionRequest wrong = new PermissionRequest(new Selection("different", "selected", "boot", 4, 9), "nonce");
        rejects(IllegalArgumentException.class, () -> f.owner.requestPermission(wrong), "request selection mismatch");
        check(f.platform.requestCalls() == 0 && !f.owner.snapshot().permissionConsumed, "wrong request has no mutation");
        f.stop(); f.finished(false);
    }

    private static void denial() {
        Fixture f = new Fixture();
        PermissionRequest request = f.request("deny");
        f.platform.onRequest = () -> f.platform.reply(request, false);
        f.owner.requestPermission(request);
        check(f.owner.snapshot().permissionConsumed && !f.owner.snapshot().permissionGranted
              && f.owner.snapshot().admissionClosed, "matching denial consumed and closes admission");
        check(f.ownerFailures.has(FailureKind.PERMISSION), "denial retains typed permission failure");
        f.platform.reply(request, true);
        rejects(IllegalStateException.class, () -> f.owner.open(), "late grant cannot undo denial");
        check(f.platform.openCalls() == 0, "denied session never opens");
        f.stop(); f.finished(false);
    }

    private static void nonceIdentity() {
        Fixture f = new Fixture();
        PermissionRequest pending = f.request("current");
        f.owner.requestPermission(pending);
        rejects(IllegalStateException.class, () -> f.owner.open(), "request result pending despite system permission");
        check(f.platform.openCalls() == 0, "pending nonce cannot consume open attempt");
        List<PermissionRequest> stale = Arrays.asList(
            f.request("old-nonce"),
            new PermissionRequest(new Selection("old-session", "selected", "boot", 4, 9), "current"),
            new PermissionRequest(new Selection("session", "other-selection", "boot", 4, 9), "current"),
            new PermissionRequest(new Selection("session", "selected", "old-boot", 4, 9), "current"),
            new PermissionRequest(new Selection("session", "selected", "boot", 3, 9), "current"),
            new PermissionRequest(new Selection("session", "selected", "boot", 4, 8), "current"));
        for (PermissionRequest request : stale) {
            f.platform.reply(request, true);
            check(!f.owner.snapshot().permissionConsumed && !f.owner.snapshot().permissionGranted,
                  "mismatched identity cannot consume pending nonce");
        }
        f.platform.reply(null, true);
        check(!f.owner.snapshot().permissionConsumed, "null result ignored");
        f.platform.reply(pending, true);
        check(f.owner.snapshot().permissionConsumed && f.owner.snapshot().permissionGranted, "matching nonce consumed once");
        f.platform.reply(pending, false);
        check(f.owner.snapshot().permissionGranted && !f.owner.snapshot().admissionClosed, "duplicate result cannot revise grant");
        check(f.platform.openCalls() == 0, "grant callback never auto-opens");
        f.open(); f.stop(); f.finished(true);
    }

    private static void earlyGrant() {
        Fixture f = new Fixture();
        PermissionRequest request = f.request("early");
        Gate gate = new Gate("request-return");
        f.platform.onRequest = () -> {
            f.platform.reply(request, true); gate.enter();
            throw new PlatformUsbPort.PlatformFailure(FailureKind.IO, -71);
        };
        Task<Void> permission = new Task<Void>("permission-request", () -> { f.owner.requestPermission(request); return null; });
        try {
            gate.awaitEntered();
            check(f.owner.snapshot().permissionGranted && f.owner.snapshot().requestInFlight, "early callback registered before call");
            rejects(IllegalStateException.class, () -> f.owner.open(), "grant cannot outrun request return");
            check(f.platform.openCalls() == 0, "in-flight permission request did not consume open");
            new Task<Void>("stop-request", () -> { f.stop(); return null; }).finish();
            f.settle();
            check(!f.evidence().drained() && f.owner.snapshot().platformCalls == 1, "request stack prevents empty-owner terminal");
        } finally { gate.release(); }
        permission.finish();
        check(f.ownerFailures.last().kind == FailureKind.IO && f.ownerFailures.last().nativeCode == -71,
              "late request throw retained despite early grant");
        check(f.owner.snapshot().admissionClosed, "late request failure never reopens admission");
        f.finished(false);
    }

    private static void grantHint() {
        Fixture f = new Fixture();
        f.platform.permission = false;
        PermissionRequest request = f.request("hint");
        f.platform.onRequest = () -> f.platform.reply(request, true);
        f.owner.requestPermission(request);
        UsbConnectionOwner.OpenResult result = f.owner.open();
        check(!result.ready && result.failure != null && result.failure.kind == FailureKind.PERMISSION,
              "system permission overrides grant hint");
        check(f.platform.openCalls() == 0, "no connection opened without current permission");
        f.stop(); f.finished(false);
    }

    private static void latePermission() {
        Fixture f = new Fixture();
        PermissionRequest request = f.request("late");
        f.owner.requestPermission(request);
        f.stop(); f.finished(false);
        long revision = f.evidence().revision;
        int calls = f.platform.calls();
        f.platform.reply(request, true); f.platform.reply(request, false);
        check(!f.owner.snapshot().permissionGranted && f.owner.snapshot().admissionClosed, "late permission never revives stopped owner");
        check(f.evidence().revision == revision && f.platform.calls() == calls, "late callback cannot change terminal evidence or touch platform");
    }

    private static void stopPermissionCheck() {
        Fixture f = new Fixture();
        Gate gate = new Gate("system-permission-return");
        f.platform.onPermission = gate::enter;
        Task<UsbConnectionOwner.OpenResult> opening = new Task<UsbConnectionOwner.OpenResult>("open-permission", () -> f.owner.open());
        try {
            gate.awaitEntered(); f.stop(); f.settle();
            check(!f.evidence().drained() && f.owner.snapshot().openOperations == 1, "open operation survives permission-check stop");
            check(f.platform.openCalls() == 0 && f.platform.closeCalls() == 0, "permission stack neither opens nor closes");
        } finally { gate.release(); }
        check(!opening.finish().ready, "stopped permission check cannot publish FD");
        f.finished(false);
    }

    private static void lateOpen() {
        Fixture old = new Fixture(1);
        Gate gate = new Gate("late-open-return"); old.platform.onOpen = gate::enter;
        Task<UsbConnectionOwner.OpenResult> opening = new Task<UsbConnectionOwner.OpenResult>("old-open", () -> old.owner.open());
        Fixture fresh = new Fixture(2);
        try {
            gate.awaitEntered(); old.stop(); old.settle();
            check(!old.evidence().drained() && old.platform.closeCalls() == 0, "late connection cannot close before open returns");
            fresh.open();
            check(fresh.platform.fd == old.platform.fd, "independent owners deliberately share numeric FD zero");
        } finally { gate.release(); }
        check(!opening.finish().ready && old.owner.snapshot().connectionAcquired, "late resource retained by original owner");
        old.finished(true);
        check(!fresh.platform.closed() && fresh.platform.closeCalls() == 0 && fresh.owner.snapshot().fdReady,
              "old cleanup cannot affect new owner with same numeric FD");
        fresh.stop(); fresh.finished(true);
    }

    private static void stopGetFd() {
        Fixture f = new Fixture();
        Gate gate = new Gate("getfd-return"); f.platform.onGetFd = gate::enter;
        Task<UsbConnectionOwner.OpenResult> opening = new Task<UsbConnectionOwner.OpenResult>("open-getfd", () -> f.owner.open());
        try {
            gate.awaitEntered();
            new Task<Void>("stop-getfd", () -> { f.stop(); return null; }).finish();
            f.settle();
            check(f.owner.snapshot().connectionAcquired && f.owner.snapshot().openOperations == 1
                  && f.owner.snapshot().platformCalls == 1, "getFD call and whole open remain registered");
            check(!f.evidence().drained() && f.platform.closeCalls() == 0, "getFD return is required before connection close");
        } finally { gate.release(); }
        check(!opening.finish().ready && !f.owner.snapshot().fdReady, "late FD does not restore borrow admission");
        rejects(IllegalStateException.class, () -> f.owner.borrow(), "borrow after getFD stop");
        f.finished(true);
    }

    private static void postOpenRechecks() {
        Fixture detached = new Fixture();
        detached.platform.onOpen = () -> detached.platform.current = false;
        UsbConnectionOwner.OpenResult attachment = detached.owner.open();
        check(!attachment.ready && attachment.failure.kind == FailureKind.DETACHED, "attachment rechecked after connection acquisition");
        check(detached.platform.getFdCalls() == 0, "stale attachment never reads FD");
        detached.stop(); detached.finished(true);
        Fixture revoked = new Fixture();
        revoked.platform.onGetFd = () -> revoked.platform.permission = false;
        UsbConnectionOwner.OpenResult permission = revoked.owner.open();
        check(!permission.ready && permission.failure.kind == FailureKind.PERMISSION, "system permission rechecked after getFD");
        check(!revoked.owner.snapshot().fdReady, "revoked FD never enters borrow admission");
        revoked.stop(); revoked.finished(true);
    }

    private static void fdValues() {
        Fixture zero = new Fixture(); zero.open();
        UsbConnectionOwner.BorrowLease lease = zero.borrow();
        check(lease.descriptor.fd == 0 && lease.descriptor.binding.equals(zero.binding), "FD zero preserves full binding");
        zero.stop(); zero.settle();
        check(zero.platform.closeCalls() == 0 && !zero.evidence().drained(), "live FD zero borrow blocks owner close");
        zero.giveBack(lease); zero.finished(true);
        Fixture negative = new Fixture(); negative.platform.fd = -1;
        UsbConnectionOwner.OpenResult result = negative.owner.open();
        check(!result.ready && result.failure != null && result.failure.kind == FailureKind.IO, "negative FD fails with typed IO");
        rejects(IllegalStateException.class, () -> negative.owner.borrow(), "negative FD cannot be borrowed");
        negative.stop(); negative.finished(true);
    }

    private static void borrowCapabilities() {
        Fixture old = new Fixture(1), fresh = new Fixture(2);
        old.open(); fresh.open();
        UsbConnectionOwner.BorrowLease a = old.borrow(), b = fresh.borrow();
        check(a.descriptor.fd == b.descriptor.fd && !a.descriptor.binding.equals(b.descriptor.binding), "FD number is not capability identity");
        int sourceConstructors = 0;
        for (Constructor<?> constructor : UsbConnectionOwner.BorrowLease.class.getDeclaredConstructors()) {
            int modifiers = constructor.getModifiers();
            check(!Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers), "borrow capability has no exported constructor");
            // Java 8 emits a package-private synthetic access bridge for the enclosing class.
            // This is an ordinary API boundary, not protection against reflection or Unsafe.
            if (!constructor.isSynthetic()) {
                sourceConstructors++;
                check(Modifier.isPrivate(modifiers), "source borrow constructor remains private");
            }
        }
        check(sourceConstructors > 0, "source borrow constructor was inspected");
        rejects(IllegalArgumentException.class, () -> fresh.owner.returnBorrow(a), "foreign capability return");
        rejects(IllegalArgumentException.class, () -> old.owner.returnBorrow(null), "null capability return");
        check(old.owner.snapshot().borrows == 1 && fresh.owner.snapshot().borrows == 1, "invalid returns preserve both owners");
        old.giveBack(a); old.giveBack(a); a.close();
        check(old.owner.snapshot().borrows == 0 && fresh.owner.snapshot().borrows == 1, "duplicate return cannot affect other owner");
        old.stop(); old.finished(true);
        check(!fresh.platform.closed(), "old FD-number cleanup cannot close new connection");
        fresh.stop(); fresh.settle();
        check(!fresh.evidence().drained() && fresh.platform.closeCalls() == 0, "other owner's live capability still blocks close");
        fresh.giveBack(b); fresh.finished(true);
    }

    private static void invalidation() {
        for (FailureKind reason : Arrays.asList(FailureKind.DETACHED, FailureKind.PERMISSION)) {
            Fixture f = new Fixture(); f.open();
            UsbConnectionOwner.BorrowLease lease = f.borrow(); f.nativeOpen(lease);
            rejects(IllegalArgumentException.class, () -> f.owner.invalidate(binding(2), reason), "old-generation invalidation");
            check(!f.owner.snapshot().admissionClosed, "foreign invalidation has no effect");
            f.owner.invalidate(f.binding, reason);
            check(f.ownerFailures.has(reason) && f.owner.snapshot().admissionClosed, "explicit revoke/detach closes owner admission");
            f.stop();
            check(f.session.nativeEvidence(f.stop).admissionClosed, "owner stop synchronously closes native admission");
            check(f.backend.events.submit(op(f.binding, "after-stop", CallKind.SUBMIT, Channel.STATUS), 32).status == SubmitStatus.REJECTED,
                  "native status submit cannot slip in before deferred owner cleanup");
            f.settle();
            check(f.session.nativeEvidence(f.stop).drained() && f.platform.closeCalls() == 0, "native drained but issued borrow still blocks owner");
            f.giveBack(lease); f.finished(true);
        }
    }

    private static void nativeStack() {
        Fixture f = new Fixture(); f.open();
        UsbConnectionOwner.BorrowLease lease = f.borrow(); f.nativeOpen(lease);
        Gate gate = new Gate("native-control-return"); f.backend.onControl = gate::enter;
        Task<Failure> control = new Task<Failure>("native-control", () -> f.backend.events.control(
            op(f.binding, "control", CallKind.CONTROL, Channel.CONTROL)));
        try {
            gate.awaitEntered();
            f.stop(); f.giveBack(lease); f.settle();
            check(f.owner.snapshot().borrows == 0 && !f.session.nativeEvidence(f.stop).drained(), "native call remains after explicit borrow return");
            check(f.platform.closeCalls() == 0 && !f.evidence().drained(), "zero owner borrows cannot substitute for native drain");
        } finally { gate.release(); }
        check(control.finish() == null, "actual native control returns");
        f.finished(true);
    }

    private static final class AppBuffers implements FrameSink {
        final StopKey key;
        OwnedRgba held;
        long revision = 1;
        boolean revoked;
        AppBuffers(StopKey key) { this.key = key; }
        @Override public void accept(OwnedRgba frame) {
            if (revoked) { frame.close(); return; }
            check(held == null, "exclusive application frame"); held = frame; revision++;
        }
        void revoke() { if (!revoked) { revoked = true; revision++; } }
        void release() { if (held != null) { held.close(); held = null; revision++; } }
        BufferEvidence evidence() {
            return new BufferEvidence(key, revision, revoked, revoked, 0, held == null ? 0 : OwnedRgba.LENGTH, 0);
        }
    }

    private static void composedStop() {
        Fixture f = new Fixture(); f.open();
        UsbConnectionOwner.BorrowLease lease = f.borrow(); f.nativeOpen(lease);
        AppBuffers app = new AppBuffers(f.stop);
        check(f.session.start(op(f.binding, "native-start", CallKind.START, Channel.DEVICE), WIRE, app).failure == null,
              "actual parent adapter start");
        try (Reservation source = f.budget.reserve(OwnedRgba.LENGTH)) {
            check(source.bytes == OwnedRgba.LENGTH, "source callback bytes share actual adapter budget");
            byte[] pixels = new byte[OwnedRgba.LENGTH];
            Arrays.fill(pixels, (byte) 0x37);
            check(f.backend.events.submit(op(f.binding, "image", CallKind.SUBMIT, Channel.IMAGE), 64).status == SubmitStatus.ACCEPTED,
                  "actual parent adapter transfer");
            f.backend.emit(new OwnedFrameBridge.BorrowedFrame(pixels, 0, pixels.length,
                OwnedRgba.WIDTH, OwnedRgba.HEIGHT, OwnedRgba.STRIDE, "RGBA", 1, 100));
            Arrays.fill(pixels, (byte) 0);
            boolean intact = app.held != null;
            if (intact) for (byte value : app.held.pixelsForOwner()) if (value != (byte) 0x37) intact = false;
            check(intact, "application owns independent bytes after callback source reuse");
        }
        app.revoke(); f.stop(); f.settle();
        NativeEvidence nativeDrain = f.session.nativeEvidence(f.stop);
        check(nativeDrain.drained() && f.owner.snapshot().borrows == 1 && f.platform.closeCalls() == 0,
              "actual native drain does not return platform borrow");
        f.giveBack(lease); f.settle();
        check(f.evidence().drained() && f.platform.closeCalls() == 1 && f.platform.closed(), "owner closes only after drain and capability return");
        check(f.budget.used() == OwnedRgba.LENGTH, "owned application bytes survive native and owner drain");
        StopBarrier barrier = new StopBarrier(f.stop);
        barrier.update(nativeDrain); barrier.update(f.evidence()); barrier.update(app.evidence());
        check(!barrier.snapshot().drained, "real StopBarrier still waits for application owned frame");
        long ownerRevision = f.evidence().revision, nativeRevision = nativeDrain.revision;
        app.release(); barrier.update(app.evidence());
        check(barrier.snapshot().drained && !barrier.snapshot().protocolViolation, "all three producers complete composed stop");
        long bufferRevision = app.evidence().revision;
        app.accept(OwnedRgba.allocate(f.budget, f.binding, 2, 101));
        check(app.evidence().revision == bufferRevision && app.held == null && f.budget.used() == 0,
              "closed application admission disposes late owned entry without changing terminal evidence");
        f.owner.refreshNativeDrain(f.stop); f.stop(); f.settle();
        check(f.evidence().revision == ownerRevision && f.session.nativeEvidence(f.stop).revision == nativeRevision,
              "owner and native terminal evidence remain frozen");
        check(f.budget.used() == 0, "application owner eventually returns its budget");
        f.healthy();
    }

    private static void closeStack() {
        Fixture f = new Fixture(); f.open();
        Gate gate = new Gate("platform-close-return"); f.platform.onClose = gate::enter;
        f.stop(); f.nativeExecutor.runAll(); f.owner.refreshNativeDrain(f.stop);
        long acceptedNativeRevision = f.session.nativeEvidence(f.stop).revision;
        Task<Void> closing = new Task<Void>("owner-close", () -> { f.ownerExecutor.runAll(); return null; });
        try {
            gate.awaitEntered();
            check(f.platform.closeCalls() == 1 && !f.evidence().drained() && f.owner.snapshot().platformCalls == 1,
                  "close entry cannot certify close return");
            int reads = f.producer.reads.get();
            f.owner.refreshNativeDrain(f.stop);
            check(f.producer.reads.get() == reads && !f.evidence().drained(),
                  "close commit seals producer reads while platform close is still pending");
            new Task<Void>("repeat-stop", () -> { f.stop(); return null; }).finish();
            long revision = f.evidence().revision;
            rejects(IllegalArgumentException.class, () -> f.owner.requestStop(new StopKey(f.binding, "other")), "different canonical stop");
            rejects(IllegalArgumentException.class, () -> f.owner.ownerEvidence(new StopKey(binding(2), "canonical-stop")), "wrong owner evidence");
            rejects(IllegalArgumentException.class, () -> f.owner.refreshNativeDrain(new StopKey(f.binding, "other")), "wrong native refresh key");
            check(f.evidence().revision == revision && f.platform.closeCalls() == 1, "wrong keys and repeated stop do not duplicate close");
        } finally { gate.release(); }
        closing.finish(); f.finished(true);
        check(f.evidence().nativeDrainRevision == acceptedNativeRevision,
              "owner terminal binds exactly the accepted actual native drain revision");
        long revision = f.evidence().revision;
        f.stop(); f.owner.refreshNativeDrain(f.stop); f.ownerExecutor.runAll();
        check(f.evidence().revision == revision && f.platform.closeCalls() == 1, "terminal owner frozen and unique close");
    }

    private static void closeFailure() {
        Fixture f = new Fixture(); f.open(); f.ownerFailures.throwing = true;
        f.platform.onClose = () -> { throw new PlatformUsbPort.PlatformFailure(FailureKind.IO, -72); };
        f.stop(); f.settle();
        check(f.owner.snapshot().closeUncertain && !f.evidence().drained() && !f.platform.closed(), "throwing close remains unconfirmed");
        check(f.ownerFailures.last().kind == FailureKind.IO && f.ownerFailures.last().nativeCode == -72
              && f.ownerFailures.last().operation.operationId.equals("usb-owner-close"), "original close failure identity retained");
        check(f.owner.failureSinkExceptions() == 1 && f.owner.failures().size() == 1, "failure receiver exception isolated");
        long revision = f.evidence().revision;
        f.stop(); f.settle(); f.settle();
        check(f.platform.closeCalls() == 1 && f.evidence().revision == revision, "uncertain close never retried or certified");
        f.healthy(); // Pending fake connection is intentionally not claimed safely disposed.
    }

    private static void openFailure() {
        Fixture f = new Fixture();
        f.platform.onOpen = () -> { throw new PlatformUsbPort.PlatformFailure(FailureKind.IO, -73); };
        UsbConnectionOwner.OpenResult result = f.owner.open();
        check(!result.ready && result.failure != null && result.failure.nativeCode == -73, "open failure retained");
        f.stop(); f.settle();
        check(f.owner.snapshot().openUncertain && !f.evidence().drained() && f.platform.closeCalls() == 0,
              "unknown acquisition cannot be guessed absent or safely closed");
        f.stop(); f.settle();
        check(f.platform.openCalls() == 1 && f.platform.closeCalls() == 0, "unknown acquisition does not retry or invent a handle");
        f.healthy();
    }

    private static void negativeControls() {
        ControlledPlatform call = new ControlledPlatform();
        PlatformUsbPort.Connection acquired = call.openConnection(binding(1));
        Gate gate = new Gate("negative-getfd-return"); call.onGetFd = gate::enter;
        Task<Integer> getFd = new Task<Integer>("negative-getfd", () -> acquired.getFileDescriptor());
        try {
            gate.awaitEntered();
            acquired.close(); // Deliberately unsafe: only the getFD call remains live.
            check(call.violations().equals(Collections.singletonList("EARLY_CLOSE_PLATFORM_CALL")),
                  "negative control specifically catches close during getFD");
            negativeControls++;
        } finally { gate.release(); }
        check(getFd.finish().intValue() == 0, "controlled getFD eventually returns zero");
        ControlledPlatform borrow = new ControlledPlatform();
        PlatformUsbPort.Connection second = borrow.openConnection(binding(2));
        borrow.borrowEntered(); // Native drain is independently true; no platform call is active.
        second.close();
        check(borrow.violations().equals(Collections.singletonList("EARLY_CLOSE_BORROW")),
              "negative control specifically catches close before borrow return");
        negativeControls++; borrow.borrowReturned();
    }

    /** Deliberately contract-breaking producer, confined to malformed-producer test vectors. */
    private static final class BadProducer implements CaptureSession {
        final Binding binding;
        NativeEvidence next;
        int reads, stopCalls;
        boolean throwStop, stopped;
        BadProducer(Binding binding) { this.binding = binding; }
        @Override public Binding binding() { return binding; }
        @Override public OpenResult open(OperationKey operation, BorrowedFd fd, WireFormat wire) {
            throw new UnsupportedOperationException("malformed-producer tests never open native");
        }
        @Override public StartResult start(OperationKey operation, WireFormat wire, FrameSink sink) {
            throw new UnsupportedOperationException("malformed-producer tests never start native");
        }
        @Override public void requestStop(StopKey key) {
            stopCalls++;
            if (throwStop) throw new IllegalStateException("controlled native stop failure");
            stopped = true;
        }
        @Override public NativeEvidence nativeEvidence(StopKey key) { reads++; return next; }
    }

    private static NativeEvidence pendingNative(StopKey key, long revision, long calls) {
        return new NativeEvidence(key, revision, true, calls, 0, 0, 0, 0, 0, false, false);
    }
    private static NativeEvidence completeNative(StopKey key, long revision) {
        return new NativeEvidence(key, revision, true, 0, 0, 0, 0, 0, 0, true, true);
    }

    private static void producerContradictions() {
        for (String vector : Arrays.asList("wrong-key", "revision-regression", "same-revision-conflict", "terminal-change")) {
            Binding binding = binding(1);
            StopKey key = new StopKey(binding, "stop");
            ControlledPlatform platform = new ControlledPlatform();
            BadProducer producer = new BadProducer(binding);
            Failures failures = new Failures();
            ManualExecutor executor = new ManualExecutor();
            UsbConnectionOwner owner = new UsbConnectionOwner(binding, platform, producer, failures, executor);
            check(owner.open().ready, vector + ": explicit connection acquired");
            UsbConnectionOwner.BorrowLease held = owner.borrow(); platform.borrowEntered();
            producer.next = vector.equals("terminal-change") ? completeNative(key, 2) : pendingNative(key, 2, 1);
            owner.requestStop(key); owner.refreshNativeDrain(key);
            if (vector.equals("wrong-key")) producer.next = completeNative(new StopKey(binding, "foreign"), 3);
            else if (vector.equals("revision-regression")) producer.next = pendingNative(key, 1, 1);
            else if (vector.equals("same-revision-conflict")) producer.next = pendingNative(key, 2, 2);
            else producer.next = completeNative(key, 3);
            owner.refreshNativeDrain(key);
            check(owner.snapshot().nativeEvidenceInvalid, vector + ": producer contradiction latched");
            Failure failure = failures.last();
            check(failure.kind == FailureKind.PROTOCOL && failure.operation.binding.equals(binding)
                  && failure.operation.operationId.equals("usb-owner-native-evidence"), vector + ": precise protocol diagnosis");
            owner.returnBorrow(held); platform.borrowReturned(); executor.runAll();
            check(!owner.ownerEvidence(key).drained() && platform.closeCalls() == 0,
                  vector + ": releasing last borrow cannot use invalid native evidence");
            check(producer.reads == 2 && platform.violations().isEmpty(), vector + ": invalid producer is sealed without platform close");
        }
    }

    private static void nativeStopException() {
        Binding binding = binding(1);
        StopKey key = new StopKey(binding, "stop");
        ControlledPlatform platform = new ControlledPlatform();
        BadProducer producer = new BadProducer(binding); producer.throwStop = true;
        Failures failures = new Failures();
        ManualExecutor executor = new ManualExecutor();
        UsbConnectionOwner owner = new UsbConnectionOwner(binding, platform, producer, failures, executor);
        check(owner.open().ready, "native-stop-failure connection acquired");
        owner.requestStop(key); // The owner isolates the exception and returns void; this is not success.
        executor.runAll();
        check(!producer.stopped && producer.stopCalls == 1 && owner.snapshot().nativeEvidenceInvalid
              && !owner.snapshot().nativeStopCompleted, "native stop exception preserves unsuccessful producer status");
        check(failures.last().kind == FailureKind.IO
              && failures.last().operation.operationId.equals("usb-owner-native-evidence"), "native stop error retains typed operation");
        rejects(IllegalStateException.class, () -> owner.refreshNativeDrain(key), "cannot observe completion of failed native stop");
        check(producer.reads == 0 && !owner.ownerEvidence(key).drained() && platform.closeCalls() == 0,
              "normal owner void return cannot certify drain or platform close");
        check(platform.violations().isEmpty(), "native stop failure remains pending without unsafe close");
    }

    private static void badExecutors() {
        for (boolean inline : Arrays.asList(Boolean.TRUE, Boolean.FALSE)) {
            Binding binding = binding(1);
            StopKey key = new StopKey(binding, "stop");
            ControlledPlatform platform = new ControlledPlatform();
            OwnerBackend backend = new OwnerBackend(platform);
            ByteBudget budget = new ByteBudget(1024);
            Failures failures = new Failures();
            ManualExecutor nativeExecutor = new ManualExecutor();
            IsolatedCaptureSession actual = new IsolatedCaptureSession(binding, budget, failures, backend, nativeExecutor);
            Executor broken = inline ? Runnable::run : command -> { throw new RejectedExecutionException("controlled owner executor"); };
            UsbConnectionOwner owner = new UsbConnectionOwner(binding, platform, actual, failures, broken);
            check(owner.open().ready, "bad-executor connection acquired");
            owner.requestStop(key); nativeExecutor.runAll(); owner.refreshNativeDrain(key);
            Failure failure = failures.last();
            check(failure.kind == FailureKind.PROTOCOL && failure.nativeCode == 0
                  && failure.operation.operationId.equals("usb-owner-scheduler")
                  && failure.operation.kind == CallKind.CLOSE && failure.operation.channel == Channel.DEVICE,
                  "inline/rejected executor emits exact scheduler diagnosis");
            check(actual.nativeEvidence(key).drained() && !owner.ownerEvidence(key).drained()
                  && !owner.snapshot().closeAttempted && platform.closeCalls() == 0,
                  "completed actual native producer cannot make broken owner executor close connection");
            check(platform.violations().isEmpty() && budget.used() == 0, "bad executor causes pending ownership, not fake platform work");
        }
    }
}

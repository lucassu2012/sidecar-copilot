package local.sidecar.copilot.usbbridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import local.sidecar.copilot.usbowner.UsbConnectionOwner;
import local.sidecar.copilot.uvcadapter.BackendPort.SubmitStatus;
import local.sidecar.copilot.uvcadapter.IsolatedCaptureSession;
import local.sidecar.copilot.uvcadapter.OwnedFrameBridge;
import local.sidecar.copilot.uvccontract.CaptureSession;
import local.sidecar.copilot.uvccontract.StopBarrier;
import static local.sidecar.copilot.uvccontract.UvcContract.*;
import static local.sidecar.copilot.usbbridge.BridgeTestSupport.*;

/** Executes real-SDK-compiled product classes against explicitly limited project-owned Android doubles. */
public final class BridgeChecks {
    private static final WireFormat WIRE = new WireFormat("YUY2", 640, 360, 333333);
    private static int cases, negativeControls;
    private BridgeChecks() {}

    public static void main(String[] args) {
        run("only_explicit_actions_request_and_open", BridgeChecks::explicitActions);
        run("malformed_permission_does_not_consume_registry", BridgeChecks::malformedPermission);
        run("synchronous_denial_stops_without_open", BridgeChecks::syncDenial);
        run("asynchronous_denial_revokes_without_root_hook", BridgeChecks::asyncDenial);
        run("early_callback_request_stack_and_stop", BridgeChecks::earlyCallback);
        run("revocation_cancel_unregister_and_late_callback", BridgeChecks::cancelAndLate);
        run("isolated_loader_has_empty_process_registry", BridgeChecks::registryLoss);
        run("permission_identity_is_unique_and_not_extras_only", BridgeChecks::uniqueIdentity);
        run("detach_and_same_numeric_fd_new_owner", BridgeChecks::detachAndReuse);
        run("lifecycle_and_permission_revocation_order", BridgeChecks::lifecycleOrder);
        run("preacquisition_permission_recheck_has_no_unknown_handle", BridgeChecks::knownNoAcquisition);
        run("detach_during_open_keeps_late_handle_old", BridgeChecks::lateOpen);
        run("lifecycle_stop_during_getfd", BridgeChecks::stopGetFd);
        run("negative_fd_rejected_and_old_connection_closed", BridgeChecks::negativeFd);
        run("actual_parent_adapter_owner_and_stop_barrier", BridgeChecks::composedStop);
        run("stop_during_receiver_registration", BridgeChecks::registrationStop);
        run("late_pending_intent_factory_return_is_cancelled", BridgeChecks::latePendingIntent);
        run("port_executor_inline_and_reject_keep_cleanup_pending", BridgeChecks::portExecutors);
        run("publication_gate_reentry_is_bounded", BridgeChecks::gateReentry);
        run("publication_gate_failure_cannot_fake_full_stop", BridgeChecks::gateFailure);
        run("sdk30_31_35_surface_branches_model_only", BridgeChecks::sdkBranches);
        run("double_pending_intent_fill_rules", BridgeChecks::fillRules);
        run("independent_dispatcher_negative_controls", BridgeChecks::negativeControls);
        System.out.println("BRIDGE_CHECKS_OK cases=" + cases + " assertions=" + assertions()
            + " negative_controls=" + negativeControls);
    }
    private static void run(String name, Runnable test) {
        Build.VERSION.SDK_INT = 35;
        test.run(); cases++; System.out.println("PASS " + name);
    }
    private static Binding binding(long generation) {
        return new Binding(new Selection("session", "selection", "boot", generation, 7),
                           "owner-" + generation, generation, generation);
    }
    private static OperationKey op(Binding binding, String name, CallKind kind, Channel channel) {
        return new OperationKey(binding, name, 1, kind, channel);
    }
    private static UsbDevice device(String name, int product) {
        return new UsbDevice(name, 14, 0x1234, product, 14, 1, 0);
    }
    private static Bundle result(UsbDevice device, boolean grant) {
        Bundle extras = new Bundle();
        extras.putParcelable(UsbManager.EXTRA_DEVICE, device);
        extras.putBoolean(UsbManager.EXTRA_PERMISSION_GRANTED, grant);
        return extras;
    }

    private static final class Failures implements CaptureSession.FailureSink {
        private final List<Failure> values = new ArrayList<Failure>();
        @Override public synchronized void accept(Failure failure) { values.add(failure); }
        synchronized boolean has(FailureKind kind) {
            for (Failure failure : values) if (failure.kind == kind) return true;
            return false;
        }
    }

    private static final class AppFrames implements FrameSink {
        final StopKey key;
        private OwnedRgba held;
        private long revision = 1;
        private boolean revoked;
        AppFrames(StopKey key) { this.key = key; }
        @Override public synchronized void accept(OwnedRgba frame) {
            if (revoked) { frame.close(); return; }
            check(held == null, "application slot owns one frame"); held = frame; revision++;
        }
        synchronized OwnedRgba held() { return held; }
        synchronized void revoke() { if (!revoked) { revoked = true; revision++; } }
        synchronized void release() { if (held != null) { held.close(); held = null; revision++; } }
        synchronized BufferEvidence evidence() {
            return new BufferEvidence(key, revision, revoked, revoked, 0, held == null ? 0 : OwnedRgba.LENGTH, 0);
        }
    }

    /** Delegates all native behavior to the actual parent adapter; only ordering is observed. */
    private static final class StopProbe implements CaptureSession {
        final IsolatedCaptureSession actual;
        final List<String> order;
        final List<String> violations = new ArrayList<String>();
        StopProbe(IsolatedCaptureSession actual, List<String> order) { this.actual = actual; this.order = order; }
        @Override public Binding binding() { return actual.binding(); }
        @Override public OpenResult open(OperationKey operation, BorrowedFd fd, WireFormat wire) { return actual.open(operation, fd, wire); }
        @Override public StartResult start(OperationKey operation, WireFormat wire, FrameSink sink) { return actual.start(operation, wire, sink); }
        @Override public void requestStop(StopKey key) {
            synchronized (order) {
                if (!order.contains("publication-gate")) violations.add("NATIVE_STOP_BEFORE_GATE");
                order.add("native-stop");
            }
            actual.requestStop(key);
        }
        @Override public NativeEvidence nativeEvidence(StopKey key) { return actual.nativeEvidence(key); }
    }

    private static final class Fixture {
        final Binding binding;
        final StopKey stop;
        final BridgeContext context = new BridgeContext("local.sidecar.host");
        final UsbManager manager = new UsbManager();
        final UsbDevice selected = device("/dev/bus/usb/001/002", 0x5678);
        final UsbDeviceConnection connection;
        final AtomicBoolean epochCurrent = new AtomicBoolean(true);
        final ManualExecutor platformExecutor = new ManualExecutor(), ownerExecutor = new ManualExecutor(), nativeExecutor = new ManualExecutor();
        final ByteBudget budget = new ByteBudget(8L * 1024 * 1024);
        final Failures failures = new Failures();
        final List<String> order = new ArrayList<String>();
        final AtomicInteger gateCalls = new AtomicInteger();
        final BridgeBackend backend;
        final IsolatedCaptureSession session;
        final StopProbe probe;
        final AndroidPlatformUsbPort port;
        final UsbConnectionOwner owner;
        final AppFrames app;
        final AndroidUsbLifecycleBridge lifecycle;
        final Map<UsbConnectionOwner.BorrowLease, Boolean> borrows = new IdentityHashMap<UsbConnectionOwner.BorrowLease, Boolean>();
        volatile Runnable onGate = () -> {};
        Fixture() { this(1, 0, null); }
        Fixture(long generation, int fd) { this(generation, fd, null); }
        Fixture(long generation, int fd, Executor platformCleanup) {
            binding = BridgeChecks.binding(generation); stop = new StopKey(binding, "canonical-stop");
            context.manifest(PermissionReceiver.class, new PermissionReceiver());
            manager.testPut(selected); manager.testConnection = new UsbDeviceConnection(fd);
            connection = manager.testConnection;
            backend = new BridgeBackend(connection);
            session = new IsolatedCaptureSession(binding, budget, failures, backend, nativeExecutor);
            probe = new StopProbe(session, order);
            port = new AndroidPlatformUsbPort(context, manager, binding, selected,
                (identity, chosen, listed) -> binding.equals(identity) && chosen == selected && listed == selected && epochCurrent.get(),
                platformCleanup == null ? platformExecutor : platformCleanup);
            owner = new UsbConnectionOwner(binding, port, probe, failures, ownerExecutor);
            app = new AppFrames(stop);
            lifecycle = new AndroidUsbLifecycleBridge(port, owner, stop, key -> {
                gateCalls.incrementAndGet();
                synchronized (order) { order.add("publication-gate"); }
                onGate.run();
                app.revoke();
            });
        }
        void attach() { lifecycle.attach(); }
        PermissionRequest request() { return new PermissionRequest(binding.selection, "same-user-nonce"); }
        void requestWithoutResult() { lifecycle.requestPermissionFromUser(request()); }
        void requestEarly(boolean grant) {
            manager.testOnRequest = () -> {
                check(port.snapshot().requestRegistered, "registry exists before system request can callback");
                check(manager.testPending.testSend(result(selected, grant)), "modeled explicit dispatcher routed response");
            };
            lifecycle.requestPermissionFromUser(request());
        }
        void open() {
            manager.testPermission = true;
            check(lifecycle.openFromUser().ready, "explicit user open succeeded");
        }
        UsbConnectionOwner.BorrowLease borrow() {
            UsbConnectionOwner.BorrowLease lease = owner.borrow(); borrows.put(lease, Boolean.TRUE);
            connection.testBorrowEnter(); return lease;
        }
        void giveBack(UsbConnectionOwner.BorrowLease lease) {
            owner.returnBorrow(lease);
            if (borrows.remove(lease) != null) connection.testBorrowExit();
        }
        void nativeOpen(UsbConnectionOwner.BorrowLease lease) {
            check(session.open(op(binding, "native-open", CallKind.OPEN, Channel.DEVICE), lease.descriptor, WIRE).failure == null,
                  "actual adapter opened using issued borrowed FD");
        }
        void stop() { lifecycle.stopFromUser(); }
        void settle() {
            platformExecutor.runAll(); ownerExecutor.runAll(); nativeExecutor.runAll();
            owner.refreshNativeDrain(stop); ownerExecutor.runAll(); platformExecutor.runAll();
        }
        void healthy() {
            check(context.violations().isEmpty(), "dispatcher ledger: " + context.violations());
            check(connection.testViolations().isEmpty(), "connection ledger: " + connection.testViolations());
            check(backend.violations().isEmpty(), "native ledger: " + backend.violations());
            check(probe.violations.isEmpty(), "native stop ordering: " + probe.violations);
        }
        void finished(boolean acquired) {
            settle();
            check(owner.ownerEvidence(stop).drained(), "actual owner drain completed");
            check(connection.testCloseCalls() == (acquired ? 1 : 0), "unique close of actual acquired fake SDK connection");
            check(!acquired || connection.testClosed(), "SDK connection close returned");
            check(context.unregisters() == 1 && context.activeReceivers() == 0, "detach receiver unregistered once");
            check(port.snapshot().unregisterSucceeded, "unregister completion is recorded");
            if (manager.testPending != null) check(manager.testPending.testCancelCalls() == 1, "permission token cancellation occurs once");
            check(budget.used() == 0, "all accounted pixel/transfer bytes returned");
            healthy();
        }
        Intent permissionIntent(boolean granted) {
            return manager.testPending.testIntent().putExtra(UsbManager.EXTRA_DEVICE, selected)
                .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, granted);
        }
        void direct(Intent intent) { new PermissionReceiver().onReceive(context, intent); }
    }

    private static void explicitActions() {
        Fixture f = new Fixture();
        check(f.manager.testRequestCalls() == 0 && f.manager.testOpenCalls() == 0, "construction performs no SDK request/open");
        f.attach();
        f.context.broadcast(new Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED).putExtra(UsbManager.EXTRA_DEVICE, f.selected));
        check(f.manager.testRequestCalls() == 0 && f.manager.testOpenCalls() == 0 && f.backend.startCalls() == 0,
              "attach and attached broadcast do not request/open/start");
        f.manager.testPermission = true; f.requestEarly(true);
        check(f.owner.snapshot().permissionGranted && f.manager.testOpenCalls() == 0 && f.backend.openCalls() == 0,
              "grant is a consumed result, never auto-open");
        check(f.manager.testPending.testIntent().getExtras() == null, "no base device/grant extras can override system fill-in");
        f.open();
        check(f.manager.testOpenCalls() == 1 && f.backend.openCalls() == 0 && f.backend.startCalls() == 0,
              "user platform open is separate from native capture");
        UsbConnectionOwner.BorrowLease lease = f.borrow();
        check(lease.descriptor.fd == 0 && lease.descriptor.binding.equals(f.binding), "SDK FD zero survives complete owner binding");
        f.giveBack(lease); f.stop(); f.finished(true);
    }

    private static void malformedPermission() {
        Fixture f = new Fixture(); f.attach(); f.requestWithoutResult();
        Intent valid = f.permissionIntent(true);
        List<Intent> invalid = Arrays.asList(
            new Intent(valid).setAction("wrong-action"),
            new Intent(valid).setData(Uri.parse("sidecar-usb-permission://request/unknown")),
            new Intent(valid).setComponent(new ComponentName("other.package", PermissionReceiver.class.getName())),
            new Intent(valid).setComponent(null),
            new Intent(valid).putExtra(UsbManager.EXTRA_DEVICE, device("/dev/bus/usb/001/003", 0x5678)),
            new Intent(valid).putExtra(UsbManager.EXTRA_DEVICE, device(f.selected.getDeviceName(), 0x9999)),
            f.manager.testPending.testIntent().putExtra(UsbManager.EXTRA_DEVICE, f.selected),
            f.manager.testPending.testIntent().putExtra(UsbManager.EXTRA_DEVICE, f.selected).putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, "true"),
            f.manager.testPending.testIntent().putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true));
        f.direct(null);
        for (Intent event : invalid) {
            f.direct(event); // Malformed receiver input, not an assertion that real Binder would deliver it.
            check(!f.owner.snapshot().permissionConsumed && f.port.snapshot().requestRegistered,
                  "wrong address/device/non-Boolean grant leaves valid registered capability unconsumed");
            check(f.manager.testOpenCalls() == 0, "malformed receiver input never opens");
        }
        check(f.manager.testPending.testSend(result(f.selected, true)), "matching explicit response routes normally");
        check(f.owner.snapshot().permissionGranted, "valid request still usable after malformed inputs");
        f.direct(valid);
        check(f.manager.testRequestCalls() == 1 && f.manager.testOpenCalls() == 0, "duplicate direct delivery does not request/open");
        f.stop(); f.finished(false);
    }

    private static void syncDenial() {
        Fixture f = new Fixture(); f.attach(); f.requestEarly(false);
        check(f.failures.has(FailureKind.PERMISSION) && f.lifecycle.snapshot().closed && f.port.snapshot().revoked,
              "synchronous denial shuts owner and bridge");
        check(f.manager.testOpenCalls() == 0 && f.gateCalls.get() == 1, "denial never opens and stop dispatch is once");
        check(f.context.unregisters() == 0 && f.manager.testPending.testCancelCalls() == 0, "receiver callback does no cancellation/unregistration inline");
        f.finished(false);
    }

    private static void asyncDenial() {
        Fixture f = new Fixture(); f.attach(); f.requestWithoutResult();
        check(f.manager.testPending.testSend(result(f.selected, false)), "async denial routed");
        check(f.failures.has(FailureKind.PERMISSION) && f.lifecycle.snapshot().closed && f.port.snapshot().revoked,
              "async denial reaches lifecycle without a root failure callback");
        check(f.gateCalls.get() == 1 && !f.port.snapshot().requestRegistered, "denial revokes publication and registry once");
        rejects(IllegalStateException.class, () -> f.lifecycle.openFromUser(), "denied lifecycle cannot open");
        f.direct(f.permissionIntent(true));
        check(!f.owner.snapshot().permissionGranted && f.manager.testOpenCalls() == 0, "late grant cannot undo denial");
        f.finished(false);
    }

    private static void earlyCallback() {
        Fixture f = new Fixture(); f.attach();
        Gate gate = new Gate("SDK-request-return");
        f.manager.testOnRequest = () -> {
            check(f.port.snapshot().requestRegistered, "early registry established");
            f.manager.testPending.testSend(result(f.selected, true)); gate.enter();
        };
        Task<Void> requesting = new Task<Void>("SDK-request", () -> { f.requestWithoutResult(); return null; });
        try {
            gate.awaitEntered();
            check(f.owner.snapshot().permissionGranted && f.owner.snapshot().requestInFlight, "early grant recorded while SDK request remains on stack");
            rejects(IllegalStateException.class, () -> f.lifecycle.openFromUser(), "open waits for request method return");
            new Task<Void>("stop-early-request", () -> { f.lifecycle.onLifecycleStop(); return null; }).finish();
            f.settle();
            check(!f.owner.ownerEvidence(f.stop).drained(), "cancelled PI is not proof permission-call stack returned");
            check(f.manager.testOpenCalls() == 0, "early callback cannot start capture");
        } finally { gate.release(); }
        requesting.finish(); f.finished(false);
    }

    private static void cancelAndLate() {
        Fixture f = new Fixture(); f.attach(); f.requestWithoutResult();
        Intent late = f.permissionIntent(true);
        f.stop();
        check(f.port.snapshot().revoked && !f.port.snapshot().requestRegistered, "revoke immediately removes process-local authority");
        check(f.context.unregisters() == 0 && f.manager.testPending.testCancelCalls() == 0, "blocking platform cleanup is queued");
        f.direct(late);
        check(!f.owner.snapshot().permissionConsumed && !f.owner.snapshot().permissionGranted, "already queued late delivery cannot regain authority");
        f.finished(false);
        check(f.manager.testPending.testCancelled() && !f.manager.testPending.testSend(result(f.selected, true)), "cancelled modeled token cannot dispatch again");
        long revision = f.owner.ownerEvidence(f.stop).revision;
        f.direct(late);
        check(f.owner.ownerEvidence(f.stop).revision == revision, "late result cannot change terminal owner evidence");
    }

    private static final class EmptyRegistryLoader extends URLClassLoader {
        EmptyRegistryLoader(URL productLocation, ClassLoader parent) { super(new URL[] {productLocation}, parent); }
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> type = findLoadedClass(name);
            if (type == null && name.startsWith("local.sidecar.copilot.usbbridge.")) {
                try { type = findClass(name); } catch (ClassNotFoundException missing) { type = null; }
            }
            if (type == null) type = super.loadClass(name, false);
            if (resolve) resolveClass(type);
            return type;
        }
    }

    private static void registryLoss() {
        Fixture f = new Fixture(); f.attach(); f.requestWithoutResult();
        Intent originalAddress = f.permissionIntent(true);
        URL product = PermissionReceiver.class.getProtectionDomain().getCodeSource().getLocation();
        try (EmptyRegistryLoader loader = new EmptyRegistryLoader(product, PermissionReceiver.class.getClassLoader())) {
            Class<?> receiverType = Class.forName(PermissionReceiver.class.getName(), true, loader);
            check(receiverType.getClassLoader() == loader, "same product bytecode loaded with fresh static registry");
            BroadcastReceiver fresh = (BroadcastReceiver) receiverType.getDeclaredConstructor().newInstance();
            fresh.onReceive(f.context, originalAddress);
            check(!f.owner.snapshot().permissionConsumed && f.port.snapshot().requestRegistered, "empty registry rejects formerly valid address");
        } catch (ReflectiveOperationException | IOException failure) {
            throw new AssertionError("isolated-registry host fixture failed", failure);
        }
        f.direct(originalAddress);
        check(f.owner.snapshot().permissionGranted, "identical address succeeds only in still-registered original loader");
        f.stop(); f.finished(false);
    }

    private static void uniqueIdentity() {
        Fixture a = new Fixture(1, 0), b = new Fixture(2, 0);
        a.attach(); b.attach(); a.requestWithoutResult(); b.requestWithoutResult();
        Intent first = a.manager.testPending.testIntent(), second = b.manager.testPending.testIntent();
        check(a.manager.testPending != b.manager.testPending && !first.getAction().equals(second.getAction())
              && !first.getData().equals(second.getData()), "same user nonce still receives unique platform capability identity");
        check(first.getComponent().equals(second.getComponent()) && first.getExtras() == null && second.getExtras() == null,
              "identity is explicit component/action/data, not mutable extras");
        Intent extrasOnly = new Intent(a.context, PermissionReceiver.class).setAction("model-fixed")
            .setData(Uri.parse("sidecar-model://fixed")).putExtra("nonce", "one");
        PendingIntent one = PendingIntent.getBroadcast(a.context, 700, extrasOnly, PendingIntent.FLAG_MUTABLE);
        PendingIntent two = PendingIntent.getBroadcast(a.context, 700, new Intent(extrasOnly).putExtra("nonce", "two"), PendingIntent.FLAG_MUTABLE);
        check(one == two, "independent PI model ignores extras for identity");
        one.cancel(); a.stop(); b.stop(); a.finished(false); b.finished(false);
    }

    private static void detachAndReuse() {
        Fixture old = new Fixture(1, 0); old.attach(); old.open();
        UsbConnectionOwner.BorrowLease lease = old.borrow(); old.nativeOpen(lease);
        old.context.broadcast(new Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, old.selected));
        check(old.lifecycle.snapshot().closed && old.port.snapshot().revoked && old.session.nativeEvidence(old.stop).admissionClosed,
              "detach revokes owner/registry/native admission");
        Fixture fresh = new Fixture(2, 0); fresh.attach(); fresh.open();
        old.settle();
        check(old.connection.testCloseCalls() == 0 && old.owner.snapshot().borrows == 1, "detach still waits for issued borrow after actual native drain");
        old.giveBack(lease); old.finished(true);
        check(fresh.connection.testCloseCalls() == 0 && fresh.owner.snapshot().fdReady, "old detach cannot close fresh owner reusing numeric FD");
        fresh.stop(); fresh.finished(true);
    }

    private static void lifecycleOrder() {
        for (boolean permission : Arrays.asList(Boolean.FALSE, Boolean.TRUE)) {
            Fixture f = new Fixture(); f.attach(); f.open();
            UsbConnectionOwner.BorrowLease lease = f.borrow(); f.nativeOpen(lease);
            rejects(IllegalArgumentException.class, () -> f.lifecycle.onPermissionRevoked(binding(2)), "wrong binding revocation");
            check(f.gateCalls.get() == 0 && !f.owner.snapshot().admissionClosed, "wrong revocation has no side effect");
            if (permission) f.lifecycle.onPermissionRevoked(f.binding); else f.lifecycle.onLifecycleStop();
            check(f.order.indexOf("publication-gate") >= 0 && f.order.indexOf("publication-gate") < f.order.indexOf("native-stop"),
                  "publication gate is invoked before trusted native stop");
            check(f.app.evidence().publicationRevoked && f.session.nativeEvidence(f.stop).admissionClosed && f.port.snapshot().revoked,
                  "successful lifecycle stop closes all admissions synchronously");
            if (permission) check(f.failures.has(FailureKind.PERMISSION), "revocation retains typed reason");
            f.settle(); check(f.connection.testCloseCalls() == 0, "owner cannot close borrowed SDK connection");
            f.giveBack(lease); f.finished(true);
        }
    }

    private static void knownNoAcquisition() {
        Fixture f = new Fixture(); f.attach(); f.manager.testPermission = true;
        AtomicInteger checks = new AtomicInteger();
        f.manager.testOnPermission = () -> {
            // The owner's first check succeeds; only the port's pre-acquisition recheck fails.
            if (checks.incrementAndGet() == 2) f.manager.testPermission = false;
        };
        UsbConnectionOwner.OpenResult result = f.lifecycle.openFromUser();
        check(checks.get() == 2 && f.manager.testOpenCalls() == 0,
              "permission was lost inside port precheck before any SDK open acquisition");
        check(!result.ready && result.failure != null && result.failure.kind == FailureKind.IO,
              "stable no-handle SPI result retains typed IO failure rather than claiming open success");
        check(!f.owner.snapshot().openUncertain && !f.owner.snapshot().connectionAcquired,
              "known absence of acquisition does not invent an unknown handle obligation");
        f.stop(); f.finished(false);
    }

    private static void lateOpen() {
        Fixture old = new Fixture(1, 0); old.attach(); old.manager.testPermission = true;
        Gate gate = new Gate("SDK-open-return"); old.manager.testOnOpen = gate::enter;
        Task<UsbConnectionOwner.OpenResult> opening = new Task<UsbConnectionOwner.OpenResult>("SDK-open", () -> old.lifecycle.openFromUser());
        Fixture fresh = new Fixture(2, 0);
        try {
            gate.awaitEntered();
            old.context.broadcast(new Intent(UsbManager.ACTION_USB_DEVICE_DETACHED).putExtra(UsbManager.EXTRA_DEVICE, old.selected));
            old.settle();
            check(!old.owner.ownerEvidence(old.stop).drained() && old.connection.testCloseCalls() == 0, "late SDK open stack still owns its connection");
            fresh.attach(); fresh.open();
        } finally { gate.release(); }
        check(!opening.finish().ready && old.owner.snapshot().connectionAcquired, "acquired late connection is returned to old owner cleanup");
        old.finished(true);
        check(fresh.connection.testCloseCalls() == 0 && fresh.owner.snapshot().fdReady, "late handle never transfers to replacement owner");
        fresh.stop(); fresh.finished(true);
    }

    private static void stopGetFd() {
        Fixture f = new Fixture(); f.attach(); f.manager.testPermission = true;
        Gate gate = new Gate("SDK-getFD-return"); f.connection.testOnGetFd = gate::enter;
        Task<UsbConnectionOwner.OpenResult> opening = new Task<UsbConnectionOwner.OpenResult>("SDK-getFD", () -> f.lifecycle.openFromUser());
        try {
            gate.awaitEntered(); f.lifecycle.onLifecycleStop(); f.settle();
            check(f.connection.testCloseCalls() == 0 && !f.owner.ownerEvidence(f.stop).drained(), "getFD stack cannot be closed by lifecycle stop");
        } finally { gate.release(); }
        check(!opening.finish().ready, "late SDK FD cannot restore admission");
        f.finished(true);
    }

    private static void negativeFd() {
        Fixture f = new Fixture(1, -1); f.attach(); f.manager.testPermission = true;
        UsbConnectionOwner.OpenResult result = f.lifecycle.openFromUser();
        check(!result.ready && result.failure != null && result.failure.kind == FailureKind.IO, "negative SDK FD remains typed failure");
        check(f.lifecycle.snapshot().closed && !f.owner.snapshot().fdReady, "negative FD cannot be borrowed or reopened");
        f.finished(true);
    }

    private static void composedStop() {
        Fixture f = new Fixture(); f.attach(); f.open();
        UsbConnectionOwner.BorrowLease lease = f.borrow(); f.nativeOpen(lease);
        check(f.session.start(op(f.binding, "native-start", CallKind.START, Channel.DEVICE), WIRE, f.app).failure == null, "actual parent adapter starts only by explicit test action");
        try (Reservation source = f.budget.reserve(OwnedRgba.LENGTH)) {
            check(source.bytes == OwnedRgba.LENGTH, "source bytes use the same actual byte budget");
            byte[] pixels = new byte[OwnedRgba.LENGTH]; Arrays.fill(pixels, (byte) 0x47);
            check(f.backend.events.submit(op(f.binding, "image", CallKind.SUBMIT, Channel.IMAGE), 64).status == SubmitStatus.ACCEPTED, "actual parent image submission");
            f.backend.emit(new OwnedFrameBridge.BorrowedFrame(pixels, 0, pixels.length,
                OwnedRgba.WIDTH, OwnedRgba.HEIGHT, OwnedRgba.STRIDE, "RGBA", 1, 100));
            Arrays.fill(pixels, (byte) 0);
            boolean intact = f.app.held() != null;
            if (intact) for (byte value : f.app.held().pixelsForOwner()) if (value != (byte) 0x47) intact = false;
            check(intact, "owned application copy survives borrowed source reuse");
        }
        f.lifecycle.onLifecycleStop(); f.settle();
        NativeEvidence nativeDrain = f.session.nativeEvidence(f.stop);
        check(nativeDrain.drained() && f.connection.testCloseCalls() == 0, "native drain alone cannot return platform borrow");
        f.giveBack(lease); f.settle();
        check(f.owner.ownerEvidence(f.stop).drained() && f.connection.testCloseCalls() == 1 && f.connection.testClosed(), "actual owner closes SDK wrapper after native drain and borrow return");
        StopBarrier barrier = new StopBarrier(f.stop);
        barrier.update(nativeDrain); barrier.update(f.owner.ownerEvidence(f.stop)); barrier.update(f.app.evidence());
        check(!barrier.snapshot().drained && f.budget.used() == OwnedRgba.LENGTH, "real composed stop still waits for application owned bytes");
        f.app.release(); barrier.update(f.app.evidence());
        check(barrier.snapshot().drained && !barrier.snapshot().protocolViolation, "actual three-way stop completes after app frame close");
        f.finished(true);
    }

    private static void registrationStop() {
        Fixture f = new Fixture();
        Gate gate = new Gate("registerReceiver-return"); f.context.onRegister = gate::enter;
        Task<Void> registration = new Task<Void>("registerReceiver", () -> { f.attach(); return null; });
        try {
            gate.awaitEntered(); f.stop(); f.platformExecutor.runAll();
            check(f.port.snapshot().registrationInFlight && f.port.snapshot().revoked && f.context.unregisters() == 0,
                  "registration in progress cannot be forgotten or concurrently unregistered");
        } finally { gate.release(); }
        registration.finish(); f.finished(false);
        check(!f.port.snapshot().monitoring && f.manager.testRequestCalls() == 0 && f.manager.testOpenCalls() == 0,
              "late registration cannot restore permission or capture admission");
    }

    private static void gateReentry() {
        Fixture f = new Fixture(); f.attach(); f.open();
        f.onGate = () -> f.lifecycle.stopFromUser();
        f.lifecycle.onLifecycleStop();
        check(f.gateCalls.get() == 1 && f.lifecycle.snapshot().stopDispatchCompleted, "reentrant gate is bounded to one outer stop dispatch");
        check(f.owner.snapshot().admissionClosed && f.port.snapshot().revoked, "reentry does not skip owner or registry closure");
        f.finished(true);
    }

    private static void latePendingIntent() {
        Fixture f = new Fixture(); f.attach();
        Gate gate = new Gate("getBroadcast-return");
        AtomicReference<PendingIntent> created = new AtomicReference<PendingIntent>();
        PendingIntent.testBeforeReturn = pending -> { created.set(pending); gate.enter(); };
        Task<Void> request = new Task<Void>("getBroadcast", () -> { f.requestWithoutResult(); return null; });
        try {
            gate.awaitEntered();
            f.stop(); f.settle();
            check(f.port.snapshot().revoked && !f.port.snapshot().requestRegistered
                  && f.context.unregisters() == 1, "stop unregisters while late factory capability is still outside port ledger");
            check(created.get().testCancelCalls() == 0 && f.manager.testRequestCalls() == 0,
                  "factory has not returned a capability to cancel or issue to UsbManager");
            check(!f.owner.ownerEvidence(f.stop).drained(), "owner still retains in-flight permission request stack");
        } finally {
            PendingIntent.testBeforeReturn = pending -> {};
            gate.release();
        }
        request.finish(); f.finished(false);
        check(created.get().testCancelCalls() == 1 && f.port.snapshot().cancelSucceeded
              && f.context.unregisters() == 1 && f.manager.testRequestCalls() == 0,
              "late factory result is retained and cancelled exactly once without requesting permission");
        Intent late = created.get().testIntent().putExtra(UsbManager.EXTRA_DEVICE, f.selected)
            .putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true);
        f.direct(late);
        check(!f.owner.snapshot().permissionGranted && !f.port.snapshot().permissionConsumed,
              "old callback cannot revive the never-admitted late capability");
    }

    private static void portExecutors() {
        for (boolean inline : Arrays.asList(Boolean.TRUE, Boolean.FALSE)) {
            Executor invalid = inline ? Runnable::run : command -> { throw new RejectedExecutionException("controlled port cleanup rejection"); };
            Fixture f = new Fixture(inline ? 401 : 402, 0, invalid); f.attach(); f.requestWithoutResult();
            Intent late = f.permissionIntent(true);
            f.stop(); f.settle();
            check(f.port.snapshot().revoked && !f.port.snapshot().requestRegistered
                  && f.port.snapshot().cleanupFailures > 0, "invalid platform executor leaves authority revoked and retains cleanup diagnosis");
            check(f.manager.testPending.testCancelCalls() == 0 && f.context.unregisters() == 0
                  && f.context.activeReceivers() == 1 && !f.port.snapshot().cancelAttempted
                  && !f.port.snapshot().unregisterAttempted,
                  "inline/rejected platform cleanup performs no synchronous cancellation or unregister");
            f.direct(late);
            check(!f.owner.snapshot().permissionGranted, "remaining modeled OS token cannot revive removed process registry");
            check(f.owner.ownerEvidence(f.stop).drained() && !f.port.snapshot().cancelSucceeded
                  && !f.port.snapshot().unregisterSucceeded,
                  "owner drain is explicitly separate from still-pending Android token/receiver cleanup");
            f.healthy(); // Fake token/receiver deliberately remain pending; no successful Android cleanup is claimed.
        }
    }

    private static void gateFailure() {
        Fixture f = new Fixture(); f.attach(); f.open();
        f.onGate = () -> { throw new IllegalStateException("controlled publication gate failure"); };
        f.lifecycle.onLifecycleStop(); f.settle();
        check(f.lifecycle.snapshot().callbackFailures == 1 && f.failures.has(FailureKind.IO), "failed publication gate retains failure and continues shutdown");
        check(!f.app.evidence().publicationRevoked && f.port.snapshot().revoked, "gate exception is not forged into publication evidence");
        StopBarrier barrier = new StopBarrier(f.stop);
        barrier.update(f.session.nativeEvidence(f.stop)); barrier.update(f.owner.ownerEvidence(f.stop)); barrier.update(f.app.evidence());
        check(!barrier.snapshot().drained, "native and owner completion cannot hide failed application publication revoke");
        f.finished(true);
    }

    private static void sdkBranches() {
        try {
            for (int sdk : new int[] {30, 31, 35}) {
                Build.VERSION.SDK_INT = sdk;
                Fixture f = new Fixture(sdk, 0); f.attach(); f.requestEarly(true);
                int flags = f.manager.testPending.testFlags();
                check((flags & PendingIntent.FLAG_ONE_SHOT) != 0, "single-use capability on modeled SDK branch");
                check(((flags & PendingIntent.FLAG_MUTABLE) != 0) == (sdk >= 31), "explicit mutability selected for modeled API level");
                check(f.context.lastFlags() == (sdk >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0), "registerReceiver signature and flags branch");
                check(f.owner.snapshot().permissionGranted, "typed/legacy parcelable descriptor branch executes actual compiled bytecode");
                f.stop(); f.finished(false);
            }
        } finally { Build.VERSION.SDK_INT = 35; }
    }

    private static final class RecordingReceiver extends BroadcastReceiver {
        Intent last;
        @Override public void onReceive(Context context, Intent intent) { last = intent; }
    }

    private static void fillRules() {
        BridgeContext context = new BridgeContext("fill.model");
        RecordingReceiver receiver = new RecordingReceiver(); context.manifest(RecordingReceiver.class, receiver);
        Intent base = new Intent(context, RecordingReceiver.class).setAction("fill-model").setData(Uri.parse("fill-model://one"));
        Bundle supplied = new Bundle(); supplied.putBoolean(UsbManager.EXTRA_PERMISSION_GRANTED, true);
        PendingIntent immutable = PendingIntent.getBroadcast(context, 1, base, PendingIntent.FLAG_IMMUTABLE);
        check(immutable.testSend(supplied) && !receiver.last.hasExtra(UsbManager.EXTRA_PERMISSION_GRANTED), "model immutable token ignores supplied fill-in");
        immutable.cancel();
        PendingIntent prefilled = PendingIntent.getBroadcast(context, 2, new Intent(base).putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false), PendingIntent.FLAG_MUTABLE);
        check(prefilled.testSend(supplied) && !receiver.last.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true), "model base extras win over supplied fill-in");
        prefilled.cancel();
        PendingIntent mutable = PendingIntent.getBroadcast(context, 3, base, PendingIntent.FLAG_MUTABLE);
        check(mutable.testSend(supplied) && receiver.last.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false), "model mutable empty base accepts supplied typed value");
        mutable.cancel();
        check(context.violations().isEmpty(), "fill-model routing uses registered exact addresses");
    }

    private static void negativeControls() {
        BridgeContext missing = new BridgeContext("negative.missing");
        Intent address = new Intent(missing, PermissionReceiver.class).setAction("negative-address").setData(Uri.parse("negative://one"));
        PendingIntent token = PendingIntent.getBroadcast(missing, 1, address, PendingIntent.FLAG_MUTABLE);
        check(!token.testDispatchAs(token.testIntent()) && missing.violations().equals(Collections.singletonList("UNREGISTERED_MANIFEST_ADDRESS")),
              "negative control detects precisely an unregistered receiver address");
        negativeControls++; token.cancel();
        BridgeContext mismatch = new BridgeContext("negative.identity");
        mismatch.manifest(PermissionReceiver.class, new PermissionReceiver());
        Intent valid = new Intent(mismatch, PermissionReceiver.class).setAction("negative-identity").setData(Uri.parse("negative://two"));
        PendingIntent second = PendingIntent.getBroadcast(mismatch, 2, valid, PendingIntent.FLAG_MUTABLE);
        check(!second.testDispatchAs(new Intent(valid).setData(Uri.parse("negative://changed")))
              && mismatch.violations().equals(Collections.singletonList("PENDING_INTENT_IDENTITY_MISMATCH")),
              "negative control detects precisely wrong PI identity with a valid registered address");
        negativeControls++; second.cancel();
    }
}

package local.sidecar.copilot.usbbridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import local.sidecar.copilot.usbowner.PlatformUsbPort;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/**
 * Android API implementation for the isolated owner experiment. No capture factory or start.
 * The caller supplies an application Context and the selected device's trusted epoch guard.
 * USB device names and descriptor values are not proof that an attachment epoch survived.
 * The cleanup executor is a trusted nonblocking enqueue dependency; inline execution is refused.
 * This class must not be installed/wired into the application without its external gates.
 */
public final class AndroidPlatformUsbPort implements PlatformUsbPort {
    public interface AttachmentGuard {
        /** Metadata-only, bounded check against a trusted complete attachment/selection history. */
        boolean isCurrent(Binding binding, UsbDevice selected, UsbDevice listed);
    }

    private static final AtomicLong REQUEST_SERIAL = new AtomicLong();
    private final Object lock = new Object();
    private final Context context;
    private final UsbManager manager;
    private final Binding binding;
    private final UsbDevice selected;
    private final DeviceKey selectedKey;
    private final AttachmentGuard attachmentGuard;
    private final Executor cleanup;
    private final ThreadLocal<Boolean> enqueueStack = new ThreadLocal<>();
    private final BroadcastReceiver detachReceiver;
    private Runnable onDetached;
    private Runnable onPermissionFailure;
    private boolean registrationAttempted, registrationInFlight, monitoring;
    private boolean revoked, requestAttempted, permissionConsumed;
    private boolean cancelAttempted, cancelSucceeded, unregisterAttempted, unregisterSucceeded;
    private boolean cleanupQueued, cleanupRunning;
    private long cleanupFailures, callbackFailures;
    private PendingIntent permissionIntent;
    private PermissionRegistry.Entry permissionEntry;

    public AndroidPlatformUsbPort(Context applicationContext, UsbManager manager, Binding binding,
                                  UsbDevice selected, AttachmentGuard guard, Executor platformCleanup) {
        context = Objects.requireNonNull(applicationContext); this.manager = Objects.requireNonNull(manager);
        this.binding = Objects.requireNonNull(binding); this.selected = Objects.requireNonNull(selected);
        selectedKey = new DeviceKey(selected); attachmentGuard = Objects.requireNonNull(guard);
        cleanup = Objects.requireNonNull(platformCleanup);
        detachReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) { detached(intent); }
        };
    }

    public Binding binding() { return binding; }

    /** Lifecycle-owned metadata hook, installed before any permission request. */
    void setPermissionFailureListener(Runnable listener) {
        Objects.requireNonNull(listener);
        synchronized (lock) {
            if (onPermissionFailure != null || requestAttempted)
                throw new IllegalStateException("permission failure hook already fixed");
            onPermissionFailure = listener;
        }
    }

    /** Explicit worker setup. No permission request, open, FD lookup or capture start. */
    public void attachDetachMonitoring(Runnable callback) {
        Objects.requireNonNull(callback);
        synchronized (lock) {
            if (revoked || registrationAttempted) throw new IllegalStateException("monitor admission closed");
            registrationAttempted = true; registrationInFlight = true; onDetached = callback;
        }
        boolean success = false;
        try {
            IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
            if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(detachReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else context.registerReceiver(detachReceiver, filter);
            success = true;
        } finally {
            synchronized (lock) {
                registrationInFlight = false; monitoring = success && !revoked;
                if (!success) revoked = true;
            }
            scheduleCleanup();
        }
    }

    @Override public boolean isCurrentAttachment(Binding expected) {
        requireBinding(expected);
        synchronized (lock) { if (revoked || !monitoring) return false; }
        Map<String, UsbDevice> devices = manager.getDeviceList();
        UsbDevice listed = devices == null ? null : devices.get(selectedKey.name);
        boolean current = listed != null && selectedKey.matches(listed)
            && attachmentGuard.isCurrent(binding, selected, listed);
        synchronized (lock) { return current && monitoring && !revoked; }
    }

    @Override public boolean hasPermission(Binding expected) {
        requireBinding(expected);
        if (!isCurrentAttachment(expected)) return false;
        boolean permitted = manager.hasPermission(selected);
        return permitted && isCurrentAttachment(expected);
    }

    @Override public Connection openConnection(Binding expected) {
        requireBinding(expected);
        // These known-false checks precede acquisition. Null is the stable SPI's explicit
        // no-handle result (owner reports IO); throwing here would falsely imply unknown open.
        if (!isCurrentAttachment(expected)) return null;
        if (!hasPermission(expected)) return null;
        // Allocate bookkeeping BEFORE acquiring a platform connection, so allocation cannot lose it.
        AndroidConnection wrapper = new AndroidConnection();
        UsbDeviceConnection acquired = manager.openDevice(selected);
        if (acquired == null) return null;
        wrapper.connection = acquired;
        // Always return an acquired late connection. The owner performs its post-open recheck.
        return wrapper;
    }

    @Override public void requestPermission(PermissionRequest request, PermissionCallback callback) {
        if (request == null || !binding.selection.equals(request.selection))
            throw new IllegalArgumentException("permission selection mismatch");
        Objects.requireNonNull(callback);
        synchronized (lock) {
            if (revoked || !monitoring || requestAttempted) throw new IllegalStateException("permission admission closed");
            requestAttempted = true;
        }
        if (!isCurrentAttachment(binding)) throw new PlatformFailure(FailureKind.DETACHED, 0);
        String token = freshToken();
        String action = context.getPackageName() + ".SIDECAR_USB_PERMISSION." + token;
        Uri data = Uri.parse("sidecar-usb-permission://request/" + token);
        ComponentName component = new ComponentName(context, PermissionReceiver.class);
        Intent intent = new Intent(context, PermissionReceiver.class).setAction(action).setData(data);
        // Do NOT prefill DEVICE or GRANTED extras: base extras would override the system fill-in.
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        PendingIntent capability = PendingIntent.getBroadcast(context, 0, intent, flags);
        boolean admitted;
        synchronized (lock) {
            permissionIntent = capability; // Retain even when getBroadcast returned after revoke.
            admitted = !revoked && monitoring;
            if (admitted) {
                permissionEntry = new PermissionRegistry.Entry(this, request, callback,
                    action, data.toString(), component, selectedKey);
                PermissionRegistry.register(permissionEntry);
            }
        }
        if (!admitted) {
            scheduleCleanup();
            throw new PlatformFailure(FailureKind.PERMISSION, 0);
        }
        // A stop after this admission can cancel the PI while this already registered call runs.
        // The owner retains its call reference, and a revoked registry can never grant admission.
        try { manager.requestPermission(selected, capability); }
        catch (RuntimeException | Error failure) { revoke(); throw failure; }
    }

    private static String freshToken() {
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        StringBuilder value = new StringBuilder(90);
        for (byte b : random) {
            int n = b & 255; value.append(Character.forDigit(n >>> 4, 16)); value.append(Character.forDigit(n & 15, 16));
        }
        long serial = REQUEST_SERIAL.incrementAndGet();
        if (serial <= 0) throw new IllegalStateException("permission identity exhausted");
        return value.append('-').append(serial).toString();
    }

    void deliverPermission(PermissionRegistry.Entry entry, boolean granted) {
        PermissionCallback callback;
        Runnable failureListener;
        synchronized (lock) {
            if (revoked || entry != permissionEntry || permissionConsumed) return;
            permissionConsumed = true; callback = entry.callback; failureListener = onPermissionFailure;
        }
        boolean callbackFailed = false;
        try { callback.result(entry.request, granted); }
        catch (RuntimeException | Error failure) {
            callbackFailed = true;
            synchronized (lock) { callbackFailures++; }
        } finally {
            if (!granted || callbackFailed) {
                try { if (failureListener != null) failureListener.run(); }
                catch (RuntimeException | Error failure) { synchronized (lock) { callbackFailures++; } }
                finally { revoke(); }
            }
        }
    }

    private void detached(Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) return;
        String detachedName;
        try {
            UsbDevice device = PermissionReceiver.deviceExtra(intent);
            detachedName = device == null ? null : device.getDeviceName();
        } catch (RuntimeException | Error failure) { detachedName = null; }
        // Missing identity is uncertain. Same-name detach invalidates even if descriptors differ.
        if (detachedName != null && !selectedKey.name.equals(detachedName)) return;
        Runnable callback;
        synchronized (lock) { if (revoked) return; callback = onDetached; }
        try {
            if (callback != null) callback.run();
        } catch (RuntimeException | Error failure) {
            synchronized (lock) { callbackFailures++; }
        } finally { revoke(); }
    }

    /**
     * Close this port's permission registry/admission; Android cancel/unregister is deferred.
     * This does not revoke publication or return owner/native leases: use the lifecycle sequence.
     */
    public void revoke() {
        PermissionRegistry.Entry entry;
        synchronized (lock) { revoked = true; monitoring = false; entry = permissionEntry; }
        if (entry != null) PermissionRegistry.remove(entry);
        scheduleCleanup();
    }

    private void requireBinding(Binding expected) {
        if (expected == null || !binding.equals(expected)) throw new IllegalArgumentException("platform binding mismatch");
    }

    private void scheduleCleanup() {
        synchronized (lock) {
            if (!revoked || cleanupQueued || cleanupRunning || registrationInFlight) return;
            if ((permissionIntent == null || cancelAttempted) && (!registrationAttempted || unregisterAttempted)) return;
            cleanupQueued = true;
        }
        enqueueStack.set(Boolean.TRUE);
        try {
            cleanup.execute(() -> {
                if (Boolean.TRUE.equals(enqueueStack.get())) {
                    synchronized (lock) { cleanupQueued = false; cleanupFailures++; }
                } else cleanupPlatform();
            });
        } catch (RuntimeException | Error failure) {
            synchronized (lock) { cleanupQueued = false; cleanupFailures++; }
        } finally { enqueueStack.remove(); }
    }

    private void cleanupPlatform() {
        PendingIntent cancel;
        boolean unregister;
        synchronized (lock) {
            if (cleanupRunning) { cleanupQueued = false; return; }
            cleanupQueued = false; cleanupRunning = true;
            cancel = permissionIntent != null && !cancelAttempted ? permissionIntent : null;
            if (cancel != null) cancelAttempted = true;
            unregister = registrationAttempted && !registrationInFlight && !unregisterAttempted;
            if (unregister) unregisterAttempted = true;
        }
        if (cancel != null) {
            try { cancel.cancel(); synchronized (lock) { cancelSucceeded = true; } }
            catch (RuntimeException | Error failure) { synchronized (lock) { cleanupFailures++; } }
        }
        if (unregister) {
            try { context.unregisterReceiver(detachReceiver); synchronized (lock) { unregisterSucceeded = true; } }
            catch (RuntimeException | Error failure) { synchronized (lock) { cleanupFailures++; } }
        }
        synchronized (lock) { cleanupRunning = false; }
        scheduleCleanup(); // A PI may have arrived while an earlier cleanup was running.
    }

    public Snapshot snapshot() { synchronized (lock) { return new Snapshot(this); } }
    public static final class Snapshot {
        public final boolean monitoring, registrationInFlight, revoked, requestRegistered, permissionConsumed;
        public final boolean cleanupQueued, cleanupRunning, cancelAttempted, cancelSucceeded, unregisterAttempted, unregisterSucceeded;
        public final long cleanupFailures, callbackFailures;
        private Snapshot(AndroidPlatformUsbPort port) {
            monitoring = port.monitoring; registrationInFlight = port.registrationInFlight; revoked = port.revoked;
            requestRegistered = port.permissionEntry != null && port.permissionEntry.active;
            permissionConsumed = port.permissionConsumed; cleanupQueued = port.cleanupQueued;
            cleanupRunning = port.cleanupRunning; cancelAttempted = port.cancelAttempted; cancelSucceeded = port.cancelSucceeded;
            unregisterAttempted = port.unregisterAttempted; unregisterSucceeded = port.unregisterSucceeded;
            cleanupFailures = port.cleanupFailures; callbackFailures = port.callbackFailures;
        }
    }

    static final class DeviceKey {
        final String name;
        final int vendor, product, deviceClass, subclass, protocol;
        DeviceKey(UsbDevice device) {
            name = Objects.requireNonNull(device.getDeviceName()); vendor = device.getVendorId(); product = device.getProductId();
            deviceClass = device.getDeviceClass(); subclass = device.getDeviceSubclass(); protocol = device.getDeviceProtocol();
        }
        boolean matches(UsbDevice device) {
            return device != null && name.equals(device.getDeviceName()) && vendor == device.getVendorId()
                && product == device.getProductId() && deviceClass == device.getDeviceClass()
                && subclass == device.getDeviceSubclass() && protocol == device.getDeviceProtocol();
        }
    }

    private static final class AndroidConnection implements Connection {
        private UsbDeviceConnection connection;
        private boolean closeAttempted;
        @Override public int getFileDescriptor() {
            UsbDeviceConnection current;
            synchronized (this) {
                if (closeAttempted) throw new IllegalStateException("connection close has begun");
                current = connection;
            }
            return current.getFileDescriptor();
        }
        @Override public void close() {
            UsbDeviceConnection current;
            synchronized (this) {
                if (closeAttempted) throw new IllegalStateException("connection close already attempted");
                closeAttempted = true; current = connection;
            }
            current.close();
        }
    }
}

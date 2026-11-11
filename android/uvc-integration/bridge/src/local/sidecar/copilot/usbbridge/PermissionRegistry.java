package local.sidecar.copilot.usbbridge;

import android.content.ComponentName;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import java.util.HashMap;
import java.util.Map;
import local.sidecar.copilot.usbowner.PlatformUsbPort.PermissionCallback;
import local.sidecar.copilot.uvccontract.UvcContract.PermissionRequest;

/** Process-local correlation only. Restart has no entries, therefore receives no authority. */
final class PermissionRegistry {
    private static final Object LOCK = new Object();
    private static final Map<String, Entry> ENTRIES = new HashMap<>();
    private PermissionRegistry() {}
    static final class Entry {
        final AndroidPlatformUsbPort owner;
        final PermissionRequest request;
        final PermissionCallback callback;
        final String action, data;
        final ComponentName component;
        final AndroidPlatformUsbPort.DeviceKey device;
        volatile boolean active;
        Entry(AndroidPlatformUsbPort owner, PermissionRequest request, PermissionCallback callback,
              String action, String data, ComponentName component, AndroidPlatformUsbPort.DeviceKey device) {
            this.owner = owner; this.request = request; this.callback = callback;
            this.action = action; this.data = data; this.component = component; this.device = device;
        }
    }
    static void register(Entry entry) {
        synchronized (LOCK) {
            if (ENTRIES.containsKey(entry.data)) throw new IllegalStateException("duplicate permission capability");
            ENTRIES.put(entry.data, entry); entry.active = true;
        }
    }
    static void remove(Entry entry) {
        synchronized (LOCK) {
            if (ENTRIES.get(entry.data) == entry) ENTRIES.remove(entry.data);
            entry.active = false;
        }
    }
    static void receive(Intent intent, UsbDevice device, boolean granted) {
        if (intent.getData() == null) return;
        String data = intent.getData().toString();
        Entry entry;
        synchronized (LOCK) { entry = ENTRIES.get(data); }
        if (entry == null || !entry.action.equals(intent.getAction())
                || !entry.component.equals(intent.getComponent()) || !entry.device.matches(device)) return;
        synchronized (LOCK) {
            if (ENTRIES.get(data) != entry || !entry.active) return;
            ENTRIES.remove(data); entry.active = false;
        }
        // Never call owner, platform, native or application code under the registry lock.
        entry.owner.deliverPermission(entry, granted);
    }
}

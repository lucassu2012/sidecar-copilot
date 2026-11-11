package android.hardware.usb;
import android.app.PendingIntent;
import java.util.HashMap;
/** Controlled methods with hooks, never a system service. */
public final class UsbManager {
    public static final String ACTION_USB_DEVICE_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    public static final String ACTION_USB_DEVICE_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";
    public static final String EXTRA_DEVICE = "device";
    public static final String EXTRA_PERMISSION_GRANTED = "permission";
    private final HashMap<String, UsbDevice> devices = new HashMap<String, UsbDevice>();
    public volatile boolean testPermission;
    public volatile Runnable testOnRequest = () -> {};
    public volatile Runnable testOnOpen = () -> {};
    public volatile Runnable testOnPermission = () -> {};
    public volatile PendingIntent testPending;
    public volatile UsbDevice testRequestedDevice;
    public volatile UsbDeviceConnection testConnection = new UsbDeviceConnection(0);
    private int requests, opens, permissionChecks;
    public synchronized HashMap<String, UsbDevice> getDeviceList() { return new HashMap<String, UsbDevice>(devices); }
    public synchronized void testPut(UsbDevice device) { devices.put(device.getDeviceName(), device); }
    public synchronized void testRemove(UsbDevice device) { devices.remove(device.getDeviceName()); }
    public boolean hasPermission(UsbDevice device) {
        synchronized (this) { permissionChecks++; }
        testOnPermission.run(); return testPermission;
    }
    public void requestPermission(UsbDevice device, PendingIntent pending) {
        testRequestedDevice = device; testPending = pending;
        synchronized (this) { requests++; }
        testOnRequest.run();
    }
    public UsbDeviceConnection openDevice(UsbDevice device) {
        synchronized (this) { opens++; }
        UsbDeviceConnection acquired = testConnection;
        if (acquired != null) acquired.testOpenEnter();
        try { testOnOpen.run(); return acquired; }
        finally { if (acquired != null) acquired.testOpenExit(); }
    }
    public synchronized int testRequestCalls() { return requests; }
    public synchronized int testOpenCalls() { return opens; }
    public synchronized int testPermissionChecks() { return permissionChecks; }
}

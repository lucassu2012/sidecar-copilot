package local.sidecar.copilot.uvc;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Read-only descriptor discovery. Does not request permissions, open USB or load a native library. */
public final class AndroidUvcDiscovery {
    private AndroidUvcDiscovery() {}
    public static final class Candidate {
        public final String deviceName;
        public final int vendorId, productId;
        public final boolean currentUsbPermission;
        private Candidate(UsbDevice d, boolean permission) {
            deviceName=d.getDeviceName(); vendorId=d.getVendorId(); productId=d.getProductId();
            currentUsbPermission=permission;
        }
    }
    public static List<Candidate> enumerate(UsbManager manager) {
        if (manager==null) return Collections.emptyList();
        List<Candidate> result=new ArrayList<>();
        for (UsbDevice device:manager.getDeviceList().values()) {
            boolean control=false,stream=false;
            // Composite devices may advertise class zero at device level.
            for (int i=0;i<device.getInterfaceCount();i++) {
                UsbInterface iface=device.getInterface(i);
                control |= isVideoControl(iface.getInterfaceClass(),iface.getInterfaceSubclass());
                stream |= isVideoStreaming(iface.getInterfaceClass(),iface.getInterfaceSubclass());
            }
            if (control && stream) result.add(new Candidate(device,manager.hasPermission(device)));
        }
        return Collections.unmodifiableList(result);
    }
    public static boolean isVideoControl(int clazz,int subclass) { return clazz==14 && subclass==1; }
    public static boolean isVideoStreaming(int clazz,int subclass) { return clazz==14 && subclass==2; }
}

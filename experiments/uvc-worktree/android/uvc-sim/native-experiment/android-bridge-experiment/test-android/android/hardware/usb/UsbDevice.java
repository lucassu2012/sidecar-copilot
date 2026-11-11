package android.hardware.usb;
import android.os.Parcelable;
/** Immutable test device metadata, with a test-only constructor absent from the SDK surface. */
public final class UsbDevice implements Parcelable {
    private final String name;
    private final int id, vendor, product, deviceClass, subclass, protocol;
    public UsbDevice(String name, int id, int vendor, int product, int deviceClass, int subclass, int protocol) {
        this.name = name; this.id = id; this.vendor = vendor; this.product = product;
        this.deviceClass = deviceClass; this.subclass = subclass; this.protocol = protocol;
    }
    public String getDeviceName() { return name; }
    public int getDeviceId() { return id; }
    public int getVendorId() { return vendor; }
    public int getProductId() { return product; }
    public int getDeviceClass() { return deviceClass; }
    public int getDeviceSubclass() { return subclass; }
    public int getDeviceProtocol() { return protocol; }
    @Override public boolean equals(Object other) { return other instanceof UsbDevice && name.equals(((UsbDevice) other).name); }
    @Override public int hashCode() { return name.hashCode(); }
}

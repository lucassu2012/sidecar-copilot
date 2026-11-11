package android.hardware.usb;
import java.util.ArrayList;
import java.util.List;
/** Actual Java call and close ledger only; fd is a number, never an OS descriptor. */
public final class UsbDeviceConnection {
    private final int fd;
    public volatile Runnable testOnGetFd = () -> {};
    public volatile Runnable testOnClose = () -> {};
    private int getFdActive, platformOpenActive, borrows, closes;
    private boolean nativeDrained = true, closed;
    private final List<String> violations = new ArrayList<String>();
    public UsbDeviceConnection(int fd) { this.fd = fd; }
    public int getFileDescriptor() {
        synchronized (this) { getFdActive++; }
        try { testOnGetFd.run(); return fd; } finally { synchronized (this) { getFdActive--; } }
    }
    public void close() {
        synchronized (this) {
            closes++;
            if (getFdActive != 0 || platformOpenActive != 0) violations.add("EARLY_FD_CLOSE_PLATFORM_CALL");
            if (borrows != 0) violations.add("EARLY_FD_CLOSE_BORROW");
            if (!nativeDrained) violations.add("EARLY_FD_CLOSE_NATIVE_DRAIN");
            if (closes > 1) violations.add("DUPLICATE_FD_CLOSE");
        }
        testOnClose.run();
        synchronized (this) { closed = true; }
    }
    public synchronized void testOpenEnter() { platformOpenActive++; }
    public synchronized void testOpenExit() { platformOpenActive--; }
    public synchronized void testBorrowEnter() { borrows++; }
    public synchronized void testBorrowExit() { if (--borrows < 0) throw new AssertionError("borrow ledger underflow"); }
    public synchronized void testNativeStarted() { nativeDrained = false; }
    public synchronized void testNativeDrained() { nativeDrained = true; }
    public synchronized int testCloseCalls() { return closes; }
    public synchronized boolean testClosed() { return closed; }
    public synchronized List<String> testViolations() { return new ArrayList<String>(violations); }
}

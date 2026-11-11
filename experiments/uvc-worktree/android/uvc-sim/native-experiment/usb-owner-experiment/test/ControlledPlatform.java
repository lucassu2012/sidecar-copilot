package local.sidecar.copilot.usbowner;

import java.util.ArrayList;
import java.util.List;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/** Independent fake platform ledger: no Android, USB or OS descriptor operations. */
final class ControlledPlatform implements PlatformUsbPort {
    private static final Runnable NOOP = () -> {};
    volatile Runnable onRequest = NOOP, onAttachment = NOOP, onPermission = NOOP;
    volatile Runnable onOpen = NOOP, onGetFd = NOOP, onClose = NOOP;
    volatile boolean permission = true, current = true;
    volatile int fd;
    volatile PermissionRequest request;
    volatile PermissionCallback callback;
    final FakeConnection connection = new FakeConnection();
    private int activeCalls, requestCalls, permissionCalls, attachmentCalls, openCalls, getFdCalls;
    private int closeCalls, closeActive, nativeBorrows;
    private boolean nativeDrained = true, closed;
    private final List<String> violations = new ArrayList<String>();
    private final List<String> trace = new ArrayList<String>();

    private synchronized void enter(String phase) { activeCalls++; trace.add(phase + ":enter"); }
    private synchronized void exit(String phase) { activeCalls--; trace.add(phase + ":exit"); }
    synchronized int calls() { return trace.size(); }
    synchronized int activeCalls() { return activeCalls; }
    synchronized int requestCalls() { return requestCalls; }
    synchronized int permissionCalls() { return permissionCalls; }
    synchronized int attachmentCalls() { return attachmentCalls; }
    synchronized int openCalls() { return openCalls; }
    synchronized int getFdCalls() { return getFdCalls; }
    synchronized int closeCalls() { return closeCalls; }
    synchronized boolean closed() { return closed; }
    synchronized void borrowEntered() { nativeBorrows++; }
    synchronized void borrowReturned() {
        if (--nativeBorrows < 0) throw new AssertionError("independent borrow ledger underflow");
    }
    synchronized void nativeStarted() { nativeDrained = false; }
    synchronized void nativeDrained() { nativeDrained = true; }
    synchronized List<String> violations() { return new ArrayList<String>(violations); }
    synchronized List<String> trace() { return new ArrayList<String>(trace); }

    @Override public void requestPermission(PermissionRequest value, PermissionCallback receiver) {
        request = value; callback = receiver;
        synchronized (this) { requestCalls++; }
        enter("permission-request");
        try { onRequest.run(); } finally { exit("permission-request"); }
    }

    void reply(PermissionRequest value, boolean granted) {
        PermissionCallback receiver = callback;
        if (receiver == null) throw new AssertionError("no registered platform permission callback");
        receiver.result(value, granted);
    }

    @Override public boolean isCurrentAttachment(Binding binding) {
        synchronized (this) { attachmentCalls++; }
        enter("attachment-check");
        try { onAttachment.run(); return current; } finally { exit("attachment-check"); }
    }

    @Override public boolean hasPermission(Binding binding) {
        synchronized (this) { permissionCalls++; }
        enter("permission-check");
        try { onPermission.run(); return permission; } finally { exit("permission-check"); }
    }

    @Override public Connection openConnection(Binding binding) {
        synchronized (this) { openCalls++; }
        enter("connection-open");
        try { onOpen.run(); return connection; } finally { exit("connection-open"); }
    }

    final class FakeConnection implements Connection {
        @Override public int getFileDescriptor() {
            synchronized (ControlledPlatform.this) { getFdCalls++; }
            enter("get-fd");
            try { onGetFd.run(); return fd; } finally { exit("get-fd"); }
        }

        @Override public void close() {
            synchronized (ControlledPlatform.this) {
                closeCalls++;
                if (activeCalls != 0) violations.add("EARLY_CLOSE_PLATFORM_CALL");
                if (nativeBorrows != 0) violations.add("EARLY_CLOSE_BORROW");
                if (!nativeDrained) violations.add("EARLY_CLOSE_NATIVE_DRAIN");
                if (closeCalls > 1 || closed || closeActive != 0) violations.add("DUPLICATE_CONNECTION_CLOSE");
                closeActive++;
                trace.add("connection-close:enter");
            }
            try {
                onClose.run();
                synchronized (ControlledPlatform.this) { closed = true; }
            } finally {
                synchronized (ControlledPlatform.this) {
                    closeActive--; trace.add("connection-close:exit");
                }
            }
        }
    }
}

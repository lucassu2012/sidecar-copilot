package local.sidecar.copilot.usbowner;

import java.util.Objects;
import static local.sidecar.copilot.uvccontract.UvcContract.*;

/**
 * Controlled Java boundary, NOT an Android implementation. All calls may block or throw.
 * The platform implementation must authenticate permission callback origin and associate the
 * device/attachment with the complete identity. A nonce in extras is correlation metadata,
 * not proof of PendingIntent identity, receiver security, permission, or attachment freshness.
 * requestPermission may invoke its callback before returning, more than once, or much later.
 * A grant is only a hint: hasPermission and the current attachment must still be checked.
 * No method starts capture. The real lifecycle/service/publication policies are external.
 */
public interface PlatformUsbPort {
    void requestPermission(PermissionRequest request, PermissionCallback callback);
    boolean isCurrentAttachment(Binding binding);
    boolean hasPermission(Binding binding);

    /**
     * Returns exclusive Connection ownership, even if stop raced the call. Null means no
     * connection was acquired. A throw leaves acquisition unknown: the owner stays pending.
     * A real implementation needs an acquisition/exception contract that cannot lose a handle.
     */
    Connection openConnection(Binding binding);

    interface PermissionCallback {
        void result(PermissionRequest request, boolean granted);
    }

    interface Connection {
        /** Zero is valid. This integer is a borrowed view, never a separately owned raw FD. */
        int getFileDescriptor();
        /** Successful return proves this connection closed. A throw leaves state unknown. */
        void close();
    }

    final class PlatformFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final FailureKind kind;
        public final int nativeCode;
        public PlatformFailure(FailureKind kind, int nativeCode) {
            super("controlled platform failure");
            this.kind = Objects.requireNonNull(kind); this.nativeCode = nativeCode;
        }
    }
}

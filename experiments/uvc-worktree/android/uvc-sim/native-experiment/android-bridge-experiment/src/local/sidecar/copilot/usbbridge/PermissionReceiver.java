package local.sidecar.copilot.usbbridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;

/** Explicit, non-exported manifest receiver. Metadata-only; never opens or closes USB. */
public final class PermissionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        try {
            Boolean granted = grantedExtra(intent);
            UsbDevice device = deviceExtra(intent);
            if (granted == null || device == null) return;
            PermissionRegistry.receive(intent, device, granted.booleanValue());
        } catch (RuntimeException | Error malformedParcelable) {
            // Malformed/unmatched deliveries do not consume a valid registered capability.
        }
    }
    @SuppressWarnings("deprecation")
    private static Boolean grantedExtra(Intent intent) {
        Bundle extras = intent.getExtras();
        Object value = extras == null ? null : extras.get(UsbManager.EXTRA_PERMISSION_GRANTED);
        return value instanceof Boolean ? (Boolean) value : null;
    }
    @SuppressWarnings("deprecation")
    static UsbDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        Object device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        return device instanceof UsbDevice ? (UsbDevice) device : null;
    }
}

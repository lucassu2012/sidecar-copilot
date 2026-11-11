package android.app;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
/**
 * Project-owned identity/cache model. It is not evidence of real system PI/Binder authenticity.
 * Only explicit action/data/component and request code are used by the product slice.
 */
public final class PendingIntent {
    public static final int FLAG_ONE_SHOT = 0x40000000;
    public static final int FLAG_NO_CREATE = 0x20000000;
    public static final int FLAG_CANCEL_CURRENT = 0x10000000;
    public static final int FLAG_UPDATE_CURRENT = 0x08000000;
    public static final int FLAG_IMMUTABLE = 0x04000000;
    public static final int FLAG_MUTABLE = 0x02000000;
    private static final Map<String, PendingIntent> CACHE = new HashMap<String, PendingIntent>();
    public static volatile Consumer<PendingIntent> testBeforeReturn = pending -> {};
    private final Context context;
    private final String key;
    private final Intent base;
    private final int flags;
    private boolean cancelled, used;
    private int cancelCalls;
    private PendingIntent(Context context, String key, Intent intent, int flags) {
        this.context = context; this.key = key; base = new Intent(intent); this.flags = flags;
    }
    public static PendingIntent getBroadcast(Context context, int code, Intent intent, int flags) {
        final PendingIntent pending;
        synchronized (PendingIntent.class) {
            int identityFlags = flags & (FLAG_ONE_SHOT | FLAG_IMMUTABLE | FLAG_MUTABLE);
            String key = context.getPackageName() + "\n" + code + "\n" + identityFlags + "\n" + intent.testFilterKey();
            PendingIntent old = CACHE.get(key);
            if (old != null && !old.cancelled && (flags & FLAG_CANCEL_CURRENT) == 0) {
                if ((flags & FLAG_UPDATE_CURRENT) != 0 && intent.getExtras() != null) old.base.putExtras(intent.getExtras());
                pending = old;
            } else {
                if (old != null) old.cancel();
                if ((flags & FLAG_NO_CREATE) != 0) pending = null;
                else {
                    pending = new PendingIntent(context, key, intent, flags); CACHE.put(key, pending);
                }
            }
        }
        // The test seam is outside the cache lock; stop/cleanup may run while factory return waits.
        if (pending != null) testBeforeReturn.accept(pending);
        return pending;
    }
    public void cancel() {
        synchronized (PendingIntent.class) {
            cancelled = true; cancelCalls++;
            if (CACHE.get(key) == this) CACHE.remove(key);
        }
    }
    public Intent testIntent() { synchronized (PendingIntent.class) { return new Intent(base); } }
    public int testFlags() { return flags; }
    public int testCancelCalls() { synchronized (PendingIntent.class) { return cancelCalls; } }
    public boolean testCancelled() { synchronized (PendingIntent.class) { return cancelled; } }
    public boolean testSend(Bundle resultExtras) {
        Intent delivery;
        synchronized (PendingIntent.class) {
            if (cancelled || used) return false;
            if ((flags & FLAG_ONE_SHOT) != 0) used = true;
            delivery = new Intent(base);
            if (resultExtras != null && (flags & FLAG_IMMUTABLE) == 0) {
                Bundle merged = new Bundle(resultExtras);
                Bundle original = base.getExtras();
                if (original != null) merged.putAll(original); // Base extras win, as with Intent.fillIn.
                delivery.putExtras(merged);
            }
        }
        return context.testDispatch(this, delivery);
    }
    /** Deliberate invalid-dispatch seam for independent oracle negative controls only. */
    public boolean testDispatchAs(Intent delivery) { return context.testDispatch(this, delivery); }
}

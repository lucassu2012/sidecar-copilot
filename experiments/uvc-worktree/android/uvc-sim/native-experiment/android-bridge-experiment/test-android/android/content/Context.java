package android.content;
import android.app.PendingIntent;
/** Only the production methods referenced by this isolated source slice are modeled. */
public abstract class Context {
    public static final int RECEIVER_NOT_EXPORTED = 4;
    public abstract String getPackageName();
    public Context getApplicationContext() { return this; }
    public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter) { return registerReceiver(receiver, filter, 0); }
    public abstract Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags);
    public abstract void unregisterReceiver(BroadcastReceiver receiver);
    public abstract boolean testDispatch(PendingIntent pending, Intent delivered);
}

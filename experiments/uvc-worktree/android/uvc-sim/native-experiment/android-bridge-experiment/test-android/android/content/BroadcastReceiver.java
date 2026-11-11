package android.content;
/** No goAsync, Binder identity, export rules, or main looper are modeled. */
public abstract class BroadcastReceiver {
    public BroadcastReceiver() {}
    public abstract void onReceive(Context context, Intent intent);
}

package local.sidecar.copilot.usbbridge;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Independent routing oracle; it models addresses only, not Binder/exported/caller authenticity. */
final class BridgeContext extends Context {
    private final String packageName;
    private final Map<ComponentName, BroadcastReceiver> manifest = new HashMap<ComponentName, BroadcastReceiver>();
    private final Map<BroadcastReceiver, IntentFilter> dynamic = new IdentityHashMap<BroadcastReceiver, IntentFilter>();
    private final List<String> violations = new ArrayList<String>();
    volatile Runnable onRegister = () -> {};
    volatile Runnable onUnregister = () -> {};
    private int registrations, unregisters, lastFlags;
    BridgeContext(String packageName) { this.packageName = packageName; }
    @Override public String getPackageName() { return packageName; }
    synchronized void manifest(Class<?> type, BroadcastReceiver receiver) {
        manifest.put(new ComponentName(this, type), receiver);
    }
    @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, int flags) {
        synchronized (this) {
            registrations++; lastFlags = flags;
            if (dynamic.put(receiver, filter) != null) violations.add("DUPLICATE_RECEIVER_REGISTRATION");
        }
        onRegister.run(); return null;
    }
    @Override public void unregisterReceiver(BroadcastReceiver receiver) {
        synchronized (this) { unregisters++; }
        onUnregister.run();
        synchronized (this) {
            if (dynamic.remove(receiver) == null) {
                violations.add("UNREGISTERED_RECEIVER_REMOVE");
                throw new IllegalArgumentException("receiver was not registered");
            }
        }
    }
    void broadcast(Intent intent) {
        List<BroadcastReceiver> recipients = new ArrayList<BroadcastReceiver>();
        synchronized (this) {
            for (Map.Entry<BroadcastReceiver, IntentFilter> entry : dynamic.entrySet())
                if (entry.getValue().hasAction(intent.getAction())) recipients.add(entry.getKey());
        }
        for (BroadcastReceiver receiver : recipients) receiver.onReceive(this, intent);
    }
    @Override public boolean testDispatch(PendingIntent pending, Intent delivered) {
        final BroadcastReceiver receiver;
        synchronized (this) {
            receiver = manifest.get(delivered.getComponent());
            if (receiver == null) { violations.add("UNREGISTERED_MANIFEST_ADDRESS"); return false; }
            if (!pending.testIntent().testFilterKey().equals(delivered.testFilterKey())) {
                violations.add("PENDING_INTENT_IDENTITY_MISMATCH"); return false;
            }
        }
        receiver.onReceive(this, delivered);
        return true;
    }
    synchronized int registrations() { return registrations; }
    synchronized int unregisters() { return unregisters; }
    synchronized int lastFlags() { return lastFlags; }
    synchronized int activeReceivers() { return dynamic.size(); }
    synchronized List<String> violations() { return new ArrayList<String>(violations); }
}

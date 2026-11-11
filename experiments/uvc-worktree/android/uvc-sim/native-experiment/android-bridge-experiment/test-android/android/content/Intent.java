package android.content;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import java.util.Objects;
/** Minimal host Intent. Extras are deliberately excluded from testFilterKey(). */
public class Intent implements Parcelable {
    private String action;
    private Uri data;
    private ComponentName component;
    private final Bundle extras = new Bundle();
    public Intent() {}
    public Intent(String action) { this.action = action; }
    public Intent(Context context, Class<?> type) { component = new ComponentName(context, type); }
    public Intent(Intent other) {
        action = other.action; data = other.data; component = other.component;
        extras.putAll(other.extras);
    }
    public String getAction() { return action; }
    public Uri getData() { return data; }
    public ComponentName getComponent() { return component; }
    public Intent setAction(String value) { action = value; return this; }
    public Intent setData(Uri value) { data = value; return this; }
    public Intent setComponent(ComponentName value) { component = value; return this; }
    public Intent putExtra(String key, boolean value) { extras.putBoolean(key, value); return this; }
    public Intent putExtra(String key, String value) { extras.putString(key, value); return this; }
    public Intent putExtra(String key, Parcelable value) { extras.putParcelable(key, value); return this; }
    public Intent putExtras(Bundle values) { extras.putAll(values); return this; }
    public Bundle getExtras() { return extras.isEmpty() ? null : new Bundle(extras); }
    public boolean hasExtra(String key) { return extras.containsKey(key); }
    public boolean getBooleanExtra(String key, boolean fallback) {
        Object value = extras.get(key); return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }
    public String getStringExtra(String key) {
        Object value = extras.get(key); return value instanceof String ? (String) value : null;
    }
    @SuppressWarnings("unchecked")
    public <T extends Parcelable> T getParcelableExtra(String key) {
        Object value = extras.get(key); return value instanceof Parcelable ? (T) value : null;
    }
    public <T> T getParcelableExtra(String key, Class<T> type) {
        Object value = extras.get(key); return type.isInstance(value) ? type.cast(value) : null;
    }
    public String testFilterKey() {
        return Objects.toString(action, "<null>") + "\n" + Objects.toString(data, "<null>")
            + "\n" + Objects.toString(component, "<null>");
    }
}

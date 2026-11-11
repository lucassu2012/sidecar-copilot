package android.os;
import java.util.HashMap;
import java.util.Map;
/** Minimal superclass preserves the SDK Bundle method-resolution hierarchy. */
public class BaseBundle {
    protected final Map<String, Object> values = new HashMap<String, Object>();
    public Object get(String key) { return values.get(key); }
    public boolean containsKey(String key) { return values.containsKey(key); }
    public boolean isEmpty() { return values.isEmpty(); }
    public void putBoolean(String key, boolean value) { values.put(key, Boolean.valueOf(value)); }
    public void putString(String key, String value) { values.put(key, value); }
}

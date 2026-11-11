package android.os;
/** Project-owned typed-extra map only; not Android serialization. */
public final class Bundle extends BaseBundle implements Parcelable, Cloneable {
    public Bundle() {}
    public Bundle(Bundle other) { values.putAll(other.values); }
    public void putParcelable(String key, Parcelable value) { values.put(key, value); }
    public void putAll(Bundle other) { values.putAll(other.values); }
}

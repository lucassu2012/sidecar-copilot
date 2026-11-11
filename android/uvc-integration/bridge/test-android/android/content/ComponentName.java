package android.content;
import android.os.Parcelable;
public final class ComponentName implements Parcelable {
    private final String packageName, className;
    public ComponentName(String packageName, String className) { this.packageName = packageName; this.className = className; }
    public ComponentName(Context context, Class<?> type) { this(context.getPackageName(), type.getName()); }
    public String getPackageName() { return packageName; }
    public String getClassName() { return className; }
    public String flattenToString() { return packageName + "/" + className; }
    @Override public String toString() { return flattenToString(); }
    @Override public int hashCode() { return flattenToString().hashCode(); }
    @Override public boolean equals(Object other) {
        return other instanceof ComponentName && packageName.equals(((ComponentName) other).packageName)
            && className.equals(((ComponentName) other).className);
    }
}

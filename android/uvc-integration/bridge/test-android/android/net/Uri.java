package android.net;
import android.os.Parcelable;
/** Opaque textual URI equality suffices for these fixed generated addresses. */
public final class Uri implements Parcelable {
    private final String text;
    private Uri(String text) { this.text = text; }
    public static Uri parse(String text) { return new Uri(text); }
    @Override public String toString() { return text; }
    @Override public int hashCode() { return text.hashCode(); }
    @Override public boolean equals(Object other) { return other instanceof Uri && text.equals(((Uri) other).text); }
}

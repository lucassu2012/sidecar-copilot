package android.os;
/** Mutable SDK level is a host-test branch selector, not a device observation. */
public final class Build {
    private Build() {}
    public static final class VERSION {
        private VERSION() {}
        public static int SDK_INT = 35;
    }
}

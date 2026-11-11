package local.sidecar.copilot;

/** Pure domain rules shared by the native UI/store and host regression checks. */
public final class NotePolicy {
    public static final long BUFFER_MS = 15 * 60 * 1000L;
    // Explicit synthetic-development choice in the current UI, not a production default.
    public static final long RETENTION_MS = 90L * 24 * 60 * 60 * 1000;
    public static void checkRevision(long actual, long expected) {
        if (actual != expected) throw new IllegalStateException("版本冲突，请重新打开会议");
    }
    public static void checkReadable(long now, long created, long expires, boolean deleted) {
        if (deleted) throw new IllegalStateException("资料已删除");
        if (now < created) throw new IllegalStateException("时间早于创建时间，资料暂不可读");
        if (now >= expires) throw new IllegalStateException("资料已到期");
    }
    public static void checkScope(String scope) {
        if (!scope.equals("meeting_background") && !scope.equals("unassigned"))
            throw new IllegalArgumentException("此版本仅支持会议背景或待归类资料");
    }
    public static long endBoundary(long existing, long now) {
        return existing == 0 ? now : existing;
    }
    private NotePolicy() {}
}

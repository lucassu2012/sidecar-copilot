package local.sidecar.copilot;

/** Pure policy: no wall-clock rollback can make a previously observed deadline later. */
public final class RetentionClock {
    public static long conservativeNow(long wall, long elapsed, long previousWall,
                                       long previousElapsed, boolean sameBoot) {
        // Small clock quantization differences never reduce the monotonic-derived deadline.
        if (wall < previousWall - 2000) throw new IllegalStateException("设备时间回拨，资料暂不可访问；请校正系统时间后重试");
        if (!sameBoot) throw new IllegalStateException("设备已重启，保留时间待可信校验；当前开发版暂不开放旧资料");
        if (elapsed < previousElapsed) throw new IllegalStateException("单调时钟异常，资料暂不可访问");
        if (wall < 0 || previousWall < 0 || elapsed < 0 || previousElapsed < 0)
            throw new IllegalStateException("clock anchor unavailable");
        long projected = Math.addExact(previousWall, Math.subtractExact(elapsed, previousElapsed));
        // Sleep counts toward elapsed time. Both backward and forward wall jumps are uncertain.
        // In particular, an unverified forward jump must not destroy retained content.
        long difference = Math.subtractExact(wall, projected);
        if (difference < -2000 || difference > 2000)
            throw new IllegalStateException("wall clock disagrees with monotonic projection; verify time");
        // Tolerance validates clock sampling; it must not ratchet the durable anchor.
        return projected;
    }
    /** Validates against a persisted anchor even when routine reads do not rewrite that anchor. */
    public static long anchoredNow(long wall, long elapsed, long previousWall,
                                   long previousElapsed, boolean sameBoot) {
        long result=conservativeNow(wall,elapsed,previousWall,previousElapsed,sameBoot);
        long projected=Math.addExact(previousWall,elapsed-previousElapsed);
        if(wall<projected-2000)throw new IllegalStateException("wall clock precedes monotonic projection");
        return result;
    }
    private RetentionClock() {}
}

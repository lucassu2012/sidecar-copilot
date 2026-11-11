package local.sidecar.copilot.uvc;

import java.util.Objects;

/**
 * Isolated, metadata-only UVC lifecycle proposal. No USB, pixels, disk, or network calls.
 * The integration owner must bind this protocol to the shared control barrier before
 * attaching a real driver. All methods are bounded; no external code runs under its lock.
 */
public final class UvcLifecycle {
    // Existing PRD recording labels only; pending operations are diagnostic facts.
    public enum State { UNCONFIGURED, READY, RECORDING, PAUSED, SOURCE_INTERRUPTED, STOPPED }
    public enum End { PAUSE, STOP, SOURCE_LOST, FORMAT_CHANGED, PERMISSION_REVOKED }

    public static final class Device {
        public final String attachmentId; // fresh for each enumeration; never VID/PID alone
        public final int vendorId, productId;
        public final boolean videoControl, videoStreaming;
        public Device(String attachmentId, int vendorId, int productId,
                      boolean videoControl, boolean videoStreaming) {
            this.attachmentId = nonempty(attachmentId);
            this.vendorId = vendorId; this.productId = productId;
            this.videoControl = videoControl; this.videoStreaming = videoStreaming;
        }
        public boolean candidate() { return videoControl && videoStreaming; }
    }
    public static final class Format {
        public final int width, height, fpsNumerator, fpsDenominator;
        public final String encoding;
        public Format(int width, int height, int fpsNumerator, int fpsDenominator, String encoding) {
            if (width <= 0 || height <= 0 || fpsNumerator <= 0 || fpsDenominator <= 0)
                throw new IllegalArgumentException("invalid negotiated format");
            this.width=width; this.height=height; this.fpsNumerator=fpsNumerator;
            this.fpsDenominator=fpsDenominator; this.encoding=nonempty(encoding);
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Format)) return false;
            Format f=(Format)o;
            return width==f.width && height==f.height && fpsNumerator==f.fpsNumerator
                && fpsDenominator==f.fpsDenominator && encoding.equals(f.encoding);
        }
        @Override public int hashCode() { return Objects.hash(width,height,fpsNumerator,fpsDenominator,encoding); }
    }
    /** Capability objects use identity, so another lifecycle's callbacks cannot authorize this one. */
    public static final class Attempt {
        public final String attachmentId;
        private Attempt(String attachmentId) { this.attachmentId=attachmentId; }
    }
    public static final class Run {
        public final long generation, requestedMonoNs;
        public final String bootId;
        private Run(long generation, long requestedMonoNs, String bootId) {
            this.generation=generation; this.requestedMonoNs=requestedMonoNs; this.bootId=bootId;
        }
    }
    public static final class Barrier {
        public final long requestedMonoNs, acceptedMonoNs, generation;
        public final End reason;
        private Barrier(long requested, long accepted, long generation, End reason) {
            this.requestedMonoNs=requested; this.acceptedMonoNs=accepted;
            this.generation=generation; this.reason=reason;
        }
    }
    public static final class Snapshot {
        public final State state;
        public final boolean opening, starting, stopPending, acceptingFrames;
        public final long generation, lastAcceptedReceiveMonoNs, stopEffectiveMonoNs;
        public final String diagnostic;
        private Snapshot(UvcLifecycle s) {
            state=s.state; opening=s.opening; starting=s.starting; stopPending=s.barrier!=null;
            acceptingFrames=s.accepting; generation=s.generation;
            lastAcceptedReceiveMonoNs=s.lastReceive; stopEffectiveMonoNs=s.stopEffective;
            diagnostic=s.diagnostic;
        }
    }

    private final String bootId;
    private State state=State.UNCONFIGURED;
    private Device device;
    private Attempt attempt;
    private Run run;
    private Barrier barrier;
    private End desiredEnd;
    private Format format;
    private boolean permission, opening, opened, starting, accepting, displayConfirmed;
    private long generation, lastReceive=-1, stopEffective=-1, lastEvent=-1;
    private String diagnostic="no selected source";

    public UvcLifecycle(String bootId) { this.bootId=nonempty(bootId); }

    /** Explicit user selection. No auto-select, fallback, or implicit open on attach. */
    public synchronized Attempt select(Device selected, long now) {
        event(now);
        if (opened || opening || starting || run!=null || barrier!=null)
            throw new IllegalStateException("previous device has not acknowledged close");
        if (!selected.candidate()) throw new IllegalArgumentException("not a UVC candidate");
        device=selected; attempt=new Attempt(selected.attachmentId);
        permission=false; format=null; displayConfirmed=false; state=State.UNCONFIGURED;
        diagnostic="USB permission required; compatibility unverified";
        return attempt;
    }

    /** Recheck UsbManager.hasPermission and current attachment, never trust broadcast extras alone. */
    public synchronized boolean permissionResult(Attempt ticket, boolean currentPermission, long now) {
        event(now);
        if (ticket==null || ticket!=attempt || barrier!=null || opened || opening) return false;
        permission=currentPermission;
        if (!permission) { attempt=null; diagnostic="USB permission denied"; }
        return permission;
    }

    /** Caller dispatches open on its device worker, without acquiring this monitor. */
    public synchronized boolean beginOpen(Attempt ticket, long now) {
        event(now);
        if (ticket==null || ticket!=attempt || !permission || opening || opened || barrier!=null) return false;
        opening=true; diagnostic="opening selected source"; return true;
    }

    /** False means do not use the handle. Caller must close any returned handle, including late opens. */
    public synchronized boolean opened(Attempt ticket, Format actual, long now) {
        event(now);
        if (ticket==null || ticket!=attempt || !opening || barrier!=null) return false;
        Objects.requireNonNull(actual);
        opening=false; opened=true; format=actual;
        state=State.READY; diagnostic="confirm actual format and selected screen"; return true;
    }

    /** Permission is not content consent. A real integration must recheck all shared start gates. */
    public synchronized boolean confirmDisplay(Attempt ticket, Format actual, long now) {
        event(now);
        if (ticket!=attempt || !opened || barrier!=null || !format.equals(actual)) return false;
        displayConfirmed=true; return true;
    }

    public synchronized Run beginStart(boolean explicitUserAction, boolean sharedGatesPass, long now) {
        event(now);
        if (!explicitUserAction || !sharedGatesPass || !opened || !permission || !displayConfirmed
                || starting || accepting || run!=null || barrier!=null) return null;
        run=new Run(++generation,now,bootId); starting=true; lastReceive=-1; stopEffective=-1;
        diagnostic="awaiting driver start acknowledgment"; return run;
    }
    public synchronized boolean started(Run ticket, long now) {
        event(now);
        if (ticket==null || ticket!=run || !starting || barrier!=null) return false;
        starting=false; accepting=true; state=State.RECORDING; diagnostic=""; return true;
    }

    /**
     * Metadata admission check only, NOT a permit to persist later. Real integration must
     * atomically recheck its shared generation/barrier at preview, queue and commit boundaries.
     * host_receive is an observation, never a fabricated exposure or hardware timestamp.
     */
    public synchronized boolean observeFrame(Run ticket, String frameBootId, Format actual,
                                             long hostReceiveMonoNs, long now) {
        event(now);
        if (ticket==null || ticket!=run || !accepting || barrier!=null || !bootId.equals(frameBootId)) return false;
        if (!format.equals(actual)) {
            end(End.FORMAT_CHANGED,now,now); return false;
        }
        if (hostReceiveMonoNs<run.requestedMonoNs || hostReceiveMonoNs>now || hostReceiveMonoNs<=lastReceive)
            return false;
        lastReceive=hostReceiveMonoNs; return true;
    }

    /** Invalidate admission before dispatching potentially blocking driver stop/close. Idempotent. */
    public synchronized Barrier end(End reason, long requested, long accepted) {
        event(accepted);
        Objects.requireNonNull(reason);
        if (requested<0 || requested>accepted) throw new IllegalArgumentException("invalid control time");
        if (barrier!=null) {
            if (reason==End.STOP) desiredEnd=End.STOP;
            return barrier;
        }
        accepting=false; displayConfirmed=false; permission=false; generation++;
        barrier=new Barrier(requested,accepted,generation,reason);
        desiredEnd=reason;
        if (reason==End.SOURCE_LOST || reason==End.FORMAT_CHANGED || reason==End.PERMISSION_REVOKED)
            state=State.SOURCE_INTERRUPTED;
        diagnostic="admission blocked; awaiting stop and close acknowledgment";
        return barrier;
    }

    /** Detach from an unrelated peripheral cannot stop the selected video source. */
    public synchronized Barrier detached(String attachmentId, long now) {
        event(now);
        if (device==null || !device.attachmentId.equals(attachmentId)) return null;
        return end(End.SOURCE_LOST,now,now);
    }

    /** Caller proves pending open/start work is drained and every owned handle is closed. */
    public synchronized boolean closed(Barrier ticket, boolean success, long effective, long now) {
        event(now);
        if (ticket==null || ticket!=barrier) return false;
        if (!success || effective<ticket.acceptedMonoNs || effective>now) {
            diagnostic="stop/close not confirmed; recovery required"; return false;
        }
        stopEffective=effective; opening=false; opened=false; starting=false;
        attempt=null; run=null; format=null; barrier=null;
        state=desiredEnd==End.PAUSE ? State.PAUSED : desiredEnd==End.STOP ? State.STOPPED : State.SOURCE_INTERRUPTED;
        diagnostic="closed; explicit source selection and confirmation required"; return true;
    }
    public synchronized Snapshot snapshot() { return new Snapshot(this); }
    private void event(long now) {
        if (now<0 || now<lastEvent) throw new IllegalArgumentException("monotonic event time moved backwards");
        lastEvent=now;
    }
    private static String nonempty(String value) {
        if (value==null || value.isEmpty()) throw new IllegalArgumentException("empty identity");
        return value;
    }
}

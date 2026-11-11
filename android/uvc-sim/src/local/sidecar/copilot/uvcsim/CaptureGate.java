package local.sidecar.copilot.uvcsim;

/** In-memory fixture admission barrier. No device call, I/O or media processing under this lock. */
public final class CaptureGate {
    public static final class Lease {
        public final String sessionId, attachmentId;
        public final long permissionRevision, generation;
        private Lease(String session, String attachment, long revision, long generation) {
            sessionId=session; attachmentId=attachment; permissionRevision=revision; this.generation=generation;
        }
    }
    private Lease active;
    private long generation;
    public synchronized Lease issue(String session, String attachment, long permissionRevision) {
        if(session==null || session.isEmpty() || attachment==null || attachment.isEmpty() || permissionRevision<0)
            throw new IllegalArgumentException("invalid fixture binding");
        if(active!=null) throw new IllegalStateException("another run is active");
        active=new Lease(session,attachment,permissionRevision,++generation);
        return active;
    }
    public synchronized boolean isCurrent(Lease lease) { return lease!=null && lease==active; }
    public synchronized boolean invalidate(Lease lease) {
        if(!isCurrent(lease)) return false;
        active=null; ++generation; return true;
    }
    public synchronized void invalidateAll() { if(active!=null){active=null; ++generation;} }
    /** Only trusted bounded pointer/metadata swaps belong here; never invoke a driver or hash/copy pixels. */
    public synchronized boolean publish(Lease lease, Runnable boundedPointerSwap) {
        if(!isCurrent(lease)) return false;
        boundedPointerSwap.run(); return true;
    }
}

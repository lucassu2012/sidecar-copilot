package local.sidecar.copilot.uvcsim;

import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Fixture-only RGBA pipeline. Owns transferred pixel arrays; never writes files or exports raw pixels. */
public final class FramePipeline implements UvcCoordinator.FrameSink, AutoCloseable {
    public interface Failure { void interrupted(CaptureGate.Lease lease,String reason); }
    public interface Hook { void at(String stage,long sequence)throws Exception; }
    public static final int WIDTH=640, HEIGHT=360, FRAME_BYTES=WIDTH*HEIGHT*4;
    public static final int QUEUE_CAPACITY=2;
    // Reserve one additional <= 1 MiB generator buffer outside this owned-frame budget.
    public static final long OWNED_BYTE_LIMIT=7L*1024*1024;
    public static final class Mask {
        public final int left,top,right,bottom;
        public Mask(int left,int top,int right,int bottom) {
            if(left<0 || top<0 || right>WIDTH || bottom>HEIGHT || left>=right || top>=bottom)
                throw new IllegalArgumentException("mask outside fixture format");
            this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        }
    }
    public static final class Record {
        public final long generation,sequence,hostReceiveMonoNs;
        public final String kind,reason,sha256;
        private Record(Owned frame,String kind,String reason,String hash) {
            this(frame.lease,frame.frame,kind,reason,hash);
        }
        private Record(CaptureGate.Lease lease,UvcDriver.Frame frame,String kind,String reason,String hash) {
            generation=lease==null?-1:lease.generation; sequence=frame.sequence;
            hostReceiveMonoNs=frame.hostReceiveMonoNs;
            this.kind=kind; this.reason=reason; sha256=hash;
        }
    }
    public static final class Metrics {
        public final long ownedBytes,peakOwnedBytes,accepted,published,dropped,released,overflows,auditEvicted,notificationFailures;
        public final int queued;
        private Metrics(FramePipeline p) {
            ownedBytes=p.bytes.get(); peakOwnedBytes=p.peak.get(); accepted=p.accepted.get();
            published=p.published.get(); dropped=p.dropped.get(); released=p.released.get();
            overflows=p.overflows.get(); synchronized(p.queueLock){queued=p.queue.size();}
            auditEvicted=p.auditEvicted.get();
            notificationFailures=p.notificationFailures.get();
        }
    }
    private final class Owned {
        final CaptureGate.Lease lease;
        final UvcDriver.Frame frame;
        final AtomicBoolean freed=new AtomicBoolean();
        Owned(CaptureGate.Lease lease,UvcDriver.Frame frame){this.lease=lease;this.frame=frame;}
        void release() {
            if(freed.compareAndSet(false,true)) {
                java.util.Arrays.fill(frame.pixels,(byte)0);
                bytes.addAndGet(-frame.pixels.length); released.incrementAndGet();
            }
        }
    }
    private final CaptureGate gate;
    private final Mask mask;
    private final Failure failure;
    private final Hook hook;
    private final Object queueLock=new Object();
    private final ArrayDeque<Owned> queue=new ArrayDeque<>();
    // Audit contains only metadata/hashes and is bounded; callers consume it during fixture runs.
    private final ArrayDeque<Record> audit=new ArrayDeque<>();
    private final AtomicLong bytes=new AtomicLong(),peak=new AtomicLong(),accepted=new AtomicLong(),
            published=new AtomicLong(),dropped=new AtomicLong(),released=new AtomicLong(),overflows=new AtomicLong(),auditEvicted=new AtomicLong(),notificationFailures=new AtomicLong();
    private final Thread worker;
    private volatile boolean closed;
    // Both fields are protected by gate, including reads and replacement.
    private Owned preview;
    private String previewHash;

    public FramePipeline(CaptureGate gate,Mask mask,Failure failure) { this(gate,mask,failure,(s,n)->{}); }
    public FramePipeline(CaptureGate gate,Mask mask,Failure failure,Hook hook) {
        if(gate==null || mask==null || failure==null || hook==null)throw new NullPointerException();
        this.gate=gate;this.mask=mask;this.failure=failure;this.hook=hook;
        worker=new Thread(this::run,"UVC-synthetic-frame-worker"); worker.setDaemon(true); worker.start();
    }
    @Override public void accept(CaptureGate.Lease lease,UvcDriver.Frame frame) {
        if(frame==null || frame.pixels==null)throw new IllegalArgumentException("missing transferred buffer");
        boolean valid=frame.format!=null && frame.format.width==WIDTH && frame.format.height==HEIGHT
                && "RGBA".equals(frame.format.encoding) && frame.pixels.length==FRAME_BYTES;
        if(!valid || closed || !gate.isCurrent(lease)) {
            synchronized(gate){addRecord(new Record(lease,frame,"gap",valid?"admission closed":"unsupported fixture format",null));}
            java.util.Arrays.fill(frame.pixels,(byte)0); dropped.incrementAndGet();
            if(!valid && gate.isCurrent(lease)) interrupt(lease,"unsupported fixture format");
            return;
        }
        if(!reserve(frame.pixels.length)) {
            synchronized(gate){addRecord(new Record(lease,frame,"gap","frame memory budget exhausted",null));}
            java.util.Arrays.fill(frame.pixels,(byte)0); dropped.incrementAndGet();
            interrupt(lease,"frame memory budget exhausted"); return;
        }
        Owned owned=new Owned(lease,frame); boolean enqueued=false,overflow=false;
        synchronized(queueLock) {
            if(!closed && gate.isCurrent(lease)) {
                if(queue.size()<QUEUE_CAPACITY){queue.addLast(owned);accepted.incrementAndGet();enqueued=true;queueLock.notifyAll();}
                else overflow=true;
            }
        }
        if(!enqueued) {
            record(owned,"gap",overflow?"queue capacity exhausted":"admission closed",null);
            dropped.incrementAndGet(); owned.release();
            if(overflow){overflows.incrementAndGet();interrupt(lease,"queue capacity exhausted");}
        }
    }
    private boolean reserve(int length) {
        for(;;){long old=bytes.get(),next=old+length;if(next>OWNED_BYTE_LIMIT)return false;
            if(bytes.compareAndSet(old,next)){peak.accumulateAndGet(next,Math::max);return true;}}
    }
    private void interrupt(CaptureGate.Lease lease,String reason) {
        gate.invalidate(lease); cancel(lease);
        // Never call another subsystem with queue or publication lock held.
        try{failure.interrupted(lease,reason);}catch(RuntimeException error){notificationFailures.incrementAndGet();}
    }
    @Override public void cancel(CaptureGate.Lease lease) {
        gate.invalidate(lease);
        List<Owned> discarded=new ArrayList<>();
        synchronized(queueLock){queue.removeIf(f->{if(f.lease==lease){discarded.add(f);return true;}return false;});}
        Owned previous=null;
        synchronized(gate){if(preview!=null && preview.lease==lease){previous=preview;preview=null;previewHash=null;}}
        if(previous!=null)previous.release();
        for(Owned f:discarded){record(f,"gap","cancelled unpublished queue",null);dropped.incrementAndGet();f.release();}
    }
    private void run() {
        for(;;){Owned current;
            synchronized(queueLock){
                while(queue.isEmpty()&&!closed)try{queueLock.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
                if(queue.isEmpty()&&closed)return; current=queue.removeFirst();
            }
            boolean transferred=false;String discardReason="frame not published";
            try {
                if(!gate.isCurrent(current.lease))continue;
                hook.at("before_mask",current.frame.sequence);
                if(!gate.isCurrent(current.lease))continue;
                redact(current.frame.pixels);
                String hash=hex(MessageDigest.getInstance("SHA-256").digest(current.frame.pixels));
                hook.at("before_publish",current.frame.sequence);
                Owned[] previous=new Owned[1];
                synchronized(gate){if(!closed && gate.isCurrent(current.lease)) {
                    // A current lease retains its preview: use it as the publication watermark.
                    // Admission can precede a delayed sink call, so queue order is not source order.
                    if(preview!=null && preview.lease==current.lease
                            && (current.frame.sequence<=preview.frame.sequence
                                || current.frame.hostReceiveMonoNs<preview.frame.hostReceiveMonoNs)) {
                        discardReason="superseded frame";
                    } else transferred=gate.publish(current.lease,()->{
                        previous[0]=preview;preview=current;previewHash=hash;
                        addRecord(new Record(current,"commit","masked synthetic frame",hash));published.incrementAndGet();
                    });
                }}
                if(previous[0]!=null)previous[0].release();
            } catch(Exception e){
                discardReason="processing failure";interrupt(current.lease,"processing failure");
            } finally {
                if(!transferred){record(current,"gap",discardReason,null);dropped.incrementAndGet();current.release();}
            }
        }
    }
    private void redact(byte[] rgba) {
        for(int y=mask.top;y<mask.bottom;y++)for(int x=mask.left;x<mask.right;x++){
            int i=(y*WIDTH+x)*4;rgba[i]=0;rgba[i+1]=0;rgba[i+2]=0;rgba[i+3]=(byte)255;
        }
    }
    /** A single masked pixel, not an exported full-frame array. null means no authorized preview. */
    public Integer previewPixel(CaptureGate.Lease lease,int x,int y) {
        if(x<0||y<0||x>=WIDTH||y>=HEIGHT)throw new IllegalArgumentException("pixel bounds");
        synchronized(gate){
            if(!gate.isCurrent(lease)||preview==null||preview.lease!=lease)return null;
            int i=(y*WIDTH+x)*4;byte[] p=preview.frame.pixels;
            return ((p[i]&255)<<24)|((p[i+1]&255)<<16)|((p[i+2]&255)<<8)|(p[i+3]&255);
        }
    }
    public String previewHash(CaptureGate.Lease lease) {
        synchronized(gate){return gate.isCurrent(lease)&&preview!=null&&preview.lease==lease?previewHash:null;}
    }
    public List<Record> drainAudit() {
        synchronized(gate){List<Record> result=new ArrayList<>(audit);audit.clear();return Collections.unmodifiableList(result);}
    }
    private void record(Owned f,String kind,String reason,String hash){synchronized(gate){addRecord(new Record(f,kind,reason,hash));}}
    private void addRecord(Record r){if(audit.size()==128){audit.removeFirst();auditEvicted.incrementAndGet();}audit.addLast(r);}
    public Metrics metrics(){return new Metrics(this);}
    public boolean awaitWorkerExit(long timeoutMillis)throws InterruptedException{worker.join(timeoutMillis);return !worker.isAlive();}
    @Override public void close() {
        List<Owned> discarded=new ArrayList<>();
        Owned previous;
        synchronized(gate){if(closed)return;closed=true;gate.invalidateAll();previous=preview;preview=null;previewHash=null;}
        synchronized(queueLock){discarded.addAll(queue);queue.clear();queueLock.notifyAll();}
        if(previous!=null)previous.release();
        for(Owned f:discarded){record(f,"gap","pipeline closed before publication",null);dropped.incrementAndGet();f.release();}
    }
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format("%02x",b&255));return result.toString();}
}

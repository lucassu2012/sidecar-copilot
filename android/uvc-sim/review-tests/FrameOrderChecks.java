package local.sidecar.copilot.uvcsim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Independent order-fix checks. Generated pixels only; no devices or native dependencies. */
public final class FrameOrderChecks {
    private static final UvcDriver.Format FORMAT=new UvcDriver.Format(640,360,"RGBA",5,1);
    private static int scenarios,assertions;

    private static void check(boolean value,String message) {
        assertions++;
        if(!value)throw new AssertionError(message);
    }
    private static void await(BooleanSupplier condition,String message)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(1);
        check(condition.getAsBoolean(),message);
    }
    private static boolean zero(byte[] pixels) {
        for(byte b:pixels)if(b!=0)return false;
        return true;
    }
    private static int color(int value) {
        return (value<<24)|(value<<16)|(value<<8)|255;
    }
    private static final class Fixture implements AutoCloseable {
        final CaptureGate gate=new CaptureGate();
        final AtomicInteger interruptions=new AtomicInteger();
        final List<byte[]> transferred=new ArrayList<>();
        final FramePipeline pipeline;
        Fixture(){this((stage,sequence)->{});}
        Fixture(FramePipeline.Hook hook) {
            pipeline=new FramePipeline(gate,new FramePipeline.Mask(10,10,20,20),
                    (lease,reason)->interruptions.incrementAndGet(),hook);
        }
        CaptureGate.Lease lease(){return gate.issue("order-review","same-attachment",1);}
        byte[] accept(CaptureGate.Lease lease,long sequence,long receive,int value) {
            byte[] pixels=new byte[FramePipeline.FRAME_BYTES];
            Arrays.fill(pixels,(byte)value);
            for(int i=3;i<pixels.length;i+=4)pixels[i]=(byte)255;
            transferred.add(pixels);
            pipeline.accept(lease,new UvcDriver.Frame(FORMAT,"boot",sequence,receive,pixels));
            return pixels;
        }
        void processed(long count)throws Exception {
            await(()->pipeline.metrics().published+pipeline.metrics().dropped>=count,"all submitted frames processed");
            check(pipeline.metrics().published+pipeline.metrics().dropped==count,"one outcome per submitted frame");
        }
        void visible(CaptureGate.Lease lease,int value) {
            check(Integer.valueOf(color(value)).equals(pipeline.previewPixel(lease,0,0)),"newest authorized pixel remains visible");
            check(Integer.valueOf(255).equals(pipeline.previewPixel(lease,10,10)),"visible frame remains redacted");
        }
        @Override public void close()throws Exception {
            pipeline.close();
            check(pipeline.awaitWorkerExit(5000),"frame worker terminates");
            check(pipeline.metrics().ownedBytes==0,"all owned memory released after worker exit");
            for(byte[] pixels:transferred)check(zero(pixels),"every transferred buffer is zero after cleanup");
            check(pipeline.metrics().peakOwnedBytes<=FramePipeline.OWNED_BYTE_LIMIT,"owned memory remains within cap");
            check(interruptions.get()==0,"ordering or control paths cause no source failure");
        }
    }
    private static void expectGap(List<FramePipeline.Record> records,CaptureGate.Lease lease,
                                  long sequence,long receive,String reason) {
        long matches=records.stream().filter(r->r.generation==lease.generation&&r.sequence==sequence
                &&r.hostReceiveMonoNs==receive&&r.kind.equals("gap")&&r.sha256==null
                &&(reason==null||r.reason.equals(reason))).count();
        check(matches==1,"exactly one explained gap for sequence="+sequence+", receive="+receive);
    }
    private static void expectCommit(List<FramePipeline.Record> records,CaptureGate.Lease lease,
                                     long sequence,long receive) {
        long matches=records.stream().filter(r->r.generation==lease.generation&&r.sequence==sequence
                &&r.hostReceiveMonoNs==receive&&r.kind.equals("commit")&&r.sha256!=null&&r.sha256.length()==64).count();
        check(matches==1,"exactly one hashed commit for sequence="+sequence+", receive="+receive);
    }

    public static void main(String[] args)throws Exception {
        olderAndDuplicateSequences();
        receiveTimeOrdering();
        newLeaseResetsOrder();
        controlAtPublication(false);
        controlAtPublication(true);
        System.out.println("PASS FrameOrderChecks: "+scenarios+" scenarios, "+assertions+" assertions");
    }

    private static void olderAndDuplicateSequences()throws Exception {
        scenarios++;
        try(Fixture f=new Fixture()) {
            CaptureGate.Lease lease=f.lease();
            byte[] newest=f.accept(lease,10,1000,0x10);f.processed(1);f.visible(lease,0x10);
            String newestHash=f.pipeline.previewHash(lease);
            // A later receive time cannot make a lower or duplicate sequence current.
            byte[] older=f.accept(lease,9,1100,0x09);f.processed(2);
            check(f.pipeline.metrics().published==1&&f.pipeline.metrics().dropped==1,"older sequence is dropped instead of republished");
            await(()->f.pipeline.metrics().released==1,"older sequence buffer released");
            check(zero(older)&&!zero(newest),"drop clears old input without clearing preview");
            byte[] duplicate=f.accept(lease,10,1200,0x33);f.processed(3);
            check(f.pipeline.metrics().published==1&&f.pipeline.metrics().dropped==2,"duplicate sequence cannot overwrite same-lease preview");
            await(()->f.pipeline.metrics().released==2,"duplicate buffer released");
            check(zero(duplicate)&&newestHash.equals(f.pipeline.previewHash(lease)),"duplicate leaves preview hash unchanged");
            f.visible(lease,0x10);check(f.gate.isCurrent(lease),"superseded frame does not interrupt recording");
            check(f.pipeline.metrics().ownedBytes==FramePipeline.FRAME_BYTES,"only latest committed preview retains ownership");
            List<FramePipeline.Record> records=f.pipeline.drainAudit();check(records.size()==3,"one record per sequence test input");
            expectCommit(records,lease,10,1000);
            expectGap(records,lease,9,1100,"superseded frame");
            expectGap(records,lease,10,1200,"superseded frame");
        }
    }

    private static void receiveTimeOrdering()throws Exception {
        scenarios++;
        try(Fixture f=new Fixture()) {
            CaptureGate.Lease lease=f.lease();
            byte[] first=f.accept(lease,1,1000,0x11);f.processed(1);
            byte[] backwards=f.accept(lease,2,999,0x22);f.processed(2);
            check(f.pipeline.metrics().published==1&&f.pipeline.metrics().dropped==1,"higher sequence with earlier receive time is rejected");
            await(()->f.pipeline.metrics().released==1,"time-regressed input released");
            check(zero(backwards),"time-regressed frame zeroed");f.visible(lease,0x11);
            // A rejected candidate must not advance the watermark; retrying that sequence at
            // the current receive time is valid, including equal monotonic timestamps.
            byte[] equal=f.accept(lease,2,1000,0x33);f.processed(3);
            check(f.pipeline.metrics().published==2&&f.pipeline.metrics().dropped==1,"greater sequence with equal time is allowed");
            await(()->f.pipeline.metrics().released==2,"original preview released after valid replacement");
            check(zero(first)&&!zero(equal),"equal-time replacement transfers preview ownership");f.visible(lease,0x33);
            f.accept(lease,3,1001,0x44);f.processed(4);
            check(f.pipeline.metrics().published==3&&f.pipeline.metrics().dropped==1,"greater sequence and time advances normally");
            await(()->f.pipeline.metrics().released==3,"equal-time preview released after next replacement");
            check(zero(equal),"replaced equal-time buffer zeroed");f.visible(lease,0x44);
            List<FramePipeline.Record> records=f.pipeline.drainAudit();check(records.size()==4,"one record per time-order input");
            expectCommit(records,lease,1,1000);expectGap(records,lease,2,999,"superseded frame");
            expectCommit(records,lease,2,1000);expectCommit(records,lease,3,1001);
        }
    }

    private static void newLeaseResetsOrder()throws Exception {
        scenarios++;
        try(Fixture f=new Fixture()) {
            CaptureGate.Lease old=f.lease();
            byte[] oldPreview=f.accept(old,100,10000,0x55);f.processed(1);
            check(f.gate.invalidate(old),"old lease revoked before fresh run");
            check(f.pipeline.previewHash(old)==null,"old preview inaccessible immediately after barrier");
            // Leave old preview retained to exercise the lease identity boundary itself.
            CaptureGate.Lease fresh=f.lease();
            check(fresh!=old&&fresh.generation>old.generation,"identical binding still has a distinct lease generation");
            f.accept(fresh,1,1,0x66);f.processed(2);
            check(f.pipeline.metrics().published==2&&f.pipeline.metrics().dropped==0,"new lease restarts both sequence and receive watermark");
            await(()->f.pipeline.metrics().released==1,"old retained preview released by new generation publication");
            check(zero(oldPreview),"old retained buffer zeroed after fresh commit");f.visible(fresh,0x66);
            String freshHash=f.pipeline.previewHash(fresh);f.pipeline.cancel(old);
            check(f.gate.isCurrent(fresh)&&freshHash.equals(f.pipeline.previewHash(fresh)),"delayed old cancellation preserves fresh preview and lease");
            byte[] late=f.accept(old,101,10001,0x77);f.processed(3);
            check(zero(late)&&f.pipeline.metrics().published==2,"late old callback cannot publish despite higher sequence");
            f.pipeline.cancel(old);f.visible(fresh,0x66);
            check(f.pipeline.metrics().ownedBytes==FramePipeline.FRAME_BYTES,"fresh preview is sole retained owner");
            List<FramePipeline.Record> records=f.pipeline.drainAudit();check(records.size()==3,"generation outcomes fully audited");
            expectCommit(records,old,100,10000);expectCommit(records,fresh,1,1);expectGap(records,old,101,10001,"admission closed");
        }
    }

    private static void controlAtPublication(boolean close)throws Exception {
        scenarios++;
        CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
        Fixture f=new Fixture((stage,sequence)->{
            if(stage.equals("before_publish")&&sequence==4) {
                entered.countDown();
                if(!resume.await(5,TimeUnit.SECONDS))throw new IllegalStateException("bounded review latch timed out");
            }
        });
        try {
            CaptureGate.Lease lease=f.lease();
            byte[] preview=f.accept(lease,5,500,0x15);f.processed(1);
            byte[] paused=f.accept(lease,4,400,0x24);
            check(entered.await(5,TimeUnit.SECONDS),"older frame paused before final publication decision");
            byte[] queued=f.accept(lease,6,600,0x36);
            check(f.pipeline.metrics().queued==1&&f.pipeline.metrics().ownedBytes==3L*FramePipeline.FRAME_BYTES,"preview flight and queue all owned before control");
            if(close)f.pipeline.close();else f.pipeline.cancel(lease);
            check(!f.gate.isCurrent(lease)&&f.pipeline.previewHash(lease)==null,"control revokes publication before paused worker resumes");
            check(zero(preview)&&zero(queued)&&f.pipeline.metrics().queued==0,"control clears preview and queued buffers");
            byte[] late=f.accept(lease,7,700,0x47);check(zero(late),"post-control input zeroed synchronously");
            resume.countDown();f.processed(4);
            await(()->f.pipeline.metrics().ownedBytes==0&&f.pipeline.metrics().released==3,"paused old input released after control");
            check(zero(paused)&&f.pipeline.metrics().published==1&&f.pipeline.metrics().dropped==3,"neither superseded paused frame nor queued frame publishes after control");
            check(f.pipeline.previewHash(lease)==null,"no preview resurrection after worker resumes");
            List<FramePipeline.Record> records=f.pipeline.drainAudit();check(records.size()==4,"one outcome for every control-race input");
            expectCommit(records,lease,5,500);expectGap(records,lease,4,400,null);
            expectGap(records,lease,6,600,close?"pipeline closed before publication":"cancelled unpublished queue");
            expectGap(records,lease,7,700,"admission closed");
        } finally {resume.countDown();f.close();}
    }
}

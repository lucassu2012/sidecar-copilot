package local.sidecar.copilot.uvcsim;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class FramePipelineChecks {
    private static int checks, scenarios;
    private static long peakBytes, stopObservationNs;
    private static final UvcDriver.Format FORMAT=new UvcDriver.Format(640,360,"RGBA",5,1);
    private static final FramePipeline.Mask MASK=new FramePipeline.Mask(10,10,40,30);
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void await(BooleanSupplier test,String message)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!test.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(1);
        check(test.getAsBoolean(),message);
    }
    static byte[] pixels() {
        byte[] pixels=new byte[FramePipeline.FRAME_BYTES];
        Arrays.fill(pixels,(byte)0x55);
        for(int i=3;i<pixels.length;i+=4)pixels[i]=(byte)255;
        return pixels;
    }
    static UvcDriver.Frame frame(long seq,byte[] p){return new UvcDriver.Frame(FORMAT,"boot",seq,seq+1,p);}
    private static CaptureGate.Lease lease(CaptureGate gate){return gate.issue("synthetic-session","attach-1",1);}
    private static boolean zero(byte[] p){for(byte b:p)if(b!=0)return false;return true;}
    private static String hash(byte[] p)throws Exception {
        StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(p))result.append(String.format("%02x",b&255));return result.toString();
    }
    private static void finish(FramePipeline p)throws Exception {
        p.close();check(p.awaitWorkerExit(5000),"worker drained");check(p.metrics().ownedBytes==0,"all owned pixels released");
        peakBytes=Math.max(peakBytes,p.metrics().peakOwnedBytes);
        check(p.metrics().peakOwnedBytes<=FramePipeline.OWNED_BYTE_LIMIT,"owned media budget respected");
    }
    public static void main(String[] args)throws Exception {
        maskedCommit(); pausedAt("before_mask");pausedAt("before_publish");overflow();closeAtPublish();
        staleLease();failure();invalidFormat();gateLinearization();boundedAudit();
        System.out.println("PASS FramePipelineChecks: "+scenarios+" scenarios, "+checks+" assertions");
        System.out.println("Owned pixel peak bytes="+peakBytes+"; blocked-worker stop admission observation ns="+stopObservationNs+" (JVM fixture only)");
    }
    private static void maskedCommit()throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=lease(gate);
        FramePipeline p=new FramePipeline(gate,MASK,(l,r)->{throw new AssertionError(r);});
        byte[] raw=pixels();String rawHash=hash(raw);p.accept(lease,frame(1,raw));
        await(()->p.metrics().published==1,"masked frame committed");
        check(p.previewPixel(lease,10,10)==255,"mask top-left opaque black");
        check(p.previewPixel(lease,39,29)==255,"mask bottom-right opaque black");
        check(p.previewPixel(lease,9,10)==0x555555ff,"outside mask preserved");
        check(!rawHash.equals(p.previewHash(lease)),"raw image was not published");
        byte[] expected=pixels();for(int y=10;y<30;y++)for(int x=10;x<40;x++){int i=(y*640+x)*4;expected[i]=expected[i+1]=expected[i+2]=0;}
        check(hash(expected).equals(p.previewHash(lease)),"complete redacted-frame hash matches independent expected image");Arrays.fill(expected,(byte)0);
        List<FramePipeline.Record> audit=p.drainAudit();check(audit.size()==1&&audit.get(0).kind.equals("commit"),"one metadata commit");
        p.cancel(lease);check(p.previewPixel(lease,9,10)==null,"cancel hides preview");
        check(p.metrics().dropped==0,"clearing committed preview is not a capture gap");check(zero(raw),"transferred buffer cleared");finish(p);
    }
    private static void pausedAt(String stage)throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=lease(gate);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        FramePipeline p=new FramePipeline(gate,MASK,(l,r)->{},(s,n)->{if(s.equals(stage)){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}});
        byte[] raw=pixels();p.accept(lease,frame(1,raw));check(entered.await(5,TimeUnit.SECONDS),"worker at "+stage);
        long before=System.nanoTime();p.cancel(lease);stopObservationNs=Math.max(stopObservationNs,System.nanoTime()-before);
        check(!gate.isCurrent(lease),"pause before blocked worker release");
        check(p.previewPixel(lease,10,10)==null&&p.metrics().published==0,"no premature publish");
        release.countDown();await(()->p.metrics().ownedBytes==0,"in-flight pixels eventually released");
        check(p.metrics().published==0&&zero(raw),"no late publication");
        check(p.drainAudit().stream().anyMatch(r->r.kind.equals("gap")&&r.sequence==1),"discarded sequence explained");finish(p);
    }
    private static void overflow()throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=lease(gate);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger interruptions=new AtomicInteger();
        FramePipeline p=new FramePipeline(gate,MASK,(l,r)->interruptions.incrementAndGet(),(s,n)->{if(s.equals("before_mask")&&n==1){entered.countDown();release.await(5,TimeUnit.SECONDS);}});
        p.accept(lease,frame(1,pixels()));check(entered.await(5,TimeUnit.SECONDS),"worker blocked before overflow");
        p.accept(lease,frame(2,pixels()));p.accept(lease,frame(3,pixels()));check(p.metrics().queued==2,"bounded queue full");
        byte[] rejected=pixels();p.accept(lease,frame(4,rejected));
        check(!gate.isCurrent(lease)&&interruptions.get()==1,"overflow interrupts once");
        check(p.metrics().queued==0&&zero(rejected),"overflow clears queued and rejected buffers");
        release.countDown();await(()->p.metrics().ownedBytes==0,"overflow worker drained");
        check(p.metrics().published==0&&p.metrics().overflows==1,"overflow does not silently overwrite");
        List<FramePipeline.Record> audit=p.drainAudit();
        for(long seq=1;seq<=4;seq++){final long wanted=seq;check(audit.stream().anyMatch(r->r.kind.equals("gap")&&r.sequence==wanted),"gap sequence "+seq);}
        finish(p);
    }
    private static void closeAtPublish()throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=lease(gate);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        FramePipeline p=new FramePipeline(gate,MASK,(l,r)->{},(s,n)->{if(s.equals("before_publish")){entered.countDown();release.await(5,TimeUnit.SECONDS);}});
        byte[] raw=pixels();p.accept(lease,frame(1,raw));check(entered.await(5,TimeUnit.SECONDS),"close races publish");
        p.close();check(!gate.isCurrent(lease),"close invalidates admission before return");
        CaptureGate.Lease newer=gate.issue("new","new attachment",2);
        byte[] late=pixels();p.accept(newer,frame(2,late));check(zero(late),"closed pipeline rejects newer lease");
        release.countDown();check(p.awaitWorkerExit(5000),"close drains blocked publication");
        check(p.metrics().published==0&&zero(raw),"close cannot resurrect preview");finish(p);
    }
    private static void staleLease()throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease old=lease(gate);gate.invalidate(old);
        CaptureGate.Lease current=gate.issue("synthetic-session","attach-2",2);
        FramePipeline p=new FramePipeline(gate,MASK,(l,r)->{});
        byte[] stale=pixels();p.accept(old,frame(1,stale));check(zero(stale),"old run pixels discarded");
        p.cancel(old);check(gate.isCurrent(current),"stale cancel cannot stop new run");
        p.accept(current,frame(2,pixels()));await(()->p.metrics().published==1,"new run publishes");
        check(p.previewHash(old)==null&&p.previewHash(current)!=null,"preview lease checked");finish(p);
    }
    private static void failure()throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=lease(gate);
        FramePipeline p=new FramePipeline(gate,MASK,(l,r)->{throw new IllegalStateException("notification failure");},(s,n)->{throw new IllegalStateException("processing fault");});
        byte[] raw=pixels();p.accept(lease,frame(1,raw));await(()->p.metrics().ownedBytes==0,"processing fault cleanup");
        check(!gate.isCurrent(lease)&&p.metrics().notificationFailures==1,"notification failure cannot undo barrier");
        check(p.metrics().published==0&&zero(raw),"failed frame excluded");finish(p);
    }
    private static void invalidFormat()throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=lease(gate);AtomicInteger faults=new AtomicInteger();
        FramePipeline p=new FramePipeline(gate,MASK,(l,r)->faults.incrementAndGet());byte[] small={1,2,3,4};
        p.accept(lease,new UvcDriver.Frame(new UvcDriver.Format(1,1,"RGBA",5,1),"boot",1,2,small));
        check(!gate.isCurrent(lease)&&faults.get()==1&&zero(small),"format failure invalidates and clears");finish(p);
    }
    private static void gateLinearization()throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=lease(gate);AtomicInteger publications=new AtomicInteger();
        check(gate.publish(lease,publications::incrementAndGet),"publish before barrier");
        check(gate.invalidate(lease),"barrier linearized");
        check(!gate.publish(lease,publications::incrementAndGet)&&publications.get()==1,"publish after barrier rejected");
        CaptureGate other=new CaptureGate();CaptureGate.Lease unrelated=lease(other);
        check(!gate.publish(unrelated,publications::incrementAndGet),"foreign gate lease rejected");
    }
    private static void boundedAudit()throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=lease(gate);gate.invalidate(lease);
        FramePipeline p=new FramePipeline(gate,MASK,(l,r)->{});
        for(int i=0;i<130;i++)p.accept(lease,new UvcDriver.Frame(FORMAT,"boot",i,i+1,new byte[0]));
        check(p.drainAudit().size()==128&&p.metrics().auditEvicted==2,"audit truncation explicit and bounded");finish(p);
    }
}

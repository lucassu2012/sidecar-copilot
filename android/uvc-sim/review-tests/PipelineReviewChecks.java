package local.sidecar.copilot.uvcsim;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.security.MessageDigest;

/** Independent merge-review regression: legal concurrent driver callbacks can reach the sink out of order. */
public final class PipelineReviewChecks {
    private static int assertions,scenarios;
    private static final UvcDriver.Format FORMAT=new UvcDriver.Format(640,360,"RGBA",5,1);
    private static final class Driver implements UvcDriver {
        private final Handle handle=new Handle(){};
        private volatile Callback callback;
        @Override public Handle open(String attachment,Format requested){return handle;}
        @Override public Format negotiatedFormat(Handle h){return FORMAT;}
        @Override public void start(Handle h,Callback cb){callback=cb;}
        @Override public void stop(Handle h){}
        @Override public void close(Handle h){}
        void emit(Frame f){callback.onFrame(handle,f);}
    }
    private static byte[] pixels(int value) {
        byte[] p=new byte[FramePipeline.FRAME_BYTES];Arrays.fill(p,(byte)value);
        for(int i=3;i<p.length;i+=4)p[i]=(byte)255;return p;
    }
    private static void require(boolean result,String message){assertions++;if(!result)throw new AssertionError(message);}
    private static void await(BooleanSupplier test,String message)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!test.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(1);
        require(test.getAsBoolean(),message);
    }
    private static boolean zero(byte[] pixels){for(byte b:pixels)if(b!=0)return false;return true;}
    private static UvcDriver.Frame frame(long sequence,byte[] pixels){return new UvcDriver.Frame(FORMAT,"boot",sequence,100+sequence,pixels);}
    private static void finish(FramePipeline p)throws Exception {
        p.close();require(p.awaitWorkerExit(5000),"frame worker exits");
        await(()->p.metrics().ownedBytes==0,"eventually no owned buffers");
        require(p.metrics().peakOwnedBytes<=FramePipeline.OWNED_BYTE_LIMIT,"owned-pixel cap respected");
    }
    private static String hash(byte[] pixels)throws Exception {
        StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(pixels))s.append(String.format("%02x",b&255));return s.toString();
    }
    private static void awaitPublications(FramePipeline p,long count)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(p.metrics().published<count&&System.nanoTime()<deadline)Thread.sleep(1);
        require(p.metrics().published==count,"expected publication count "+count);
    }
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("--safety")) {
            maskingAndOwnership();
            crossGenerationAt("before_mask");crossGenerationAt("before_publish");
            closeWithPreviewQueueAndFlight();processingAndNotificationFailure();
            System.out.println("PASS PipelineReviewChecks safety: "+scenarios+" scenarios, "+assertions+" assertions");
            return;
        }
        orderRegression();
    }
    private static void maskingAndOwnership()throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=gate.issue("review","attachment",1);
        FramePipeline.Mask mask=new FramePipeline.Mask(0,0,640,1);
        FramePipeline p=new FramePipeline(gate,mask,(l,r)->{throw new AssertionError(r);});
        byte[] first=pixels(0x23),second=pixels(0x45),expected=pixels(0x23);
        try {
            for(int x=0;x<640;x++){int i=x*4;expected[i]=expected[i+1]=expected[i+2]=0;expected[i+3]=(byte)255;}
            p.accept(lease,frame(1,first));awaitPublications(p,1);
            require(hash(expected).equals(p.previewHash(lease)),"whole-frame hash equals independently redacted boundary image");
            require(p.previewPixel(lease,0,0)==255&&p.previewPixel(lease,639,0)==255,"mask includes both horizontal edges");
            require(p.previewPixel(lease,0,1)==0x232323ff&&p.previewPixel(lease,639,359)==0x232323ff,"pixels beyond exclusive bottom preserved");
            require(p.metrics().ownedBytes==FramePipeline.FRAME_BYTES&&p.metrics().released==0,"published preview owns one retained frame");
            p.accept(lease,frame(2,second));awaitPublications(p,2);
            await(()->p.metrics().released==1,"replaced preview released");
            require(zero(first)&&!zero(second),"replacement clears only prior buffer");
            require(p.previewPixel(lease,0,1)==0x454545ff,"replacement visible with new color");
            gate.invalidate(lease);
            require(p.previewHash(lease)==null&&p.previewPixel(lease,0,1)==null,"revocation hides retained preview before asynchronous cancel");
            p.cancel(lease);p.cancel(lease);
            require(zero(second)&&p.metrics().released==2&&p.metrics().ownedBytes==0,"repeat cancellation clears exactly two owned buffers");
            List<FramePipeline.Record> records=p.drainAudit();
            require(records.size()==2&&records.stream().allMatch(r->r.kind.equals("commit")&&r.sha256!=null),"only redacted commit hashes audited");
        } finally {Arrays.fill(expected,(byte)0);finish(p);}
    }
    private static void crossGenerationAt(String stage)throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease old=gate.issue("review","attachment",1);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger failures=new AtomicInteger();
        FramePipeline p=new FramePipeline(gate,new FramePipeline.Mask(10,10,20,20),(l,r)->failures.incrementAndGet(),(s,n)->{
            if(s.equals(stage)&&n==2){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}
        });
        byte[] preview=pixels(0x11),inFlight=pixels(0x22),queued=pixels(0x33),newFrame=pixels(0x44),late=pixels(0x55);
        try {
            p.accept(old,frame(1,preview));awaitPublications(p,1);
            p.accept(old,frame(2,inFlight));require(entered.await(5,TimeUnit.SECONDS),"old frame blocked at "+stage);
            p.accept(old,frame(3,queued));require(p.metrics().queued==1,"old queued frame retained before stop");
            require(gate.invalidate(old),"old run synchronously revoked");
            require(p.previewHash(old)==null&&p.previewPixel(old,0,0)==null,"stop hides already published old preview");
            CaptureGate.Lease current=gate.issue("review","attachment",1);
            require(current!=old&&current.generation>old.generation,"same binding creates distinct generation identity");
            p.accept(current,frame(1,newFrame));require(p.metrics().queued==2,"new run queues beside old unpublished frame");
            p.cancel(old);
            require(gate.isCurrent(current)&&p.metrics().queued==1,"stale cancel removes only old queue without revoking new run");
            require(zero(preview)&&zero(queued),"cancel clears prior preview and queued old frame");
            p.accept(old,frame(4,late));require(zero(late),"late old admission clears transferred bytes");
            release.countDown();awaitPublications(p,2);
            await(()->p.metrics().released==3,"all three admitted old buffers released");
            require(zero(inFlight),"blocked old worker clears its buffer after resume");
            require(p.previewHash(old)==null&&p.previewPixel(current,0,0)==0x444444ff,"only fresh-generation preview visible");
            require(p.previewPixel(current,10,10)==255,"new-generation frame masked");
            p.cancel(old);
            require(gate.isCurrent(current)&&p.previewPixel(current,0,0)==0x444444ff,"late repeat cancel preserves new preview");
            require(p.metrics().ownedBytes==FramePipeline.FRAME_BYTES&&p.metrics().accepted==4&&p.metrics().dropped==3,"ownership and drops reconcile across generations");
            List<FramePipeline.Record> records=p.drainAudit();
            require(records.stream().filter(r->r.kind.equals("commit")&&r.generation==old.generation).count()==1,"old in-flight frame never commits after barrier");
            require(records.stream().filter(r->r.kind.equals("gap")&&r.generation==old.generation).count()==3,"all old cancelled/rejected frames have gap metadata");
            require(failures.get()==0,"normal stop/new generation produces no failure notification");
        } finally {release.countDown();finish(p);}
        require(zero(newFrame)&&p.metrics().released==4,"final close clears final fresh-generation owner exactly once");
    }
    private static void closeWithPreviewQueueAndFlight()throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=gate.issue("review","attachment",1);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        FramePipeline p=new FramePipeline(gate,new FramePipeline.Mask(0,0,640,360),(l,r)->{},(s,n)->{
            if(s.equals("before_publish")&&n==2){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}
        });
        byte[] preview=pixels(0x11),inFlight=pixels(0x22),queued=pixels(0x33),afterClose=pixels(0x44);
        try {
            p.accept(lease,frame(1,preview));awaitPublications(p,1);
            require(p.previewPixel(lease,639,359)==255,"full-frame mask covers last pixel");
            p.accept(lease,frame(2,inFlight));require(entered.await(5,TimeUnit.SECONDS),"close race paused after redaction before publish");
            p.accept(lease,frame(3,queued));p.close();
            require(!gate.isCurrent(lease)&&p.previewHash(lease)==null,"close revokes lease and removes preview synchronously");
            require(zero(preview)&&zero(queued)&&p.metrics().queued==0,"close clears retained preview and queue immediately");
            CaptureGate.Lease current=gate.issue("new","attachment",2);p.close();
            require(gate.isCurrent(current),"idempotent close does not revoke subsequently issued lease");
            p.accept(current,frame(1,afterClose));require(zero(afterClose),"closed pipeline zeroes fresh-lease admission");
            release.countDown();require(p.awaitWorkerExit(5000),"closed in-flight worker exits after resume");
            require(zero(inFlight)&&p.metrics().published==1&&p.metrics().released==3,"close prevents paused publication and releases all accepted buffers");
            require(p.drainAudit().stream().filter(r->r.kind.equals("gap")).count()==3,"close/reject gaps retained");
        } finally {release.countDown();finish(p);}
    }
    private static void processingAndNotificationFailure()throws Exception {
        scenarios++;CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=gate.issue("review","attachment",1);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        FramePipeline p=new FramePipeline(gate,new FramePipeline.Mask(0,0,1,1),(l,r)->{throw new IllegalStateException("notification failure");},(s,n)->{
            if(s.equals("before_publish")&&n==2){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");throw new IllegalStateException("processing failure");}
        });
        byte[] preview=pixels(0x11),inFlight=pixels(0x22),queued=pixels(0x33);
        try {
            p.accept(lease,frame(1,preview));awaitPublications(p,1);
            p.accept(lease,frame(2,inFlight));require(entered.await(5,TimeUnit.SECONDS),"failure paused with owned in-flight frame");
            p.accept(lease,frame(3,queued));release.countDown();await(()->p.metrics().ownedBytes==0&&p.metrics().released==3,"processing failure clears preview queue and in-flight frame");
            require(zero(preview)&&zero(inFlight)&&zero(queued),"every transferred fault-path buffer zeroed");
            require(!gate.isCurrent(lease)&&p.previewHash(lease)==null,"notification exception cannot reopen publication");
            require(p.metrics().notificationFailures==1&&p.metrics().published==1&&p.metrics().released==3,"fault counters and exact release reconcile");
            require(p.drainAudit().stream().filter(r->r.kind.equals("gap")).count()==2,"faulted in-flight and cancelled queue gaps recorded");
        } finally {release.countDown();finish(p);}
    }
    private static void orderRegression()throws Exception {
        CaptureGate gate=new CaptureGate();AtomicLong clock=new AtomicLong(100);Driver driver=new Driver();
        CountDownLatch firstInSink=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
        AtomicReference<Throwable> error=new AtomicReference<>();AtomicReference<UvcCoordinator> owner=new AtomicReference<>();
        FramePipeline p=new FramePipeline(gate,new FramePipeline.Mask(10,10,20,20),(l,r)->owner.get().sourceInterrupted(l,r));
        UvcCoordinator.FrameSink delayed=new UvcCoordinator.FrameSink() {
            @Override public void accept(CaptureGate.Lease lease,UvcDriver.Frame frame) {
                if(frame.sequence==1) {
                    firstInSink.countDown();
                    try{if(!releaseFirst.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                }
                p.accept(lease,frame);
            }
            @Override public void cancel(CaptureGate.Lease lease){p.cancel(lease);}
        };
        UvcCoordinator c=new UvcCoordinator(driver,gate,clock::get,delayed,"boot");owner.set(c);
        Thread first=null;
        try {
        UvcCoordinator.Selection selected=c.select("session","attachment",1,FORMAT);
        require(c.permissionResult(selected,1,true)&&c.open(selected,true),"open");
        require(c.awaitWorkerIdle(5000),"open idle");
        require(c.confirmDisplay(selected,FORMAT)&&c.start(selected,true,true),"start");
        require(c.awaitWorkerIdle(5000),"start idle");CaptureGate.Lease lease=c.snapshot().lease;
        clock.set(500);
        first=new Thread(()->{try{driver.emit(new UvcDriver.Frame(FORMAT,"boot",1,200,pixels(0x11)));}
            catch(Throwable failure){error.set(failure);}},"review-delayed-frame-1");
        first.start();require(firstInSink.await(5,TimeUnit.SECONDS),"first frame admitted before sink delay");
        driver.emit(new UvcDriver.Frame(FORMAT,"boot",2,300,pixels(0x22)));
        awaitPublications(p,1);require(p.previewPixel(lease,0,0)==0x222222ff,"newer frame initially visible");
        releaseFirst.countDown();first.join(5000);require(!first.isAlive()&&error.get()==null,"late older callback completed");
        await(()->p.metrics().published+p.metrics().dropped>=2,"both delivered frames processed or explicitly dropped");
        int actual=p.previewPixel(lease,0,0);List<FramePipeline.Record> audit=p.drainAudit();
        System.out.println("coordinator last accepted sequence="+c.snapshot().lastAcceptedSequence);
        System.out.println("publication sequences="+audit.stream().filter(r->r.kind.equals("commit")).map(r->Long.toString(r.sequence)).collect(java.util.stream.Collectors.joining(",")));
        System.out.printf("preview pixel=%08x; newest frame expected=%08x%n",actual,0x222222ff);
        require(actual==0x222222ff,"late sequence 1 overwrote already published sequence 2");
        } finally {
        releaseFirst.countDown();if(first!=null){first.join(5000);require(!first.isAlive(),"callback thread exits");}
        c.close();require(c.awaitWorkerIdle(5000),"coordinator cleanup");p.close();require(p.awaitWorkerExit(5000),"pipeline cleanup");
        long cleanupDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(p.metrics().ownedBytes!=0&&System.nanoTime()<cleanupDeadline)Thread.sleep(1);
        require(p.metrics().ownedBytes==0,"all review buffers eventually released (including async sink cancellation)");
        }
    }
}

package local.sidecar.copilot.uvcsim;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** All bytes are generated in process. No Android/USB/native library is invoked. */
public final class IntegrationChecks {
    private static int checks;
    private static final UvcDriver.Format FORMAT=new UvcDriver.Format(640,360,"RGBA",5,1);
    private static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
    private static void await(BooleanSupplier b,String m)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!b.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(1);
        check(b.getAsBoolean(),m);
    }
    private static UvcCoordinator.Selection start(UvcCoordinator c,String attachment,long rev)throws Exception {
        UvcCoordinator.Selection s=c.select("fixture-session",attachment,rev,FORMAT);
        check(!c.open(s,false),"no implicit open");
        check(c.permissionResult(s,rev,true)&&c.open(s,true),"selected source opens with current permission");
        check(c.awaitWorkerIdle(5000),"open completes");
        check(c.snapshot().state==UvcCoordinator.State.READY,"connection is not recording");
        check(!c.start(s,true,false),"shared gates required");
        check(c.confirmDisplay(s,FORMAT)&&c.start(s,true,true),"explicit confirmed start");
        check(c.awaitWorkerIdle(5000)&&c.snapshot().state==UvcCoordinator.State.RECORDING,"start acknowledged");
        return s;
    }
    private static UvcDriver.Frame frame(long seq,long now){return new UvcDriver.Frame(FORMAT,"boot",seq,now,FramePipelineChecks.pixels());}
    private static boolean zero(byte[] p){for(byte b:p)if(b!=0)return false;return true;}
    public static void main(String[] args)throws Exception {
        fullFlow(); overflowStopsCoordinator(); shutdownWithBufferedFrames();
        System.out.println("PASS IntegrationChecks: 3 end-to-end synthetic scenarios, "+checks+" assertions");
    }
    private static void fullFlow()throws Exception {
        CaptureGate gate=new CaptureGate();AtomicLong clock=new AtomicLong(100);SyntheticUvcDriver driver=new SyntheticUvcDriver();driver.actualFormat=FORMAT;
        AtomicReference<UvcCoordinator> ref=new AtomicReference<>();
        FramePipeline pipeline=new FramePipeline(gate,new FramePipeline.Mask(10,10,40,30),(l,r)->ref.get().sourceInterrupted(l,r));
        UvcCoordinator c=new UvcCoordinator(driver,gate,clock::get,pipeline,"boot");ref.set(c);
        UvcCoordinator.Selection first=start(c,"attach-1",1);CaptureGate.Lease old=c.snapshot().lease;
        SyntheticUvcDriver.SyntheticHandle h=driver.lastHandle();
        clock.set(200);driver.emitGenerated(h,()->frame(1,200));await(()->pipeline.metrics().published==1,"masked frame reaches commit");
        check(pipeline.previewPixel(old,10,10)==255,"preview redacted");
        UvcCoordinator.StopReceipt pause=c.end(first,"pause",UvcCoordinator.End.PAUSE,99);
        check(!gate.isCurrent(old)&&pipeline.previewHash(old)==null,"old requested time cannot block stop barrier");
        check(c.awaitWorkerIdle(5000)&&pause.confirmed(),"driver drained before pause confirmation");
        check(h.stopCalls.get()==1&&h.closeCalls.get()==1,"one stop and release");
        check(c.end(first,"pause",UvcCoordinator.End.PAUSE,99)==pause,"repeat control returns receipt");
        clock.set(300);UvcCoordinator.Selection second=start(c,"attach-2",2);CaptureGate.Lease newer=c.snapshot().lease;
        UvcDriver.Frame stale=frame(2,300);driver.emit(h,stale);
        check(zero(stale.pixels)&&gate.isCurrent(newer),"late old callback clears pixels and leaves new run active");
        clock.set(400);driver.emitGenerated(driver.lastHandle(),()->frame(1,400));await(()->pipeline.metrics().published==2,"new explicit run commits");
        clock.set(500);byte[] wrong=FramePipelineChecks.pixels();
        driver.emit(driver.lastHandle(),new UvcDriver.Frame(new UvcDriver.Format(640,360,"RGBA",30,1),"boot",2,500,wrong));
        check(c.awaitWorkerIdle(5000)&&c.snapshot().interruption==UvcCoordinator.End.FORMAT_CHANGED,"format change closes source");
        check(zero(wrong)&&pipeline.metrics().published==2,"changed-format frame excluded");
        c.close();pipeline.close();check(pipeline.awaitWorkerExit(5000)&&pipeline.metrics().ownedBytes==0,"full flow releases all pixels");
    }
    private static void overflowStopsCoordinator()throws Exception {
        CaptureGate gate=new CaptureGate();AtomicLong clock=new AtomicLong(100);SyntheticUvcDriver driver=new SyntheticUvcDriver();driver.actualFormat=FORMAT;
        AtomicReference<UvcCoordinator> ref=new AtomicReference<>();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        FramePipeline pipeline=new FramePipeline(gate,new FramePipeline.Mask(0,0,1,1),(l,r)->ref.get().sourceInterrupted(l,r),
            (stage,seq)->{if(stage.equals("before_mask")&&seq==1){entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("fixture timeout");}});
        UvcCoordinator c=new UvcCoordinator(driver,gate,clock::get,pipeline,"boot");ref.set(c);start(c,"attach",1);
        clock.set(200);driver.emitGenerated(driver.lastHandle(),()->frame(1,200));check(entered.await(5,TimeUnit.SECONDS),"blocked processing stage");
        for(int i=2;i<=4;i++){final int seq=i;clock.set(200+i);driver.emitGenerated(driver.lastHandle(),()->frame(seq,200+seq));}
        check(c.awaitWorkerIdle(5000),"driver stop does not wait for processing");
        check(c.snapshot().state==UvcCoordinator.State.PROTECTIVE_PAUSE,"overflow is visible protective pause");
        check(driver.lastHandle().closeCalls.get()==1&&pipeline.metrics().published==0,"device released without late commits");
        release.countDown();await(()->pipeline.metrics().ownedBytes==0,"all overflow buffers cleared");
        check(pipeline.drainAudit().stream().filter(r->r.kind.equals("gap")).count()==4,"four discarded frames have gap evidence");
        c.close();pipeline.close();check(pipeline.awaitWorkerExit(5000),"overflow fixture shutdown");
    }
    private static void shutdownWithBufferedFrames()throws Exception {
        CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=gate.issue("fixture","attachment",1);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        FramePipeline pipeline=new FramePipeline(gate,new FramePipeline.Mask(0,0,1,1),(l,r)->{},
            (stage,seq)->{if(stage.equals("before_mask")){entered.countDown();release.await(5,TimeUnit.SECONDS);}});
        pipeline.accept(lease,frame(1,100));check(entered.await(5,TimeUnit.SECONDS),"shutdown fixture blocked");
        pipeline.accept(lease,frame(2,200));pipeline.close();release.countDown();
        check(pipeline.awaitWorkerExit(5000)&&pipeline.metrics().ownedBytes==0,"shutdown clears queued and in-flight frames");
        check(pipeline.drainAudit().stream().filter(r->r.kind.equals("gap")).count()==2,"shutdown gap evidence complete");
        CaptureGate.Lease newer=gate.issue("new","new",2);pipeline.close();check(gate.isCurrent(newer),"repeat close cannot revoke unrelated new lease");
    }
}

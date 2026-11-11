import local.sidecar.copilot.uvcsim.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Independent merge-review reproduction. No real input or native library. */
public final class CoordinatorReviewChecks {
    private static final UvcDriver.Format FORMAT=new UvcDriver.Format(640,360,"RGBA",5,1);
    private static final class ConcurrentDriver implements UvcDriver {
        final Handle handle=new Handle(){};
        volatile Callback callback;
        public Handle open(String attachment,Format requested){return handle;}
        public Format negotiatedFormat(Handle h){return FORMAT;}
        public void start(Handle h,Callback callback){this.callback=callback;}
        public void stop(Handle h){}
        public void close(Handle h){}
        void emit(Frame frame){callback.onFrame(handle,frame);}
    }
    private static UvcDriver.Frame frame(long sequence) {
        byte[] pixels=new byte[640*360*4];Arrays.fill(pixels,(byte)(sequence*17));
        for(int i=3;i<pixels.length;i+=4)pixels[i]=(byte)255;
        return new UvcDriver.Frame(FORMAT,"boot",sequence,100+sequence,pixels);
    }
    private static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
    private static void waitFor(BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(1);
        require(condition.getAsBoolean(),"fixture timed out");
    }
    public static void main(String[] args)throws Exception {
        boolean observeOnly=args.length>0&&"--observe".equals(args[0]);
        concurrentFrameOrder(observeOnly);
    }
    private static void concurrentFrameOrder(boolean observeOnly)throws Exception {
        CaptureGate gate=new CaptureGate();ConcurrentDriver driver=new ConcurrentDriver();AtomicLong now=new AtomicLong(100);
        CountDownLatch firstEntered=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        FramePipeline pipeline=new FramePipeline(gate,new FramePipeline.Mask(0,0,1,1),(lease,reason)->{});
        UvcCoordinator.FrameSink reorderedSink=new UvcCoordinator.FrameSink(){
            public void accept(CaptureGate.Lease lease,UvcDriver.Frame frame) {
                if(frame.sequence==1){firstEntered.countDown();
                    try{if(!releaseFirst.await(5,TimeUnit.SECONDS))throw new IllegalStateException("latch timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
                pipeline.accept(lease,frame);
            }
            public void cancel(CaptureGate.Lease lease){pipeline.cancel(lease);}
        };
        UvcCoordinator coordinator=new UvcCoordinator(driver,gate,now::get,reorderedSink,"boot");
        Thread first=null,second=null;
        try {
            UvcCoordinator.Selection s=coordinator.select("session","attachment",1,FORMAT);
            require(coordinator.permissionResult(s,1,true)&&coordinator.open(s,true),"open");
            require(coordinator.awaitWorkerIdle(2000),"open drain");
            require(coordinator.confirmDisplay(s,FORMAT)&&coordinator.start(s,true,true),"start");
            require(coordinator.awaitWorkerIdle(2000),"start drain");
            CaptureGate.Lease lease=coordinator.snapshot().lease;now.set(200);
            first=new Thread(()->{try{driver.emit(frame(1));}catch(Throwable t){failure.set(t);}});
            first.start();require(firstEntered.await(2000,TimeUnit.MILLISECONDS),"first admitted into sink");
            second=new Thread(()->{try{driver.emit(frame(2));}catch(Throwable t){failure.set(t);}});
            second.start();
            // Baseline allows second publication while the already-admitted first sink call is blocked.
            // Fixed implementations may serialize delivery or reject the older publication instead.
            long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(300);
            while(pipeline.metrics().published==0&&System.nanoTime()<deadline)Thread.sleep(1);
            releaseFirst.countDown();first.join(2000);second.join(2000);
            require(!first.isAlive()&&!second.isAlive()&&failure.get()==null,"callback completion");
            waitFor(()->pipeline.metrics().published+pipeline.metrics().dropped>=2);
            List<Long> sequences=new ArrayList<>();
            for(FramePipeline.Record r:pipeline.drainAudit())if(r.kind.equals("commit"))sequences.add(r.sequence);
            Integer preview=pipeline.previewPixel(lease,5,5);
            boolean monotonic=true;for(int i=1;i<sequences.size();i++)if(sequences.get(i)<=sequences.get(i-1))monotonic=false;
            System.out.println("commit sequences="+sequences+", previewPixel="+Integer.toHexString(preview==null?0:preview)
                    +", coordinator lastAcceptedSequence="+coordinator.snapshot().lastAcceptedSequence);
            if(!observeOnly){
                require(monotonic,"concurrent admitted frames published out of order");
                require(preview!=null&&preview==0x222222ff,"older frame overwrote latest preview");
            }
        } finally {
            releaseFirst.countDown();if(first!=null)first.join(2000);if(second!=null)second.join(2000);
            coordinator.close();require(coordinator.awaitWorkerIdle(2000),"coordinator cleanup");
            pipeline.close();require(pipeline.awaitWorkerExit(2000),"pipeline cleanup");
            // Coordinator sink cancellation runs independently of the driver and media workers.
            waitFor(()->pipeline.metrics().ownedBytes==0);
            require(pipeline.metrics().ownedBytes==0,"buffer release");
        }
    }
}

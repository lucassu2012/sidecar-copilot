package local.sidecar.copilot.uvcsim;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Research-only byte-copy experiment. No USB, JNI, libyuv or physical device access. */
public final class PixelBridgeExperiment {
    private static int checks;
    private static boolean injectWorkerFailure;
    private static final AtomicReference<Throwable> ASYNC_ERROR=new AtomicReference<>();
    private static final UvcDriver.Format FIXTURE = new UvcDriver.Format(640,360,"RGBA",30,1);

    private static void check(boolean condition,String message) {
        checks++; if(!condition) throw new AssertionError(message);
    }
    private static void await(BooleanSupplier condition,String description)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(1);
        check(condition.getAsBoolean(),description);
    }
    private static void waitFor(CountDownLatch latch)throws Exception {
        if(!latch.await(3,TimeUnit.SECONDS))throw new AssertionError("timed out waiting for synthetic hook");
    }
    private static void holdWorker(CountDownLatch latch)throws Exception {
        try {waitFor(latch);} catch(Throwable failure) {
            ASYNC_ERROR.compareAndSet(null,failure);
            throw new RuntimeException("synthetic worker hook failed",failure);
        }
    }
    private static void unexpectedFailure(CaptureGate.Lease lease,String reason) {
        ASYNC_ERROR.compareAndSet(null,new AssertionError("unexpected pipeline failure: "+reason));
    }
    private static boolean zero(byte[] value) {
        for(byte b:value)if(b!=0)return false;
        return true;
    }
    /** Chosen fixture contract: byte RGBX, no resize, preserve RGB, set opaque alpha, own copy. */
    private static byte[] copyOpaqueRgbx(byte[] borrowed,int width,int height,int stride) {
        if(width!=FramePipeline.WIDTH || height!=FramePipeline.HEIGHT)
            throw new IllegalArgumentException("only existing fixture dimensions accepted");
        long rowBytes=(long)width*4;
        long required=(long)(height-1)*stride+rowBytes;
        if(stride<rowBytes || required<rowBytes || required>borrowed.length)
            throw new IllegalArgumentException("invalid row layout or capacity");
        byte[] owned=new byte[FramePipeline.FRAME_BYTES];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int src=y*stride+x*4,dst=(y*width+x)*4;
            owned[dst]=borrowed[src];owned[dst+1]=borrowed[src+1];owned[dst+2]=borrowed[src+2];
            owned[dst+3]=(byte)255;
        }
        return owned;
    }
    private static byte[] source(int stride) {
        // Last-row padding is deliberately absent; all prior row padding is sentinel data.
        byte[] bytes=new byte[(FramePipeline.HEIGHT-1)*stride+FramePipeline.WIDTH*4];
        Arrays.fill(bytes,(byte)0xA5);
        for(int y=0;y<FramePipeline.HEIGHT;y++)for(int x=0;x<FramePipeline.WIDTH;x++){
            int i=y*stride+x*4;
            bytes[i]=17;bytes[i+1]=34;bytes[i+2]=51;bytes[i+3]=(byte)((x+y)%256);
        }
        int marker=4*stride+16*4;
        bytes[marker]=(byte)200;bytes[marker+1]=8;bytes[marker+2]=4;
        return bytes;
    }
    private static UvcDriver.Frame frame(long seq,long mono,byte[] data) {
        return new UvcDriver.Frame(FIXTURE,"synthetic-boot",seq,mono,data);
    }
    private static void finish(FramePipeline pipe,byte[]... owned)throws Exception {
        pipe.close();
        check(pipe.awaitWorkerExit(3000),"explicit worker drain after close");
        check(ASYNC_ERROR.get()==null,"no hook or asynchronous pipeline failure");
        check(pipe.metrics().ownedBytes==0,"all owned bytes released after drain");
        for(byte[] bytes:owned)check(zero(bytes),"transferred buffer wiped");
    }
    private static void copyMaskAndSourceReuse()throws Exception {
        CaptureGate gate=new CaptureGate();
        CaptureGate.Lease lease=gate.issue("fixture","synthetic-attachment",1);
        CountDownLatch entered=new CountDownLatch(1),allow=new CountDownLatch(1);
        FramePipeline pipe=new FramePipeline(gate,new FramePipeline.Mask(0,0,1,1),PixelBridgeExperiment::unexpectedFailure,(stage,seq)->{
            if(stage.equals("before_mask")){entered.countDown();holdWorker(allow);}
        });
        byte[] borrowed=source(640*4+16),owned=copyOpaqueRgbx(borrowed,640,360,640*4+16);
        try {
            check(owned.length==FramePipeline.FRAME_BYTES,"padding excluded without resizing");
            pipe.accept(lease,frame(1,100,owned));waitFor(entered);
            Arrays.fill(borrowed,(byte)0); // Mimics callback return and immediate source-pool reuse.
            check(owned[4]==17,"owned array survives borrowed-buffer reuse");
            allow.countDown();await(()->pipe.metrics().published==1,"masked frame published");
            check(pipe.previewPixel(lease,0,0)==0x000000ff,"privacy rectangle opaque black");
            check(pipe.previewPixel(lease,1,0)==0x112233ff,"RGB byte order and alpha are explicit");
            check(pipe.previewPixel(lease,16,4)==0xc80804ff,"one-pixel marker survives copy and mask elsewhere");
            check(pipe.previewPixel(lease,639,359)==0x112233ff,"last row needs no trailing padding");
            check(pipe.metrics().peakOwnedBytes==FramePipeline.FRAME_BYTES,"fixture-owned budget observes transferred frame");
        } finally {allow.countDown();finish(pipe,owned);}
    }
    private static void orderAndStop()throws Exception {
        CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=gate.issue("fixture","synthetic-attachment",2);
        FramePipeline pipe=new FramePipeline(gate,new FramePipeline.Mask(0,0,1,1),PixelBridgeExperiment::unexpectedFailure);
        byte[] newest=copyOpaqueRgbx(source(2560),640,360,2560);
        byte[] older=copyOpaqueRgbx(source(2560),640,360,2560);
        byte[] late=copyOpaqueRgbx(source(2560),640,360,2560);
        try {
            pipe.accept(lease,frame(10,1000,newest));await(()->pipe.metrics().published==1,"newest ready");
            String hash=pipe.previewHash(lease);
            older[4]=99;pipe.accept(lease,frame(9,900,older));
            await(()->pipe.metrics().dropped==1 && pipe.metrics().released==1,"older copied frame rejected and released");
            check(hash.equals(pipe.previewHash(lease)),"older callback cannot replace masked preview");
            check(zero(older),"rejected old frame wiped");
            gate.invalidate(lease);pipe.cancel(lease);
            check(pipe.previewHash(lease)==null,"invalidated preview inaccessible");
            pipe.accept(lease,frame(11,1100,late));
            check(zero(late),"callback after invalidation wiped on admission");
            check(pipe.metrics().published==1,"late callback never published");
        } finally {finish(pipe,newest,older,late);}
    }
    private static void stopDuringPublication()throws Exception {
        CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=gate.issue("fixture","synthetic-attachment",3);
        CountDownLatch entered=new CountDownLatch(1),allow=new CountDownLatch(1);
        FramePipeline pipe=new FramePipeline(gate,new FramePipeline.Mask(0,0,1,1),PixelBridgeExperiment::unexpectedFailure,(stage,seq)->{
            if(stage.equals("before_publish")){
                entered.countDown();holdWorker(allow);
                if(injectWorkerFailure)throw new IllegalStateException("injected research worker failure");
            }
        });
        byte[] owned=copyOpaqueRgbx(source(2560),640,360,2560);
        try {
            pipe.accept(lease,frame(1,100,owned));waitFor(entered);
            gate.invalidate(lease);pipe.cancel(lease);pipe.close();
            check(pipe.previewHash(lease)==null,"stop hides image while worker still held");
            check(!pipe.awaitWorkerExit(5),"close alone is not worker-drain evidence");
            allow.countDown();check(pipe.awaitWorkerExit(3000),"worker drains after release");
            check(pipe.metrics().published==0,"held frame does not publish after stop");
        } finally {allow.countDown();finish(pipe,owned);}
    }
    private static void unsupportedLayoutAndResolution()throws Exception {
        int rejected=0;
        try {copyOpaqueRgbx(new byte[10],640,360,2560);}catch(IllegalArgumentException expected){rejected++;}
        try {copyOpaqueRgbx(new byte[FramePipeline.FRAME_BYTES],640,360,2559);}catch(IllegalArgumentException expected){rejected++;}
        try {copyOpaqueRgbx(new byte[0],1920,1080,7680);}catch(IllegalArgumentException expected){rejected++;}
        check(rejected==3,"bad capacity, short stride and nonfixture resolution refused");
        long fullHd=1920L*1080*4;
        check(fullHd>FramePipeline.OWNED_BYTE_LIMIT,"single Full HD RGBA exceeds fixture-owned limit");
        CaptureGate gate=new CaptureGate();CaptureGate.Lease lease=gate.issue("fixture","synthetic-attachment",4);
        FramePipeline pipe=new FramePipeline(gate,new FramePipeline.Mask(0,0,1,1),(l,r)->{});
        byte[] hd=new byte[(int)fullHd];Arrays.fill(hd,(byte)23);
        try {
            pipe.accept(lease,new UvcDriver.Frame(new UvcDriver.Format(1920,1080,"RGBA",30,1),"synthetic-boot",1,100,hd));
            check(zero(hd),"unsupported full-size frame wiped rather than silently resized");
            check(pipe.metrics().accepted==0 && pipe.metrics().published==0,"Full HD not admitted to fixture");
            check(!gate.isCurrent(lease),"unsupported format invalidates affected lease");
        } finally {finish(pipe,hd);}
    }
    public static void main(String[] args)throws Exception {
        injectWorkerFailure=args.length==1 && "--fault-hook".equals(args[0]);
        copyMaskAndSourceReuse();orderAndStop();stopDuringPublication();unsupportedLayoutAndResolution();
        System.out.println("{\"passed\":true,\"scenarios\":4,\"checks\":"+checks+",\"native_code_executed\":false,\"hardware_tested\":false}");
    }
}

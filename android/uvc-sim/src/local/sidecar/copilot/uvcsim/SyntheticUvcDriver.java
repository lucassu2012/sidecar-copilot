package local.sidecar.copilot.uvcsim;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Controllable fake: generates no real input and deliberately allows queued late callbacks. */
public final class SyntheticUvcDriver implements UvcDriver {
    public static final int MAX_GENERATED_FRAME_BYTES=1024*1024;
    public static final class Block {
        private final CountDownLatch entered=new CountDownLatch(1),released=new CountDownLatch(1);
        private void enter()throws InterruptedException {entered.countDown();released.await();}
        public boolean awaitEntered(long millis)throws InterruptedException{return entered.await(millis,TimeUnit.MILLISECONDS);}
        public void release(){released.countDown();}
    }
    public static final class SyntheticHandle implements Handle {
        public final int id;
        public final AtomicInteger stopCalls=new AtomicInteger(),closeCalls=new AtomicInteger();
        private volatile Callback callback;
        private SyntheticHandle(int id){this.id=id;}
    }
    public final AtomicInteger openCalls=new AtomicInteger(),startCalls=new AtomicInteger();
    public volatile Block blockOpen,blockStart,blockStop,blockClose;
    public volatile boolean failOpen,failStart,failStop,failClose;
    public volatile Format actualFormat;
    private final List<SyntheticHandle> handles=new ArrayList<>();
    @Override public Handle open(String attachment,Format requested)throws Exception {
        openCalls.incrementAndGet();enter(blockOpen);
        if(failOpen)throw new IllegalStateException("synthetic open failure");
        synchronized(handles) {
            SyntheticHandle h=new SyntheticHandle(handles.size()+1);handles.add(h);return h;
        }
    }
    @Override public Format negotiatedFormat(Handle handle){return actualFormat;}
    @Override public void start(Handle handle,Callback callback)throws Exception {
        startCalls.incrementAndGet();((SyntheticHandle)handle).callback=callback;enter(blockStart);
        if(failStart)throw new IllegalStateException("synthetic start failure");
    }
    @Override public void stop(Handle handle)throws Exception {
        ((SyntheticHandle)handle).stopCalls.incrementAndGet();enter(blockStop);
        if(failStop)throw new IllegalStateException("synthetic stop failure");
    }
    @Override public void close(Handle handle)throws Exception {
        ((SyntheticHandle)handle).closeCalls.incrementAndGet();enter(blockClose);
        if(failClose)throw new IllegalStateException("synthetic close outcome unknown");
    }
    public SyntheticHandle lastHandle(){synchronized(handles){return handles.isEmpty()?null:handles.get(handles.size()-1);}}
    /**
     * Serialized callback delivery. The controlled memory fixture uses one producer, creating
     * at most one frame before transferring it here. Caller-allocated concurrent frames are
     * outside that fixture's memory bound; this method does not bound arbitrary caller heaps.
     */
    public synchronized void emit(SyntheticHandle h,Frame frame) {
        Callback callback=h.callback;
        if(callback==null)throw new IllegalStateException("start has not registered callback");
        callback.onFrame(h,frame);
    }
    /**
     * Controlled fixture producer: factory and ownership transfer share one producer lock.
     * A conforming factory allocates only this one frame, at most 1 MiB. Other allocations by
     * arbitrary factories or direct fault-injection emit() calls are outside the fixture budget.
     */
    public synchronized void emitGenerated(SyntheticHandle h,Supplier<Frame> factory) {
        Frame frame=Objects.requireNonNull(factory.get(),"generated frame");
        try {
            if(frame.pixels.length>MAX_GENERATED_FRAME_BYTES)
                throw new IllegalArgumentException("generated frame exceeds 1 MiB fixture limit");
            emit(h,frame);
        } catch(RuntimeException failure) {Arrays.fill(frame.pixels,(byte)0);throw failure;}
    }
    public void fail(SyntheticHandle h,String reason){if(h.callback!=null)h.callback.onFailure(h,reason);}
    private static void enter(Block block)throws InterruptedException{if(block!=null)block.enter();}
}

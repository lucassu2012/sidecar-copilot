package local.sidecar.copilot;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/** Single-consumer PCM handoff. Capacity includes the item owned by the consumer.
 * Stop and discard never acquire a storage/device lock. Only the array owner clears it.
 */
public final class BoundedPcmQueue {
    public interface Sink {
        void accept(short[] pcm,int count,long receivedNs) throws Exception;
        /** Called once before post-close draining, or retried by the sink's finish recovery. */
        default void closing(Closed boundary) throws Exception {}
        void finish(long acceptedEnd,long observedEnd,boolean overflow,Exception failure) throws Exception;
    }
    public static final class Closed {
        public final String eventId,kind;
        public final long requestedNs,acceptedNs,acceptedEnd,observedEnd;
        public final boolean overflow;
        private Closed(String id,String kind,long requested,long accepted,long end,long observed,boolean overflow){
            this.eventId=id;this.kind=kind;this.requestedNs=requested;this.acceptedNs=accepted;
            this.acceptedEnd=end;this.observedEnd=observed;this.overflow=overflow;
        }
    }
    private static final class Admission {
        final long accepted;final Closed closed;
        Admission(long accepted,Closed closed){this.accepted=accepted;this.closed=closed;}
    }
    private static final class Item {
        final short[] pcm;final long received;
        Item(short[] pcm,long received){this.pcm=pcm;this.received=received;}
    }
    private final Object lock=new Object();
    private final ArrayDeque<Item> items=new ArrayDeque<>();
    private final AtomicReference<Admission> admission=new AtomicReference<>(new Admission(0,null));
    private final CountDownLatch done=new CountDownLatch(1),faultNotified=new CountDownLatch(1);
    private final int capacity;
    private final Sink sink;
    private final Runnable failureSignal;
    private final LongSupplier clock;
    private final String eventId=UUID.randomUUID().toString();
    private final Thread consumer,discardWorker;
    private final AtomicBoolean discard=new AtomicBoolean(),discardScheduled=new AtomicBoolean();
    private int outstanding,highWater;
    private long lastReceived;
    private volatile Throwable completionFailure,signalFailure;
    private final AtomicBoolean faultSignalStarted=new AtomicBoolean();

    public BoundedPcmQueue(int capacity,Sink sink,Runnable failureSignal){this(capacity,sink,failureSignal,System::nanoTime);}
    public BoundedPcmQueue(int capacity,Sink sink,Runnable failureSignal,LongSupplier clock){
        if(capacity<=0||sink==null||failureSignal==null||clock==null)throw new IllegalArgumentException("queue parameters");
        this.capacity=capacity;this.sink=sink;this.failureSignal=failureSignal;this.clock=clock;
        // A blocked sink cannot retain queued plaintext after a privacy discard request.
        discardWorker=new Thread(()->{synchronized(lock){clearPending();}},"Sidecar-PCM-discard");
        consumer=new Thread(this::consume,"Sidecar-PCM-storage");consumer.start();
    }
    /** Copies only admitted data. False is not a durability receipt. */
    public boolean offer(short[] pcm,int count,long receivedNs){
        if(pcm==null||count<0||count>pcm.length||receivedNs<0)throw new IllegalArgumentException("PCM input");
        boolean full=false;
        synchronized(lock){
            Admission before=admission.get();if(before.closed!=null||discard.get())return false;
            if(receivedNs<lastReceived)throw new IllegalArgumentException("receive clock moved backwards");
            if(count==0)return true;
            long end=Math.addExact(before.accepted,count);
            if(count>capacity-outstanding){
                long now=clock.getAsLong();
                Closed closed=new Closed(eventId,"pcm_backpressure",now,now,before.accepted,end,true);
                if(!admission.compareAndSet(before,new Admission(before.accepted,closed)))return false;
                full=true;
            }else{
                short[] copy=Arrays.copyOf(pcm,count);
                if(!admission.compareAndSet(before,new Admission(end,null))){Arrays.fill(copy,(short)0);return false;}
                items.addLast(new Item(copy,receivedNs));outstanding+=count;
                highWater=Math.max(highWater,outstanding);lastReceived=receivedNs;
            }
        }
        if(full)notifyFailure();
        LockSupport.unpark(consumer);return !full;
    }
    /** Atomic-only close; a separate memory worker clears pending arrays if discard is requested. */
    public void requestStop(String kind,long requestedNs,boolean discardPending){
        if(kind==null||kind.isEmpty()||requestedNs<0)throw new IllegalArgumentException("stop request");
        if(discardPending)discard.set(true);
        for(;;){
            Admission before=admission.get();if(before.closed!=null)break;
            long acceptedNs=Math.max(requestedNs,clock.getAsLong());
            Closed closed=new Closed(eventId,kind,requestedNs,acceptedNs,before.accepted,before.accepted,false);
            if(admission.compareAndSet(before,new Admission(before.accepted,closed)))break;
        }
        if(discardPending&&discardScheduled.compareAndSet(false,true))discardWorker.start();
        LockSupport.unpark(consumer);
    }
    public void stopAdmission(){requestStop("pause",clock.getAsLong(),false);}
    public boolean isAccepting(){return admission.get().closed==null&&!discard.get();}
    public boolean isDiscarding(){return discard.get();}
    public Closed closedBoundary(){return admission.get().closed;}
    public long acceptedSamples(){return admission.get().accepted;}
    public int highWaterSamples(){synchronized(lock){return highWater;}}
    public int outstandingSamples(){synchronized(lock){return outstanding;}}
    public boolean isFinished(){return done.getCount()==0;}
    public void awaitFinished()throws Exception{
        if(Thread.currentThread()==consumer)throw new IllegalStateException("consumer cannot join itself");
        boolean interrupted=false;
        for(;;)try{done.await();consumer.join();if(discardScheduled.get())discardWorker.join();break;}catch(InterruptedException e){interrupted=true;}
        if(interrupted)Thread.currentThread().interrupt();
        Throwable failure=completionFailure;
        if(failure instanceof Exception)throw (Exception)failure;
        // Callers own source/store cleanup under the existing checked-failure contract.
        if(failure!=null)throw new Exception("PCM queue worker failed",failure);
        if(interrupted)throw new InterruptedException("interrupted while waiting for PCM storage ownership to end");
    }
    private void clearPending(){
        for(Item item:items){Arrays.fill(item.pcm,(short)0);outstanding-=item.pcm.length;}
        items.clear();
    }
    private void notifyFailure(){
        if(!faultSignalStarted.compareAndSet(false,true))return;
        try{failureSignal.run();}catch(Throwable failure){signalFailure=failure;}
        finally{faultNotified.countDown();}
    }
    /** Failure reporting must never replace the original failure or prevent completion publication. */
    private static Throwable mergeFailure(Throwable first,Throwable next){
        if(first==null)return next;
        if(next!=null&&first!=next)try{first.addSuppressed(next);}catch(Throwable ignored){/* Preserve primary failure even if reporting fails. */}
        return first;
    }
    private static Exception sinkFailure(Throwable failure){
        if(failure==null)return null;
        return failure instanceof Exception?(Exception)failure:new Exception("PCM queue worker failed",failure);
    }
    private void consume(){
        Throwable failure=null;boolean closingSent=false,interrupted=false;
        try{
            while(true){
                if(Thread.interrupted())throw new InterruptedException("PCM storage consumer interrupted");
                Item item;Closed closed;
                synchronized(lock){
                    if(discard.get())clearPending();
                    closed=admission.get().closed;
                    item=items.pollFirst();
                }
                if(item==null){
                    if(closed!=null){if(!closingSent){sink.closing(closed);closingSent=true;}break;}
                    LockSupport.park(this);continue;
                }
                try{
                    if(closed!=null&&!closingSent){sink.closing(closed);closingSent=true;}
                    if(!discard.get())sink.accept(item.pcm,item.pcm.length,item.received);
                }
                finally{Arrays.fill(item.pcm,(short)0);synchronized(lock){outstanding-=item.pcm.length;}}
            }
        }catch(Throwable error){
            failure=error;
            if(error instanceof InterruptedException)interrupted=true;
            try{requestStop("write_failure",clock.getAsLong(),true);}
            catch(Throwable stopError){failure=mergeFailure(failure,stopError);}
            notifyFailure();
        }finally{
            try{
                synchronized(lock){clearPending();}
                Closed closed=admission.get().closed;
                if(closed==null){requestStop("write_failure",clock.getAsLong(),true);closed=admission.get().closed;}
                if(Thread.interrupted()){
                    if(!interrupted)failure=mergeFailure(failure,new InterruptedException("PCM storage consumer interrupted"));
                    interrupted=true;
                }
                if(faultSignalStarted.get()||closed.overflow){
                    for(;;)try{faultNotified.await();break;}
                    catch(InterruptedException error){
                        if(!interrupted)failure=mergeFailure(failure,error);
                        interrupted=true;
                    }
                }
                // A failing stop callback is observable by the sink before it decides whether to seal.
                failure=mergeFailure(failure,signalFailure);
                Exception supplied=sinkFailure(failure);
                try{sink.finish(closed.acceptedEnd,closed.observedEnd,closed.overflow,supplied);}
                catch(Throwable finishError){
                    // The Error adapter is not a new failure when the sink rethrows it unchanged.
                    if(finishError!=supplied||failure==null)failure=mergeFailure(failure,finishError);
                    if(finishError instanceof InterruptedException)interrupted=true;
                }
                if(Thread.interrupted()){
                    if(!interrupted)failure=mergeFailure(failure,new InterruptedException("PCM storage finalization interrupted"));
                    interrupted=true;
                }
            }catch(Throwable finalizationError){
                failure=mergeFailure(failure,finalizationError);
                if(finalizationError instanceof InterruptedException)interrupted=true;
            }finally{
                // Even an Error must publish failure and release waiters; awaitFinished still joins owners.
                completionFailure=failure;
                done.countDown();
                if(interrupted)Thread.currentThread().interrupt();
            }
        }
    }
}

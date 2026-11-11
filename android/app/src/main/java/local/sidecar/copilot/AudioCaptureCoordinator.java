package local.sidecar.copilot;

import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One owner for source callbacks, persistent pause barrier and final accepted tail. */
public final class AudioCaptureCoordinator {
    private final AudioSegmentStore store;
    private final String session;
    public final int sampleRate;
    private PcmFrameAssembler assembler;
    // A failed synchronous close must retry the same idempotent stop transaction.
    private CaptureBoundary synchronousPause;
    private long generation,assemblerToken,accepted;
    private volatile boolean active;
    private volatile BoundedPcmQueue queue;
    private final AtomicLong stopSequence=new AtomicLong();
    private final AtomicBoolean discardRequested=new AtomicBoolean();
    private static final class StopRequest {
        final String kind;final long requested;
        StopRequest(String kind,long requested){this.kind=kind;this.requested=requested;}
    }
    private final AtomicReference<StopRequest> stopRequest=new AtomicReference<>();
    private long admissionSequence;
    private volatile Runnable inputStop=()->{};
    // Range deletion stops input without invoking the source-failure/shutdown callback.
    private volatile Runnable privacyInputStop=()->{};
    private final Object rangeControl=new Object();
    private boolean starting,deleting;
    private volatile RunIdentity runIdentity;
    private volatile RangeDeletion pendingDeletion;
    private static final class RunIdentity {
        final String session,stream;final long generation,boot;
        RunIdentity(String session,String stream,long generation,long boot){
            this.session=session;this.stream=stream;this.generation=generation;this.boot=boot;
        }
    }
    private static final class RangeDeletion {
        final RunIdentity run;final long start,end,requestedNs;
        RangeDeletion(RunIdentity run,long start,long end,long requestedNs){
            this.run=run;this.start=start;this.end=end;this.requestedNs=requestedNs;
        }
    }
    private final Runnable stopPublicationProbe;

    public AudioCaptureCoordinator(AudioSegmentStore store,String session,int rate){this(store,session,rate,()->{});}
    // Package-private scheduling seam; production stop has no blocking probe.
    AudioCaptureCoordinator(AudioSegmentStore store,String session,int rate,Runnable stopPublicationProbe){
        if(rate!=store.sampleRate(session))throw new IllegalArgumentException("sample rate differs from session");
        if(stopPublicationProbe==null)throw new IllegalArgumentException("stop publication probe required");
        this.store=store;this.session=session;this.sampleRate=rate;this.stopPublicationProbe=stopPublicationProbe;
    }
    public synchronized long start(boolean consent)throws Exception {
        synchronized(rangeControl){
            if(pendingDeletion!=null)throw new IllegalStateException("range deletion remains incomplete");
            starting=true;
        }
        try{
        if(active||synchronousPause!=null||(queue!=null&&!queue.isFinished()))throw new IllegalStateException("already recording or finalizing");
        queue=null;stopRequest.set(null);discardRequested.set(false);
        admissionSequence=stopSequence.get();
        generation=store.start(session,consent);accepted=0;
        final RunIdentity opened=new RunIdentity(session,store.stream(session),generation,store.sessionBoot(session));
        assembler=new PcmFrameAssembler(sampleRate,5,frame->{
            if(discardPending())throw new SecurityException("pending capture revoked");
            store.append(session,opened.generation,frame);
        });
        assemblerToken=assembler.start(consent);active=true;runIdentity=opened;return generation;
        }finally{synchronized(rangeControl){starting=false;}}
    }
    /** Production sources use this bounded handoff; start() remains a synchronous store test seam. */
    public synchronized long startBuffered(boolean consent)throws Exception {
        final long token=start(consent),boot=runIdentity.boot;
        final String stream=runIdentity.stream;
        queue=new BoundedPcmQueue(Math.multiplyExact(sampleRate,2),new BoundedPcmQueue.Sink(){
            private CaptureBoundary boundary;
            public void closing(BoundedPcmQueue.Closed close)throws Exception {
                synchronized(AudioCaptureCoordinator.this){
                    boundary=new CaptureBoundary(close.eventId,close.kind,stream,token,boot,
                        close.requestedNs,close.acceptedNs,close.acceptedEnd,close.observedEnd);
                    store.beginPause(session,boundary);
                }
            }
            public void accept(short[] pcm,int count,long receiveNs)throws Exception {
                // This old prefix was admitted before stop; only privacy/failure may revoke draining.
                synchronized(AudioCaptureCoordinator.this){
                    if(discardPending())return;
                    if(!active||generation!=token||store.isDeleted(session))throw new SecurityException("capture no longer authorized");
                    accepted+=count;
                    assembler.accept(assemblerToken,pcm,count,receiveNs);
                }
            }
            public void finish(long admittedEnd,long observedEnd,boolean overflow,Exception failure)throws Exception {
                synchronized(AudioCaptureCoordinator.this){
                    active=false;
                    boolean discarded=discardPending()||failure!=null;
                    try{
                        if(discarded)assembler.discard();
                        // Retry the same idempotent transaction if closing failed before commit.
                        if(boundary==null){
                            BoundedPcmQueue.Closed close=queue.closedBoundary();
                            boundary=new CaptureBoundary(close.eventId,close.kind,stream,token,boot,
                                close.requestedNs,close.acceptedNs,admittedEnd,observedEnd);
                        }
                        store.beginPause(session,boundary);
                        try{if(!discarded&&!discardPending()&&!store.isDeleted(session))assembler.pause();}
                        finally{store.finishPause(session);}
                    }finally{assembler.discard();}
                }
            }
        },()->inputStop.run(),SystemClock::elapsedRealtimeNanos);
        if(admissionSequence!=stopSequence.get()){
            StopRequest request=stopRequest.get();
            queue.requestStop(request==null?"pause":request.kind,
                request==null?SystemClock.elapsedRealtimeNanos():request.requested,discardRequested.get());
        }
        return token;
    }
    public boolean samples(long token,short[] pcm,int count,long receiveNs)throws Exception {
        if(pendingDeletion!=null)return false;
        BoundedPcmQueue buffered=queue;
        if(buffered!=null){
            if(token!=generation)return false;
            if(store.isDeleted(session)){requestStop("privacy",SystemClock.elapsedRealtimeNanos(),true);inputStop.run();return false;}
            return buffered.offer(pcm,count,receiveNs);
        }
        return synchronousSamples(token,pcm,count,receiveNs);
    }
    private synchronized boolean synchronousSamples(long token,short[] pcm,int count,long receiveNs)throws Exception {
        if(!active||token!=generation||admissionSequence!=stopSequence.get())return false;
        if(count<0||count>pcm.length)throw new IllegalArgumentException("sample count");
        accepted+=count;
        try{if(store.isDeleted(session))throw new SecurityException("meeting deleted");return assembler.accept(assemblerToken,pcm,count,receiveNs);}
        catch(Exception e){
            active=false;requestStop("write_failure",SystemClock.elapsedRealtimeNanos(),true);
            try{inputStop.run();}catch(RuntimeException stopFailure){e.addSuppressed(stopFailure);}
            assembler.discard();
            try{store.beginPause(session,generation,accepted);store.finishPause(session);}
            catch(Exception recoveryFailure){e.addSuppressed(recoveryFailure);}
            throw e;
        }
    }
    /** Can be called from UI/control paths even while the consumer owns the coordinator/storage monitor. */
    public void requestStop(String kind,long requestedNs,boolean discardPending){
        if(kind==null||kind.isEmpty()||requestedNs<0)throw new IllegalArgumentException("stop request");
        if(discardPending)discardRequested.set(true);
        StopRequest proposed=new StopRequest(kind,requestedNs);
        // Keep the returned object: a concurrent explicit start may clear the shared slot.
        StopRequest first=stopRequest.updateAndGet(existing->existing==null?proposed:existing);
        stopPublicationProbe.run();
        // A concurrent explicit start can reset the per-run shared flag. The caller's revocation survives it.
        if(discardPending)discardRequested.set(true);
        stopSequence.incrementAndGet();
        BoundedPcmQueue buffered=queue;
        if(buffered!=null)buffered.requestStop(first.kind,first.requested,discardPending||discardRequested.get());
    }
    private boolean discardPending(){
        BoundedPcmQueue buffered=queue;
        // start() cannot replace a buffered run until its consumer has finished all these callbacks.
        return discardRequested.get()||(buffered!=null&&buffered.isDiscarding());
    }
    public void stopAdmission(){requestStop("pause",SystemClock.elapsedRealtimeNanos(),false);}
    public void setInputStop(Runnable stopInput){if(stopInput==null)throw new IllegalArgumentException("input stop required");inputStop=stopInput;}
    /** Signal/schedule only: never wait for native stop or call service shutdown on this callback. */
    public void setPrivacyInputStop(Runnable stopInput){if(stopInput==null)throw new IllegalArgumentException("privacy input stop required");privacyInputStop=stopInput;}
    public void pause()throws Exception {
        stopAdmission();
        BoundedPcmQueue buffered=queue;
        if(buffered==null)synchronized(this){
            // startBuffered may have published its queue while this caller awaited the monitor.
            buffered=queue;
            if(buffered==null){
                if(!active&&synchronousPause==null)return;
                if(synchronousPause==null){
                    StopRequest request=stopRequest.get();
                    long requested=request==null?SystemClock.elapsedRealtimeNanos():request.requested;
                    RunIdentity opened=runIdentity;
                    synchronousPause=new CaptureBoundary(java.util.UUID.randomUUID().toString(),request==null?"pause":request.kind,
                        opened.stream,opened.generation,opened.boot,requested,Math.max(requested,SystemClock.elapsedRealtimeNanos()),accepted,accepted);
                }
                active=false;
                try{
                    if(discardPending())assembler.discard();
                    store.beginPause(session,synchronousPause);
                    try{if(!discardPending())assembler.pause();}finally{store.finishPause(session);}
                    synchronousPause=null;
                }finally{assembler.discard();}
                return;
            }
        }
        buffered.awaitFinished(); // No coordinator/storage monitor while waiting for the owner.
    }
    public boolean isActive(){if(pendingDeletion!=null)return false;BoundedPcmQueue buffered=queue;return buffered!=null?buffered.isAccepting():active&&admissionSequence==stopSequence.get();}
    public void end()throws Exception{requestStop("end",SystemClock.elapsedRealtimeNanos(),false);pause();synchronized(this){store.end(session);}}
    /** Keep deletion authority durable even when the queue's eventual pause/finalization fails. */
    public void deleteSamples(long start,long end)throws Exception{
        if(start<0||end<=start)throw new IllegalArgumentException("range");
        final RangeDeletion request;
        synchronized(rangeControl){
            if(deleting)throw new IllegalStateException("range deletion already in progress");
            if(pendingDeletion!=null){
                if(pendingDeletion.start!=start||pendingDeletion.end!=end)
                    throw new IllegalStateException("retry the pending range before deleting another range");
                request=pendingDeletion;
            }else{
                if(starting||runIdentity==null)throw new IllegalStateException("stable capture identity required");
                request=new RangeDeletion(runIdentity,start,end,SystemClock.elapsedRealtimeNanos());
                pendingDeletion=request;
            }
            deleting=true;
        }
        Throwable failure=null;boolean committed=false;
        try{
            // Closing admission is immediate; privacy discard must wait for durable range authority.
            try{requestStop("privacy",request.requestedNs,false);}catch(Exception|Error error){failure=error;}
            try{privacyInputStop.run();}catch(Exception|Error error){failure=mergeDeletionFailure(failure,error);}
            // Never own this coordinator's monitor while waiting for the store or SQLite writer.
            try{
                store.deleteRangeDurably(request.run.session,request.run.stream,request.start,request.end);
                committed=true;
            }catch(Exception|Error error){failure=mergeDeletionFailure(failure,error);}
            if(committed){
                try{requestStop("privacy",request.requestedNs,true);}catch(Exception|Error error){failure=mergeDeletionFailure(failure,error);}
                try{pause();}catch(Exception|Error error){failure=mergeDeletionFailure(failure,error);}
            }
        }finally{
            synchronized(rangeControl){
                // A failed durable write or stop leaves the same exact request retryable and capture closed.
                if(committed&&failure==null&&pendingDeletion==request)pendingDeletion=null;
                deleting=false;
            }
        }
        if(failure instanceof Exception)throw(Exception)failure;
        if(failure instanceof Error)throw(Error)failure;
    }
    private static Throwable mergeDeletionFailure(Throwable first,Throwable next){
        if(first==null)return next;
        if(first!=next)try{first.addSuppressed(next);}catch(Throwable ignored){/* Keep the original failure. */}
        return first;
    }
}

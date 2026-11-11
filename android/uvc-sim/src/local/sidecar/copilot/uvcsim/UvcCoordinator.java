package local.sidecar.copilot.uvcsim;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Single-source synthetic coordinator. Controls never wait for driver or sink work.
 * State mutation is serialized here; every USB-like operation has one serial worker owner.
 * Requested/observed times are data, never a global ordering precondition on stop.
 */
public final class UvcCoordinator implements AutoCloseable {
    public interface Clock { long now(); }
    public interface FrameSink {
        void accept(CaptureGate.Lease lease,UvcDriver.Frame frame);
        default void cancel(CaptureGate.Lease lease) {}
    }
    public enum State { UNCONFIGURED, READY, RECORDING, PAUSED, SOURCE_INTERRUPTED, PROTECTIVE_PAUSE, STOPPED }
    public enum End { PAUSE, STOP, SOURCE_LOST, FORMAT_CHANGED, PERMISSION_REVOKED, SOURCE_FAILED, RESOURCE_PRESSURE }
    public static final class Selection {
        public final String sessionId, attachmentId;
        public final long permissionRevision;
        private final long id;
        private Selection(String session,String attachment,long revision,long id) {
            sessionId=session; attachmentId=attachment; permissionRevision=revision; this.id=id;
        }
    }
    public static final class StopReceipt {
        public final String eventId;
        public final End reason;
        public final long requestedMonoNs,acceptedMonoNs;
        private volatile boolean confirmed;
        private volatile long effectiveMonoNs=-1;
        private StopReceipt(String eventId,End reason,long requested,long accepted) {
            this.eventId=eventId; this.reason=reason; requestedMonoNs=requested; acceptedMonoNs=accepted;
        }
        public boolean confirmed(){return confirmed;}
        public long effectiveMonoNs(){return effectiveMonoNs;}
    }
    public static final class Snapshot {
        public final State state;
        public final boolean opening,starting,stopPending;
        public final int pendingDriverTasks;
        public final long lastAcceptedSequence,lastHostReceiveMonoNs;
        public final End interruption;
        public final boolean clockUncertain;
        public final String diagnostic;
        public final CaptureGate.Lease lease;
        private Snapshot(Operation op) {
            state=op==null?State.UNCONFIGURED:op.state;
            opening=op!=null&&op.opening; starting=op!=null&&op.starting;
            stopPending=op!=null&&op.cancelled&&!op.drained;
            pendingDriverTasks=op==null?0:op.tasks;
            lastAcceptedSequence=op==null?-1:op.lastSequence;
            lastHostReceiveMonoNs=op==null?-1:op.lastReceive;
            interruption=op==null?null:op.interruption;
            clockUncertain=op!=null&&op.clockUncertain;
            diagnostic=op==null?"no source selected":op.diagnostic;
            lease=op==null?null:op.lease;
        }
    }
    private static final class Operation {
        final Selection selection;
        final UvcDriver.Format requested;
        final Map<String,StopReceipt> receipts=new LinkedHashMap<>();
        State state=State.UNCONFIGURED;
        boolean permission,opening,opened,starting,started,cancelled,cleanupScheduled,drained,closedConfirmed;
        boolean displayConfirmed,stopAttempted,closeAttempted;
        boolean clockUncertain;
        int tasks;
        UvcDriver.Handle handle;
        UvcDriver.Format actual;
        CaptureGate.Lease lease;
        End desiredEnd,interruption;
        long lastSequence=-1,lastReceive=-1,startedAt=-1,closedAt=-1;
        String diagnostic="permission and explicit open required";
        Operation(Selection selection,UvcDriver.Format requested){this.selection=selection;this.requested=requested;}
    }
    private final Object lock=new Object();
    private final UvcDriver driver;
    private final CaptureGate gate;
    private final Clock clock;
    private final FrameSink sink;
    private final String bootId;
    private final ExecutorService deviceWorker=Executors.newSingleThreadExecutor(daemon("uvc-sim-device"));
    private final ExecutorService sinkControls=Executors.newSingleThreadExecutor(daemon("uvc-sim-sink-controls"));
    private final IdentityHashMap<UvcDriver.Handle,Operation> handleOwners=new IdentityHashMap<>();
    private Operation current;
    private long nextId;
    private long lastClock=-1;
    private int pendingTasks;
    private boolean shutdown;

    public UvcCoordinator(UvcDriver driver,CaptureGate gate,Clock clock,FrameSink sink,String bootId) {
        this.driver=Objects.requireNonNull(driver);this.gate=Objects.requireNonNull(gate);
        this.clock=Objects.requireNonNull(clock);this.sink=Objects.requireNonNull(sink);
        this.bootId=nonempty(bootId);
    }
    private static ThreadFactory daemon(final String name) {
        return r->{Thread t=new Thread(r,name);t.setDaemon(true);return t;};
    }
    public Selection select(String session,String attachment,long permissionRevision,UvcDriver.Format requested) {
        synchronized(lock) {
            if(shutdown)throw new IllegalStateException("coordinator closed");
            if(current!=null && !current.drained)throw new IllegalStateException("previous source not drained");
            if(permissionRevision<0)throw new IllegalArgumentException("permission revision");
            Selection s=new Selection(nonempty(session),nonempty(attachment),permissionRevision,++nextId);
            current=new Operation(s,Objects.requireNonNull(requested));return s;
        }
    }
    public boolean permissionResult(Selection selection,long revision,boolean granted) {
        synchronized(lock) {
            Operation op=matching(selection);
            if(op==null || op.cancelled || revision!=selection.permissionRevision)return false;
            if(!granted) { requestStop(op,"permission-revoked",End.PERMISSION_REVOKED,-1);return false; }
            op.permission=true;return true;
        }
    }
    public boolean open(Selection selection,boolean explicitUserAction) {
        synchronized(lock) {
            Operation op=matching(selection);
            if(op==null || op.cancelled || !explicitUserAction || !op.permission || op.opening || op.opened)return false;
            op.opening=true;op.diagnostic="opening selected synthetic source";
            submit(op,()->openOnWorker(op));return true;
        }
    }
    private void openOnWorker(Operation op)throws Exception {
        synchronized(lock){if(op.cancelled)return;}
        UvcDriver.Handle handle=Objects.requireNonNull(driver.open(op.selection.attachmentId,op.requested),"driver handle");
        synchronized(lock) {
            if(handleOwners.containsKey(handle))throw new IllegalStateException("driver reused owned handle identity");
            handleOwners.put(handle,op);op.handle=handle;
            if(op.cancelled)return; // queued cleanup owns this late result
        }
        UvcDriver.Format actual=Objects.requireNonNull(driver.negotiatedFormat(handle));
        synchronized(lock) {
            if(op.cancelled)return;
            op.actual=actual;op.opened=true;op.opening=false;op.state=State.READY;
            op.diagnostic="confirm actual format and selected display";
        }
    }
    public UvcDriver.Format negotiatedFormat(Selection selection) {
        synchronized(lock){Operation op=matching(selection);return op==null?null:op.actual;}
    }
    public boolean confirmDisplay(Selection selection,UvcDriver.Format actual) {
        synchronized(lock) {
            Operation op=matching(selection);
            if(op==null || op.cancelled || !op.opened || !op.actual.equals(actual))return false;
            op.displayConfirmed=true;return true;
        }
    }
    /** Shared gates are checked by the caller; the current lease remains the final publication authority. */
    public boolean start(Selection selection,boolean explicitUserAction,boolean sharedGatesPass) {
        synchronized(lock) {
            Operation op=matching(selection);
            if(op==null || op.cancelled || !explicitUserAction || !sharedGatesPass || !op.permission
                    || !op.opened || !op.displayConfirmed || op.starting || op.started)return false;
            op.starting=true;submit(op,()->startOnWorker(op));return true;
        }
    }
    private void startOnWorker(Operation op)throws Exception {
        synchronized(lock) {
            if(op.cancelled)return;
            // Lease creation and cancellation share lock. A cancellation during start invalidates this lease.
            op.lease=gate.issue(op.selection.sessionId,op.selection.attachmentId,op.selection.permissionRevision);
        }
        driver.start(op.handle,new UvcDriver.Callback() {
            @Override public void onFrame(UvcDriver.Handle handle,UvcDriver.Frame frame){receive(op,handle,frame);}
            @Override public void onFailure(UvcDriver.Handle handle,String reason) {
                synchronized(lock) {
                    if(op==current && !op.cancelled && handle==op.handle)
                        requestStop(op,"driver-failure",End.SOURCE_FAILED,-1);
                }
            }
        });
        synchronized(lock) {
            if(op.cancelled)return;
            if(!gate.isCurrent(op.lease)){requestStop(op,"lease-revoked",End.PERMISSION_REVOKED,-1);return;}
            op.startedAt=safeNow(op);
            if(op.startedAt<0){requestStop(op,"start-clock-unknown",End.SOURCE_FAILED,-1);return;}
            op.starting=false;op.started=true;op.state=State.RECORDING;op.diagnostic="";
        }
    }
    private void receive(Operation op,UvcDriver.Handle handle,UvcDriver.Frame frame) {
        if(frame==null)return;
        CaptureGate.Lease admitted=null;
        synchronized(lock) {
            if(op==current && !op.cancelled && op.started && handle==op.handle && gate.isCurrent(op.lease)) {
                long receivedAt=safeNow(op);
                if(receivedAt<0)requestStop(op,"frame-clock-unknown",End.SOURCE_FAILED,-1);
                else if(!op.actual.equals(frame.format))requestStop(op,"format-changed",End.FORMAT_CHANGED,receivedAt);
                else if(bootId.equals(frame.bootId) && frame.sequence>op.lastSequence
                        && frame.hostReceiveMonoNs>=op.startedAt && frame.hostReceiveMonoNs<=receivedAt
                        && frame.hostReceiveMonoNs>=op.lastReceive) {
                    op.lastSequence=frame.sequence;op.lastReceive=frame.hostReceiveMonoNs;admitted=op.lease;
                }
            }
        }
        if(admitted==null){Arrays.fill(frame.pixels,(byte)0);return;}
        try { sink.accept(admitted,frame); }
        catch(RuntimeException failure) {
            // Failure invalidates publication; sink contract must not retain a frame when throwing.
            Arrays.fill(frame.pixels,(byte)0);sourceInterrupted(admitted,"frame sink failure");
        }
    }
    /** Works after the pipeline has already invalidated the lease. Stale leases cannot stop a new source. */
    public void sourceInterrupted(CaptureGate.Lease lease,String reason) {
        synchronized(lock) {
            if(current!=null && lease!=null && current.lease==lease && !current.cancelled)
                requestStop(current,"pipeline-failure",
                    "queue capacity exhausted".equals(reason)||"frame memory budget exhausted".equals(reason)
                        ?End.RESOURCE_PRESSURE:End.SOURCE_FAILED,-1);
        }
    }
    public StopReceipt end(Selection selection,String eventId,End reason,long requestedMonoNs) {
        synchronized(lock) {
            Operation op=matching(selection);
            if(op==null)return null;
            return requestStop(op,nonempty(eventId),Objects.requireNonNull(reason),requestedMonoNs);
        }
    }
    public StopReceipt detached(String attachmentId,long observedMonoNs) {
        synchronized(lock) {
            if(current==null || !current.selection.attachmentId.equals(attachmentId) || current.drained)return null;
            return requestStop(current,"detach",End.SOURCE_LOST,observedMonoNs);
        }
    }
    private StopReceipt requestStop(Operation op,String eventId,End reason,long requested) {
        StopReceipt existing=op.receipts.get(eventId);
        if(existing!=null)return existing;
        // Revoke publication before consulting any fallible clock or queuing cleanup.
        if(!op.cancelled && !op.drained) {
            op.cancelled=true;op.permission=false;op.displayConfirmed=false;
            if(op.lease!=null) {
                gate.invalidate(op.lease);
                final CaptureGate.Lease lease=op.lease;
                sinkControls.execute(()->{try{sink.cancel(lease);}catch(RuntimeException ignored){}});
            }
        }
        long accepted=safeNow(op);
        StopReceipt receipt=new StopReceipt(eventId,reason,requested,accepted);
        op.receipts.put(eventId,receipt);
        if(op.desiredEnd==null || reason==End.STOP)op.desiredEnd=reason;
        if(reason!=End.PAUSE && reason!=End.STOP)op.interruption=reason;
        if(op.drained) {
            receipt.effectiveMonoNs=accepted;receipt.confirmed=true;
            if(reason==End.STOP)op.state=State.STOPPED;
            return receipt;
        }
        if(reason!=End.PAUSE && reason!=End.STOP)op.state=State.SOURCE_INTERRUPTED;
        op.diagnostic="admission blocked; waiting for driver drain and close";
        if(!op.cleanupScheduled) {
            op.cleanupScheduled=true;submit(op,()->cleanupOnWorker(op));
        }
        return receipt;
    }
    private void cleanupOnWorker(Operation op) {
        UvcDriver.Handle handle;
        synchronized(lock){handle=op.handle;}
        if(handle==null) {
            synchronized(lock){op.closedConfirmed=true;op.closedAt=safeNow(op);}return;
        }
        try {
            synchronized(lock){if(op.stopAttempted)throw new IllegalStateException("duplicate stop");op.stopAttempted=true;}
            driver.stop(handle);
        } catch(Exception ignored) { /* A successful close still guarantees release by driver contract. */ }
        try {
            synchronized(lock){if(op.closeAttempted)throw new IllegalStateException("duplicate close");op.closeAttempted=true;}
            driver.close(handle);
            synchronized(lock){op.closedConfirmed=true;op.closedAt=safeNow(op);handleOwners.remove(handle);}
        } catch(Exception failure) {
            synchronized(lock){op.diagnostic="close outcome uncertain; no automatic reopen";op.state=State.SOURCE_INTERRUPTED;}
        }
    }
    private interface Work {void run()throws Exception;}
    private void submit(Operation op,Work work) {
        op.tasks++;pendingTasks++;
        deviceWorker.execute(()->{
            try {work.run();}
            catch(Exception failure) {
                synchronized(lock) {
                    if(!op.cancelled)requestStop(op,"operation-failure",End.SOURCE_FAILED,-1);
                    op.diagnostic="driver operation failed; safe cleanup required";
                }
            } finally {
                synchronized(lock) {
                    op.tasks--;pendingTasks--;
                    if(op.cancelled && op.tasks==0 && op.closedConfirmed) {
                        op.drained=true;op.opening=false;op.starting=false;op.opened=false;op.started=false;
                        op.state=op.desiredEnd==End.STOP?State.STOPPED:op.desiredEnd==End.PAUSE?State.PAUSED
                            :op.desiredEnd==End.RESOURCE_PRESSURE?State.PROTECTIVE_PAUSE:State.SOURCE_INTERRUPTED;
                        for(StopReceipt r:op.receipts.values()){r.effectiveMonoNs=op.closedAt;r.confirmed=true;}
                        op.diagnostic=op.clockUncertain?"closed; clock time unknown; explicit setup required":"closed; explicit selection and fresh confirmation required";
                    }
                    lock.notifyAll();
                }
            }
        });
    }
    public Snapshot snapshot(){synchronized(lock){return new Snapshot(current);}}
    /** Test/host shutdown helper only. A true result means tasks drained, not that close succeeded. */
    public boolean awaitWorkerIdle(long timeoutMillis)throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized(lock) {
            while(pendingTasks>0) {
                long remaining=deadline-System.nanoTime();if(remaining<=0)return false;
                TimeUnit.NANOSECONDS.timedWait(lock,remaining);
            }
            return true;
        }
    }
    @Override public void close() {
        synchronized(lock) {
            if(shutdown)return;
            if(current!=null && !current.drained)requestStop(current,"coordinator-close",End.STOP,-1);
            shutdown=true;deviceWorker.shutdown();sinkControls.shutdown();
        }
    }
    private Operation matching(Selection selection){return current!=null&&current.selection==selection?current:null;}
    /** Called under lock. Clock failure is diagnostic data, never an exception that cancels a stop. */
    private long safeNow(Operation op) {
        try {
            long now=clock.now();
            if(now>=0 && now>=lastClock){lastClock=now;return now;}
        } catch(RuntimeException ignored) {}
        op.clockUncertain=true;return -1;
    }
    private static String nonempty(String s){if(s==null||s.isEmpty())throw new IllegalArgumentException("empty identity");return s;}
}

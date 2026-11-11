import local.sidecar.copilot.uvcsim.*;
import local.sidecar.copilot.uvcsim.UvcCoordinator.*;
import local.sidecar.copilot.uvcsim.SyntheticUvcDriver.*;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Pure JVM lifecycle tests. Assertion counts are not additional PRD acceptance identifiers. */
public final class CoordinatorChecks {
    private static final UvcDriver.Format FORMAT=new UvcDriver.Format(2,2,"GRAY8",30,1);
    private static int assertions;
    private static void check(boolean good,String label){assertions++;if(!good)throw new AssertionError(label);}
    private static void denied(Runnable action,String label){try{action.run();}catch(IllegalStateException expected){assertions++;return;}throw new AssertionError(label);}
    private static final class Harness implements AutoCloseable {
        final SyntheticUvcDriver driver=new SyntheticUvcDriver();
        final CaptureGate gate=new CaptureGate();
        final AtomicLong time=new AtomicLong(100);
        final AtomicLong acceptedFrames=new AtomicLong();
        final AtomicLong cancelled=new AtomicLong();
        final UvcCoordinator c;
        Selection selection;
        Harness(){this(null);}
        Harness(FrameSink replacement) {
            driver.actualFormat=FORMAT;
            FrameSink sink=replacement==null?new FrameSink(){
                public void accept(CaptureGate.Lease lease,UvcDriver.Frame frame){
                    gate.publish(lease,()->acceptedFrames.incrementAndGet());Arrays.fill(frame.pixels,(byte)0);
                }
                public void cancel(CaptureGate.Lease lease){cancelled.incrementAndGet();}
            }:replacement;
            c=new UvcCoordinator(driver,gate,time::get,sink,"boot");
        }
        Selection select(String attachment){selection=c.select("session",attachment,7,FORMAT);return selection;}
        void ready()throws Exception {
            select("attachment-1");check(c.permissionResult(selection,7,true),"permission granted");
            check(c.open(selection,true),"open requested");idle();
            check(c.snapshot().state==State.READY,"opened ready");
            check(c.confirmDisplay(selection,FORMAT),"display confirmed");
        }
        void start()throws Exception{ready();check(c.start(selection,true,true),"start requested");idle();check(c.snapshot().state==State.RECORDING,"recording after start acknowledgment");}
        void idle()throws Exception{check(c.awaitWorkerIdle(2000),"driver worker drained");}
        StopReceipt pause(){return c.end(selection,"pause",End.PAUSE,1);}
        public void close()throws Exception {
            for(Block b:new Block[]{driver.blockOpen,driver.blockStart,driver.blockStop,driver.blockClose})if(b!=null)b.release();
            c.close();check(c.awaitWorkerIdle(2000),"test cleanup drained");
        }
    }
    private static UvcDriver.Frame frame(long sequence,long time){return new UvcDriver.Frame(FORMAT,"boot",sequence,time,new byte[]{1,2,3,4});}
    private static boolean zero(byte[] b){for(byte v:b)if(v!=0)return false;return true;}
    public static void main(String[]args)throws Exception {
        permissionsAndStart();requestedTimeReordering();lateOpenAndStop();stopDuringStart();closeFailure();
        formatAndStaleCallbacks();pipelineFailureAfterInvalidation();sinkCannotBlockControl();operationFailures();
        failingClock();resourcePressure();generatedFrameBudget();samePathReattachment();
        System.out.println("PASS CoordinatorChecks: "+assertions+" assertions; controlled synthetic lifecycle and race scenarios");
    }
    private static void permissionsAndStart()throws Exception {
        try(Harness h=new Harness()) {
            h.select("one");check(!h.c.open(h.selection,true),"no permission cannot open");
            check(!h.c.permissionResult(h.selection,6,true),"old permission revision");
            check(!h.c.start(h.selection,true,true),"unopened cannot start");
            check(!h.c.permissionResult(h.selection,7,false),"permission denied");h.idle();
            check(h.driver.openCalls.get()==0,"denied permission made zero driver calls");
            check(h.c.snapshot().state==State.SOURCE_INTERRUPTED,"denial visible");
        }
        try(Harness h=new Harness()) {
            h.ready();check(!h.c.start(h.selection,false,true),"attach is not explicit start");
            check(!h.c.start(h.selection,true,false),"shared gates closed");
            check(h.driver.startCalls.get()==0,"closed gates never start");
        }
    }
    private static void requestedTimeReordering()throws Exception {
        try(Harness h=new Harness()) {
            h.start();h.time.set(200);h.driver.emit(h.driver.lastHandle(),frame(1,200));
            CaptureGate.Lease lease=h.c.snapshot().lease;
            StopReceipt receipt=h.c.end(h.selection,"older-request",End.PAUSE,150);
            check(receipt.requestedMonoNs==150 && receipt.acceptedMonoNs==200,"requested and internally accepted time separate");
            check(!h.gate.isCurrent(lease),"stop invalidates despite older external request");h.idle();
            check(receipt.confirmed() && h.c.snapshot().state==State.PAUSED,"pause completed");
            StopReceipt duplicate=h.c.end(h.selection,"older-request",End.PAUSE,100);
            check(duplicate==receipt,"stop idempotent after completion");
            check(h.c.detached("attachment-1",199)==null,"late detach after complete ignored");
            check(!h.c.snapshot().stopPending && h.driver.lastHandle().closeCalls.get()==1,"repeat does not reclose or reopen pending");
            StopReceipt end=h.c.end(h.selection,"end",End.STOP,175);
            check(end.confirmed() && h.c.snapshot().state==State.STOPPED,"stop after paused needs no new driver work");
        }
    }
    private static void lateOpenAndStop()throws Exception {
        try(Harness h=new Harness()) {
            h.select("one");h.c.permissionResult(h.selection,7,true);
            h.driver.blockOpen=new Block();h.c.open(h.selection,true);
            check(h.driver.blockOpen.awaitEntered(2000),"open blocked");
            StopReceipt receipt=h.pause();
            check(!receipt.confirmed() && h.c.snapshot().stopPending,"not falsely paused while open unresolved");
            denied(()->h.c.select("new","two",7,FORMAT),"cannot replace pending source");
            check(!h.c.awaitWorkerIdle(10),"timeout reports pending");
            h.driver.blockOpen.release();h.idle();
            check(receipt.confirmed() && h.driver.lastHandle().closeCalls.get()==1,"late opened handle closed once");
            check(h.driver.startCalls.get()==0,"cancelled open never starts");
        }
    }
    private static void stopDuringStart()throws Exception {
        try(Harness h=new Harness()) {
            h.ready();h.driver.blockStart=new Block();h.c.start(h.selection,true,true);
            check(h.driver.blockStart.awaitEntered(2000),"start blocked");
            CaptureGate.Lease lease=h.c.snapshot().lease;
            check(lease!=null,"lease assigned before driver start");
            UvcDriver.Frame early=frame(1,100);h.driver.emit(h.driver.lastHandle(),early);
            check(zero(early.pixels) && h.acceptedFrames.get()==0,"pre-ack frame discarded");
            StopReceipt receipt=h.pause();
            check(!h.gate.isCurrent(lease) && !receipt.confirmed(),"start cancellation immediate and close pending");
            h.c.end(h.selection,"stop",End.STOP,80);h.driver.blockStart.release();h.idle();
            check(h.c.snapshot().state==State.STOPPED,"STOP dominates pending PAUSE");
            check(h.driver.lastHandle().stopCalls.get()==1 && h.driver.lastHandle().closeCalls.get()==1,"started handle stopped/released once");
        }
    }
    private static void closeFailure()throws Exception {
        try(Harness h=new Harness()) {
            h.start();h.driver.failClose=true;StopReceipt receipt=h.pause();h.idle();
            check(!receipt.confirmed() && h.c.snapshot().stopPending,"failed close remains uncertain");
            denied(()->h.c.select("new","two",7,FORMAT),"uncertain close blocks replacement");
            h.c.end(h.selection,"stop-again",End.STOP,100);h.idle();
            check(h.driver.lastHandle().closeCalls.get()==1,"failed close not blindly retried");
        }
    }
    private static void formatAndStaleCallbacks()throws Exception {
        try(Harness h=new Harness()) {
            h.start();SyntheticHandle old=h.driver.lastHandle();CaptureGate.Lease lease=h.c.snapshot().lease;
            UvcDriver.Frame wrong=new UvcDriver.Frame(new UvcDriver.Format(1,1,"GRAY8",30,1),"boot",1,100,new byte[]{8});
            h.driver.emit(old,wrong);check(zero(wrong.pixels),"changed format rejected");h.idle();
            check(h.c.snapshot().interruption==End.FORMAT_CHANGED,"structured format interruption");
            check(old.closeCalls.get()==1 && !h.gate.isCurrent(lease),"format automatically drained without caller guessing");
            h.start();CaptureGate.Lease newLease=h.c.snapshot().lease;
            h.c.sourceInterrupted(lease,"late old pipeline failure");
            UvcDriver.Frame late=frame(2,100);h.driver.emit(old,late);h.driver.fail(old,"late failure");
            check(zero(late.pixels) && h.gate.isCurrent(newLease),"old callbacks cannot affect replacement");
            SyntheticHandle current=h.driver.lastHandle();h.driver.emit(current,frame(2,100));
            UvcDriver.Frame reordered=frame(1,100);h.driver.emit(current,reordered);
            check(zero(reordered.pixels) && h.acceptedFrames.get()==1,"duplicate/reordered frames rejected");
            UvcDriver.Frame wrongBoot=new UvcDriver.Frame(FORMAT,"new-boot",3,100,new byte[]{1,2,3,4});h.driver.emit(current,wrongBoot);
            check(zero(wrongBoot.pixels),"wrong boot rejected");
        }
    }
    private static void pipelineFailureAfterInvalidation()throws Exception {
        try(Harness h=new Harness()) {
            h.start();CaptureGate.Lease lease=h.c.snapshot().lease;h.gate.invalidate(lease);
            h.c.sourceInterrupted(lease,"queue full");h.idle();
            check(h.c.snapshot().interruption==End.SOURCE_FAILED && h.driver.lastHandle().closeCalls.get()==1,
                "pipeline may invalidate before reporting interruption");
        }
    }
    private static void sinkCannotBlockControl()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        FrameSink slow=new FrameSink(){
            public void accept(CaptureGate.Lease lease,UvcDriver.Frame f) {
                entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("blocked sink timeout");}
                catch(InterruptedException e){throw new RuntimeException(e);}finally{Arrays.fill(f.pixels,(byte)0);}
            }
        };
        try(Harness h=new Harness(slow)) {
            h.start();Thread callback=new Thread(()->h.driver.emit(h.driver.lastHandle(),frame(1,100)));callback.start();
            try {
                check(entered.await(2000,TimeUnit.MILLISECONDS),"sink blocked");CaptureGate.Lease lease=h.c.snapshot().lease;
                h.pause();check(!h.gate.isCurrent(lease),"blocked sink cannot delay stop gate");h.idle();
            } finally{release.countDown();callback.join(2000);}
            check(!callback.isAlive(),"callback released");
        }
    }
    private static void operationFailures()throws Exception {
        try(Harness h=new Harness()) {
            h.select("one");h.c.permissionResult(h.selection,7,true);h.driver.failOpen=true;h.c.open(h.selection,true);h.idle();
            check(h.c.snapshot().state==State.SOURCE_INTERRUPTED && !h.c.snapshot().stopPending,"failed open safely drained");
        }
        try(Harness h=new Harness()) {
            h.ready();h.driver.failStart=true;h.c.start(h.selection,true,true);h.idle();
            check(h.driver.lastHandle().closeCalls.get()==1 && !h.gate.isCurrent(h.c.snapshot().lease),"failed start invalidates and closes");
        }
        try(Harness h=new Harness()) {
            h.start();h.driver.failStop=true;StopReceipt receipt=h.pause();h.idle();
            check(receipt.confirmed() && h.driver.lastHandle().closeCalls.get()==1,"successful release establishes boundary after stop error");
        }
    }
    private static void failingClock()throws Exception {
        for(int kind=0;kind<3;kind++) {
            final int fault=kind;
            SyntheticUvcDriver driver=new SyntheticUvcDriver();driver.actualFormat=FORMAT;
            CaptureGate gate=new CaptureGate();AtomicLong mode=new AtomicLong(0);
            UvcCoordinator.Clock clock=()->{
                if(mode.get()==0)return 100;
                if(fault==0)throw new IllegalStateException("clock failed");
                return fault==1?50:-1;
            };
            try(UvcCoordinator c=new UvcCoordinator(driver,gate,clock,(lease,f)->Arrays.fill(f.pixels,(byte)0),"boot")) {
                Selection s=c.select("session","clock-fault",1,FORMAT);c.permissionResult(s,1,true);c.open(s,true);
                check(c.awaitWorkerIdle(2000),"clock fixture opened");c.confirmDisplay(s,FORMAT);c.start(s,true,true);
                check(c.awaitWorkerIdle(2000),"clock fixture started");CaptureGate.Lease lease=c.snapshot().lease;
                mode.set(1);StopReceipt receipt=c.end(s,"stop",End.PAUSE,80);
                check(!gate.isCurrent(lease) && receipt.acceptedMonoNs==-1,"clock fault cannot prevent barrier");
                check(c.awaitWorkerIdle(2000),"clock fault still drains");
                check(receipt.confirmed() && receipt.effectiveMonoNs()==-1 && c.snapshot().clockUncertain,"unknown timestamp separate from confirmed stop");
            }
        }
    }
    private static void samePathReattachment()throws Exception {
        try(Harness h=new Harness()) {
            h.start();Selection first=h.selection;h.c.detached("attachment-1",100);h.idle();
            h.select("attachment-2");check(!h.c.permissionResult(first,7,true),"old attachment permission rejected");
            check(h.c.detached("attachment-1",99)==null,"old detach cannot interrupt fresh attachment");
            check(h.driver.startCalls.get()==1,"reconnection never automatically starts");
        }
    }
    private static void resourcePressure()throws Exception {
        for(String reason:new String[]{"queue capacity exhausted","frame memory budget exhausted"}) {
            try(Harness h=new Harness()) {
                h.start();CaptureGate.Lease lease=h.c.snapshot().lease;h.gate.invalidate(lease);
                h.c.sourceInterrupted(lease,reason);h.idle();
                check(h.c.snapshot().state==State.PROTECTIVE_PAUSE,"resource pressure projects protective pause after drain");
                check(h.c.snapshot().interruption==End.RESOURCE_PRESSURE,"resource reason preserved");
                check(!h.c.start(h.selection,true,true),"protective pause requires explicit reselection");
            }
        }
    }
    private static void generatedFrameBudget()throws Exception {
        try(Harness h=new Harness()) {
            h.start();h.driver.emitGenerated(h.driver.lastHandle(),()->frame(1,100));
            check(h.acceptedFrames.get()==1,"controlled factory delivers frame");
            final byte[][] rejected=new byte[1][];
            try {
                h.driver.emitGenerated(h.driver.lastHandle(),()->{
                    byte[] b=new byte[SyntheticUvcDriver.MAX_GENERATED_FRAME_BYTES+1];Arrays.fill(b,(byte)1);
                    rejected[0]=b;return new UvcDriver.Frame(FORMAT,"boot",2,100,b);
                });
                throw new AssertionError("oversized factory frame accepted");
            } catch(IllegalArgumentException expected) {
                check(zero(rejected[0]) && h.acceptedFrames.get()==1,"oversized generated input cleared before transfer");
            }
        }
    }
}

import local.sidecar.copilot.uvc.UvcLifecycle;
import local.sidecar.copilot.uvc.UvcLifecycle.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class UvcLifecycleChecks {
    private static int checks;
    private static final Format FORMAT=new Format(1920,1080,30,1,"MJPEG");
    private static final Device DEVICE=new Device("attachment-1",1,2,true,true);
    private static void check(boolean ok,String name) {
        checks++; if(!ok) throw new AssertionError(name);
    }
    private static void rejected(Runnable action,String name) {
        try { action.run(); } catch(IllegalArgumentException|IllegalStateException expected) { checks++; return; }
        throw new AssertionError(name);
    }
    private static Attempt ready(UvcLifecycle s,long now) {
        Attempt a=s.select(DEVICE,now);
        check(s.permissionResult(a,true,now),"current permission");
        check(s.beginOpen(a,now),"open dispatched");
        check(!s.beginOpen(a,now),"duplicate open blocked");
        check(s.opened(a,FORMAT,now),"open acknowledged");
        check(!s.opened(a,FORMAT,now),"duplicate open callback blocked");
        check(s.snapshot().state==State.READY,"ready is not recording");
        check(s.confirmDisplay(a,FORMAT,now),"screen confirmed");
        return a;
    }
    private static Run recording(UvcLifecycle s,long now) {
        ready(s,now);
        Run r=s.beginStart(true,true,now);
        check(r!=null,"explicit start");
        check(!s.observeFrame(r,"boot",FORMAT,now,now),"frames before start ack blocked");
        check(s.started(r,now),"start ack"); return r;
    }
    public static void main(String[] args)throws Exception {
        UvcLifecycle s=new UvcLifecycle("boot");
        check(s.snapshot().state==State.UNCONFIGURED,"cold process never resumes");
        check(s.beginStart(true,true,0)==null,"no device");
        rejected(()->s.select(new Device("audio",1,2,false,false),0),"UAC is not UVC");
        rejected(()->new Format(0,1,1,1,"MJPEG"),"bad format");
        Attempt denied=s.select(DEVICE,0);
        check(!s.permissionResult(denied,false,0),"permission denied");
        check(!s.permissionResult(denied,true,0),"denied ticket cannot be replayed");
        check(!s.beginOpen(denied,0),"denied never opens");
        Attempt a=ready(s,1);
        check(s.beginStart(false,true,1)==null,"attach not start");
        check(s.beginStart(true,false,1)==null,"shared gates closed");
        check(!s.confirmDisplay(a,new Format(640,480,30,1,"YUY2"),1),"confirmation exact format");
        Run r=s.beginStart(true,true,2);
        check(s.beginStart(true,true,2)==null,"one pending start");
        check(s.started(r,2),"start acknowledgment");
        check(!s.started(r,2),"duplicate start acknowledgment");
        check(!s.observeFrame(r,"other boot",FORMAT,3,3),"wrong clock domain");
        check(!s.observeFrame(r,"boot",FORMAT,1,3),"pre-start frame");
        check(!s.observeFrame(r,"boot",FORMAT,4,3),"future frame");
        check(s.observeFrame(r,"boot",FORMAT,3,3),"current frame");
        check(!s.observeFrame(r,"boot",FORMAT,3,3),"duplicate frame time");
        check(s.detached("other peripheral",3)==null,"unrelated detach");
        Barrier b=s.end(End.PAUSE,3,4);
        check(s.snapshot().stopPending && !s.snapshot().acceptingFrames,"barrier immediate");
        check(s.snapshot().state!=State.PAUSED,"no premature pause claim");
        check(!s.observeFrame(r,"boot",FORMAT,4,4),"late frame blocked");
        check(s.end(End.PAUSE,4,4)==b,"idempotent barrier");
        check(!s.closed(b,false,4,4),"driver close failure visible");
        check(!s.closed(b,true,3,4),"invalid effective boundary");
        rejected(()->s.select(DEVICE,4),"reopen while close uncertain");
        check(s.closed(b,true,5,5),"close acknowledged");
        check(s.snapshot().state==State.PAUSED && s.snapshot().stopEffectiveMonoNs==5,"pause after boundary");
        check(!s.closed(b,true,5,5),"duplicate close ignored");
        check(!s.permissionResult(a,true,5),"old permission ignored");
        check(!s.started(r,5),"old start ignored");
        check(s.beginStart(true,true,5)==null,"resume requires source recheck");
        r=recording(s,6);
        check(!s.observeFrame(r,"boot",new Format(1280,720,30,1,"MJPEG"),7,7),"format change excluded");
        check(s.snapshot().state==State.SOURCE_INTERRUPTED,"format change visible");
        b=s.end(End.FORMAT_CHANGED,7,7);
        s.end(End.STOP,7,7);
        check(s.closed(b,true,8,8),"close changed source");
        check(s.snapshot().state==State.STOPPED,"stop dominates pending interruption");
        rejected(()->s.select(DEVICE,7),"time rollback rejected");
        UvcLifecycle fresh=new UvcLifecycle("new boot");
        check(fresh.beginStart(true,true,0)==null,"reboot needs user setup");
        check(!fresh.started(r,0),"old-process token rejected");
        blockedOpen();
        for(int i=0;i<1000;i++) {
            UvcLifecycle m=new UvcLifecycle("boot");
            Attempt p=ready(m,0);
            Run pending=m.beginStart(true,true,1);
            Barrier stop=m.detached(DEVICE.attachmentId,2);
            check(!m.started(pending,2),"detach wins delayed start");
            check(!m.opened(p,FORMAT,2),"detach wins late open");
            check(m.beginStart(true,true,2)==null,"no reconnect start");
            check(m.closed(stop,true,3,3),"detached worker drained");
            check(m.snapshot().state==State.SOURCE_INTERRUPTED,"source interruption retained");
        }
        System.out.println("PASS: "+checks+" assertions; 1000 deterministic detach/start repetitions; synthetic metadata only");
    }
    private static void blockedOpen()throws Exception {
        UvcLifecycle s=new UvcLifecycle("boot");
        Attempt a=s.select(DEVICE,0); s.permissionResult(a,true,0); s.beginOpen(a,0);
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicReference<Throwable> error=new AtomicReference<>();
        Thread worker=new Thread(()->{
            try { entered.countDown(); if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("timeout");
                if(s.opened(a,FORMAT,2)) throw new AssertionError("late handle accepted");
                // A real adapter must close this late handle before acknowledging drained close.
            } catch(Throwable e) { error.set(e); }
        });
        worker.start(); check(entered.await(5,TimeUnit.SECONDS),"blocked driver entered");
        Barrier b=s.end(End.PAUSE,1,1);
        check(s.snapshot().stopPending,"stop independent of blocked driver open");
        release.countDown(); worker.join(5000);
        check(!worker.isAlive() && error.get()==null,"late handle rejected after driver unblocks");
        check(s.closed(b,true,3,3),"drained close acknowledgment");
    }
}

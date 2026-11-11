import local.sidecar.copilot.PcmSourceLifecycle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Focused checks for existing source interruption and stop requirements; no device capture. */
public final class PcmSourceLifecycleTest {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError("source lifecycle "+checks);checks++;}
    private static void await(CountDownLatch latch)throws Exception {
        if(!latch.await(5,TimeUnit.SECONDS))throw new AssertionError("timed out waiting for source lifecycle test");
    }
    public static void main(String[] args)throws Exception {
        AtomicInteger stops=new AtomicInteger(),releases=new AtomicInteger();
        PcmSourceLifecycle source=new PcmSourceLifecycle(stops::incrementAndGet,releases::incrementAndGet);
        CountDownLatch sealing=new CountDownLatch(1),finishSeal=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread worker=new Thread(()->{
            try{source.stopAndSeal(()->{sealing.countDown();await(finishSeal);});}
            catch(Throwable e){failure.set(e);}finally{source.release();}
        });
        worker.start();await(sealing);
        check(stops.get()==1); // Hardware input has stopped even while fsync/sealing is blocked.
        source.stop();check(stops.get()==1);check(releases.get()==0);
        finishSeal.countDown();worker.join(5000);
        check(!worker.isAlive());check(failure.get()==null);check(releases.get()==1);
        source.release();source.stop();check(stops.get()==1&&releases.get()==1);

        AtomicInteger stoppedOnFailure=new AtomicInteger(),releasedOnFailure=new AtomicInteger();
        PcmSourceLifecycle failed=new PcmSourceLifecycle(stoppedOnFailure::incrementAndGet,releasedOnFailure::incrementAndGet);
        Exception diskFailure=new Exception("synthetic disk failure");
        try{failed.stopAndSeal(()->{check(stoppedOnFailure.get()==1);throw diskFailure;});throw new AssertionError("seal failure hidden");}
        catch(Exception expected){check(expected==diskFailure);}finally{failed.release();}
        check(releasedOnFailure.get()==1);

        AtomicInteger racedStops=new AtomicInteger(),racedReleases=new AtomicInteger();
        CountDownLatch stopping=new CountDownLatch(1),finishStop=new CountDownLatch(1),releaseCalled=new CountDownLatch(1);
        PcmSourceLifecycle raced=new PcmSourceLifecycle(()->{
            racedStops.incrementAndGet();stopping.countDown();
            try{await(finishStop);}catch(Exception e){throw new AssertionError(e);}
        },racedReleases::incrementAndGet);
        Thread stopThread=new Thread(raced::stop);
        Thread releaseThread=new Thread(()->{releaseCalled.countDown();raced.release();});
        stopThread.start();await(stopping);releaseThread.start();await(releaseCalled);
        check(racedReleases.get()==0);
        finishStop.countDown();stopThread.join(5000);releaseThread.join(5000);
        check(!stopThread.isAlive()&&!releaseThread.isAlive());
        check(racedStops.get()==1&&racedReleases.get()==1);

        AtomicInteger alreadyStoppedRelease=new AtomicInteger();
        PcmSourceLifecycle alreadyStopped=new PcmSourceLifecycle(()->{throw new IllegalStateException("already stopped");},alreadyStoppedRelease::incrementAndGet);
        alreadyStopped.stopAndSeal(()->check(alreadyStoppedRelease.get()==0));
        alreadyStopped.release();alreadyStopped.release();check(alreadyStoppedRelease.get()==1);

        AtomicInteger earlyStarts=new AtomicInteger(),earlyStops=new AtomicInteger(),earlyReleases=new AtomicInteger();
        AtomicBoolean earlyCancelled=new AtomicBoolean(true);
        PcmSourceLifecycle early=new PcmSourceLifecycle(earlyStops::incrementAndGet,earlyReleases::incrementAndGet);
        check(!early.startIfAllowed(earlyStarts::incrementAndGet,earlyCancelled::get));check(earlyStarts.get()==0);
        early.release();early.release();check(earlyStops.get()==1&&earlyReleases.get()==1);

        // Storage preparation may be blocked, but cancellation does not wait for its completion.
        AtomicInteger preparedStarts=new AtomicInteger(),preparedStops=new AtomicInteger(),preparedReleases=new AtomicInteger();
        AtomicBoolean preparationCancelled=new AtomicBoolean(),preparationAdmitted=new AtomicBoolean();
        CountDownLatch preparing=new CountDownLatch(1),finishPreparation=new CountDownLatch(1);
        PcmSourceLifecycle prepared=new PcmSourceLifecycle(preparedStops::incrementAndGet,preparedReleases::incrementAndGet);
        Thread storage=new Thread(()->{
            try{preparing.countDown();await(finishPreparation);preparationAdmitted.set(prepared.startIfAllowed(preparedStarts::incrementAndGet,preparationCancelled::get));}
            catch(Throwable e){failure.set(e);}
        });
        storage.start();await(preparing);preparationCancelled.set(true);prepared.stop();
        check(preparedStops.get()==1&&preparedStarts.get()==0);
        finishPreparation.countDown();storage.join(5000);
        check(!storage.isAlive()&&failure.get()==null);check(!preparationAdmitted.get()&&preparedStarts.get()==0);
        prepared.release();check(preparedReleases.get()==1);

        // A platform start already in progress is stopped before startIfAllowed reports success.
        AtomicInteger duringStarts=new AtomicInteger(),duringStops=new AtomicInteger(),duringReleases=new AtomicInteger();
        AtomicBoolean duringCancelled=new AtomicBoolean(),duringAdmitted=new AtomicBoolean(true);
        CountDownLatch starting=new CountDownLatch(1),finishStart=new CountDownLatch(1),controlCalled=new CountDownLatch(1);
        PcmSourceLifecycle during=new PcmSourceLifecycle(duringStops::incrementAndGet,duringReleases::incrementAndGet);
        Thread starter=new Thread(()->{
            try{duringAdmitted.set(during.startIfAllowed(()->{
                duringStarts.incrementAndGet();starting.countDown();
                try{await(finishStart);}catch(Exception e){throw new AssertionError(e);}
            },duringCancelled::get));}catch(Throwable e){failure.set(e);}
        });
        Thread controller=new Thread(()->{controlCalled.countDown();during.stop();});
        starter.start();await(starting);duringCancelled.set(true);controller.start();await(controlCalled);
        check(duringStops.get()==0);
        finishStart.countDown();starter.join(5000);controller.join(5000);
        check(!starter.isAlive()&&!controller.isAlive()&&failure.get()==null);check(!duringAdmitted.get());
        check(duringStarts.get()==1&&duringStops.get()==1);
        during.release();check(duringReleases.get()==1);

        AtomicInteger onceStarts=new AtomicInteger(),onceStops=new AtomicInteger(),onceReleases=new AtomicInteger();
        PcmSourceLifecycle once=new PcmSourceLifecycle(onceStops::incrementAndGet,onceReleases::incrementAndGet);
        check(once.startIfAllowed(onceStarts::incrementAndGet,()->false));
        try{once.startIfAllowed(onceStarts::incrementAndGet,()->false);throw new AssertionError("duplicate hardware start");}
        catch(IllegalStateException expected){checks++;}
        once.release();check(onceStarts.get()==1&&onceStops.get()==1&&onceReleases.get()==1);
        System.out.println(checks+" PCM source lifecycle checks passed; no device capture");
    }
}

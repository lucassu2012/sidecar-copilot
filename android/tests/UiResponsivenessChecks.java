package local.sidecar.copilot;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Existing privacy/lifecycle requirements, stalled store and synthetic content only. */
public final class UiResponsivenessChecks {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError("UI response "+checks);checks++;}
    private static boolean contains(View view,String value){
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(value))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(contains(((ViewGroup)view).getChildAt(i),value))return true;
        return false;
    }
    /** finish() only schedules destruction; shutdown() still lets queued work drain.
     * Wait off the UI thread before another fixture can acquire the exclusive audio-store lock.
     */
    private static void finishAndDrain(Instrumentation instrumentation,Activity... activities)throws Exception {
        instrumentation.runOnMainSync(()->{for(Activity activity:activities)if(activity!=null)activity.finish();});
        long deadline=SystemClock.elapsedRealtime()+10000;
        Handler main=new Handler(Looper.getMainLooper());
        boolean destroyed=false;
        while(!destroyed&&SystemClock.elapsedRealtime()<deadline){
            AtomicBoolean allDestroyed=new AtomicBoolean(true);CountDownLatch inspected=new CountDownLatch(1);
            main.post(()->{
                try{for(Activity activity:activities)if(activity!=null&&!activity.isDestroyed())allDestroyed.set(false);}
                finally{inspected.countDown();}
            });
            long remaining=deadline-SystemClock.elapsedRealtime();
            if(remaining<=0||!inspected.await(remaining,TimeUnit.MILLISECONDS))throw new AssertionError("UI fixture teardown: main-thread destruction check timed out");
            destroyed=allDestroyed.get();if(!destroyed)Thread.sleep(25);
        }
        if(!destroyed)throw new AssertionError("UI fixture teardown: Activity destruction timed out");
        for(Activity activity:activities){
            if(activity==null)continue;
            for(java.lang.reflect.Field field:activity.getClass().getDeclaredFields()){
                if(!ExecutorService.class.isAssignableFrom(field.getType()))continue;
                field.setAccessible(true);ExecutorService worker=(ExecutorService)field.get(activity);
                if(worker==null||!worker.isShutdown())throw new AssertionError("UI fixture teardown: worker not shut down: "+activity.getClass().getSimpleName()+"."+field.getName());
                long remaining=deadline-SystemClock.elapsedRealtime();
                if(remaining<=0||!worker.awaitTermination(remaining,TimeUnit.MILLISECONDS))
                    throw new AssertionError("UI fixture teardown: worker still owns pending work: "+activity.getClass().getSimpleName()+"."+field.getName());
            }
        }
    }
    private static void blocked(Instrumentation instrumentation,Context context,String id,int screenKind)throws Exception {
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),heartbeat=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread blocker=new Thread(()->{synchronized(MeetingPrivacy.LOCK){held.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("blocked UI timeout");}catch(Throwable e){failure.set(e);}}});
        blocker.start();check(held.await(2,TimeUnit.SECONDS));Activity activity=null,cover=null;
        try{
            activity=instrumentation.startActivitySync(new Intent(context,screenKind==1?MainActivity.class:screenKind==2?MeetingPreparationActivity.class:RecordingActivity.class).putExtra("meeting",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Activity screen=activity;
            if(screenKind==1)instrumentation.runOnMainSync(()->{try{java.lang.reflect.Method home=MainActivity.class.getDeclaredMethod("home");home.setAccessible(true);home.invoke(screen);}catch(Exception e){throw new RuntimeException(e);}});
            long started=SystemClock.elapsedRealtime();new Handler(Looper.getMainLooper()).post(heartbeat::countDown);
            check(heartbeat.await(2,TimeUnit.SECONDS));check(SystemClock.elapsedRealtime()-started<2000);
            // Move away while the authorized read is still blocked. Its late result must stay hidden.
            cover=instrumentation.startActivitySync(new Intent(context,screenKind==2?MainActivity.class:MeetingPreparationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            release.countDown();blocker.join(3000);check(!blocker.isAlive()&&failure.get()==null);
            SystemClock.sleep(500);instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->check(!contains(screen.getWindow().getDecorView(),"SYNTHETIC_UI_PRIVATE")));
        }finally{
            release.countDown();blocker.join(3000);
            finishAndDrain(instrumentation,cover,activity);
        }
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("emulator required");checks=0;
        Context context=instrumentation.getTargetContext();NoteStore notes=new NoteStore(context);String id;
        try(AudioSegmentStore audio=new AudioSegmentStore(context)){id=new MeetingSessionRepository(context,audio).prepare(null,"SYNTHETIC_UI_PRIVATE",true,"fixture");}
        try{blocked(instrumentation,context,id,0);blocked(instrumentation,context,id,1);blocked(instrumentation,context,id,2);}
        finally{notes.delete(id);try(AudioSegmentStore audio=new AudioSegmentStore(context)){audio.purgeDeleted(id);}}
        return checks;
    }
}

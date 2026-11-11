package local.sidecar.copilot;

import android.app.job.*;
import android.content.*;
import android.os.*;

/** Best-effort ciphertext housekeeping. No decryption, capture, network, or automatic resume. */
public final class AudioRetentionJob extends JobService {
    public static final int JOB_ID=72;
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Run current;
    private static final class Run {volatile boolean stopped;}
    public static boolean schedule(Context context){
        ComponentName component=new ComponentName(context,AudioRetentionJob.class);
        try{context.getPackageManager().getServiceInfo(component,0);}catch(Exception missing){return false;}
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(scheduler.getPendingJob(JOB_ID)!=null)return true;
        try{return scheduler.schedule(new JobInfo.Builder(JOB_ID,component).setPeriodic(15*60*1000L).setRequiresDeviceIdle(true).build())==JobScheduler.RESULT_SUCCESS;}
        catch(RuntimeException failure){context.getSharedPreferences("audio-cleanup-status",MODE_PRIVATE).edit().putString("error",failure.getClass().getSimpleName()).apply();return false;}
    }
    @Override public boolean onStartJob(JobParameters params){
        Run run=new Run();current=run;
        new Thread(()->{
            boolean retry=false;
            try{
                int removed=AudioSegmentStore.sweepExpiredForMaintenance(this,()->run.stopped);
                if(!run.stopped&&!getSharedPreferences("audio-cleanup-status",MODE_PRIVATE).edit().putLong("last_success_elapsed",SystemClock.elapsedRealtime()).putInt("removed",removed).remove("error").commit())throw new java.io.IOException("cleanup status unavailable");
            }catch(Exception failure){retry=true;if(!run.stopped)getSharedPreferences("audio-cleanup-status",MODE_PRIVATE).edit().putString("error",failure.getClass().getSimpleName()).commit();}
            boolean reschedule=retry;main.post(()->{if(current==run&&!run.stopped){current=null;jobFinished(params,reschedule);}});
        },"Sidecar-audio-retention").start();return true;
    }
    @Override public boolean onStopJob(JobParameters params){Run run=current;if(run!=null)run.stopped=true;current=null;return true;}
    @Override public void onDestroy(){Run run=current;if(run!=null)run.stopped=true;current=null;main.removeCallbacksAndMessages(null);super.onDestroy();}
}

package local.sidecar.copilot;

import android.app.Instrumentation;
import android.app.job.JobScheduler;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import java.io.*;

/** Existing retention/deletion/recovery subsets. Synthetic expiry fixture, no system-clock change. */
public final class AudioRetentionChecks {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError("retention check "+checks);checks++;}
    public static int run(Instrumentation instrumentation)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");checks=0;
        Context context=instrumentation.getTargetContext();String id,expired,retained;
        context.getSystemService(JobScheduler.class).cancel(AudioRetentionJob.JOB_ID);
        try(AudioSegmentStore store=new AudioSegmentStore(context)){
            id=new MeetingSessionRepository(context,store).prepare(null,"retention fixture",true,"synthetic");
            AudioCaptureCoordinator capture=new AudioCaptureCoordinator(store,id,16000);long token=capture.start(true);
            short[] pcm=new short[160000];java.util.Arrays.fill(pcm,(short)765);
            capture.samples(token,pcm,pcm.length,SystemClock.elapsedRealtimeNanos());capture.pause();
            expired=store.frameIds(id).get(0);retained=store.frameIds(id).get(1);
            try{store.sweepExpired(()->true);throw new AssertionError("cancelled sweep");}catch(InterruptedIOException expected){checks++;}
            check(store.read(retained)[0]==765);
        }
        File index=new File(context.getNoBackupFilesDir(),"audio-segments/index.sqlite");
        // Controlled metadata fault fixture, not a claim that device time or AEAD lifetime was advanced.
        SQLiteDatabase fixture=SQLiteDatabase.openDatabase(index.getPath(),null,SQLiteDatabase.OPEN_READWRITE);
        try{fixture.execSQL("UPDATE frames SET expires_utc=0 WHERE id=?",new Object[]{expired});}finally{fixture.close();}
        SharedPreferences status=context.getSharedPreferences("audio-cleanup-status",Context.MODE_PRIVATE);status.edit().clear().commit();
        check(AudioRetentionJob.schedule(context));check(context.getSystemService(JobScheduler.class).getPendingJob(AudioRetentionJob.JOB_ID)!=null);
        String servicePermission=context.getPackageManager().getServiceInfo(new ComponentName(context,AudioRetentionJob.class),0).permission;
        check("android.permission.BIND_JOB_SERVICE".equals(servicePermission));
        try(InputStream output=new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand("cmd jobscheduler run -f local.sidecar.copilot 72"))){byte[] bytes=new byte[1024];while(output.read(bytes)!=-1){}}
        long deadline=SystemClock.elapsedRealtime()+10000;
        while(!status.contains("last_success_elapsed")&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
        check(status.contains("last_success_elapsed"));check(!status.contains("error"));
        try(AudioSegmentStore store=new AudioSegmentStore(context)){
            check(store.frameIds(id).size()==1);check(store.read(retained)[0]==765);
            try{store.read(expired);throw new AssertionError("expired returned");}catch(SecurityException expected){checks++;}
            check(store.sweepExpired(()->false)==0);
            new MeetingSessionRepository(context,store).delete(id);
        }
        SQLiteDatabase inspect=SQLiteDatabase.openDatabase(index.getPath(),null,SQLiteDatabase.OPEN_READONLY);
        try(Cursor c=inspect.rawQuery("SELECT wrapped,deleted FROM frames WHERE id=?",new String[]{expired})){check(c.moveToFirst()&&c.isNull(0)&&c.getInt(1)==1);}finally{inspect.close();}
        context.getSystemService(JobScheduler.class).cancel(AudioRetentionJob.JOB_ID);return checks;
    }
}

package local.sidecar.copilot;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Recovery projection checks using synthetic PCM. Close/reopen and fault hooks are not abrupt-kill tests. */
public final class RecoveryProjectionChecks {
    private static final String TITLE="SYNTHETIC_RECOVERY_PROJECTION";
    private static final String WARNING="恢复前尾段可能缺失，长度未知。";
    private static int checks;
    private static void check(boolean condition){if(!condition)throw new AssertionError("recovery projection "+checks);checks++;}
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError("recovery projection "+checks+": "+label);checks++;}
    private interface Action {void run()throws Exception;}
    private static void failure(Class<? extends Exception> type,Action action,String label)throws Exception {
        try{action.run();throw new AssertionError(label+" unexpectedly succeeded");}
        catch(Exception expected){if(!type.isInstance(expected))throw expected;checks++;}
    }
    private static SQLiteDatabase database(AudioSegmentStore store)throws Exception {
        Field field=AudioSegmentStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);
    }
    private static long number(SQLiteDatabase db,String sql,String... args){
        try(Cursor cursor=db.rawQuery(sql,args)){if(!cursor.moveToFirst())throw new AssertionError("missing fixture row");return cursor.getLong(0);}
    }
    /** Isolates all fixture metadata and synthetic deletion barriers from the actual UI meeting. */
    private static final class Fixture implements AutoCloseable {
        final Context context;
        final AudioSegmentStore store;
        final SQLiteDatabase db;
        final String session,stream;
        final long generation,boot;
        String hook="";
        Runnable intervention=()->{};
        int hookCalls;
        Fixture(Context base)throws Exception {
            String nonce=UUID.randomUUID().toString(),prefix="recovery-projection-"+nonce;
            File directory=new File(base.getNoBackupFilesDir(),prefix);
            if(!directory.mkdir())throw new IOException("fixture directory unavailable");
            context=new ContextWrapper(base){
                @Override public File getNoBackupFilesDir(){return directory;}
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(prefix+"."+name,mode);}
            };
            store=new AudioSegmentStore(context,stage->{if(stage.equals(hook)){hookCalls++;intervention.run();}});
            db=database(store);session=store.createSession(8000);generation=store.start(session,true);
            stream=store.stream(session);boot=store.sessionBoot(session);
            short[] pcm=new short[4000];Arrays.fill(pcm,(short)781);
            try{store.append(session,generation,new PcmFrameAssembler.Frame(0,4000,SystemClock.elapsedRealtimeNanos(),pcm,"healthy"));}
            finally{Arrays.fill(pcm,(short)0);}
        }
        void deleteBarrier(){
            if(!context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).edit().putBoolean(session,true).commit())
                throw new AssertionError("synthetic durable deletion barrier failed");
        }
        @Override public void close()throws Exception {
            try{store.close();}finally{KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("sidecar.audio."+session);}
        }
    }
    private static void projectionCompletionGuards(Context context)throws Exception {
        try(Fixture f=new Fixture(context)){
            f.store.recover();
            check(f.store.recoveryUncertainties(f.session).length()==1,"existing unknown coverage is initially readable");
            long anchorBoot=number(f.db,"SELECT boot FROM clock WHERE singleton=1");
            f.hook="recovery_projection_before_return";
            f.intervention=()->f.db.execSQL("UPDATE clock SET boot=boot+1 WHERE singleton=1");
            try{
                failure(IllegalStateException.class,()->f.store.recoveryUncertainties(f.session),"clock becomes untrusted after projection");
                check(f.hookCalls==1,"clock changes only after result construction");
                failure(IllegalStateException.class,()->f.store.recoveryUncertainties(f.session),"untrusted clock also blocks projection entry");
                check(f.hookCalls==1,"entry rejection never reaches the projection completion hook");
            }finally{f.hook="";f.db.execSQL("UPDATE clock SET boot=? WHERE singleton=1",new Object[]{anchorBoot});}
            JSONArray restored=f.store.recoveryUncertainties(f.session);
            check(restored.length()==1&&restored.getJSONObject(0).getLong("after_sample")==4000&&restored.getJSONObject(0).isNull("end_sample"),"clock refusal neither mutates nor guesses coverage");
            f.hook="recovery_projection_before_return";f.hookCalls=0;f.intervention=f::deleteBarrier;
            failure(SecurityException.class,()->f.store.recoveryUncertainties(f.session),"deletion after projection revokes the return value");
            check(f.hookCalls==1,"durable deletion arrives after result construction");
            failure(SecurityException.class,()->f.store.recoveryUncertainties(f.session),"deleted projection also fails at entry");
            check(f.hookCalls==1,"deleted entry never builds another result");
            f.hook="";f.store.purgeDeleted(f.session);
            check(number(f.db,"SELECT COUNT(*) FROM capture_controls WHERE session=?",f.session)==0,"deleted projection controls are purged");
        }
    }
    private static void finishAfterDeletion(Context context)throws Exception {
        try(Fixture f=new Fixture(context)){
            long now=SystemClock.elapsedRealtimeNanos();
            f.store.beginPause(f.session,new CaptureBoundary(UUID.randomUUID().toString(),"pause",f.stream,f.generation,f.boot,now,now,6000,6000));
            check(number(f.db,"SELECT seal_end FROM sessions WHERE id=?",f.session)==6000,"pending accepted tail exists before deletion");
            check(number(f.db,"SELECT COUNT(*) FROM gaps WHERE session=?",f.session)==0,"fixture starts without a gap");
            f.deleteBarrier();f.store.finishPause(f.session);
            check(number(f.db,"SELECT COUNT(*) FROM gaps WHERE session=?",f.session)==0,"late finish cannot recreate gap metadata after deletion");
            check(number(f.db,"SELECT seal_generation FROM sessions WHERE id=?",f.session)==-1&&number(f.db,"SELECT seal_end FROM sessions WHERE id=?",f.session)==0,"late finish still revokes sealing permission");
            f.store.purgeDeleted(f.session);f.store.recover();
            check(number(f.db,"SELECT COUNT(*) FROM capture_controls WHERE session=?",f.session)==0&&number(f.db,"SELECT COUNT(*) FROM gaps WHERE session=?",f.session)==0,"deleted pause metadata stays absent after cleanup and recovery");
        }
    }
    private static void deletionDuringRecovery(Context context)throws Exception {
        try(Fixture f=new Fixture(context)){
            check(number(f.db,"SELECT COUNT(*) FROM capture_controls WHERE session=?",f.session)==0,"recording starts without a recovery control");
            f.hook="recovery_after_file_verification";f.intervention=f::deleteBarrier;
            f.store.recover();
            check(f.hookCalls==1&&f.store.isDeleted(f.session),"deletion arrives after the initial purge and file scan");
            check("ended".equals(f.store.state(f.session)),"recovery rechecks deletion before pausing a recording");
            check(number(f.db,"SELECT COUNT(*) FROM capture_controls WHERE session=?",f.session)==0&&number(f.db,"SELECT COUNT(*) FROM gaps WHERE session=?",f.session)==0,"recovery cannot resurrect deleted controls or gaps");
            check(number(f.db,"SELECT COUNT(*) FROM frames WHERE session=? AND (deleted=0 OR wrapped IS NOT NULL)",f.session)==0,"newly deleted frame keys are revoked");
            check(number(f.db,"SELECT COUNT(*) FROM segments WHERE session=?",f.session)==0,"newly deleted segment mappings are reclaimed");
            long generation=number(f.db,"SELECT generation FROM sessions WHERE id=?",f.session);
            f.hook="";f.store.recover();
            check(number(f.db,"SELECT generation FROM sessions WHERE id=?",f.session)==generation&&number(f.db,"SELECT COUNT(*) FROM capture_controls WHERE session=?",f.session)==0,"repeated recovery is idempotent after concurrent deletion");
            failure(SecurityException.class,()->f.store.recoveryUncertainties(f.session),"recovered deleted metadata stays inaccessible");
        }
    }
    private static boolean contains(View view,String value){
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(value))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)
            if(contains(((ViewGroup)view).getChildAt(i),value))return true;
        return false;
    }
    private static boolean shows(Instrumentation instrumentation,Activity activity,String value){
        AtomicBoolean result=new AtomicBoolean();
        instrumentation.runOnMainSync(()->result.set(contains(activity.getWindow().getDecorView(),value)));
        return result.get();
    }
    private static void awaitShown(Instrumentation instrumentation,Activity activity,String value,boolean expected)throws Exception {
        long deadline=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<deadline){
            instrumentation.waitForIdleSync();
            if(shows(instrumentation,activity,value)==expected){checks++;return;}
            SystemClock.sleep(25);
        }
        throw new AssertionError("recovery projection visibility did not become "+expected);
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        checks=0;Context context=instrumentation.getTargetContext();NoteStore notes=new NoteStore(context);
        projectionCompletionGuards(context);finishAfterDeletion(context);deletionDuringRecovery(context);
        String id=null,oldStream=null;long oldGeneration=0;Activity activity=null;
        try{
            // No source adapter is created. Persist a recording state and one frame, then leave an unsealed tail.
            try(AudioSegmentStore audio=new AudioSegmentStore(context)){
                id=new MeetingSessionRepository(context,audio).prepare(null,TITLE,true,"synthetic projection source");
                AudioCaptureCoordinator capture=new AudioCaptureCoordinator(audio,id,16000);
                oldGeneration=capture.start(true);oldStream=audio.stream(id);
                short[] pcm=new short[80000];Arrays.fill(pcm,(short)1234);
                check(capture.samples(oldGeneration,pcm,pcm.length,SystemClock.elapsedRealtimeNanos()));
                check(capture.samples(oldGeneration,pcm,1000,SystemClock.elapsedRealtimeNanos()));
                check(audio.committedSamples(id)==80000);
                // Intentionally no capture.pause(): exercises recovery projection, not power-loss durability.
            }
            try(AudioSegmentStore audio=new AudioSegmentStore(context)){
                check(audio.state(id).equals("paused"));
                JSONArray uncertainties=audio.recoveryUncertainties(id);check(uncertainties.length()==1);
                JSONObject prior=uncertainties.getJSONObject(0);
                check(prior.getString("stream").equals(oldStream)&&prior.getLong("generation")==oldGeneration);
                check(prior.getLong("after_sample")==80000&&prior.isNull("end_sample"));
                check(prior.getString("reason").equals("recovery_tail_unknown")&&prior.getLong("boot")>=0);
                // A new explicitly approved stream cannot overwrite the prior unknown recovery tail.
                new MeetingSessionRepository(context,audio).prepare(id,TITLE,true,"synthetic projection source");
                AudioCaptureCoordinator resumed=new AudioCaptureCoordinator(audio,id,16000);
                resumed.start(true);check(!audio.stream(id).equals(oldStream));resumed.pause();
                JSONArray retained=audio.recoveryUncertainties(id);
                check(retained.length()==1&&retained.getJSONObject(0).getString("stream").equals(oldStream));
            }
            activity=instrumentation.startActivitySync(new Intent(context,RecordingActivity.class)
                .putExtra("meeting",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitShown(instrumentation,activity,TITLE,true);awaitShown(instrumentation,activity,WARNING,true);
            check(CaptureService.snapshot(id)==null);
            check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty());
            notes.delete(id);
            awaitShown(instrumentation,activity,TITLE,false);awaitShown(instrumentation,activity,WARNING,false);
            try(AudioSegmentStore audio=new AudioSegmentStore(context)){
                try{audio.recoveryUncertainties(id);throw new AssertionError("deleted recovery metadata visible");}
                catch(SecurityException expected){checks++;}
                audio.purgeDeleted(id);
            }
            return checks;
        }finally{
            Activity screen=activity;if(screen!=null)instrumentation.runOnMainSync(screen::finish);
            if(id!=null){notes.delete(id);try(AudioSegmentStore audio=new AudioSegmentStore(context)){audio.purgeDeleted(id);}}
        }
    }
}

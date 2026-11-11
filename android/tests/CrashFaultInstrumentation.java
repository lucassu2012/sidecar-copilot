package local.sidecar.copilot;

import android.app.Instrumentation;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test APK only. Abruptly kills its own synthetic emulator process, with no finally/close path. */
public final class CrashFaultInstrumentation extends Instrumentation {
    private Bundle args;
    private String scenario,phase,sid,stream;
    private long generation;
    private int checks;
    private Context scope;
    private SharedPreferences evidence;
    private final AtomicBoolean once=new AtomicBoolean();
    private final CountDownLatch writeEntered=new CountDownLatch(1),writeRelease=new CountDownLatch(1);
    private static final Set<String> CASES=new HashSet<>(Arrays.asList(
        "before_first_frame","memory_tail","after_fsync","before_index_commit","after_pause_barrier",
        "after_pause_finish","deleted","recovery_transaction","overflow_pause_barrier","legacy_active"));
    @Override public void onCreate(Bundle bundle){super.onCreate(bundle);args=bundle;start();}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private void checkpoint(String name){
        if(!evidence.edit().putString("checkpoint",name).commit())throw new IllegalStateException("checkpoint persistence");
        Bundle status=new Bundle();status.putString("stream","CRASH_CHECKPOINT "+scenario+" "+name+"\n");sendStatus(2,status);
        android.os.Process.killProcess(android.os.Process.myPid());
        for(;;)SystemClock.sleep(1000); // Never unwind store/coordinator finally blocks.
    }
    private void fault(String stage)throws IOException {
        if(phase.equals("recover-crash")&&stage.equals("before_recovery_commit"))checkpoint("recovery_transaction");
        if(!phase.equals("crash"))return;
        if((scenario.equals(stage)||scenario.equals("after_pause_barrier")&&stage.equals("control_after_commit"))&&once.compareAndSet(false,true))checkpoint(scenario);
        if((scenario.equals("after_pause_finish")||scenario.equals("overflow_pause_barrier"))&&stage.equals("after_fsync")&&once.compareAndSet(false,true)){
            writeEntered.countDown();
            try{if(!writeRelease.await(20,TimeUnit.SECONDS))throw new IOException("overflow fixture release timeout");}
            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
        }
        if(scenario.equals("after_pause_finish")&&stage.equals("after_pause_finish"))checkpoint(stage);
        if(scenario.equals("overflow_pause_barrier")&&stage.equals("control_after_commit"))checkpoint("after_pause_barrier");
    }
    private BoundedPcmQueue queue(AudioCaptureCoordinator capture)throws Exception {
        Field field=AudioCaptureCoordinator.class.getDeclaredField("queue");field.setAccessible(true);
        return (BoundedPcmQueue)field.get(capture);
    }
    private void drained(BoundedPcmQueue queue)throws Exception {
        long deadline=SystemClock.elapsedRealtime()+10000;
        while(queue.outstandingSamples()!=0&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(1);
        check(queue.outstandingSamples()==0,"callback not consumed");
    }
    private void offer(AudioCaptureCoordinator capture,long token,short[] pcm)throws Exception {
        check(capture.samples(token,pcm,pcm.length,SystemClock.elapsedRealtimeNanos()),"unexpected callback rejection");
    }
    private void crash()throws Exception {
        AudioSegmentStore store=new AudioSegmentStore(scope,this::fault);
        sid=store.createSession(8000);
        AudioCaptureCoordinator capture=new AudioCaptureCoordinator(store,sid,8000);
        capture.setInputStop(()->{}); // No physical input exists in this harness.
        long token=capture.startBuffered(true);generation=token;stream=store.stream(sid);
        check(evidence.edit().putString("session",sid).putString("stream",stream).putLong("generation",token)
            .putString("checkpoint","").commit(),"persist test identity");
        short[] pcm=new short[800];Arrays.fill(pcm,(short)1234);BoundedPcmQueue queue=queue(capture);
        if(scenario.equals("before_first_frame")||scenario.equals("recovery_transaction")||scenario.equals("legacy_active")){
            offer(capture,token,pcm);drained(queue);
            if(scenario.equals("legacy_active"))try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(scope.getNoBackupFilesDir(),"audio-segments/index.sqlite").getPath(),null,SQLiteDatabase.OPEN_READWRITE)){
                db.execSQL("DROP TABLE capture_controls"); // Simulate pre-migration active database, not media loss.
            }
            checkpoint("volatile_tail");
        }
        for(int i=0;i<49;i++){offer(capture,token,pcm);drained(queue);}
        offer(capture,token,pcm);
        if(scenario.equals("after_fsync")||scenario.equals("before_index_commit")){
            // Consumer will kill this process at the named persistence boundary.
            SystemClock.sleep(15000);throw new AssertionError("crash hook not reached");
        }
        if(scenario.equals("after_pause_finish")||scenario.equals("overflow_pause_barrier")){
            check(writeEntered.await(10,TimeUnit.SECONDS),"writer not blocked");
            for(int i=0;i<19;i++)offer(capture,token,pcm);
            check(!capture.samples(token,pcm,800,SystemClock.elapsedRealtimeNanos()),"overflow not rejected");
            writeRelease.countDown();SystemClock.sleep(15000);throw new AssertionError("pause finish crash not reached");
        }
        drained(queue);check(store.committedSamples(sid)==40000,"committed prefix absent");
        offer(capture,token,pcm);drained(queue);
        if(scenario.equals("after_pause_barrier")){
            capture.pause();throw new AssertionError("pause crash not reached");
        }
        if(scenario.equals("deleted")){
            check(scope.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).edit().putBoolean(sid,true).commit(),"deletion barrier");
            checkpoint("deleted_before_close");
        }
        checkpoint("committed_prefix_volatile_tail");
    }
    private long count(SQLiteDatabase db,String table)throws Exception {
        try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM "+table+" WHERE session=?"+(table.equals("capture_controls")?" AND kind='recovery'":""),new String[]{sid})){c.moveToFirst();return c.getLong(0);}
    }
    private void verify()throws Exception {
        sid=evidence.getString("session",null);stream=evidence.getString("stream",null);generation=evidence.getLong("generation",-1);
        check(sid!=null&&stream!=null&&generation>0&&!evidence.getString("checkpoint","").isEmpty(),"missing crash evidence");
        String expectedCheckpoint=scenario.equals("recovery_transaction")?(phase.equals("recover-crash")?"volatile_tail":"recovery_transaction"):
            scenario.equals("before_first_frame")||scenario.equals("legacy_active")?"volatile_tail":
            scenario.equals("memory_tail")?"committed_prefix_volatile_tail":scenario.equals("deleted")?"deleted_before_close":
            scenario.equals("overflow_pause_barrier")?"after_pause_barrier":scenario;
        check(evidence.getString("checkpoint","").equals(expectedCheckpoint),"wrong crash checkpoint");
        if(scenario.equals("recovery_transaction")&&phase.equals("verify")){
            // Inspect BEFORE constructor recovery can hide a partial transaction.
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(scope.getNoBackupFilesDir(),"audio-segments/index.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY);
                Cursor c=db.rawQuery("SELECT state,generation FROM sessions WHERE id=?",new String[]{sid})){
                check(c.moveToFirst()&&c.getString(0).equals("recording")&&c.getLong(1)==generation,"recovery transaction did not roll back");
                check(count(db,"capture_controls")==0,"marker survived uncommitted recovery");
            }
        }
        boolean known=scenario.equals("after_pause_barrier")||scenario.equals("after_pause_finish")||scenario.equals("overflow_pause_barrier"),deleted=scenario.equals("deleted");
        long expected=scenario.equals("memory_tail")||scenario.equals("after_pause_barrier")||scenario.equals("overflow_pause_barrier")?40000:
            scenario.equals("after_pause_finish")?55200:0;
        try(AudioSegmentStore store=new AudioSegmentStore(scope,this::fault)){
            if(deleted){
                check(store.state(sid).equals("ended"),"deleted session not ended");
                try{store.recoveryUncertainties(sid);throw new AssertionError("deleted marker exposed");}catch(SecurityException correct){checks++;}
            }else{
                check(store.state(sid).equals("paused"),"automatic recording after crash");
                check(store.committedSamples(sid)==expected,"wrong committed prefix");
                for(String frame:store.frameIds(sid)){
                    short[] pcm=store.read(frame);check(pcm.length>0&&pcm[0]==1234&&pcm[pcm.length-1]==1234,"recovered waveform changed");Arrays.fill(pcm,(short)0);
                }
                JSONArray markers=store.recoveryUncertainties(sid);
                check(markers.length()==(known?0:1),"unknown marker count");
                if(!known){JSONObject marker=markers.getJSONObject(0);
                    check(marker.getString("stream").equals(stream)&&marker.getLong("generation")==generation,"old stream identity lost");
                    check(marker.getLong("after_sample")==expected&&marker.isNull("end_sample"),"invented loss endpoint");
                    check(marker.getString("reason").equals("recovery_tail_unknown"),"unknown reason lost");
                }
            }
            store.recover();store.recover();
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(scope.getNoBackupFilesDir(),"audio-segments/index.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                check(count(db,"capture_controls")==(!known&&!deleted?1:0),"duplicate/deleted recovery marker");
                if(known)try(Cursor c=db.rawQuery("SELECT start_sample,end_sample,reason FROM gaps WHERE session=? ORDER BY start_sample",new String[]{sid})){
                    long from=scenario.equals("after_pause_finish")?55200:40000,to=scenario.equals("after_pause_finish")?56000:scenario.equals("overflow_pause_barrier")?55200:40800;
                    check(c.moveToFirst()&&c.getLong(0)==from&&c.getLong(1)==to,"known tail boundaries lost");
                    check(c.getString(2).equals(scenario.equals("after_pause_finish")?"pcm_backpressure":"uncommitted_tail"),"known reason lost");
                    if(scenario.equals("overflow_pause_barrier"))check(c.moveToNext()&&c.getLong(0)==55200&&c.getLong(1)==56000&&c.getString(2).equals("pcm_backpressure"),"overflow reason missing at pause barrier");
                    check(!c.moveToNext(),"known gap duplicated");
                }
                if(deleted){check(count(db,"gaps")==0,"deleted gap restored");}
                try(Cursor c=db.rawQuery("SELECT id,committed FROM segments WHERE session=?",new String[]{sid})){
                    while(c.moveToNext())check(new File(scope.getNoBackupFilesDir(),"audio-segments/"+c.getString(0)+".seg").length()==c.getLong(1),"orphan tail retained");
                }
            }
        }
        try(AudioSegmentStore store=new AudioSegmentStore(scope)){
            if(!deleted){
                check(store.recoveryUncertainties(sid).length()==(known?0:1),"marker lost on reopen");
                long next=store.start(sid,true);check(!store.stream(sid).equals(stream),"stream reused");
                store.beginPause(sid,next,0);store.finishPause(sid);
                check(store.recoveryUncertainties(sid).length()==(known?0:1),"new stream erased old uncertainty");
                scope.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).edit().putBoolean(sid,true).commit();
                store.purgeDeleted(sid);store.recover();
                try{store.recoveryUncertainties(sid);throw new AssertionError("deleted coverage visible");}catch(SecurityException correct){checks++;}
            }
        }
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        try{
            if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator only");
            Context base=getTargetContext();
            if(!base.getPackageName().equals("local.sidecar.copilot"))throw new SecurityException("development target only");
            scenario=args.getString("scenario","");phase=args.getString("phase","");
            if(!CASES.contains(scenario)||!Arrays.asList("crash","verify","recover-crash").contains(phase))throw new IllegalArgumentException("scenario/phase");
            if(!CaptureEnvironment.unlocked(base))throw new SecurityException("authenticate and unlock emulator");
            evidence=base.getSharedPreferences("synthetic-crash-"+scenario,Context.MODE_PRIVATE);
            if(phase.equals("crash"))check(evidence.edit().putString("root","crash-"+scenario+"-"+UUID.randomUUID()).commit(),"fixture namespace");
            String name=evidence.getString("root",null);if(name==null||!name.matches("crash-[a-z_]+-[0-9a-f-]{36}"))throw new IllegalStateException("fixture namespace missing");
            File root=new File(base.getNoBackupFilesDir(),name);if(!root.exists()&&!root.mkdirs())throw new IOException("fixture namespace");
            scope=new ContextWrapper(base){@Override public File getNoBackupFilesDir(){return root;}};
            if(phase.equals("crash"))crash();else verify();
            result.putString("stream","PASS "+scenario+": "+checks+" crash recovery checks; synthetic abrupt process termination only\n");finish(-1,result);
        }catch(Throwable error){result.putString("stream","FAILED "+scenario+": "+error+"\n");finish(0,result);}
    }
}

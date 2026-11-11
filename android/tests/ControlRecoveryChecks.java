package local.sidecar.copilot;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

/** Existing stop/recovery requirements, using synthetic PCM only. Exception injection is not power loss.
 * The separate abrupt entry points retain an unsealed RAM tail until the host explicitly force-stops
 * the disposable target application; they never start a microphone, speaker or service.
 */
public final class ControlRecoveryChecks {
    private static int checks;
    private static final String ABRUPT_PREFS="control-recovery-abrupt-fixture";
    private static volatile AudioSegmentStore abruptStore;
    private static volatile AudioCaptureCoordinator abruptCapture;
    private interface Action {void run()throws Exception;}
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError("control recovery "+checks+": "+label);checks++;}
    private static void failure(Class<? extends Exception> type,Action action,String label)throws Exception {
        try{action.run();throw new AssertionError(label+" unexpectedly succeeded");}
        catch(Exception expected){if(!type.isInstance(expected))throw expected;checks++;}
    }
    private static void emulatorOnly(){if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");}
    private static SQLiteDatabase database(AudioSegmentStore store)throws Exception {
        Field field=AudioSegmentStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);
    }
    private static long number(SQLiteDatabase db,String sql,String... args){
        try(Cursor cursor=db.rawQuery(sql,args)){if(!cursor.moveToFirst())throw new AssertionError("missing numeric fixture row");return cursor.getLong(0);}
    }
    private static String text(SQLiteDatabase db,String sql,String... args){
        try(Cursor cursor=db.rawQuery(sql,args)){if(!cursor.moveToFirst())throw new AssertionError("missing text fixture row");return cursor.getString(0);}
    }
    private static Context isolated(Context base,File directory,String prefix){
        return new ContextWrapper(base){
            @Override public File getNoBackupFilesDir(){return directory;}
            @Override public SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(prefix+"."+name,mode);}
        };
    }
    private static void destroyKey(String session)throws Exception {
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("sidecar.audio."+session);
    }
    private static final class Fixture implements AutoCloseable {
        final Context context;
        final String session,stream;
        final long generation,boot;
        AudioSegmentStore store;
        SQLiteDatabase db;
        String failAt="";
        final String frame;
        Fixture(Context base)throws Exception {
            String nonce=UUID.randomUUID().toString();File directory=new File(base.getNoBackupFilesDir(),"control-recovery-"+nonce);
            if(!directory.mkdir())throw new IOException("fixture directory unavailable");
            context=isolated(base,directory,"control-recovery-"+nonce);
            store=new AudioSegmentStore(context,stage->{if(stage.equals(failAt))throw new IOException("synthetic "+stage);});db=database(store);
            session=store.createSession(8000);generation=store.start(session,true);stream=store.stream(session);boot=store.sessionBoot(session);
            short[] pcm=new short[4000];Arrays.fill(pcm,(short)913);
            try{frame=store.append(session,generation,new PcmFrameAssembler.Frame(0,4000,SystemClock.elapsedRealtimeNanos(),pcm,"healthy"));}
            finally{Arrays.fill(pcm,(short)0);}
        }
        CaptureBoundary boundary(long accepted,long observed){
            long now=SystemClock.elapsedRealtimeNanos();
            return new CaptureBoundary(UUID.randomUUID().toString(),observed>accepted?"pcm_backpressure":"pause",stream,generation,boot,now,now,accepted,observed);
        }
        long sessionGeneration(){return number(db,"SELECT generation FROM sessions WHERE id=?",session);}
        void reopen()throws Exception {store.close();failAt="";store=new AudioSegmentStore(context);db=database(store);}
        boolean prefixMatches()throws Exception {
            short[] pcm=store.read(frame);try{return pcm.length==4000&&pcm[0]==913&&pcm[pcm.length-1]==913;}finally{Arrays.fill(pcm,(short)0);}
        }
        @Override public void close()throws Exception {try{store.close();}finally{destroyKey(session);}}
    }
    private static void assertUnknown(SQLiteDatabase db,String session,long prefix)throws Exception {
        check(number(db,"SELECT COUNT(*) FROM capture_controls WHERE session=?",session)==1,"one control per recovered stream");
        try(Cursor c=db.rawQuery("SELECT kind,unknown_tail_from_sample,requested_ns,accepted_ns,accepted_end,observed_end FROM capture_controls WHERE session=?",new String[]{session})){
            check(c.moveToFirst()&&"recovery".equals(c.getString(0)),"recovery reason is explicit");
            check(c.getLong(1)==prefix,"unknown coverage begins after the committed prefix");
            check(c.isNull(2)&&c.isNull(3)&&c.isNull(4)&&c.isNull(5),"unknown times and tail length are never fabricated");
        }
        check(number(db,"SELECT COUNT(*) FROM gaps WHERE session=?",session)==0,"unknown RAM loss is not converted to a guessed exact gap");
    }
    private static void beforeControlCommit(Context context)throws Exception {
        try(Fixture f=new Fixture(context)){
            CaptureBoundary boundary=f.boundary(6000,6400);f.failAt="control_before_commit";
            failure(IOException.class,()->f.store.beginPause(f.session,boundary),"control transaction rollback");f.failAt="";
            check("recording".equals(f.store.state(f.session)),"failed control leaves original recording state for recovery");
            check(f.sessionGeneration()==f.generation,"failed transaction does not advance generation");
            check(number(f.db,"SELECT COUNT(*) FROM capture_controls")==0&&number(f.db,"SELECT COUNT(*) FROM gaps")==0,"no partial control or overflow gap");
            check(number(f.db,"SELECT seal_generation FROM sessions WHERE id=?",f.session)==-1,"failed pause does not expose a sealing permission");
            f.reopen();
            check("paused".equals(f.store.state(f.session)),"reopen pauses the lost recording without starting input");
            assertUnknown(f.db,f.session,4000);
            check(f.prefixMatches(),"committed prefix survives the failed control transaction");
            long generation=f.sessionGeneration();f.store.recover();
            check(f.sessionGeneration()==generation&&number(f.db,"SELECT COUNT(*) FROM capture_controls")==1,"unknown recovery is idempotent");
        }
    }
    private static void afterControlCommit(Context context)throws Exception {
        try(Fixture f=new Fixture(context)){
            CaptureBoundary boundary=f.boundary(6000,6400);f.failAt="control_after_commit";
            failure(IOException.class,()->f.store.beginPause(f.session,boundary),"error after committed control");f.failAt="";
            check("paused".equals(f.store.state(f.session))&&f.sessionGeneration()==f.generation+1,"control and pause commit together");
            check(number(f.db,"SELECT seal_end FROM sessions WHERE id=?",f.session)==6000,"accepted watermark persists before final sealing");
            check(number(f.db,"SELECT COUNT(*) FROM capture_controls")==1,"control survives post-commit failure");
            check(number(f.db,"SELECT COUNT(*) FROM gaps WHERE reason='pcm_backpressure' AND start_sample=6000 AND end_sample=6400")==1,"known rejected range commits with pause");
            f.reopen();
            check(number(f.db,"SELECT COUNT(*) FROM gaps WHERE reason='uncommitted_tail' AND start_sample=4000 AND end_sample=6000")==1,"recovery adds exact uncommitted accepted tail");
            check(number(f.db,"SELECT COUNT(*) FROM gaps")==2&&number(f.db,"SELECT SUM(end_sample-start_sample) FROM gaps")==2400,"accepted loss and rejected range are disjoint and complete");
            check(number(f.db,"SELECT unknown_tail_from_sample IS NULL FROM capture_controls")==1,"durable known boundary is not replaced with unknown coverage");
            check(f.prefixMatches(),"post-commit recovery preserves legal committed audio");
            f.store.recover();f.reopen();
            check(number(f.db,"SELECT COUNT(*) FROM gaps")==2&&number(f.db,"SELECT COUNT(*) FROM capture_controls")==1,"repeated known recovery does not duplicate gaps");
        }
    }
    private static void duplicateAndStaleControls(Context context)throws Exception {
        try(Fixture f=new Fixture(context)){
            CaptureBoundary boundary=f.boundary(4000,4400);f.store.beginPause(f.session,boundary);
            long generation=f.sessionGeneration();f.store.beginPause(f.session,boundary);
            check(f.sessionGeneration()==generation&&number(f.db,"SELECT COUNT(*) FROM gaps")==1,"duplicate control before finish is idempotent");
            f.store.finishPause(f.session);f.store.beginPause(f.session,boundary);
            check(f.sessionGeneration()==generation&&number(f.db,"SELECT seal_generation FROM sessions WHERE id=?",f.session)==-1,"duplicate control after finish does not reopen sealing");
            CaptureBoundary differentEvent=new CaptureBoundary(UUID.randomUUID().toString(),boundary.kind,boundary.stream,boundary.generation,boundary.boot,boundary.requestedNs,boundary.acceptedNs,boundary.acceptedEnd,boundary.observedEnd);
            failure(SecurityException.class,()->f.store.beginPause(f.session,differentEvent),"conflicting event for closed stream");
            CaptureBoundary changedRange=new CaptureBoundary(boundary.eventId,boundary.kind,boundary.stream,boundary.generation,boundary.boot,boundary.requestedNs,boundary.acceptedNs,boundary.acceptedEnd,boundary.observedEnd+1);
            failure(SecurityException.class,()->f.store.beginPause(f.session,changedRange),"conflicting range for repeated event");
            long next=f.store.start(f.session,true);String newStream=f.store.stream(f.session);
            check(!newStream.equals(f.stream)&&next>generation,"explicit restart owns a new stream and generation");
            failure(SecurityException.class,()->f.store.beginPause(f.session,boundary),"old stream cannot close a new recording");
            check("recording".equals(f.store.state(f.session))&&f.sessionGeneration()==next,"rejected stale control leaves new source admission unchanged");
            check(number(f.db,"SELECT COUNT(*) FROM capture_controls")==1&&number(f.db,"SELECT COUNT(*) FROM gaps")==1,"stale control cannot duplicate old coverage or retag it");
            f.store.beginPause(f.session,next,0);f.store.finishPause(f.session);
        }
    }
    private static void endCoverage(Context context)throws Exception {
        try(Fixture f=new Fixture(context)){
            CaptureBoundary boundary=f.boundary(4000,4400);f.store.beginPause(f.session,boundary);f.store.finishPause(f.session);
            f.store.end(f.session,SystemClock.elapsedRealtimeNanos());
            check(f.store.endMetadata(f.session).getLong("sample_end")==4400,"ended coverage contains observed rejected tail");
            check(f.store.committedSamples(f.session)==4000,"coverage is not counted as committed audio");
            check(number(f.db,"SELECT COUNT(*) FROM gaps WHERE start_sample=4000 AND end_sample=4400 AND reason='pcm_backpressure'")==1,"ended gap remains visible as a range");
            String event=text(f.db,"SELECT event_id FROM capture_controls WHERE session=?",f.session);f.reopen();
            check("ended".equals(f.store.state(f.session))&&f.store.endMetadata(f.session).getLong("sample_end")==4400,"end boundary survives reopen");
            check(event.equals(text(f.db,"SELECT event_id FROM capture_controls WHERE session=?",f.session)),"reopen preserves the original control identity");
            check(f.prefixMatches()&&f.store.committedSamples(f.session)==4000,"reopen preserves the separate durable sample count");
        }
    }
    private static void deletedControls(Context context)throws Exception {
        try(Fixture f=new Fixture(context)){
            CaptureBoundary boundary=f.boundary(4000,4400);f.store.beginPause(f.session,boundary);f.store.finishPause(f.session);
            check(f.context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).edit().putBoolean(f.session,true).commit(),"synthetic durable whole-meeting barrier commits");
            f.store.purgeDeleted(f.session);
            check(number(f.db,"SELECT COUNT(*) FROM capture_controls WHERE session=?",f.session)==0,"whole-meeting cleanup removes control metadata");
            long generation=f.sessionGeneration();
            failure(SecurityException.class,()->f.store.beginPause(f.session,boundary),"late control after whole-meeting deletion");
            check(number(f.db,"SELECT COUNT(*) FROM capture_controls WHERE session=?",f.session)==0,"late callback cannot recreate controls");
            f.store.purgeDeleted(f.session);f.reopen();
            check(f.sessionGeneration()==generation&&number(f.db,"SELECT COUNT(*) FROM capture_controls WHERE session=?",f.session)==0,"repeat deletion and recovery are idempotent");
            failure(SecurityException.class,()->f.store.read(f.frame),"deleted prefix remains inaccessible");
        }
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        emulatorOnly();checks=0;Context context=instrumentation.getTargetContext();
        beforeControlCommit(context);afterControlCommit(context);duplicateAndStaleControls(context);endCoverage(context);deletedControls(context);
        return checks;
    }
    /** Host must wait for CONTROL_ABRUPT_READY then force-stop only this disposable target app. */
    public static void prepareAbrupt(Instrumentation instrumentation)throws Exception {
        emulatorOnly();Context base=instrumentation.getTargetContext();SharedPreferences marker=base.getSharedPreferences(ABRUPT_PREFS,Context.MODE_PRIVATE);
        if(marker.contains("directory")&&!marker.getBoolean("verified",false))throw new IllegalStateException("an abrupt fixture is already awaiting verification");
        String nonce=UUID.randomUUID().toString(),name="control-abrupt-"+nonce,prefix="control-abrupt-"+nonce;
        File directory=new File(base.getNoBackupFilesDir(),name);if(!directory.mkdir())throw new IOException("abrupt fixture directory unavailable");
        Context context=isolated(base,directory,prefix);abruptStore=new AudioSegmentStore(context);
        String session=abruptStore.createSession(8000);abruptCapture=new AudioCaptureCoordinator(abruptStore,session,8000);long token=abruptCapture.start(true);
        short[] pcm=new short[40000];Arrays.fill(pcm,(short)1217);
        try{abruptCapture.samples(token,pcm,40000,SystemClock.elapsedRealtimeNanos());abruptCapture.samples(token,pcm,1000,SystemClock.elapsedRealtimeNanos());}
        finally{Arrays.fill(pcm,(short)0);}
        String frame=abruptStore.frameIds(session).get(0);
        if(!marker.edit().clear().putString("directory",name).putString("prefix",prefix).putString("session",session).putString("frame",frame).putBoolean("verified",false).commit())
            throw new IOException("abrupt fixture identity could not be persisted");
        Bundle ready=new Bundle();ready.putString("stream","CONTROL_ABRUPT_READY: committed prefix plus synthetic unsealed RAM tail; awaiting host force-stop\n");ready.putString("control_abrupt","ready");
        instrumentation.sendStatus(1,ready);
        new CountDownLatch(1).await();
        throw new AssertionError("abrupt fixture must be terminated by the host, never gracefully sealed");
    }
    public static int verifyAbrupt(Instrumentation instrumentation)throws Exception {
        emulatorOnly();checks=0;Context base=instrumentation.getTargetContext();SharedPreferences marker=base.getSharedPreferences(ABRUPT_PREFS,Context.MODE_PRIVATE);
        String name=marker.getString("directory",null),prefix=marker.getString("prefix",null),session=marker.getString("session",null),frame=marker.getString("frame",null);
        if(name==null||prefix==null||session==null||frame==null||!name.matches("control-abrupt-[0-9a-f-]{36}"))throw new IllegalStateException("abrupt fixture identity unavailable");
        Context context=isolated(base,new File(base.getNoBackupFilesDir(),name),prefix);
        try(AudioSegmentStore store=new AudioSegmentStore(context)){
            SQLiteDatabase db=database(store);
            check("paused".equals(store.state(session)),"force-stopped recording recovers paused without input");
            assertUnknown(db,session,40000);
            check(number(db,"SELECT COUNT(*) FROM frames WHERE session=? AND deleted=0",session)==1,"only previously committed frame survived");
            short[] pcm=store.read(frame);try{check(pcm.length==40000&&pcm[0]==1217&&pcm[pcm.length-1]==1217,"committed encrypted prefix remains readable");}finally{Arrays.fill(pcm,(short)0);}
            long generation=number(db,"SELECT generation FROM sessions WHERE id=?",session);store.recover();
            check(number(db,"SELECT generation FROM sessions WHERE id=?",session)==generation&&number(db,"SELECT COUNT(*) FROM capture_controls WHERE session=?",session)==1,"repeat abrupt recovery does not change coverage or generation");
        }
        check(marker.edit().putBoolean("verified",true).commit(),"host-kill fixture verification recorded");
        destroyKey(session);
        return checks;
    }
}

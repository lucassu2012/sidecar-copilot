package local.sidecar.copilot;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Actual SQLite queries on isolated synthetic data; no WAL-byte erasure or power-loss claim. */
public final class WalConfigurationChecks {
    private static final String GUARD="fixture_writer_configuration_required";
    private static int checks;
    private interface Work {void run()throws Exception;}
    private static void check(boolean value,String label){if(!value)throw new AssertionError("WAL configuration "+checks+": "+label);checks++;}
    private static long scalar(SQLiteDatabase db,String sql,String... args){
        try(Cursor c=db.rawQuery(sql,args)){
            if(!c.moveToFirst())throw new AssertionError("missing fixture scalar");long value=c.getLong(0);
            if(c.moveToNext())throw new AssertionError("multiple fixture scalar rows");return value;
        }
    }
    private static String text(SQLiteDatabase db,String sql){
        try(Cursor c=db.rawQuery(sql,null)){if(!c.moveToFirst())throw new AssertionError("missing fixture text");return c.getString(0);}
    }
    private static SQLiteDatabase database(AudioSegmentStore store)throws Exception {
        // Access only the actual database handle, never internal connection/configuration fields.
        Field field=AudioSegmentStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);
    }
    private static long writerScalar(SQLiteDatabase db,String pragma){
        db.beginTransaction();try{return scalar(db,pragma);}finally{db.endTransaction();}
    }
    private static void disableSecureWriter(SQLiteDatabase db){
        check(!db.inTransaction(),"fixture disables writer only outside production transaction");
        db.beginTransaction();try{
            check(scalar(db,"PRAGMA secure_delete=OFF")==0,"actual transaction-bound writer accepts OFF control");
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private static void rejectsSql(Work work,String token,String label)throws Exception {
        try{work.run();throw new AssertionError(label+" unexpectedly succeeded");}
        catch(SQLiteException failure){if(failure.getMessage()==null||!failure.getMessage().contains(token))throw failure;checks++;}
    }
    private static Throwable cleanupFailure(Throwable first,Throwable next){
        if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;
    }
    private static void rethrow(Throwable failure)throws Exception {
        if(failure instanceof Exception)throw (Exception)failure;
        if(failure instanceof Error)throw (Error)failure;
        if(failure!=null)throw new AssertionError(failure);
    }
    private static final class Scope extends ContextWrapper {
        final String prefix="wal-configuration-"+UUID.randomUUID()+"-";
        final File directory;
        final Set<String> preferences=new HashSet<>();
        Scope(Context base)throws IOException {
            super(base);directory=new File(base.getNoBackupFilesDir(),prefix);
            if(!directory.mkdir())throw new IOException("WAL fixture directory unavailable");
        }
        @Override public File getNoBackupFilesDir(){return directory;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            String scoped=prefix+name;preferences.add(scoped);return getBaseContext().getSharedPreferences(scoped,mode);
        }
        void dispose()throws IOException {
            for(String name:preferences)if(!getBaseContext().deleteSharedPreferences(name))throw new IOException("WAL fixture preferences cleanup failed");
            File audio=new File(directory,"audio-segments");File[] files=audio.listFiles();
            if(files!=null)for(File file:files){
                if(!file.isFile()||!file.getCanonicalFile().getParentFile().equals(audio.getCanonicalFile())||!file.delete())
                    throw new IOException("WAL fixture flat file cleanup failed");
            }
            if(audio.exists()&&!audio.delete())throw new IOException("WAL fixture directory cleanup failed");
            if(!directory.delete())throw new IOException("WAL fixture scope cleanup failed");
        }
    }
    private static final class Frames {
        final String session,stream;
        final List<String> ids;
        final short first;
        Frames(String session,String stream,List<String> ids,short first){this.session=session;this.stream=stream;this.ids=ids;this.first=first;}
    }
    private static final class Fixture implements AutoCloseable {
        final Scope context;
        final Set<String> sessions=new LinkedHashSet<>();
        AudioSegmentStore store;
        SQLiteDatabase db;
        Frames frames;
        String failStage="",observeStage="",disableStage="";
        IOException injected;
        int observations,disables;
        Fixture(Context base)throws Exception {
            context=new Scope(base);
            try{open();frames=append(3,(short)1401);}catch(Exception|Error failure){try{close();}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        void at(String stage)throws IOException {
            if(stage.equals(observeStage)){
                check(db.inTransaction(),"observed revocation stage remains inside writer transaction");
                check(scalar(db,"PRAGMA secure_delete")==1&&scalar(db,"PRAGMA synchronous")==2,"actual revocation writer has secure deletion and FULL sync");observations++;
            }
            if(stage.equals(disableStage)){disableStage="";disableSecureWriter(db);disables++;}
            if(stage.equals(failStage))throw injected;
        }
        void open()throws Exception {store=new AudioSegmentStore(context,this::at);db=database(store);}
        void closeStore()throws Exception {if(store!=null){store.close();store=null;db=null;}}
        Frames append(int count,short first)throws Exception {
            String session=store.createSession(8000);sessions.add(session);long generation=store.start(session,true);
            String stream=store.stream(session);List<String> ids=new ArrayList<>();
            for(int i=0;i<count;i++){
                short[] pcm=new short[40000];Arrays.fill(pcm,(short)(first+i));
                try{ids.add(store.append(session,generation,new PcmFrameAssembler.Frame(i*40000L,(i+1)*40000L,SystemClock.elapsedRealtimeNanos(),pcm,"healthy")));}
                finally{Arrays.fill(pcm,(short)0);}
            }
            store.beginPause(session,generation,count*40000L);store.finishPause(session);return new Frames(session,stream,ids,first);
        }
        boolean revoked(int index){return scalar(db,"SELECT deleted=1 AND wrapped IS NULL FROM frames WHERE id=?",frames.ids.get(index))==1;}
        boolean live(int index){return scalar(db,"SELECT deleted=0 AND wrapped IS NOT NULL FROM frames WHERE id=?",frames.ids.get(index))==1;}
        void matches(Frames value,int index)throws Exception {
            short[] pcm=store.read(value.ids.get(index));try{
                check(pcm.length==40000,"retained neighbor has complete samples");
                for(short sample:pcm)if(sample!=(short)(value.first+index))throw new AssertionError("retained neighbor PCM changed");checks++;
            }finally{Arrays.fill(pcm,(short)0);}
        }
        void guards(){
            // A real trigger queries the executing writer, not a later unrelated pool connection.
            db.execSQL("CREATE TABLE fixture_writer_observations(kind TEXT,secure_value INTEGER,sync_value INTEGER)");
            String require=" SELECT CASE WHEN (SELECT * FROM pragma_secure_delete)!=1 OR (SELECT * FROM pragma_synchronous)!=2 THEN RAISE(ABORT,'"+GUARD+"') END; ";
            db.execSQL("CREATE TRIGGER fixture_wrapped_guard BEFORE UPDATE OF wrapped ON frames WHEN OLD.wrapped IS NOT NULL AND NEW.wrapped IS NULL BEGIN"+require+
                "INSERT INTO fixture_writer_observations SELECT 'wrapped',(SELECT * FROM pragma_secure_delete),(SELECT * FROM pragma_synchronous); END");
            db.execSQL("CREATE TRIGGER fixture_segments_guard BEFORE DELETE ON segments BEGIN"+require+
                "INSERT INTO fixture_writer_observations SELECT 'segments',(SELECT * FROM pragma_secure_delete),(SELECT * FROM pragma_synchronous); END");
        }
        void observed(String kind){
            check(scalar(db,"SELECT COUNT(*) FROM fixture_writer_observations WHERE kind=?",kind)>0,"real SQL guard observed "+kind+" mutation");
            check(scalar(db,"SELECT COUNT(*) FROM fixture_writer_observations WHERE secure_value!=1 OR sync_value!=2")==0,"all observed mutation connections use required settings");
        }
        @Override public void close()throws Exception {
            Throwable failure=null;
            try{closeStore();}catch(Throwable next){failure=cleanupFailure(failure,next);}
            try{
                KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
                for(String session:sessions)try{keys.deleteEntry("sidecar.audio."+session);}
                catch(Throwable next){failure=cleanupFailure(failure,next);}
            }catch(Throwable next){failure=cleanupFailure(failure,next);}
            try{context.dispose();}catch(Throwable next){failure=cleanupFailure(failure,next);}
            rethrow(failure);
        }
    }
    private static void readerFullWhileWriterHeld(SQLiteDatabase db)throws Exception {
        CancellationSignal cancellation=new CancellationSignal();
        FutureTask<Long> read=new FutureTask<>(()->{
            try(Cursor c=db.rawQuery("SELECT * FROM pragma_synchronous",null,cancellation)){
                if(!c.moveToFirst())throw new AssertionError("missing reader synchronous mode");return c.getLong(0);
            }
        });
        Thread reader=new Thread(read,"Sidecar-wal-fixture-reader");Throwable failure=null;boolean started=false;
        db.beginTransaction();
        try{
            check(scalar(db,"PRAGMA synchronous")==2,"held primary reports FULL");
            reader.start();started=true;check(read.get(5,TimeUnit.SECONDS)==2,"separate WAL reader reports FULL while primary is occupied");
        }catch(Throwable next){failure=cleanupFailure(failure,next);
        }finally{
            try{db.endTransaction();}catch(Throwable next){failure=cleanupFailure(failure,next);}
            try{cancellation.cancel();}catch(Throwable next){failure=cleanupFailure(failure,next);}
            if(started)try{
                reader.join(5000);if(reader.isAlive())throw new AssertionError("fixture reader did not release after transaction end");
            }catch(Throwable next){failure=cleanupFailure(failure,next);}
        }
        rethrow(failure);
    }
    private static void configurationSurvivesReconfiguration(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            check(f.db.isWriteAheadLoggingEnabled()&&"wal".equalsIgnoreCase(text(f.db,"PRAGMA journal_mode")),"actual database uses WAL");
            check(writerScalar(f.db,"PRAGMA synchronous")==2,"initial primary is FULL");readerFullWhileWriterHeld(f.db);
            f.db.disableWriteAheadLogging();check(!f.db.isWriteAheadLoggingEnabled(),"public API disables WAL for isolated fixture");
            check(writerScalar(f.db,"PRAGMA synchronous")==2,"FULL remains in rollback-journal configuration");
            check(f.db.enableWriteAheadLogging(),"public API re-enables WAL");
            check(writerScalar(f.db,"PRAGMA synchronous")==2,"FULL survives public WAL configuration change");readerFullWhileWriterHeld(f.db);
            f.closeStore();f.open();check(writerScalar(f.db,"PRAGMA synchronous")==2,"normal store close/reopen reapplies FULL");
            f.matches(f.frames,0);f.matches(f.frames,2);
        }
    }
    private static void rangeWriterAndGuardControl(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            f.guards();disableSecureWriter(f.db);
            rejectsSql(()->{
                f.db.beginTransaction();try{f.db.execSQL("UPDATE frames SET wrapped=NULL WHERE id=?",new Object[]{f.frames.ids.get(1)});f.db.setTransactionSuccessful();}
                finally{f.db.endTransaction();}
            },GUARD,"OFF control must fail at the real mutation trigger");
            check(f.live(1)&&scalar(f.db,"SELECT COUNT(*) FROM fixture_writer_observations")==0,"guard control rolled back without losing a wrapped key");
            f.store.deleteRange(f.frames.session,f.frames.stream,40000,80000);
            check(f.revoked(1)&&f.live(0)&&f.live(2),"range revocation preserves adjacent wrapped keys");f.observed("wrapped");
            check(writerScalar(f.db,"PRAGMA secure_delete")==1,"range writer remains secure after commit");
            f.matches(f.frames,0);f.matches(f.frames,2);
        }
    }
    private static void replayRollbackAndRetry(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            f.guards();f.db.execSQL("INSERT INTO deletions VALUES(?,?,40000,80000)",new Object[]{f.frames.session,f.frames.stream});
            disableSecureWriter(f.db);f.observeStage=f.failStage="range_replay_before_commit";f.injected=new IOException("synthetic configured replay rollback");
            try{f.store.recover();throw new AssertionError("replay fault was ignored");}catch(IOException expected){check(expected==f.injected,"configured replay preserves original failure");}
            check(f.observations==1&&f.live(1)&&scalar(f.db,"SELECT COUNT(*) FROM deletions")==1,"failed replay retains original row and existing authority");
            check(scalar(f.db,"SELECT COUNT(*) FROM fixture_writer_observations")==0,"failed replay rolls back guard observation with wrapped change");
            f.failStage=f.observeStage="";disableSecureWriter(f.db);f.store.recover();
            check(f.revoked(1)&&f.live(0)&&f.live(2),"replay retry configures writer and revokes only the target");f.observed("wrapped");
            f.matches(f.frames,0);f.matches(f.frames,2);
        }
    }
    private static void expiryAndCompactionWriters(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            f.guards();f.db.execSQL("UPDATE frames SET expires_utc=0 WHERE id=?",new Object[]{f.frames.ids.get(1)});
            disableSecureWriter(f.db);f.observeStage="expiry_before_commit";f.disableStage="compaction_before_fsync";
            check(f.store.sweepExpired(()->false)==1,"one expired frame revokes before cleanup");
            check(f.observations==1&&f.disables==1,"expiry stage and distinct pre-compaction OFF control both occurred");
            check(f.revoked(1)&&f.live(0)&&f.live(2),"expiry and compaction preserve adjacent wrapped keys");
            f.observed("wrapped");f.observed("segments");f.matches(f.frames,0);f.matches(f.frames,2);
        }
    }
    private static void wholeMeetingWriter(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            Frames neighbor=f.append(1,(short)1701);f.guards();
            DurableDeletionMarkers.mark(f.context,"deletion-barriers",f.frames.session);disableSecureWriter(f.db);
            f.store.purgeDeleted(f.frames.session);
            check(f.revoked(0)&&f.revoked(1)&&f.revoked(2),"whole-meeting purge nulls each authorized wrapper");
            check(scalar(f.db,"SELECT COUNT(*) FROM segments WHERE session=?",f.frames.session)==0,"whole-meeting segment metadata is removed");
            f.observed("wrapped");f.observed("segments");
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            check(!keys.containsAlias("sidecar.audio."+f.frames.session)&&keys.containsAlias("sidecar.audio."+neighbor.session),"whole purge preserves unrelated audio key");
            f.matches(neighbor,0);check(writerScalar(f.db,"PRAGMA secure_delete")==1,"purge metadata writer remains secure");
        }
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        checks=0;Context context=instrumentation.getTargetContext();
        check(Build.VERSION.SDK_INT>=30&&"ranchu".equals(Build.HARDWARE),"owned disposable API30+ emulator required");
        KeyguardManager guard=context.getSystemService(KeyguardManager.class);
        check(guard.isDeviceSecure()&&!guard.isDeviceLocked(),"fixture device must already be secure and unlocked");
        configurationSurvivesReconfiguration(context);rangeWriterAndGuardControl(context);replayRollbackAndRetry(context);
        expiryAndCompactionWriters(context);wholeMeetingWriter(context);return checks;
    }
}

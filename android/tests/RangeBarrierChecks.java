package local.sidecar.copilot;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.util.*;

/** Synthetic deletion transactions, append barriers and old-row restoration with retained tombstones.
 * Fault hooks and reentrant metadata changes are not observed SQLite crashes or backup restores.
 * No microphone, speaker, system-clock changes, or unscoped app data are used.
 */
public final class RangeBarrierChecks {
    private static int checks;
    private interface Action {void run()throws Exception;}
    private static void check(boolean value,String label){if(!value)throw new AssertionError("range barrier "+checks+": "+label);checks++;}
    private static void rejects(Class<? extends Exception> type,Action action,String label)throws Exception {
        try{action.run();throw new AssertionError(label+" unexpectedly succeeded");}
        catch(Exception expected){if(!type.isInstance(expected))throw expected;checks++;}
    }
    private static long number(SQLiteDatabase db,String sql,String... args){
        try(Cursor c=db.rawQuery(sql,args)){if(!c.moveToFirst())throw new AssertionError("missing fixture row");return c.getLong(0);}
    }
    private static SQLiteDatabase database(AudioSegmentStore store)throws Exception {
        Field field=AudioSegmentStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);
    }
    private static String rows(SQLiteDatabase db,String sql,String... args){
        StringBuilder result=new StringBuilder();
        try(Cursor c=db.rawQuery(sql,args)){while(c.moveToNext()){
            for(int i=0;i<c.getColumnCount();i++){
                int type=c.getType(i);result.append(type).append(':');
                if(type==Cursor.FIELD_TYPE_BLOB)result.append(android.util.Base64.encodeToString(c.getBlob(i),android.util.Base64.NO_WRAP));
                else if(type!=Cursor.FIELD_TYPE_NULL)result.append(c.getString(i));
                result.append('|');
            }
            result.append('\n');
        }}
        return result.toString();
    }
    private static ContentValues snapshot(SQLiteDatabase db,String frame){
        ContentValues value=new ContentValues();
        try(Cursor c=db.rawQuery("SELECT * FROM frames WHERE id=?",new String[]{frame})){
            if(!c.moveToFirst())throw new AssertionError("missing snapshot frame");
            for(int i=0;i<c.getColumnCount();i++){
                String name=c.getColumnName(i);
                switch(c.getType(i)){
                    case Cursor.FIELD_TYPE_NULL:value.putNull(name);break;
                    case Cursor.FIELD_TYPE_BLOB:value.put(name,c.getBlob(i));break;
                    case Cursor.FIELD_TYPE_INTEGER:value.put(name,c.getLong(i));break;
                    case Cursor.FIELD_TYPE_FLOAT:value.put(name,c.getDouble(i));break;
                    default:value.put(name,c.getString(i));
                }
            }
        }
        return value;
    }
    private static final class IsolatedContext extends ContextWrapper {
        final String prefix="range-barrier-"+UUID.randomUUID()+"-";
        final File directory;
        final Set<String> preferences=new HashSet<>();
        IsolatedContext(Context base)throws IOException {
            super(base);directory=new File(base.getNoBackupFilesDir(),prefix);
            if(!directory.mkdir())throw new IOException("fixture directory unavailable");
        }
        @Override public File getNoBackupFilesDir(){return directory;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            String scoped=prefix+name;preferences.add(scoped);return getBaseContext().getSharedPreferences(scoped,mode);
        }
        void dispose()throws IOException {
            for(String name:preferences)getBaseContext().deleteSharedPreferences(name);
            File root=new File(directory,"audio-segments");File[] files=root.listFiles();
            if(files!=null)for(File file:files){
                // Only this fixture's flat, randomly named directory is owned by the test.
                if(!file.isFile()||!file.delete())throw new IOException("fixture file cleanup pending");
            }
            if(root.exists()&&!root.delete())throw new IOException("fixture audio directory cleanup pending");
            if(!directory.delete())throw new IOException("fixture directory cleanup pending");
        }
    }
    private static final class Probe implements AudioSegmentStore.Faults {
        final List<String> stages=new ArrayList<>();
        String failAt="";
        IOException injected;
        Runnable beforeReturn;
        String actionAt="";
        Runnable stageAction;
        int decryptions;
        @Override public void at(String stage)throws IOException {
            stages.add(stage);
            if(stage.equals(failAt))throw injected;
            if(stage.equals("read_before_return")&&beforeReturn!=null){Runnable action=beforeReturn;beforeReturn=null;action.run();}
            if(stage.equals(actionAt)&&stageAction!=null){Runnable action=stageAction;stageAction=null;action.run();}
        }
        @Override public void ownedBuffer(String stage,byte[] buffer){if(stage.equals("read_dek"))decryptions++;}
        void fail(String stage){failAt=stage;injected=new IOException("synthetic "+stage);stages.clear();}
        void clear(){failAt="";injected=null;beforeReturn=null;actionAt="";stageAction=null;stages.clear();decryptions=0;}
        int count(String stage){return Collections.frequency(stages,stage);}
    }
    private static final class StreamFrames {
        final String session,stream;
        final List<String> frames;
        final short firstValue;
        StreamFrames(String session,String stream,List<String> frames,short firstValue){this.session=session;this.stream=stream;this.frames=frames;this.firstValue=firstValue;}
    }
    private static final class Fixture implements AutoCloseable {
        final IsolatedContext context;
        final Probe probe=new Probe();
        final Set<String> sessions=new LinkedHashSet<>();
        final File root;
        AudioSegmentStore store;
        SQLiteDatabase db;
        StreamFrames main;
        Fixture(Context base)throws Exception {
            context=new IsolatedContext(base);root=new File(context.directory,"audio-segments");
            try{openStore();main=appendStream(newSession(),3,(short)911,null);}
            catch(Exception failure){try{close();}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
        }
        void openStore()throws Exception {store=new AudioSegmentStore(context,probe);db=database(store);}
        void closeStore()throws Exception {if(store!=null){store.close();store=null;db=null;}}
        String newSession()throws Exception {String id=store.createSession(8000);sessions.add(id);return id;}
        StreamFrames appendStream(String session,int count,short firstValue,String forcedStream)throws Exception {
            long generation=store.start(session,true);
            // A test-only stream collision is set BEFORE encryption, preserving valid AAD and tags.
            if(forcedStream!=null)db.execSQL("UPDATE sessions SET stream=? WHERE id=?",new Object[]{forcedStream,session});
            String stream=store.stream(session);List<String> ids=new ArrayList<>();
            for(int i=0;i<count;i++){
                short[] pcm=new short[40000];Arrays.fill(pcm,(short)(firstValue+i));
                try{ids.add(store.append(session,generation,new PcmFrameAssembler.Frame(i*40000L,(i+1)*40000L,SystemClock.elapsedRealtimeNanos(),pcm,"healthy")));}
                finally{Arrays.fill(pcm,(short)0);}
            }
            store.beginPause(session,generation,count*40000L);store.finishPause(session);
            return new StreamFrames(session,stream,ids,firstValue);
        }
        String frame(int index){return main.frames.get(index);}
        String row(int index){return rows(db,"SELECT * FROM frames WHERE id=?",frame(index));}
        boolean revoked(int index){return number(db,"SELECT deleted=1 AND wrapped IS NULL FROM frames WHERE id=?",frame(index))==1;}
        boolean live(int index){return number(db,"SELECT deleted=0 AND wrapped IS NOT NULL FROM frames WHERE id=?",frame(index))==1;}
        void restore(ContentValues old){check(db.update("frames",new ContentValues(old),"id=?",new String[]{old.getAsString("id")})==1,"complete synthetic old frame row restored");}
        void insertRange(String session,String stream,long start,long end){db.execSQL("INSERT INTO deletions VALUES(?,?,?,?)",new Object[]{session,stream,start,end});}
        File orphan()throws Exception {
            File file=new File(root,UUID.randomUUID()+".seg");try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{7,8,9});}return file;
        }
        String stableRows(){return rows(db,"SELECT * FROM frames ORDER BY id")+rows(db,"SELECT * FROM segments ORDER BY id")+rows(db,"SELECT * FROM sessions ORDER BY id")+rows(db,"SELECT * FROM deletions ORDER BY rowid");}
        @Override public void close()throws Exception {
            try{closeStore();}
            finally{
                try{KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);for(String id:sessions)keys.deleteEntry("sidecar.audio."+id);}
                finally{context.dispose();}
            }
        }
    }
    private static void readAndWipe(AudioSegmentStore store,String frame)throws Exception {short[] pcm=store.read(frame);Arrays.fill(pcm,(short)0);}
    private static void matches(AudioSegmentStore store,String frame,short value,String label)throws Exception {
        short[] pcm=store.read(frame);try{
            check(pcm.length==40000,label+" sample length");
            for(short sample:pcm)if(sample!=value)throw new AssertionError(label+" changed samples");
            checks++;
        }finally{Arrays.fill(pcm,(short)0);}
    }
    private static final class MemoryOutput implements AudioFrameReplay.Output {
        int starts,writes,stops;
        public void start(int rate){starts++;}
        public int write(short[] pcm,int offset,int count){writes++;return count;}
        public void stop(){stops++;}
    }
    private static void deniedWithoutCleanup(Fixture f,String label)throws Exception {
        int before=f.probe.decryptions;
        check(f.store.frameIds(f.main.session).equals(Arrays.asList(f.frame(0),f.frame(2))),label+" list excludes old middle row");
        check(f.store.committedSamples(f.main.session)==80000,label+" coverage excludes old middle row");
        rejects(SecurityException.class,()->readAndWipe(f.store,f.frame(1)),label+" read");
        rejects(SecurityException.class,()->f.store.requireReplayable(f.frame(1)),label+" replay admission");
        MemoryOutput output=new MemoryOutput();
        rejects(SecurityException.class,()->new AudioFrameReplay(output).play(f.store,f.frame(1),8000),label+" replay");
        check(output.starts==0&&output.writes==0&&output.stops==1,label+" no output starts");
        short[] pcm=new short[100];Arrays.fill(pcm,(short)277);
        try{rejects(SecurityException.class,()->f.store.writeReplayChunk(f.frame(1),output,pcm,0,pcm.length),label+" chunk admission");}
        finally{Arrays.fill(pcm,(short)0);}
        check(output.writes==0&&f.probe.decryptions==before,label+" no key unwrap or output for blocked row");
    }
    private static void currentStoreAdmissions(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            for(int i=0;i<3;i++)matches(f.store,f.frame(i),(short)(911+i),"baseline frame "+i);
            ContentValues old=snapshot(f.db,f.frame(1));String original=f.row(1);
            f.store.deleteRange(f.main.session,f.main.stream,40000,80000);check(f.revoked(1),"ordinary range revocation committed");
            f.restore(old);check(f.row(1).equals(original)&&f.live(1),"valid old metadata, mapping and wrapped key restored intact");
            f.probe.clear();deniedWithoutCleanup(f,"same instance");
            check(f.live(1),"admission refuses without depending on cleanup side effects");
            matches(f.store,f.frame(0),(short)911,"left frame ends at range start");
            matches(f.store,f.frame(2),(short)913,"right frame starts at range end");
            StreamFrames resumed=f.appendStream(f.main.session,2,(short)921,null);
            StreamFrames other=f.appendStream(f.newSession(),2,(short)931,f.main.stream);
            matches(f.store,resumed.frames.get(1),(short)922,"same samples in another stream survive");
            matches(f.store,other.frames.get(1),(short)932,"same stream and samples in another session survive");
            check(f.store.committedSamples(f.main.session)==160000&&f.store.committedSamples(other.session)==80000,"coverage respects both stream and session");

            // One-sample intersections are sufficient; do not mutate the valid encrypted frame row.
            f.insertRange(f.main.session,f.main.stream,39999,40001);
            rejects(SecurityException.class,()->readAndWipe(f.store,f.frame(0)),"one-sample intersection");
            check(f.store.frameIds(f.main.session).equals(Arrays.asList(f.frame(2),resumed.frames.get(0),resumed.frames.get(1))),"one-sample boundary filters only intersecting stream");
            check(f.store.committedSamples(f.main.session)==120000,"one-sample deletion conservatively excludes complete intersecting frames");
            matches(f.store,f.frame(2),(short)913,"nonintersecting third frame remains readable");

            int before=f.probe.decryptions;
            f.probe.beforeReturn=()->f.insertRange(f.main.session,f.main.stream,80000,120000);
            rejects(SecurityException.class,()->readAndWipe(f.store,f.frame(2)),"range tombstone arrives after decode");
            check(f.probe.beforeReturn==null&&f.probe.decryptions==before+1&&f.live(2),"final admission rechecks range without relying on a deleted flag");
            check(f.store.frameIds(f.main.session).equals(resumed.frames)&&f.store.committedSamples(f.main.session)==80000,"final barrier also removes projection of old stream");
        }
    }
    private static void reopenAndIdempotence(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            ContentValues old=snapshot(f.db,f.frame(1));f.store.deleteRange(f.main.session,f.main.stream,40000,80000);f.restore(old);
            long oldBoot=number(f.db,"SELECT boot FROM clock WHERE singleton=1");
            f.db.execSQL("UPDATE clock SET boot=boot+1 WHERE singleton=1");String uncertainClock=rows(f.db,"SELECT * FROM clock");
            File orphan=f.orphan();f.closeStore();f.probe.clear();f.openStore();
            check(f.revoked(1),"normal open replays retained range before exposing store");
            check(f.probe.count("range_replay_before_commit")==1&&f.probe.decryptions==0,"open replays once without decrypting");
            check(!orphan.exists()&&rows(f.db,"SELECT * FROM clock").equals(uncertainClock),"physical cleanup follows revocation without repairing uncertain clock");
            f.db.execSQL("UPDATE clock SET boot=? WHERE singleton=1",new Object[]{oldBoot});
            deniedWithoutCleanup(f,"reopened");
            String stable=f.stableRows();
            // A fixture-only trigger detects redundant row writes without relying on pooled connection counters.
            f.db.execSQL("CREATE TRIGGER fixture_no_redundant_revocation BEFORE UPDATE OF deleted,wrapped ON frames WHEN OLD.deleted=1 AND OLD.wrapped IS NULL AND NEW.deleted=1 AND NEW.wrapped IS NULL BEGIN SELECT RAISE(ABORT,'redundant revoked row update'); END");
            try{f.store.recover();f.store.recover();}
            finally{f.db.execSQL("DROP TRIGGER fixture_no_redundant_revocation");}
            check(f.stableRows().equals(stable),"repeated replay preserves rows, mappings, tombstones and session state without rewriting revoked rows");
            check(number(f.db,"SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='deletions_by_stream_end'")==1,"compatible range lookup index present");
            matches(f.store,f.frame(0),(short)911,"recovery preserves left neighbor");
            matches(f.store,f.frame(2),(short)913,"recovery preserves right neighbor");
        }
    }
    private static void replayCommitFailure(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            ContentValues old=snapshot(f.db,f.frame(1));f.store.deleteRange(f.main.session,f.main.stream,40000,80000);f.restore(old);
            File orphan=f.orphan();String original=f.row(1),ranges=rows(f.db,"SELECT * FROM deletions");
            f.probe.fail("range_replay_before_commit");IOException expected=f.probe.injected;
            try{f.store.recover();throw new AssertionError("range replay commit fault was ignored");}
            catch(IOException failure){check(failure==expected,"public recovery preserves injected exception");}
            check(f.live(1)&&f.row(1).equals(original)&&rows(f.db,"SELECT * FROM deletions").equals(ranges),"failed replay rolls back all frame changes and retains tombstone");
            check(orphan.exists()&&f.probe.count("cleanup_before_unlink")==0&&f.probe.count("recovery_after_file_verification")==0,"commit failure precedes physical recovery");
            deniedWithoutCleanup(f,"failed public recovery");
            f.closeStore();f.probe.stages.clear();
            rejects(IOException.class,f::openStore,"failed constructor cannot publish a store");
            check(f.store==null&&orphan.exists()&&f.probe.count("cleanup_before_unlink")==0,"failed open leaves no store and no prior physical deletion");
            try(SQLiteDatabase raw=SQLiteDatabase.openDatabase(new File(f.root,"index.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                check(number(raw,"SELECT deleted=0 AND wrapped IS NOT NULL FROM frames WHERE id=?",f.frame(1))==1&&rows(raw,"SELECT * FROM deletions").equals(ranges),"failed constructor rolls back only replay, never original authorization barrier");
            }
            f.probe.clear();f.openStore();check(f.revoked(1)&&!orphan.exists(),"retry commits replay then reclaims orphan");
            deniedWithoutCleanup(f,"recovered after commit fault");
        }
    }
    private static void physicalFailureAndDirectCompaction(Context base)throws Exception {
        for(boolean direct:new boolean[]{false,true})try(Fixture f=new Fixture(base)){
            ContentValues old=snapshot(f.db,f.frame(1));f.store.deleteRange(f.main.session,f.main.stream,40000,80000);f.restore(old);
            File orphan=direct?null:f.orphan();
            f.probe.fail(direct?"compaction_before_fsync":"cleanup_before_unlink");
            if(direct)rejects(IOException.class,()->f.store.compact(),"direct compact physical fault");
            else rejects(IOException.class,()->f.store.retryDeletedCleanup(()->false),"retry cleanup physical fault");
            check(f.revoked(1),"physical failure leaves range key revocation committed");
            check(f.probe.count("range_replay_before_commit")==1,"physical cleanup uses shared replay helper first");
            if(orphan!=null)check(orphan.exists(),"injected unlink failure retains synthetic orphan for retry");
            deniedWithoutCleanup(f,direct?"direct compact failure":"orphan cleanup failure");
            matches(f.store,f.frame(0),(short)911,"physical failure preserves left neighbor");
            matches(f.store,f.frame(2),(short)913,"physical failure preserves right neighbor");
            f.probe.clear();f.store.retryDeletedCleanup(()->false);check(f.revoked(1),"cleanup retry cannot restore wrapped key");
            matches(f.store,f.frame(0),(short)911,"successful cleanup preserves left neighbor");
            matches(f.store,f.frame(2),(short)913,"successful cleanup preserves right neighbor");
        }
    }
    private static long rangeCount(Fixture f,String session,String stream,long start,long end){
        return number(f.db,"SELECT COUNT(*) FROM deletions WHERE session=? AND stream=? AND start_sample=? AND end_sample=?",
            session,stream,Long.toString(start),Long.toString(end));
    }
    private static void deleteTransactionBoundaries(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            // The selected old stream remains the target after a different stream became current.
            StreamFrames resumed=f.appendStream(f.main.session,2,(short)941,null);
            StreamFrames other=f.appendStream(f.newSession(),2,(short)951,f.main.stream);
            String baseline=f.stableRows();
            f.probe.fail("range_delete_before_commit");IOException beforeCommit=f.probe.injected;
            try{f.store.deleteRangeDurably(f.main.session,f.main.stream,40000,80000);throw new AssertionError("range commit fault ignored");}
            catch(IOException failure){check(failure==beforeCommit,"range failure before commit preserves original exception");}
            check(f.stableRows().equals(baseline),"failed deletion transaction rolls back tombstone and all indexed changes");
            check(rangeCount(f,f.main.session,f.main.stream,40000,80000)==0&&f.live(1),"unconfirmed range does not revoke an existing wrapped key");
            check(f.probe.count("range_delete_before_commit")==1&&f.probe.count("range_delete_after_commit")==0
                &&f.probe.count("cleanup_before_unlink")==0,"precommit failure does not reach committed receipt or physical cleanup");
            matches(f.store,f.frame(1),(short)912,"uncommitted deletion leaves original middle frame intact");

            f.probe.clear();f.probe.fail("range_delete_after_commit");IOException afterCommit=f.probe.injected;
            try{f.store.deleteRangeDurably(f.main.session,f.main.stream,40000,80000);throw new AssertionError("postcommit range fault ignored");}
            catch(IOException failure){check(failure==afterCommit,"range failure after commit preserves original exception");}
            check(rangeCount(f,f.main.session,f.main.stream,40000,80000)==1&&f.revoked(1),"postcommit failure retains durable range and revoked wrapped key");
            check(f.probe.stages.indexOf("range_delete_before_commit")>=0
                &&f.probe.stages.indexOf("range_delete_after_commit")>f.probe.stages.indexOf("range_delete_before_commit"),
                "range precommit and postcommit probes retain order");
            rejects(SecurityException.class,()->readAndWipe(f.store,f.frame(1)),"postcommit failure never reopens deleted frame");
            matches(f.store,f.frame(0),(short)911,"exact range keeps preceding boundary neighbor");
            matches(f.store,f.frame(2),(short)913,"exact range keeps following boundary neighbor");
            matches(f.store,resumed.frames.get(1),(short)942,"old-stream request preserves current stream at same sample positions");
            matches(f.store,other.frames.get(1),(short)952,"old-stream request preserves other session with same stream identifier");
            check(f.store.committedSamples(f.main.session)==160000&&f.store.committedSamples(other.session)==80000,
                "postcommit coverage remains scoped to selected session and stream");

            f.closeStore();
            try(SQLiteDatabase raw=SQLiteDatabase.openDatabase(new File(f.root,"index.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                check(number(raw,"SELECT COUNT(*) FROM deletions WHERE session=? AND stream=? AND start_sample=40000 AND end_sample=80000",f.main.session,f.main.stream)==1,
                    "range survives close after postcommit exception");
                check(number(raw,"SELECT deleted=1 AND wrapped IS NULL FROM frames WHERE id=?",f.frame(1))==1,
                    "committed key revocation survives close after exception");
            }
            f.probe.clear();f.openStore();
            check(f.revoked(1)&&rangeCount(f,f.main.session,f.main.stream,40000,80000)==1,
                "ordinary reopening preserves committed range before exposing store");
            String committed=f.stableRows();
            f.db.execSQL("CREATE TRIGGER fixture_no_duplicate_range BEFORE INSERT ON deletions WHEN EXISTS(SELECT 1 FROM deletions d WHERE d.session=NEW.session AND d.stream=NEW.stream AND d.start_sample=NEW.start_sample AND d.end_sample=NEW.end_sample) BEGIN SELECT RAISE(ABORT,'duplicate exact range'); END");
            f.db.execSQL("CREATE TRIGGER fixture_no_redundant_range_key BEFORE UPDATE OF deleted,wrapped ON frames WHEN OLD.deleted=1 AND OLD.wrapped IS NULL AND NEW.deleted=1 AND NEW.wrapped IS NULL BEGIN SELECT RAISE(ABORT,'redundant range revocation'); END");
            try{
                f.store.deleteRangeDurably(f.main.session,f.main.stream,40000,80000);
                f.store.deleteRangeDurably(f.main.session,f.main.stream,40000,80000);
            }finally{
                f.db.execSQL("DROP TRIGGER fixture_no_redundant_range_key");
                f.db.execSQL("DROP TRIGGER fixture_no_duplicate_range");
            }
            check(f.stableRows().equals(committed),"exact range retries do not insert duplicates or rewrite revoked rows");
            check(rangeCount(f,f.main.session,f.main.stream,40000,80000)==1,"idempotent deletion retains one exact range");
            f.probe.fail("range_delete_after_commit");IOException wrappedCause=f.probe.injected;
            try{f.store.deleteRange(f.main.session,f.main.stream,40000,80000);throw new AssertionError("compatibility entry swallowed range failure");}
            catch(UncheckedIOException failure){check(failure.getCause()==wrappedCause,"compatibility entry preserves the original checked storage failure as its cause");}
            check(f.stableRows().equals(committed),"compatibility entry failure cannot undo durable revocation or duplicate the range");
        }
    }
    private static void lateAppendRangeCheck(Context base)throws Exception {
        for(String stage:new String[]{"after_fsync","before_index_commit"})try(Fixture f=new Fixture(base)){
            String session=f.newSession();long generation=f.store.start(session,true);String stream=f.store.stream(session);
            short[] pcm=new short[40000];Arrays.fill(pcm,(short)971);
            f.probe.clear();f.probe.actionAt=stage;
            f.probe.stageAction=()->f.insertRange(session,stream,0,40000);
            try{
                rejects(SecurityException.class,()->f.store.append(session,generation,
                    new PcmFrameAssembler.Frame(0,40000,SystemClock.elapsedRealtimeNanos(),pcm,"healthy")),
                    stage+" range arrival rejects the late append");
            }finally{Arrays.fill(pcm,(short)0);}
            check(f.probe.stageAction==null&&f.probe.count(stage)==1,stage+" injection actually reached");
            check(number(f.db,"SELECT COUNT(*) FROM frames WHERE session=?",session)==0,stage+" rejected append publishes no frame row or wrapped key");
            check(number(f.db,"SELECT next_sample FROM sessions WHERE id=?",session)==0
                &&number(f.db,"SELECT COALESCE(SUM(committed),0) FROM segments WHERE session=?",session)==0,
                stage+" rejected append does not advance logical sample or segment boundary");
            // after_fsync precedes the index transaction; its fixture barrier is durable.
            // before_index_commit injects inside that transaction and rolls back with it.
            // The latter is a reentrant mutation model, not proof of a separate SQLite writer race.
            long expectedRanges=stage.equals("after_fsync")?1:0;
            check(rangeCount(f,session,stream,0,40000)==expectedRanges,stage+" range lifetime matches the transaction where injected");
            matches(f.store,f.frame(0),(short)911,stage+" does not damage another session");
            f.probe.clear();f.store.recover();
            check(number(f.db,"SELECT COUNT(*) FROM frames WHERE session=?",session)==0
                &&rangeCount(f,session,stream,0,40000)==expectedRanges,
                stage+" recovery cannot promote uncommitted ciphertext to a readable frame");
        }
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        Context base=instrumentation.getTargetContext();KeyguardManager keyguard=base.getSystemService(KeyguardManager.class);
        if(!keyguard.isDeviceSecure()||keyguard.isDeviceLocked())throw new SecurityException("authenticated unlocked fixture required");
        checks=0;currentStoreAdmissions(base);reopenAndIdempotence(base);replayCommitFailure(base);physicalFailureAndDirectCompaction(base);
        deleteTransactionBoundaries(base);lateAppendRangeCheck(base);return checks;
    }
}

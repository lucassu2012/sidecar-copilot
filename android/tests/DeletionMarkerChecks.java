package local.sidecar.copilot;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;

/** Cache-first commit faults over scoped real preferences/Keystore/SQLite. These are synthetic
 * interface faults and explicit old-ciphertext restorations, not observed OS crashes or rollback. */
public final class DeletionMarkerChecks {
    private static int checks;
    private interface Work {void run()throws Exception;}
    private static void check(boolean value,String label){if(!value)throw new AssertionError("deletion marker "+checks+": "+label);checks++;}
    private static void rejects(Class<? extends Throwable> type,Work work,String label)throws Exception {
        try{work.run();throw new AssertionError(label+" unexpectedly succeeded");}
        catch(Throwable failure){if(!type.isInstance(failure)){if(failure instanceof Exception)throw (Exception)failure;throw (Error)failure;}checks++;}
    }
    private enum Failure { NONE,FALSE,THROW,ERROR,BEFORE_MEMORY }
    private static final class CommitError extends Error {CommitError(){super("synthetic cache-first Error");}}
    private static final class PreferencesModel {
        final SharedPreferences delegate;
        final Map<String,Object> memory=new HashMap<>();
        final List<Map<String,Object>> attempts=new ArrayList<>();
        final List<String> proposals=new ArrayList<>();
        final Set<String> markerKeys=new HashSet<>();
        Failure failure=Failure.NONE;
        final CommitError commitError=new CommitError();
        int commits,durableWrites;
        Runnable callback;
        final SharedPreferences preferences;
        PreferencesModel(SharedPreferences delegate){this.delegate=delegate;memory.putAll(delegate.getAll());preferences=newProxy();}
        SharedPreferences newProxy(){
            return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                String name=method.getName();
                if(name.equals("getAll"))return new HashMap<>(memory);
                if(name.equals("contains"))return memory.containsKey(args[0]);
                if(name.startsWith("get")&&args!=null&&args.length==2)return memory.containsKey(args[0])?memory.get(args[0]):args[1];
                if(name.equals("edit")){
                    Map<String,Object> pending=new HashMap<>();boolean[] clear={false};
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,operation,values)->{
                        String op=operation.getName();
                        if(op.startsWith("put")){pending.put((String)values[0],values[1]);markerKeys.add((String)values[0]);if(op.equals("putString"))proposals.add((String)values[1]);return editor;}
                        if(op.equals("remove")){pending.put((String)values[0],null);return editor;}
                        if(op.equals("clear")){clear[0]=true;return editor;}
                        if(op.equals("commit")){
                            commits++;if(clear[0])memory.clear();
                            for(Map.Entry<String,Object> entry:pending.entrySet()){
                                if(entry.getValue()==null)memory.remove(entry.getKey());else memory.put(entry.getKey(),entry.getValue());
                            }
                            attempts.add(new HashMap<>(memory));
                            if(callback!=null)callback.run();
                            // Failed calls never touch the delegate or schedule a delayed write.
                            if(failure==Failure.FALSE)return false;
                            if(failure==Failure.THROW)throw new IllegalStateException("synthetic cache-first exception");
                            if(failure==Failure.ERROR)throw commitError;
                            SharedPreferences.Editor actual=delegate.edit().clear();
                            for(Map.Entry<String,Object> entry:memory.entrySet()){
                                Object value=entry.getValue();
                                if(value instanceof String)actual.putString(entry.getKey(),(String)value);
                                else if(value instanceof Boolean)actual.putBoolean(entry.getKey(),(Boolean)value);
                                else throw new AssertionError("unsupported fixture marker type");
                            }
                            boolean result=actual.commit();if(result)durableWrites++;return result;
                        }
                        throw new UnsupportedOperationException("fixture editor "+op);
                    });
                }
                throw new UnsupportedOperationException("fixture preferences "+name);
            });
        }
        void reload(){memory.clear();memory.putAll(delegate.getAll());}
        boolean uniqueTokens(String marker){Set<Object> tokens=new HashSet<>();for(Map<String,Object> attempt:attempts){Object token=attempt.get(marker);if(!(token instanceof String)||!tokens.add(token))return false;}return true;}
        boolean uniqueProposals(){return !proposals.contains(null)&&new HashSet<>(proposals).size()==proposals.size();}
    }
    private static final class Scope extends ContextWrapper implements AutoCloseable {
        final String prefix="deletion-marker-"+UUID.randomUUID()+"-";
        final File directory;
        final Map<String,PreferencesModel> models=new HashMap<>();
        final Set<String> names=new HashSet<>(),ids=new HashSet<>();
        final List<Probe> probes=new ArrayList<>();
        final KeyStore keys;
        Scope(Context base)throws Exception{
            super(base);directory=new File(base.getNoBackupFilesDir(),prefix);
            if(!directory.mkdir())throw new IOException("fixture directory unavailable");
            keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
        }
        @Override public File getNoBackupFilesDir(){return directory;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            String scoped=prefix+name;names.add(scoped);SharedPreferences delegate=getBaseContext().getSharedPreferences(scoped,mode);
            if(!name.equals("deletion-barriers")&&!name.equals("note-deletion-barriers"))return delegate;
            PreferencesModel model=models.get(name);if(model==null){model=new PreferencesModel(delegate);models.put(name,model);}return model.preferences;
        }
        PreferencesModel model(String name){getSharedPreferences(name,Context.MODE_PRIVATE);return models.get(name);}
        File xml(String name){return new File(new File(getBaseContext().getApplicationInfo().dataDir,"shared_prefs"),prefix+name+".xml");}
        String diskXml(String name)throws Exception {File file=xml(name);return file.exists()?new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8):"";}
        boolean absent(String name,String marker)throws Exception{return !model(name).delegate.contains(marker)&&!diskXml(name).contains("name=\""+marker+"\"");}
        boolean durable(String name,String marker)throws Exception {Object token=model(name).delegate.getAll().get(marker);String xml=diskXml(name);return token instanceof String&&xml.contains("name=\""+marker+"\"")&&xml.contains((String)token);}
        File note(String id){return new File(directory,"meetings/"+id+".enc");}
        String create(NoteStore store)throws Exception{String id=store.create("synthetic deletion marker",true);ids.add(id);return id;}
        boolean alias(String kind,String id)throws Exception{return keys.containsAlias("sidecar."+kind+"."+id);}
        @Override public void close()throws Exception{
            // Clear this fixture's pending registry entries even after a failed assertion.
            for(Probe probe:probes){probe.beforeCommit=null;probe.failBeforeMemory=false;}
            for(Map.Entry<String,PreferencesModel> entry:models.entrySet()){
                PreferencesModel model=entry.getValue();model.failure=Failure.NONE;model.callback=null;
                Set<String> markers=new HashSet<>(model.memory.keySet());markers.addAll(model.markerKeys);
                for(String marker:markers)DurableDeletionMarkers.mark(this,entry.getKey(),marker);
            }
            for(String id:ids){keys.deleteEntry("sidecar.note."+id);keys.deleteEntry("sidecar.audio."+id);}
            for(String name:names)getBaseContext().deleteSharedPreferences(name);
            removeOwned(directory);
        }
        void removeOwned(File file)throws IOException{
            if(!file.equals(directory)&&!file.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator))throw new IOException("fixture cleanup escaped root");
            if(Files.isSymbolicLink(file.toPath()))throw new IOException("unexpected fixture link");
            if(file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("fixture inventory unavailable");for(File child:children)removeOwned(child);}
            if(file.exists()&&!file.delete())throw new IOException("fixture cleanup pending");
        }
    }
    private static final class Probe implements NoteStore.AccessProbe {
        final List<String> stages=new ArrayList<>();int commits;Work beforeCommit;boolean failBeforeMemory;
        @Override public void at(String stage){stages.add(stage);}
        @Override public boolean commitDeletion(String preference,String key,SharedPreferences.Editor editor)throws IOException{
            commits++;if(beforeCommit!=null)try{beforeCommit.run();}catch(IOException e){throw e;}catch(Exception e){throw new AssertionError("fixture commit invariant",e);}
            if(failBeforeMemory)throw new IOException("synthetic failure before commitToMemory");
            return editor.commit();
        }
    }
    private static boolean has(JSONObject meeting,String note)throws Exception{JSONArray notes=meeting.getJSONArray("attachments");for(int i=0;i<notes.length();i++)if(note.equals(notes.getJSONObject(i).getString("id")))return true;return false;}
    private static void notePending(Context base,Failure initial)throws Exception{
        try(Scope scope=new Scope(base)){
            Probe probe=new Probe();scope.probes.add(probe);NoteStore store=new NoteStore(scope,()->{},probe);String id=scope.create(store);
            store.append(id,0,"synthetic target","unassigned");store.append(id,1,"synthetic neighbor","unassigned");
            JSONObject before=store.read(id);String target=before.getJSONArray("attachments").getJSONObject(0).getString("id"),neighbor=before.getJSONArray("attachments").getJSONObject(1).getString("id");
            byte[] old=Files.readAllBytes(scope.note(id).toPath());String name="note-deletion-barriers",marker=id+":"+target;
            PreferencesModel model=scope.model(name);String diskBefore=scope.diskXml(name);boolean beforeMemory=initial==Failure.BEFORE_MEMORY;
            model.failure=beforeMemory?Failure.FALSE:initial;probe.failBeforeMemory=beforeMemory;
            probe.beforeCommit=()->{if(!scope.absent(name,marker)||!scope.alias("note",id)||!Arrays.equals(old,Files.readAllBytes(scope.note(id).toPath())))throw new AssertionError("side effect preceded durable note marker");};
            Class<? extends Throwable> fault=initial==Failure.FALSE||beforeMemory?IOException.class:initial==Failure.THROW?IllegalStateException.class:CommitError.class;
            rejects(fault,()->store.deleteNote(id,target,2),"initial cache-first commit");
            check(model.memory.containsKey(marker)!=beforeMemory&&scope.absent(name,marker)&&scope.diskXml(name).equals(diskBefore),"failure preserves expected cache boundary and leaves real disk unchanged");
            check(scope.alias("note",id)&&Arrays.equals(old,Files.readAllBytes(scope.note(id).toPath())),"initial failure preserves key and encrypted revision");
            model.failure=Failure.FALSE;probe.stages.clear();
            rejects(IOException.class,()->store.read(id),"same-instance cached note read");
            check(!probe.stages.contains("note_write_before_size")&&!probe.stages.contains("before_return")&&Arrays.equals(old,Files.readAllBytes(scope.note(id).toPath())),"failed retry neither rewrites nor returns plaintext");
            int calls=probe.commits;
            rejects(IOException.class,()->new NoteStore(scope).read(id),"new-instance cached note read");
            check(probe.commits==calls+1&&model.durableWrites==0&&scope.absent(name,marker)&&scope.alias("note",id)&&Arrays.equals(old,Files.readAllBytes(scope.note(id).toPath())),"new instance retains original failing committer without key or ciphertext changes");
            check(model.uniqueTokens(marker)&&model.uniqueProposals()&&model.proposals.size()==3&&model.commits==(beforeMemory?0:3),"each uncertain retry changes the same marker token, including before-cache failures");
            probe.beforeCommit=null;probe.failBeforeMemory=false;model.failure=Failure.NONE;
            JSONObject filtered=new NoteStore(scope).read(id);
            check(scope.durable(name,marker)&&model.durableWrites==1&&model.uniqueTokens(marker),"successful explicit retry reaches real preferences XML with fresh token");
            check(filtered.getLong("revision")==3&&filtered.getJSONArray("attachments").length()==1&&!has(filtered,target)&&has(filtered,neighbor),"confirmed retry filters target and preserves neighbor");
            check(scope.alias("note",id)&&!Arrays.equals(old,Files.readAllBytes(scope.note(id).toPath())),"valid neighbor retains key and receives committed filtered ciphertext");
            int committed=model.commits;new NoteStore(scope).read(id);
            check(model.commits==committed,"confirmed marker avoids repeated commits");
            // Artificial old ciphertext restoration, not an OS recovery claim.
            Files.write(scope.note(id).toPath(),old);model.reload();JSONObject restored=new NoteStore(scope).read(id);
            check(!has(restored,target)&&has(restored,neighbor)&&restored.getJSONArray("attachments").length()==1&&restored.getLong("revision")==3,"durable barrier filters restored valid old ciphertext");
            check(scope.durable(name,marker)&&model.commits==committed,"cache reload uses durable marker without a new deletion decision");
        }
    }
    private static SQLiteDatabase database(AudioSegmentStore store)throws Exception{Field field=AudioSegmentStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);}
    private static String rows(SQLiteDatabase db){
        StringBuilder result=new StringBuilder();
        for(String table:new String[]{"sessions","segments","frames","deletions","gaps","clock","meeting_links","consent_records","session_ends","capture_controls"}){
            result.append(table).append(':');try(Cursor c=db.rawQuery("SELECT * FROM "+table+" ORDER BY rowid",null)){while(c.moveToNext()){
                for(int i=0;i<c.getColumnCount();i++){int type=c.getType(i);result.append(type).append(':');if(type==Cursor.FIELD_TYPE_BLOB)result.append(android.util.Base64.encodeToString(c.getBlob(i),android.util.Base64.NO_WRAP));else if(type!=Cursor.FIELD_TYPE_NULL)result.append(c.getString(i));result.append('|');}result.append('\n');
            }}
        }return result.toString();
    }
    private static long number(SQLiteDatabase db,String sql,String id){try(Cursor c=db.rawQuery(sql,new String[]{id})){if(!c.moveToFirst())throw new AssertionError("missing fixture row");return c.getLong(0);}}
    private static String append(AudioSegmentStore audio,String id)throws Exception{
        long generation=audio.start(id,true);short[] pcm=new short[40000];Arrays.fill(pcm,(short)619);
        try{String frame=audio.append(id,generation,new PcmFrameAssembler.Frame(0,40000,SystemClock.elapsedRealtimeNanos(),pcm,"healthy"));audio.beginPause(id,generation,40000);audio.finishPause(id);return frame;}
        finally{Arrays.fill(pcm,(short)0);}
    }
    private static void wholePending(Context base)throws Exception{
        try(Scope scope=new Scope(base)){
            Probe probe=new Probe();scope.probes.add(probe);NoteStore notes=new NoteStore(scope,()->{},probe);String id=scope.create(notes);
            AudioSegmentStore audio=new AudioSegmentStore(scope);
            try{
                audio.createSession(8000,id);audio.recordDeclaration(id,0,System.currentTimeMillis());String frame=append(audio,id);
                String neighbor=audio.createSession(8000);scope.ids.add(neighbor);String neighborFrame=append(audio,neighbor);
                final AudioSegmentStore active=audio;SQLiteDatabase db=database(audio);String unchanged=rows(db);byte[] old=Files.readAllBytes(scope.note(id).toPath());
                check(number(db,"SELECT expires_utc FROM frames WHERE id=?",frame)>System.currentTimeMillis(),"target audio is unexpired and cannot use TTL as cleanup authority");
                String name="deletion-barriers";PreferencesModel model=scope.model(name);model.failure=Failure.FALSE;
                Work invariant=()->{if(!scope.absent(name,id)||!scope.alias("note",id)||!scope.alias("audio",id)||!Arrays.equals(old,Files.readAllBytes(scope.note(id).toPath()))||!unchanged.equals(rows(db)))throw new AssertionError("whole cleanup preceded durable marker");};
                probe.beforeCommit=invariant;probe.failBeforeMemory=true;
                rejects(IOException.class,()->notes.delete(id),"whole initial failure before cache update");invariant.run();checks++;
                check(!model.memory.containsKey(id)&&model.commits==0&&scope.absent(name,id),"whole pending intent exists without cache or durable marker");
                check(!notes.ids().contains(id)&&audio.isDeleted(id),"cache-only whole barrier already hides public access");
                rejects(SecurityException.class,()->notes.read(id),"whole note hidden");
                rejects(SecurityException.class,()->active.frameIds(id),"whole audio index hidden");
                rejects(SecurityException.class,()->active.read(frame),"whole audio plaintext hidden");
                rejects(IOException.class,()->new NoteStore(scope),"whole pre-memory intent participates in NoteStore recovery inventory");
                rejects(IOException.class,active::recover,"whole pre-memory intent participates in audio recovery inventory");
                invariant.run();check(model.commits==0&&probe.commits==3&&model.uniqueProposals(),"pre-memory recovery preserves SQL, aliases and ciphertext and retries original committer");
                probe.failBeforeMemory=false;
                rejects(IOException.class,()->notes.delete(id),"whole retry now fails after cache update");
                invariant.run();check(model.memory.containsKey(id)&&scope.absent(name,id),"cache-first phase still has no durable authority for cleanup");
                Work[] blocked={()->new NoteStore(scope),()->NoteStore.sweepExpired(scope,()->false,stage->{}),active::recover,()->active.retryDeletedCleanup(()->false),()->active.purgeDeleted(id)};
                for(Work work:blocked){int prior=probe.commits;rejects(IOException.class,work,"cleanup with uncertain whole barrier");invariant.run();check(probe.commits==prior+1,"each cleanup preserves SQL, keys and file and calls original pending committer");}
                check(model.durableWrites==0&&model.uniqueTokens(id),"all cleanup entry points use distinct retry tokens without disk writes");
                probe.beforeCommit=null;audio.close();audio=null;
                rejects(IOException.class,()->new AudioSegmentStore(scope),"ordinary AudioStore reopen with uncertain barrier");
                model.failure=Failure.ERROR;
                try{new AudioSegmentStore(scope);throw new AssertionError("AudioStore accepted failing marker Error");}
                catch(CommitError expected){check(expected==model.commitError,"AudioStore constructor preserves original commit Error identity");}
                model.failure=Failure.FALSE;
                rejects(IOException.class,()->new AudioSegmentStore(scope),"constructor after Error releases writer lock and retries marker");
                try(SQLiteDatabase raw=SQLiteDatabase.openDatabase(new File(scope.directory,"audio-segments/index.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                    check(unchanged.equals(rows(raw))&&scope.alias("audio",id)&&scope.alias("note",id)&&scope.absent(name,id)&&Arrays.equals(old,Files.readAllBytes(scope.note(id).toPath())),"false and Error audio constructors release lock without SQL, key or file cleanup");
                }
                model.failure=Failure.NONE;DurableDeletionMarkers.mark(scope,name,id);
                check(scope.durable(name,id)&&scope.alias("audio",id)&&scope.alias("note",id)&&Arrays.equals(old,Files.readAllBytes(scope.note(id).toPath())),"explicit durable confirmation alone does not remove aliases or note file");
                audio=new AudioSegmentStore(scope);SQLiteDatabase finalDb=database(audio);
                check(!scope.alias("audio",id)&&number(finalDb,"SELECT deleted=1 AND wrapped IS NULL FROM frames WHERE id=?",frame)==1,"confirmed audio recovery revokes wrapped DEK and alias");
                check(scope.alias("note",id),"audio purge does not remove independent note alias");
                new NoteStore(scope);
                check(!scope.alias("note",id)&&!scope.note(id).exists()&&scope.durable(name,id),"confirmed NoteStore recovery removes note behind persistent barrier");
                short[] retained=audio.read(neighborFrame);try{check(retained.length==40000&&retained[0]==619&&scope.alias("audio",neighbor),"unrelated audio remains readable");}finally{Arrays.fill(retained,(short)0);}
                String done=rows(finalDb);int attempts=model.commits;audio.recover();audio.retryDeletedCleanup(()->false);new NoteStore(scope);
                check(done.equals(rows(finalDb))&&model.commits==attempts,"confirmed cleanup is logically idempotent");
            }finally{probe.beforeCommit=null;if(audio!=null)audio.close();}
        }
    }
    private static void identityAndReentry(Context base)throws Exception{
        try(Scope scope=new Scope(base)){
            String name="deletion-barriers",marker=UUID.randomUUID().toString();PreferencesModel model=scope.model(name);model.failure=Failure.FALSE;
            rejects(IOException.class,()->DurableDeletionMarkers.mark(scope,name,marker),"initial direct marker");int attempts=model.commits;
            SharedPreferences changed=model.newProxy();Context differentIdentity=new ContextWrapper(scope){@Override public SharedPreferences getSharedPreferences(String requested,int mode){return requested.equals(name)?changed:scope.getSharedPreferences(requested,mode);}};
            rejects(IOException.class,()->DurableDeletionMarkers.requireDurable(differentIdentity,name,marker),"same root with different preference identity");
            check(model.commits==attempts&&scope.absent(name,marker),"identity mismatch cannot bypass pending persistence");
            try(Scope other=new Scope(base)){
                Context differentRoot=new ContextWrapper(other){@Override public SharedPreferences getSharedPreferences(String requested,int mode){return requested.equals(name)?model.preferences:other.getSharedPreferences(requested,mode);}};
                rejects(IOException.class,()->DurableDeletionMarkers.requireDurable(differentRoot,name,marker),"same preference identity with different root");
                check(model.commits==attempts&&scope.absent(name,marker),"reverse identity mismatch makes no commit");
            }
            final boolean[] nested={false},parallel={false};
            model.callback=()->{
                try{DurableDeletionMarkers.requireDurable(scope,name,marker);throw new AssertionError("reentrant confirmation accepted");}catch(IOException expected){nested[0]=true;}
                FutureTask<Boolean> task=new FutureTask<>(()->{try{DurableDeletionMarkers.requireDurable(scope,name,marker);return false;}catch(IOException expected){return true;}});
                Thread thread=new Thread(task,"marker-reentry-fixture");thread.setDaemon(true);thread.start();
                try{parallel[0]=task.get(2,TimeUnit.SECONDS);}catch(Exception failure){throw new AssertionError("commit callback held registry lock or blocked",failure);}
            };
            model.failure=Failure.NONE;DurableDeletionMarkers.requireDurable(scope,name,marker);model.callback=null;
            check(nested[0]&&parallel[0],"same-thread and other-thread callbacks fail closed without waiting for commit");
            check(scope.durable(name,marker)&&model.uniqueTokens(marker)&&model.durableWrites==1,"outer retry succeeds once after rejected reentry");
            int confirmed=model.commits;DurableDeletionMarkers.requireDurable(scope,name,marker);
            check(model.commits==confirmed,"outer ownership and pending entry are released after confirmation");
        }
    }
    public static int run(Instrumentation instrumentation)throws Exception{
        Context base=instrumentation.getTargetContext();if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        KeyguardManager keyguard=base.getSystemService(KeyguardManager.class);if(!keyguard.isDeviceSecure()||keyguard.isDeviceLocked())throw new SecurityException("unlocked authenticated fixture required");
        checks=0;for(Failure failure:new Failure[]{Failure.FALSE,Failure.THROW,Failure.ERROR,Failure.BEFORE_MEMORY})notePending(base,failure);
        wholePending(base);identityAndReentry(base);return checks;
    }
    private DeletionMarkerChecks(){}
}

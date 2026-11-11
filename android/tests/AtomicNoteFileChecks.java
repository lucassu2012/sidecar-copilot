package local.sidecar.copilot;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;

/** Real native NoteStore/AtomicFile with synthetic ciphertext layouts and explicitly injected
 * silent file-operation failures. These are not OS crash, flash erasure or OEM recovery tests. */
public final class AtomicNoteFileChecks {
    private static int checks;
    private interface Work {void run()throws Exception;}
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError("atomic note file "+checks+": "+label);checks++;}
    private static void rejects(Class<? extends Exception> type,Work work,String label)throws Exception {
        try{work.run();throw new AssertionError(label+" unexpectedly succeeded");}
        catch(Exception failure){if(!type.isInstance(failure))throw failure;checks++;}
    }
    private enum Mode { NORMAL,KEEP_DELETE_COMPANIONS,FINISH_WITHOUT_RENAME,FINISH_DROP_NEW,READ_WITHOUT_RECOVERY }
    private static final class Probe implements NoteStore.AccessProbe {
        final List<String> stages=new ArrayList<>(),reads=new ArrayList<>(),deletedFiles=new ArrayList<>(),trace=new ArrayList<>();
        Mode mode=Mode.NORMAL;
        File target;
        int finishes,failedWrites,deletes,interceptedReads;
        boolean deletesLocked=true;
        Runnable afterSelectedDelete;
        @Override public void at(String stage){stages.add(stage);trace.add(stage);}
        @Override public AtomicFile atomicFile(File base){
            final boolean selected=base.equals(target);
            return new AtomicFile(base){
                @Override public byte[] readFully()throws IOException {
                    reads.add(base.getName());trace.add("atomic_read:"+base.getName());
                    if(selected&&mode==Mode.READ_WITHOUT_RECOVERY){interceptedReads++;return Files.readAllBytes(base.toPath());}
                    return super.readFully();
                }
                @Override public void finishWrite(FileOutputStream out){
                    if(selected&&(mode==Mode.FINISH_WITHOUT_RENAME||mode==Mode.FINISH_DROP_NEW)){
                        finishes++;
                        try{out.close();}catch(IOException unexpected){throw new IllegalStateException("fixture stream close",unexpected);}
                        if(mode==Mode.FINISH_DROP_NEW){
                            File pending=new File(base.getPath()+".new");
                            if(!pending.delete())throw new AssertionError("fixture pending removal failed");
                        }
                        return;
                    }
                    super.finishWrite(out);
                }
                @Override public void failWrite(FileOutputStream out){if(selected)failedWrites++;super.failWrite(out);}
                @Override public void delete(){
                    deletedFiles.add(base.getName());
                    if(selected&&mode==Mode.KEEP_DELETE_COMPANIONS){
                        deletes++;deletesLocked&=Thread.holdsLock(MeetingPrivacy.LOCK);
                        if(base.exists()&&!base.delete())throw new AssertionError("fixture base removal failed");
                        if(afterSelectedDelete!=null)afterSelectedDelete.run();
                        return; // Deliberately models AtomicFile.delete silently leaving auxiliary files.
                    }
                    super.delete();
                    if(selected&&afterSelectedDelete!=null)afterSelectedDelete.run();
                }
            };
        }
        void reset(){stages.clear();reads.clear();deletedFiles.clear();trace.clear();finishes=0;failedWrites=0;deletes=0;interceptedReads=0;deletesLocked=true;afterSelectedDelete=null;}
    }
    private static final class Scope extends ContextWrapper {
        final String prefix="atomic-note-check-"+UUID.randomUUID()+"-";
        final File directory;
        final Set<String> preferences=new HashSet<>();
        Scope(Context base)throws IOException {
            super(base);directory=new File(base.getNoBackupFilesDir(),prefix);
            if(!directory.mkdir())throw new IOException("fixture directory unavailable");
        }
        @Override public File getNoBackupFilesDir(){return directory;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            String scoped=prefix+name;preferences.add(scoped);return getBaseContext().getSharedPreferences(scoped,mode);
        }
        void dispose()throws IOException {
            for(String name:preferences)getBaseContext().deleteSharedPreferences(name);
            removeOwned(directory);
        }
        private void removeOwned(File file)throws IOException {
            // All descendants are created only by this randomly scoped fixture. Never follow links.
            if(!file.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator)&&!file.equals(directory))throw new IOException("fixture cleanup escaped scope");
            if(Files.isSymbolicLink(file.toPath()))throw new IOException("unexpected fixture link");
            if(file.isDirectory()){
                File[] children=file.listFiles();if(children==null)throw new IOException("fixture inventory unavailable");
                for(File child:children)removeOwned(child);
            }
            if(file.exists()&&!file.delete())throw new IOException("fixture cleanup pending");
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Scope context;
        final Probe probe=new Probe();
        final Set<String> ids=new LinkedHashSet<>();
        final NoteStore store;
        final KeyStore keys;
        Fixture(Context base)throws Exception {
            context=new Scope(base);keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            store=new NoteStore(context,()->{},probe);
        }
        String create(String title)throws Exception {String id=store.create(title,true);ids.add(id);return id;}
        File base(String id){return new File(new File(context.directory,"meetings"),id+".enc");}
        File side(String id,String suffix){return new File(base(id).getPath()+suffix);}
        boolean absent(String id){return !base(id).exists()&&!side(id,".new").exists()&&!side(id,".bak").exists();}
        void move(String id,String suffix)throws IOException {
            File source=base(id),destination=side(id,suffix);
            if(destination.exists()||!source.renameTo(destination))throw new IOException("fixture rename failed");
        }
        boolean noteKey(String id)throws Exception{return keys.containsAlias("sidecar.note."+id);}
        boolean audioKey(String id)throws Exception{return keys.containsAlias("sidecar.audio."+id);}
        boolean barred(String id){return context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).contains(id);}
        SharedPreferences clock(){return context.getSharedPreferences("retention-clock",Context.MODE_PRIVATE);}
        NoteStore reopen()throws Exception{return new NoteStore(context,()->{},probe);}
        @Override public void close()throws Exception {
            probe.mode=Mode.NORMAL;
            try{for(String id:ids){keys.deleteEntry("sidecar.note."+id);keys.deleteEntry("sidecar.audio."+id);}}
            finally{context.dispose();}
        }
    }
    private static void restorePreferences(SharedPreferences preferences,Map<String,?> values)throws IOException {
        SharedPreferences.Editor edit=preferences.edit().clear();
        for(Map.Entry<String,?> entry:values.entrySet()){
            Object value=entry.getValue();String key=entry.getKey();
            if(value instanceof Long)edit.putLong(key,(Long)value);
            else if(value instanceof Integer)edit.putInt(key,(Integer)value);
            else if(value instanceof String)edit.putString(key,(String)value);
            else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
            else throw new IOException("unexpected fixture preference type");
        }
        if(!edit.commit())throw new IOException("fixture preferences unavailable");
    }
    private static void writeFixture(NoteStore store,String id,JSONObject body)throws Exception {
        Method writer=NoteStore.class.getDeclaredMethod("write",String.class,JSONObject.class);writer.setAccessible(true);
        try{writer.invoke(store,id,body);}catch(InvocationTargetException failure){
            Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;
        }
    }
    private static void expire(Fixture f,String id)throws Exception {
        JSONObject body=f.store.read(id);NoteRetentionChecks.expireMeetingFixture(body,NoteRetentionChecks.fixtureNow(f.store)-1000);writeFixture(f.store,id,body);
    }
    private static void title(NoteStore store,String id,String expected,String label)throws Exception {
        JSONObject body=store.read(id);check(body.getString("id").equals(id)&&body.getString("title").equals(expected),label);
    }
    private static void backupClockAndExpiry(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic recoverable backup"),keep=f.create("synthetic retained neighbor");
            byte[] original=Files.readAllBytes(f.base(id).toPath());f.move(id,".bak");
            check(f.store.ids().contains(id)&&!f.base(id).exists()&&f.side(id,".bak").isFile(),"backup-only item is discoverable without deleting its committed copy");
            title(f.store,id,"synthetic recoverable backup","normal AtomicFile restores and reads backup-only ciphertext");
            check(Arrays.equals(original,Files.readAllBytes(f.base(id).toPath()))&&!f.side(id,".bak").exists()&&!f.side(id,".new").exists()&&f.noteKey(id),"successful backup recovery preserves committed bytes and key");
            title(f.store,keep,"synthetic retained neighbor","backup recovery preserves other meeting");

            f.move(id,".bak");Map<String,?> saved=new HashMap<>(f.clock().getAll());
            if(!f.clock().edit().clear().commit())throw new IOException("fixture missing-clock setup failed");
            f.probe.reset();rejects(IllegalStateException.class,()->f.store.read(id),"missing clock precedes backup restoration");
            check(!f.base(id).exists()&&Arrays.equals(original,Files.readAllBytes(f.side(id,".bak").toPath()))&&f.clock().getAll().isEmpty()&&f.probe.reads.isEmpty()&&!f.probe.stages.contains("before_decrypt"),"missing anchor neither restores backup nor initializes a replacement clock");
            restorePreferences(f.clock(),saved);
            if(!f.clock().edit().putInt("boot",((Integer)saved.get("boot"))+1).commit())throw new IOException("fixture boot mismatch setup failed");
            Map<String,?> uncertain=new HashMap<>(f.clock().getAll());f.probe.reset();
            rejects(IllegalStateException.class,()->f.store.read(id),"untrusted clock precedes backup restoration");
            check(!f.base(id).exists()&&Arrays.equals(original,Files.readAllBytes(f.side(id,".bak").toPath()))&&f.clock().getAll().equals(uncertain)&&f.probe.reads.isEmpty()&&!f.probe.stages.contains("before_decrypt"),"untrusted clock leaves backup and anchor unchanged before decryption");
            restorePreferences(f.clock(),saved);title(f.store,id,"synthetic recoverable backup","restoring fixture anchor permits normal backup recovery");

            expire(f,id);f.move(id,".bak");
            check(f.store.ids().contains(id)&&f.noteKey(id),"expired backup remains a candidate until adjudicated");
            check(f.store.sweepExpired(()->false)==1,"backup-only expiry is discovered and counted");
            check(f.barred(id)&&f.absent(id)&&!f.noteKey(id)&&!f.store.ids().contains(id),"backup expiry commits barrier revokes key and removes all companions");
            rejects(SecurityException.class,()->f.store.read(id),"expired backup stays inaccessible");
            title(f.store,keep,"synthetic retained neighbor","backup expiry preserves valid neighboring meeting");
        }
    }
    private static void loneNewDoesNotHideDueBackup(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String a=f.create("synthetic pending A"),b=f.create("synthetic pending B"),keep=f.create("synthetic scan neighbor");
            // Public inventory is sorted: force the ambiguous pending item to fail before the due backup.
            String pending=a.compareTo(b)<0?a:b,due=a.compareTo(b)<0?b:a;
            byte[] uncommitted=Files.readAllBytes(f.base(pending).toPath());f.move(pending,".new");
            check(!f.store.ids().contains(pending)&&f.side(pending,".new").isFile()&&!f.base(pending).exists(),"lone new is not presented as committed meeting");
            f.probe.reset();rejects(IOException.class,()->f.store.read(pending),"lone new is not promoted by explicit read");
            check(Arrays.equals(uncommitted,Files.readAllBytes(f.side(pending,".new").toPath()))&&f.noteKey(pending)&&!f.barred(pending)&&!f.probe.stages.contains("before_decrypt"),"failed pending read preserves uncommitted copy and does not claim revocation");
            expire(f,due);f.move(due,".bak");f.probe.reset();
            rejects(IOException.class,()->f.store.sweepExpired(()->false),"scan reports unresolved pending item after other expiry work");
            check(f.probe.trace.indexOf("before_pending_read")>=0&&f.probe.trace.indexOf("atomic_read:"+due+".enc")>f.probe.trace.indexOf("before_pending_read"),"scan continues beyond earlier authenticated pending retention failure");
            check(f.barred(due)&&f.absent(due)&&!f.noteKey(due),"same scan still revokes and cleans independent expired backup");
            check(!f.barred(pending)&&f.noteKey(pending)&&!f.base(pending).exists()&&Arrays.equals(uncommitted,Files.readAllBytes(f.side(pending,".new").toPath())),"scan failure keeps lone new uncommitted and available for retry");
            title(f.store,keep,"synthetic scan neighbor","scan preserves successful live neighbor");
            f.probe.reset();rejects(IOException.class,()->f.reopen().sweepExpired(()->false),"new instance keeps reporting unresolved pending item");
            check(f.barred(due)&&f.absent(due)&&!f.noteKey(due)&&f.noteKey(pending)&&f.side(pending,".new").exists(),"retry neither revives expiry nor silently discards pending bytes");
        }
    }
    private static void checkedDeletionAndRetry(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic deletion companions"),keep=f.create("synthetic delete neighbor");
            try(AudioSegmentStore audio=new AudioSegmentStore(f.context)){audio.createSession(8000,id);}
            byte[] bytes=Files.readAllBytes(f.base(id).toPath());Files.write(f.side(id,".new").toPath(),bytes);Files.write(f.side(id,".bak").toPath(),bytes);
            check(f.noteKey(id)&&f.audioKey(id)&&f.base(id).exists()&&f.side(id,".new").exists()&&f.side(id,".bak").exists(),"deletion fixture owns both aliases and all three file paths");
            f.probe.target=f.base(id);f.probe.mode=Mode.KEEP_DELETE_COMPANIONS;f.probe.reset();
            rejects(IOException.class,()->f.store.delete(id),"silent companion removal failure is reported");
            check(f.barred(id)&&!f.noteKey(id)&&!f.audioKey(id)&&!f.base(id).exists(),"deletion authority and both key revocations precede file failure");
            check(f.side(id,".new").exists()&&f.side(id,".bak").exists()&&f.probe.deletes==1&&f.probe.deletesLocked,"failed delete retains visible retry evidence under mutation lock");
            rejects(SecurityException.class,()->f.store.read(id),"existing instance refuses deleted companion-only meeting");
            check(!f.store.ids().contains(id),"public ids respect deletion barrier over backup candidate");
            rejects(IOException.class,()->f.reopen(),"constructor retries and reports unresolved companion deletion");
            check(f.probe.deletes==2&&f.probe.deletesLocked&&f.barred(id)&&!f.noteKey(id)&&!f.audioKey(id)&&f.side(id,".new").exists()&&f.side(id,".bak").exists(),"constructor cleanup uses same lock and cannot recreate removed keys");
            title(f.store,keep,"synthetic delete neighbor","targeted delete fault leaves live neighbor readable");
            f.probe.mode=Mode.NORMAL;NoteStore recovered=f.reopen();
            check(f.absent(id)&&f.barred(id)&&!f.noteKey(id)&&!f.audioKey(id),"constructor retry removes all three paths without clearing deletion authority");
            rejects(SecurityException.class,()->recovered.read(id),"recovered instance still rejects deleted meeting");
            recovered.delete(id);check(f.absent(id)&&f.barred(id),"explicit deletion retry is idempotent");
            title(recovered,keep,"synthetic delete neighbor","successful deletion recovery preserves neighbor");
        }
    }
    private static void silentWriteFailure(Context base)throws Exception {
        for(Mode mode:new Mode[]{Mode.FINISH_WITHOUT_RENAME,Mode.FINISH_DROP_NEW})try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic write base"),keep=f.create("synthetic write neighbor");
            JSONObject before=f.store.read(id);byte[] original=Files.readAllBytes(f.base(id).toPath());
            f.probe.target=f.base(id);f.probe.mode=mode;f.probe.reset();
            rejects(IOException.class,()->f.store.append(id,before.getLong("revision"),"synthetic uncommitted edit","unassigned"),"silent finish failure cannot report successful mutation");
            check(f.probe.finishes==1&&f.probe.failedWrites==1,"failed postcondition invokes abort for the selected atomic write");
            check(Arrays.equals(original,Files.readAllBytes(f.base(id).toPath()))&&!f.side(id,".new").exists()&&!f.side(id,".bak").exists(),"silent finish preserves old base exactly and failWrite removes pending bytes");
            f.probe.mode=Mode.NORMAL;JSONObject after=f.reopen().read(id);
            check(after.getLong("revision")==before.getLong("revision")&&after.getJSONArray("attachments").length()==before.getJSONArray("attachments").length()&&f.noteKey(id)&&!f.barred(id),"failed edit leaves durable revision and content unchanged without deleting meeting");
            title(f.store,keep,"synthetic write neighbor","file-targeted write fault preserves independent meeting");
            f.store.append(id,before.getLong("revision"),"synthetic committed retry","unassigned");JSONObject retried=f.store.read(id);
            check(retried.getLong("revision")==before.getLong("revision")+1&&retried.getJSONArray("attachments").length()==1&&retried.getJSONArray("attachments").getJSONObject(0).getString("text").equals("synthetic committed retry"),"retry commits one revision with expected synthetic body");
            check(!Arrays.equals(original,Files.readAllBytes(f.base(id).toPath()))&&!f.side(id,".new").exists()&&!f.side(id,".bak").exists(),"successful retry changes base and leaves no companion");
        }
    }
    private static void silentReadFailure(Context base)throws Exception {
        for(String suffix:new String[]{".bak",".new"})try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic read base"),keep=f.create("synthetic read neighbor");
            byte[] original=Files.readAllBytes(f.base(id).toPath());Files.write(f.side(id,suffix).toPath(),original);
            f.probe.target=f.base(id);f.probe.mode=Mode.READ_WITHOUT_RECOVERY;f.probe.reset();
            rejects(IOException.class,()->f.store.read(id),"read cannot accept base while companion recovery is unresolved");
            boolean pending=suffix.equals(".new");
            check(f.probe.interceptedReads==(pending?0:1)&&!f.probe.stages.contains("before_decrypt")&&!f.probe.stages.contains("before_parse"),pending?"unknown pending is refused before AtomicFile can discard it":"post-read backup check rejects before key use and body processing");
            check(Arrays.equals(original,Files.readAllBytes(f.base(id).toPath()))&&Arrays.equals(original,Files.readAllBytes(f.side(id,suffix).toPath()))&&f.noteKey(id)&&!f.barred(id),"failed read preserves both files and avoids falsely claiming deletion");
            title(f.store,keep,"synthetic read neighbor","targeted read recovery fault does not affect neighbor");
            f.probe.mode=Mode.NORMAL;f.probe.reset();
            if(pending){
                // Changed conservative contract: retry may not silently discard an unknown new
                // copy, even when this fixture placed bytes identical to the current base.
                rejects(IOException.class,()->f.store.read(id),"ordinary retry still refuses unproven pending disposition");
                check(f.probe.reads.isEmpty()&&!f.probe.stages.contains("before_decrypt")&&Arrays.equals(original,Files.readAllBytes(f.base(id).toPath()))&&Arrays.equals(original,Files.readAllBytes(f.side(id,suffix).toPath()))&&f.noteKey(id)&&!f.barred(id),"normal retry preserves both copies before any AtomicFile or key operation");
                if(!f.side(id,suffix).delete())throw new IOException("fixture-only pending removal failed");
                f.probe.reset();
            }
            title(f.store,id,"synthetic read base",pending?"base becomes readable after explicit fixture-only pending removal":"normal AtomicFile resolves byte-identical backup on retry");
            check(f.probe.stages.contains("before_decrypt")&&f.probe.stages.contains("before_parse")&&!f.side(id,".new").exists()&&!f.side(id,".bak").exists()&&Arrays.equals(original,Files.readAllBytes(f.base(id).toPath())),"successful permitted read reaches decryption with exact base and no companions");
        }
    }
    private static void restoredBackupRespectsNoteBarrier(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic note barrier");
            f.store.append(id,0,"synthetic removed note","unassigned");f.store.append(id,1,"synthetic retained note","meeting_background");
            JSONObject old=f.store.read(id);String removed=old.getJSONArray("attachments").getJSONObject(0).getString("id"),retained=old.getJSONArray("attachments").getJSONObject(1).getString("id");
            byte[] oldCipher=Files.readAllBytes(f.base(id).toPath());f.store.deleteNote(id,removed,2);
            JSONObject filtered=f.store.read(id);
            check(filtered.getJSONArray("attachments").length()==1&&filtered.getJSONArray("attachments").getJSONObject(0).getString("id").equals(retained),"ordinary note barrier preserves the unrelated attachment");
            check(f.context.getSharedPreferences("note-deletion-barriers",Context.MODE_PRIVATE).contains(id+":"+removed)&&f.noteKey(id),"independent note barrier persists while meeting key remains usable");
            Files.write(f.side(id,".bak").toPath(),oldCipher);if(!f.base(id).delete())throw new IOException("fixture base replacement failed");
            NoteStore reopened=f.reopen();check(reopened.ids().contains(id)&&f.side(id,".bak").exists(),"old backup stays discoverable with independent note barrier");
            JSONObject restored=reopened.read(id);
            check(restored.getJSONArray("attachments").length()==1&&restored.getJSONArray("attachments").getJSONObject(0).getString("id").equals(retained)&&restored.getJSONArray("attachments").getJSONObject(0).getString("text").equals("synthetic retained note"),"backup recovery filters deleted note while delivering exact retained neighbor");
            check(restored.getLong("revision")==filtered.getLong("revision")&&restored.getString("title").equals("synthetic note barrier"),"backup cleanup reestablishes expected revision without changing title");
            check(!f.side(id,".bak").exists()&&!f.side(id,".new").exists()&&!Arrays.equals(oldCipher,Files.readAllBytes(f.base(id).toPath())),"filtered backup is rewritten to committed base without auxiliary copies");
            JSONObject again=reopened.read(id);check(again.getLong("revision")==restored.getLong("revision")&&again.getJSONArray("attachments").length()==1&&f.noteKey(id)&&!f.barred(id),"successful filtered recovery is idempotent and does not delete the whole meeting");
        }
    }
    private static void maintenanceContinuesPastOldDeletion(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String old=f.create("synthetic old cleanup"),due=f.create("synthetic independent due"),keep=f.create("synthetic maintenance neighbor");
            expire(f,due);f.move(due,".bak");
            byte[] stale=Files.readAllBytes(f.base(old).toPath());Files.write(f.side(old,".new").toPath(),stale);Files.write(f.side(old,".bak").toPath(),stale);
            f.probe.target=f.base(old);f.probe.mode=Mode.KEEP_DELETE_COMPANIONS;
            rejects(IOException.class,()->f.store.delete(old),"old deletion fixture retains failed companion cleanup");
            rejects(IOException.class,()->f.reopen(),"ordinary constructor still refuses old cleanup failure");
            check(f.barred(old)&&!f.noteKey(old)&&!f.base(old).exists()&&f.side(old,".new").exists()&&f.side(old,".bak").exists()&&!f.barred(due)&&f.noteKey(due)&&f.side(due,".bak").exists(),"failed ordinary open leaves independent due backup unprocessed");
            f.probe.reset();
            rejects(IOException.class,()->NoteStore.sweepExpired(f.context,()->false,f.probe),"scoped maintenance reports old cleanup error after independent expiry");
            check(f.probe.deletes==1&&f.probe.deletesLocked&&f.probe.reads.contains(due+".enc")&&f.barred(due)&&!f.noteKey(due)&&f.absent(due),"maintenance skips constructor recovery trap and still revokes due backup");
            check(f.barred(old)&&!f.noteKey(old)&&!f.base(old).exists()&&Arrays.equals(stale,Files.readAllBytes(f.side(old,".new").toPath()))&&Arrays.equals(stale,Files.readAllBytes(f.side(old,".bak").toPath())),"old failed cleanup remains barred with both original companions for retry");
            rejects(SecurityException.class,()->f.store.read(old),"old residual files remain inaccessible after maintenance failure");
            Map<String,?> barriers=new HashMap<>(f.context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).getAll());f.probe.reset();
            rejects(IOException.class,()->NoteStore.sweepExpired(f.context,()->false,f.probe),"fresh scoped maintenance instance retries remaining old cleanup failure");
            check(f.probe.deletes==1&&!f.probe.reads.contains(due+".enc")&&f.absent(due)&&!f.noteKey(due)&&f.context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).getAll().equals(barriers),"fresh maintenance retry preserves completed revocation and does not duplicate authority");
            title(f.store,keep,"synthetic maintenance neighbor","old cleanup fault does not damage valid maintenance neighbor");
            f.probe.mode=Mode.NORMAL;NoteStore recovered=f.reopen();
            check(f.absent(old)&&f.absent(due)&&f.barred(old)&&f.barred(due)&&!f.noteKey(old)&&!f.noteKey(due),"ordinary construction succeeds after companion fault clears without reviving either meeting");
            check(NoteStore.sweepExpired(f.context,()->false)==0,"production scoped maintenance is idempotent after both cleanups complete");
            title(recovered,keep,"synthetic maintenance neighbor","normal recovery retains independent live meeting");
        }
    }
    private static void barrierOnlyAliasesPrecedeClockGate(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String deleted=f.create("synthetic barrier only"),live=f.create("synthetic clock protected neighbor");
            try(AudioSegmentStore audio=new AudioSegmentStore(f.context)){audio.createSession(8000,deleted);}
            if(!f.base(deleted).delete())throw new IOException("fixture barrier-only file removal failed");
            if(!f.context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).edit().putBoolean(deleted,true).commit())throw new IOException("fixture deletion authority unavailable");
            byte[] liveCipher=Files.readAllBytes(f.base(live).toPath());Map<String,?> saved=new HashMap<>(f.clock().getAll());
            check(f.absent(deleted)&&f.noteKey(deleted)&&f.audioKey(deleted)&&f.barred(deleted)&&!f.store.ids().contains(deleted),"barrier-only fixture retains both aliases despite no file candidate");
            if(!f.clock().edit().putInt("boot",((Integer)saved.get("boot"))+1).commit())throw new IOException("fixture uncertain clock setup failed");
            Map<String,?> uncertain=new HashMap<>(f.clock().getAll());f.probe.reset();
            rejects(IllegalStateException.class,()->NoteStore.sweepExpired(f.context,()->false,f.probe),"untrusted clock still rejects unrelated live body after authorized deletion work");
            check(f.probe.deletedFiles.contains(deleted+".enc")&&f.absent(deleted)&&f.barred(deleted)&&!f.noteKey(deleted)&&!f.audioKey(deleted),"barrier-only aliases are revoked before any clock-dependent meeting read regardless of UUID order");
            check(f.probe.reads.isEmpty()&&!f.probe.stages.contains("before_read")&&!f.probe.stages.contains("before_decrypt")&&f.noteKey(live)&&!f.barred(live)&&Arrays.equals(liveCipher,Files.readAllBytes(f.base(live).toPath()))&&f.clock().getAll().equals(uncertain),"clock rejection preserves live neighbor and anchor before AtomicFile or decryption");
            restorePreferences(f.clock(),saved);Map<String,?> barriers=new HashMap<>(f.context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).getAll());
            check(NoteStore.sweepExpired(f.context,()->false,f.probe)==0&&f.context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).getAll().equals(barriers)&&!f.noteKey(deleted)&&!f.audioKey(deleted),"fresh maintenance retry keeps barrier-only key deletion idempotent");
            NoteStore reopened=f.reopen();rejects(SecurityException.class,()->reopened.read(deleted),"ordinary reopen honors barrier without any encrypted file");
            title(reopened,live,"synthetic clock protected neighbor","restored fixture clock permits unchanged live neighbor");
        }
    }
    private static void maintenanceCancellation(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String old=f.create("synthetic cancelled cleanup"),due=f.create("synthetic cancelled due backup");
            expire(f,due);f.move(due,".bak");byte[] dueCipher=Files.readAllBytes(f.side(due,".bak").toPath());
            byte[] stale=Files.readAllBytes(f.base(old).toPath());Files.write(f.side(old,".new").toPath(),stale);
            f.probe.target=f.base(old);f.probe.mode=Mode.KEEP_DELETE_COMPANIONS;
            rejects(IOException.class,()->f.store.delete(old),"cancellation fixture retains an authorized pending cleanup");
            Map<String,?> barriers=new HashMap<>(f.context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).getAll());f.probe.reset();
            Context mustNotOpen=new ContextWrapper(f.context){@Override public File getNoBackupFilesDir(){throw new AssertionError("cancelled maintenance constructed a store");}};
            rejects(InterruptedIOException.class,()->NoteStore.sweepExpired(mustNotOpen,()->true,f.probe),"pre-cancel rejects before constructing maintenance store");
            check(f.probe.deletedFiles.isEmpty()&&f.probe.reads.isEmpty()&&f.noteKey(due)&&!f.barred(due)&&Arrays.equals(dueCipher,Files.readAllBytes(f.side(due,".bak").toPath()))&&Arrays.equals(stale,Files.readAllBytes(f.side(old,".new").toPath()))&&f.context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).getAll().equals(barriers),"pre-cancel performs no file cleanup key revocation or body recovery");
            boolean[] cancelled={false};f.probe.afterSelectedDelete=()->cancelled[0]=true;
            rejects(InterruptedIOException.class,()->NoteStore.sweepExpired(f.context,()->cancelled[0],f.probe),"in-flight cancellation takes priority over pending cleanup IOException");
            check(cancelled[0]&&f.probe.deletes==1&&f.barred(old)&&!f.noteKey(old)&&Arrays.equals(stale,Files.readAllBytes(f.side(old,".new").toPath())),"in-flight cancel preserves existing deletion and its unresolved physical retry");
            check(f.probe.reads.isEmpty()&&!f.probe.deletedFiles.contains(due+".enc")&&f.noteKey(due)&&!f.barred(due)&&Arrays.equals(dueCipher,Files.readAllBytes(f.side(due,".bak").toPath())),"cancelled run never advances to independent due meeting");
            f.probe.mode=Mode.NORMAL;f.probe.reset();cancelled[0]=false;
            check(NoteStore.sweepExpired(f.context,()->cancelled[0],f.probe)==1,"explicit retry after cancellation processes exactly the still-due meeting");
            check(f.barred(old)&&f.barred(due)&&f.absent(old)&&f.absent(due)&&!f.noteKey(old)&&!f.noteKey(due),"successful retry finishes authorized cleanup and new expiry without revival");
        }
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        Context context=instrumentation.getTargetContext();KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        if(!keyguard.isDeviceSecure()||keyguard.isDeviceLocked())throw new SecurityException("authenticated unlocked fixture required");
        checks=0;backupClockAndExpiry(context);loneNewDoesNotHideDueBackup(context);checkedDeletionAndRetry(context);silentWriteFailure(context);silentReadFailure(context);restoredBackupRespectsNoteBarrier(context);
        maintenanceContinuesPastOldDeletion(context);barrierOnlyAliasesPrecedeClockGate(context);maintenanceCancellation(context);return checks;
    }
}

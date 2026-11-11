package local.sidecar.copilot;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Synthetic authenticated-copy layouts on native storage. No recording, OS crash or flash-erasure claim. */
public final class PendingNoteCopyChecks {
    private static final String COPIES="note-expired-copies-v1";
    private static int checks;
    private interface Work {void run()throws Exception;}
    private interface StageAction {void run()throws IOException;}
    private static void check(boolean value,String label){if(!value)throw new AssertionError("pending note copy "+checks+": "+label);checks++;}
    private static void rejects(Class<? extends Exception> type,Work work,String label)throws Exception {
        try{work.run();throw new AssertionError(label+" unexpectedly succeeded");}
        catch(Exception failure){if(!type.isInstance(failure))throw failure;checks++;}
    }
    private static Throwable thrown(Work work){try{work.run();return null;}catch(Throwable failure){return failure;}}
    private static boolean zero(byte[] bytes){for(byte value:bytes)if(value!=0)return false;return true;}
    private static final class Probe implements NoteStore.AccessProbe {
        final List<String> stages=new ArrayList<>();
        String faultStage="",actionStage="";
        Throwable failure;
        StageAction action;
        byte[] clear,returnedBytes;
        File returnedFile;
        boolean clearWasPopulated,failCommit,locked=true;
        int atomicReads,commits,faultHits;
        @Override public void at(String stage)throws IOException {
            stages.add(stage);
            if(stage.startsWith("pending_copy_")||stage.startsWith("marked_copy_")||stage.equals("before_pending_read"))locked&=Thread.holdsLock(MeetingPrivacy.LOCK);
            if(stage.equals(actionStage)&&action!=null)action.run();
            hit(stage);
        }
        private void hit(String stage)throws IOException {
            if(!stage.equals(faultStage))return;
            faultHits++;
            if(failure instanceof IOException)throw (IOException)failure;
            if(failure instanceof Error)throw (Error)failure;
            throw new AssertionError("unsupported injected failure");
        }
        @Override public void ownedBuffer(String stage,byte[] buffer)throws IOException {
            if(stage.equals("note_read_clear")){clear=buffer;clearWasPopulated=!zero(buffer);}
            hit("owned:"+stage);
        }
        @Override public boolean commitDeletion(String preferences,String key,SharedPreferences.Editor edit){
            if(COPIES.equals(preferences)){commits++;if(failCommit)return false;} // Explicitly no cache/disk write in this injected branch.
            return edit.commit();
        }
        @Override public AtomicFile atomicFile(File base){
            return new AtomicFile(base){@Override public byte[] readFully()throws IOException {
                atomicReads++;
                if(base.equals(returnedFile)&&returnedBytes!=null)return returnedBytes.clone();
                return super.readFully();
            }};
        }
        void reset(){stages.clear();faultStage="";actionStage="";failure=null;action=null;clear=null;returnedBytes=null;returnedFile=null;clearWasPopulated=false;failCommit=false;locked=true;atomicReads=0;commits=0;faultHits=0;}
    }
    private static final class Scope extends ContextWrapper {
        final String prefix="pending-note-copy-"+UUID.randomUUID()+"-";
        final File directory;
        final Set<String> preferences=new HashSet<>();
        Scope(Context base)throws IOException {
            super(base);directory=new File(base.getNoBackupFilesDir(),prefix);
            if(!directory.mkdir())throw new IOException("pending-copy fixture directory unavailable");
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
            String root=directory.getCanonicalPath(),path=file.getCanonicalPath();
            if(!path.equals(root)&&!path.startsWith(root+File.separator))throw new IOException("fixture cleanup escaped scope");
            if(Files.isSymbolicLink(file.toPath()))throw new IOException("unexpected fixture link");
            if(file.isDirectory()){
                File[] children=file.listFiles();if(children==null)throw new IOException("fixture inventory unavailable");
                for(File child:children)removeOwned(child);
            }
            if(file.exists()&&!file.delete())throw new IOException("fixture cleanup incomplete");
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Scope context;
        final Probe probe=new Probe();
        final Set<String> ids=new LinkedHashSet<>();
        final KeyStore keys;
        final NoteStore store;
        Fixture(Context base)throws Exception {
            context=new Scope(base);keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            store=new NoteStore(context,()->{},probe);
        }
        String create(String title)throws Exception {String id=store.create(title,true);ids.add(id);return id;}
        File base(String id){return new File(new File(context.directory,"meetings"),id+".enc");}
        File side(String id,String suffix){return new File(base(id).getPath()+suffix);}
        byte[] bytes(String id,String suffix)throws IOException{return Files.readAllBytes(side(id,suffix).toPath());}
        boolean absent(String id){return !base(id).exists()&&!side(id,".new").exists()&&!side(id,".bak").exists();}
        boolean noteKey(String id)throws Exception{return keys.containsAlias("sidecar.note."+id);}
        boolean audioKey(String id)throws Exception{return keys.containsAlias("sidecar.audio."+id);}
        boolean barred(String id){return context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).contains(id);}
        SharedPreferences copies(){return context.getSharedPreferences(COPIES,Context.MODE_PRIVATE);}
        SharedPreferences clock(){return context.getSharedPreferences("retention-clock",Context.MODE_PRIVATE);}
        NoteStore reopen()throws Exception{return new NoteStore(context,()->{},probe);}
        byte[] pending(String id,JSONObject body)throws Exception {
            writeFixture(store,id,body);byte[] value=bytes(id,"");
            if(side(id,".new").exists()||!base(id).renameTo(side(id,".new")))throw new IOException("fixture pending rename failed");
            probe.reset();return value;
        }
        JSONObject expired(String id)throws Exception {
            JSONObject body=store.read(id);
            NoteRetentionChecks.expireMeetingFixture(body,NoteRetentionChecks.fixtureNow(store)-1000);return body;
        }
        @Override public void close()throws Exception {
            probe.reset();Throwable failure=null;
            try{
                // A failed pre-cache commit still owns an in-memory pending marker. Confirm
                // it while its scoped preferences exist, even if an assertion aborted retry.
                for(String marker:DurableDeletionMarkers.keys(context,COPIES))try{
                    DurableDeletionMarkers.requireDurable(context,COPIES,marker);
                }catch(Throwable next){failure=cleanupFailure(failure,next);}
            }catch(Throwable next){failure=cleanupFailure(failure,next);}
            finally{
                for(String id:ids)for(String prefix:new String[]{"sidecar.note.","sidecar.audio."})try{
                    keys.deleteEntry(prefix+id);
                }catch(Throwable next){failure=cleanupFailure(failure,next);}
                try{context.dispose();}catch(Throwable next){failure=cleanupFailure(failure,next);}
            }
            if(failure instanceof Exception)throw (Exception)failure;
            if(failure instanceof Error)throw (Error)failure;
            if(failure!=null)throw new AssertionError("fixture cleanup failed",failure);
        }
        private static Throwable cleanupFailure(Throwable first,Throwable next){
            if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;
        }
    }
    private static void writeFixture(NoteStore store,String id,JSONObject body)throws Exception {
        Method method=NoteStore.class.getDeclaredMethod("write",String.class,JSONObject.class);method.setAccessible(true);
        synchronized(MeetingPrivacy.LOCK){try{method.invoke(store,id,body);}catch(InvocationTargetException failure){
            Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;
        }}
    }
    private static String marker(String id,byte[] bytes)throws Exception {
        StringBuilder result=new StringBuilder(id).append(':').append(bytes.length).append(':');
        for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));
        return result.toString();
    }
    private static void restorePreferences(SharedPreferences preferences,Map<String,?> values)throws IOException {
        SharedPreferences.Editor editor=preferences.edit().clear();
        for(Map.Entry<String,?> entry:values.entrySet()){
            Object value=entry.getValue();String key=entry.getKey();
            if(value instanceof Long)editor.putLong(key,(Long)value);
            else if(value instanceof Integer)editor.putInt(key,(Integer)value);
            else if(value instanceof String)editor.putString(key,(String)value);
            else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);
            else throw new IOException("unexpected fixture preference type");
        }
        if(!editor.commit())throw new IOException("fixture preference restore failed");
    }
    private static void copyOnlyAndReplay(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic current copy"),keep=f.create("synthetic independent neighbor");
            f.store.append(id,0,"synthetic retained later note","unassigned");
            JSONObject appended=f.store.read(id),newNote=new JSONObject(appended.getJSONArray("attachments").getJSONObject(0).toString());
            // S0 precedes this independent note. S1 keeps the old title origin and adds the
            // writer-created note; no content ID receives a refreshed retention origin.
            JSONObject old=new JSONObject(appended.toString()).put("attachments",new JSONArray()).put("revision",0);
            NoteRetentionChecks.expireMeetingFixture(old,NoteRetentionChecks.fixtureNow(f.store)-1000);
            JSONObject current=new JSONObject(old.toString()).put("attachments",new JSONArray().put(newNote))
                .put("expires_at",newNote.getLong("expires_at")).put("revision",1);
            writeFixture(f.store,id,current);JSONObject live=f.store.read(id); // Normal read hides only the expired title.
            byte[] liveCipher=f.bytes(id,""),neighbor=f.bytes(keep,"");
            byte[] stale=f.pending(id,old);String key=marker(id,stale);
            short[] samples=new short[4000];Arrays.fill(samples,(short)0x2345);
            try(AudioSegmentStore audio=new AudioSegmentStore(f.context)){
                audio.createSession(8000,id);audio.recordDeclaration(id,0,System.currentTimeMillis());long token=audio.start(id,true);
                String frame=audio.append(id,token,new PcmFrameAssembler.Frame(0,samples.length,SystemClock.elapsedRealtimeNanos(),samples,"healthy"));
                audio.beginPause(id,token,samples.length);audio.finishPause(id);
                check(!f.store.ids().contains(id)&&f.noteKey(id)&&f.audioKey(id),"pending is not a saved meeting and both aliases exist");
                f.probe.reset();check(f.store.sweepExpired(()->false)==0,"copy cleanup never increments meeting expiry count");
                check(f.absent(id)&&f.copies().contains(key)&&!f.barred(id)&&f.noteKey(id)&&f.audioKey(id),"only exact stale pending bytes are removed; aliases and meeting scope survive");
                check(f.probe.locked&&f.probe.commits==1&&f.probe.stages.indexOf("pending_copy_after_barrier")<f.probe.stages.indexOf("pending_copy_before_remove"),"durable copy decision precedes removal under mutation lock");
                check(Arrays.equals(neighbor,f.bytes(keep,""))&&f.store.read(keep).getString("title").equals("synthetic independent neighbor"),"independent meeting bytes and content survive");
                Files.write(f.base(id).toPath(),liveCipher);JSONObject restored=f.reopen().read(id);
                check(restored.getJSONArray("attachments").getJSONObject(0).getString("text").equals("synthetic retained later note")&&restored.getLong("expires_at")==live.getLong("expires_at")&&restored.getLong("created_at")==old.getLong("created_at")&&restored.getLong("title_expires_at")==old.getLong("title_expires_at")&&restored.getJSONArray("attachments").getJSONObject(0).getLong("created_at")==newNote.getLong("created_at"),"newer same-ID ciphertext retains old title origin and independently created note deadline");
                short[] actual=audio.read(frame);try{check(Arrays.equals(samples,actual)&&audio.committedSamples(id)==samples.length&&audio.frameIds(id).contains(frame),"same-ID audio frame remains readable and counted");}finally{Arrays.fill(actual,(short)0);}
                for(String suffix:new String[]{"",".bak",".new"}){
                    Files.write(f.side(id,suffix).toPath(),stale);f.probe.reset();
                    rejects(SecurityException.class,()->f.reopen().read(id),"exact revoked ciphertext cannot return from "+suffix);
                    check(Arrays.equals(stale,f.bytes(id,suffix))&&(suffix.isEmpty()||Arrays.equals(liveCipher,f.bytes(id,"")))&&f.probe.atomicReads==0&&!f.probe.stages.contains("before_decrypt"),"copy barrier refuses before AtomicFile changes any restored layout");
                    check(f.noteKey(id)&&f.audioKey(id)&&!f.barred(id)&&f.copies().contains(key),"copy replay never widens revocation to aliases or meeting");
                    if(suffix.isEmpty())Files.write(f.base(id).toPath(),liveCipher);
                    else if(!f.side(id,suffix).delete())throw new IOException("fixture replay companion cleanup failed");
                }
                f.probe.reset();f.probe.returnedFile=f.base(id);f.probe.returnedBytes=stale;
                rejects(SecurityException.class,()->f.store.read(id),"actual AtomicFile bytes receive a second exact-copy check");
                check(f.probe.atomicReads==1&&!f.probe.stages.contains("before_decrypt")&&Arrays.equals(liveCipher,f.bytes(id,"")),"second check rejects injected old bytes without altering current base");
                f.probe.reset();check(f.store.read(id).getLong("revision")==live.getLong("revision")&&f.store.sweepExpired(()->false)==0,"different current ciphertext and completed copy cleanup remain usable and idempotent");
            }finally{Arrays.fill(samples,(short)0);}
        }
    }
    private static void unprovenCopiesStay(Context base)throws Exception {
        for(String scenario:new String[]{"unexpired","partial","unknown","in_person","future_end","damaged","missing_key"})try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic unresolved pending");JSONObject body=f.store.read(id);
            if(scenario.equals("partial")){
                f.store.append(id,0,"synthetic still valid attachment","meeting_background");body=f.store.read(id);
                JSONObject retained=new JSONObject(body.getJSONArray("attachments").getJSONObject(0).toString());
                NoteRetentionChecks.expireMeetingFixture(body,NoteRetentionChecks.fixtureNow(f.store)-1000);
                body.put("attachments",new JSONArray().put(retained)).put("expires_at",retained.getLong("expires_at"));
            }else if(!scenario.equals("unexpired"))NoteRetentionChecks.expireMeetingFixture(body,NoteRetentionChecks.fixtureNow(f.store)-1000);
            if(scenario.equals("unknown"))body.put("future_asset","synthetic material without a verified policy");
            if(scenario.equals("in_person"))body.put("capture_mode","in_person").put("audio_session_id",id).put("consents",new JSONArray());
            if(scenario.equals("future_end"))body.put("ended_at",NoteRetentionChecks.fixtureNow(f.store)+86400000L);
            byte[] bytes=f.pending(id,body);
            if(scenario.equals("damaged")){bytes[bytes.length-1]^=1;Files.write(f.side(id,".new").toPath(),bytes);}
            if(scenario.equals("missing_key"))f.keys.deleteEntry("sidecar.note."+id);
            Class<? extends Exception> expected=scenario.equals("missing_key")?SecurityException.class:IOException.class;
            rejects(expected,()->f.store.sweepExpired(()->false),scenario+" cannot authorize pending disposal");
            check(Arrays.equals(bytes,f.bytes(id,".new"))&&!f.base(id).exists()&&!f.side(id,".bak").exists()&&f.copies().getAll().isEmpty()&&!f.barred(id),scenario+" preserves the exact unpromoted copy without deletion authority");
            check(f.probe.commits==0&&!f.probe.stages.contains("pending_copy_before_remove")&&(scenario.equals("missing_key")||f.noteKey(id)),scenario+" never removes an alias or enters file deletion");
        }
    }
    private static void unknownClockGate(Context base)throws Exception {
        for(boolean missing:new boolean[]{true,false})try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic clock-gated pending");byte[] bytes=f.pending(id,f.expired(id));
            Map<String,?> original=new HashMap<>(f.clock().getAll());
            SharedPreferences.Editor edit=f.clock().edit();if(missing)edit.clear();else edit.putInt("boot",((Integer)original.get("boot"))+1);
            if(!edit.commit())throw new IOException("fixture clock uncertainty failed");
            Map<String,?> uncertain=new HashMap<>(f.clock().getAll());f.probe.reset();
            rejects(IllegalStateException.class,()->f.reopen().sweepExpired(()->false),"unknown pending clock gate");
            check(!f.probe.stages.contains("before_pending_read")&&!f.probe.stages.contains("before_decrypt")&&f.probe.atomicReads==0&&f.probe.clear==null,"unknown copy is rejected before ciphertext read, decryption or parsing");
            check(Arrays.equals(bytes,f.bytes(id,".new"))&&f.noteKey(id)&&f.copies().getAll().isEmpty()&&f.clock().getAll().equals(uncertain),"clock uncertainty neither removes bytes nor creates replacement anchor or marker");
            restorePreferences(f.clock(),original);
        }
    }
    private static void committedDecisionRetry(Context base)throws Exception {
        for(String point:new String[]{"after_barrier_io","after_barrier_cancel","before_remove_cancel"})try(Fixture f=new Fixture(base)){
            boolean cancellation=!point.equals("after_barrier_io"),beforeRemove=point.equals("before_remove_cancel");
            String id=f.create("synthetic durable pending decision");byte[] bytes=f.pending(id,f.expired(id));String key=marker(id,bytes);
            try(AudioSegmentStore audio=new AudioSegmentStore(f.context)){audio.createSession(8000,id);}
            boolean[] cancelled={false};
            if(cancellation){f.probe.actionStage=beforeRemove?"pending_copy_before_remove":"pending_copy_after_barrier";f.probe.action=()->cancelled[0]=true;}
            else {f.probe.faultStage="pending_copy_after_barrier";f.probe.failure=new IOException("synthetic post-marker fault");}
            rejects(cancellation?InterruptedIOException.class:IOException.class,()->f.store.sweepExpired(()->cancelled[0]),"post-marker interruption retains pending copy");
            check(f.copies().contains(key)&&Arrays.equals(bytes,f.bytes(id,".new"))&&f.noteKey(id)&&f.audioKey(id)&&!f.barred(id)&&f.probe.stages.contains("pending_copy_before_remove")==beforeRemove,"durable decision survives interruption at its specified boundary without widening scope");
            Map<String,?> recorded=new HashMap<>(f.copies().getAll());Map<String,?> originalClock=new HashMap<>(f.clock().getAll());
            SharedPreferences.Editor edit=f.clock().edit();if(cancellation)edit.putInt("boot",((Integer)originalClock.get("boot"))+1);else edit.clear();
            if(!edit.commit())throw new IOException("fixture retry clock uncertainty failed");
            Map<String,?> uncertain=new HashMap<>(f.clock().getAll());
            f.keys.deleteEntry("sidecar.note."+id); // Fixture explicitly removes the key; production copy cleanup must not do so.
            f.probe.reset();cancelled[0]=false;NoteStore reopened=f.reopen();
            if(beforeRemove){
                f.probe.actionStage="pending_copy_before_remove";f.probe.action=()->cancelled[0]=true;
                rejects(InterruptedIOException.class,()->reopened.sweepExpired(()->cancelled[0]),"known-marker replay rechecks cancellation immediately before unlink");
                check(Arrays.equals(bytes,f.bytes(id,".new"))&&f.copies().getAll().equals(recorded)&&f.audioKey(id),"cancelled known-marker replay preserves exact bytes and prior durable authorization");
                check(!f.probe.stages.contains("before_decrypt")&&f.probe.clear==null&&f.probe.commits==0,"cancelled replay does not decrypt or issue a replacement decision");
                f.probe.reset();cancelled[0]=false;
            }
            check(reopened.sweepExpired(()->false)==0&&f.absent(id),"new store completes exact authorized removal without clock or note key");
            check(f.copies().getAll().equals(recorded)&&f.clock().getAll().equals(uncertain)&&f.audioKey(id)&&!f.barred(id),"retry retains exact marker and independent audio alias without changing clock");
            check(f.probe.commits==0&&!f.probe.stages.contains("before_decrypt")&&!f.probe.stages.contains("before_parse")&&f.probe.clear==null&&f.probe.locked,"authorized retry performs no decryption, parsing or new decision");
            Files.write(f.side(id,".new").toPath(),bytes);f.probe.reset();
            check(f.reopen().sweepExpired(()->false)==0&&f.absent(id)&&f.copies().getAll().equals(recorded),"same old pending ciphertext reappearance is removed idempotently");
        }
    }
    private static void changedBeforeRemoval(Context base)throws Exception {
        for(boolean layout:new boolean[]{false,true})try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic live replacement");byte[] live=f.bytes(id,""),stale=f.pending(id,f.expired(id));String key=marker(id,stale);
            f.probe.actionStage="pending_copy_before_remove";
            f.probe.action=()->Files.write((layout?f.base(id):f.side(id,".new")).toPath(),live);
            rejects(IOException.class,()->f.store.sweepExpired(()->false),"changed bytes or family layout prevents unlink");
            check(f.copies().contains(key)&&f.noteKey(id)&&!f.barred(id)&&Arrays.equals(layout?stale:live,f.bytes(id,".new")),"removal rechecks exact pending bytes and preserves changed input");
            check(!layout||Arrays.equals(live,f.bytes(id,"")),"appearing valid base is never changed by pending removal");
            f.probe.reset();
            if(layout){
                if(!f.side(id,".new").delete())throw new IOException("fixture blocked companion cleanup failed");
                check(f.reopen().read(id).getString("title").equals("synthetic live replacement"),"independent live base remains usable after fixture companion is removed");
            }else{
                rejects(IOException.class,()->f.reopen().sweepExpired(()->false),"different still-valid ciphertext cannot consume old marker");
                check(Arrays.equals(live,f.bytes(id,".new"))&&f.copies().getAll().size()==1,"different pending bytes retain their own lifetime and gain no false authorization");
            }
        }
    }
    private static void commitRejectedBeforeWrite(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic unconfirmed copy decision");byte[] bytes=f.pending(id,f.expired(id));
            f.probe.failCommit=true;
            rejects(IOException.class,()->f.store.sweepExpired(()->false),"injected pre-write marker commit failure");
            check(f.copies().getAll().isEmpty()&&Arrays.equals(bytes,f.bytes(id,".new"))&&f.noteKey(id)&&!f.barred(id)&&!f.probe.stages.contains("pending_copy_after_barrier"),"unconfirmed marker never authorizes unlink");
            rejects(IOException.class,()->f.reopen().sweepExpired(()->false),"new instance still cannot consume failed pre-write marker");
            check(f.copies().getAll().isEmpty()&&f.side(id,".new").exists()&&f.probe.commits==2,"both explicit failed attempts leave file and preferences untouched");
            f.probe.failCommit=false;
            check(f.reopen().sweepExpired(()->false)==0&&f.absent(id)&&f.copies().contains(marker(id,bytes))&&f.noteKey(id)&&!f.barred(id),"confirmed retry removes only the pending copy");
        }
    }
    private static String[] orderedPair(Fixture f)throws Exception {
        String first=f.create("synthetic mixed recovery first"),second=f.create("synthetic mixed recovery second");
        return first.compareTo(second)<0?new String[]{first,second}:new String[]{second,first};
    }
    private static void expireBase(Fixture f,String id)throws Exception {writeFixture(f.store,id,f.expired(id));}
    private static void audioAlias(Fixture f,String id)throws Exception {
        try(AudioSegmentStore audio=new AudioSegmentStore(f.context)){audio.createSession(8000,id);}
    }
    private static void mixedUnprovenCopiesStay(Context base)throws Exception {
        for(String suffix:new String[]{".new",".bak"})try(Fixture f=new Fixture(base)){
            String[] ordered=orderedPair(f);String id=ordered[0],due=ordered[1];
            byte[] live=f.bytes(id,"");expireBase(f,id);byte[] expired=f.bytes(id,"");
            Files.write(f.side(id,suffix).toPath(),live);expireBase(f,due);audioAlias(f,id);f.probe.reset();
            // This is a deliberately assembled version layout, not a demonstrated OS crash history.
            rejects(IOException.class,()->f.store.read(id),"expired base cannot discard a different unexpired "+suffix);
            check(Arrays.equals(expired,f.bytes(id,""))&&Arrays.equals(live,f.bytes(id,suffix))&&f.probe.atomicReads==0&&!f.probe.stages.contains("before_decrypt"),"mixed unknown copies are refused before AtomicFile or plaintext processing");
            check(f.noteKey(id)&&f.audioKey(id)&&!f.barred(id)&&f.copies().getAll().isEmpty(),"old base alone cannot authorize shared-key or whole-meeting deletion");
            f.probe.reset();rejects(IOException.class,()->f.reopen().sweepExpired(()->false),"mixed-layout maintenance reports unresolved copies");
            check(f.barred(due)&&f.absent(due)&&!f.noteKey(due),"unresolved earlier ID does not prevent independent later expiry");
            check(Arrays.equals(expired,f.bytes(id,""))&&Arrays.equals(live,f.bytes(id,suffix))&&f.noteKey(id)&&f.audioKey(id)&&!f.barred(id),"maintenance preserves both ambiguous versions and both shared aliases");
            f.probe.reset();rejects(IOException.class,()->f.reopen().sweepExpired(()->false),"ordinary retry retains unproven mixed layout");
            check(Arrays.equals(expired,f.bytes(id,""))&&Arrays.equals(live,f.bytes(id,suffix))&&f.probe.atomicReads==0&&!f.barred(id)&&f.absent(due),"retry neither promotes a copy nor repeats independent deletion");
        }
    }
    private static void markedMixedCopiesCanProceed(Context base)throws Exception {
        for(String suffix:new String[]{".new",".bak",""})try(Fixture f=new Fixture(base)){
            String[] ordered=orderedPair(f);String id=ordered[0],due=ordered[1];byte[] live=f.bytes(id,"");
            byte[] stale=f.pending(id,f.expired(id));String key=marker(id,stale);audioAlias(f,id);
            check(f.store.sweepExpired(()->false)==0&&f.absent(id)&&f.copies().contains(key),"fixture obtains exact-copy authorization through normal isolated expiry");
            if(suffix.isEmpty()){
                Files.write(f.base(id).toPath(),stale);Files.write(f.side(id,".bak").toPath(),live);
            }else{
                Files.write(f.base(id).toPath(),live);Files.write(f.side(id,suffix).toPath(),stale);
            }
            expireBase(f,due);Map<String,?> markers=new HashMap<>(f.copies().getAll());f.probe.reset();
            rejects(SecurityException.class,()->f.store.read(id),"read still denies the exact marked member before maintenance");
            check(f.probe.atomicReads==0&&Arrays.equals(stale,f.bytes(id,suffix))&&f.noteKey(id)&&f.audioKey(id),"read refusal does not mutate a mixed recovery layout");
            f.probe.reset();check(f.reopen().sweepExpired(()->false)==1,"marked-copy cleanup counts only the independent due meeting");
            check(Arrays.equals(live,f.bytes(id,""))&&!f.side(id,".new").exists()&&!f.side(id,".bak").exists(),"maintenance removes only exact marked bytes and recovers the different legitimate version");
            check(f.probe.stages.contains("marked_copy_before_remove")&&f.probe.locked&&f.probe.commits==0&&f.copies().getAll().equals(markers),"known-copy removal uses existing durable authority under the privacy lock");
            check(f.noteKey(id)&&f.audioKey(id)&&!f.barred(id)&&f.barred(due)&&f.absent(due)&&!f.noteKey(due),"copy cleanup preserves shared aliases while allowing later independent expiry");
            f.probe.reset();JSONObject restored=f.store.read(id);
            check(restored.getString("id").equals(id)&&restored.getJSONArray("attachments").length()==0&&Arrays.equals(live,f.bytes(id,"")),"current different ciphertext remains readable without a rewrite");
            check(f.store.sweepExpired(()->false)==0&&f.copies().getAll().equals(markers)&&f.noteKey(id)&&f.audioKey(id),"mixed-copy recovery retry is idempotent");
        }
        try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic all marked members");byte[] stale=f.pending(id,f.expired(id));String key=marker(id,stale);audioAlias(f,id);
            check(f.store.sweepExpired(()->false)==0&&f.absent(id)&&f.copies().contains(key),"all-marked fixture first establishes exact authority");
            for(String suffix:new String[]{"",".new",".bak"})Files.write(f.side(id,suffix).toPath(),stale);
            f.probe.reset();check(f.reopen().sweepExpired(()->false)==0&&f.absent(id),"all three marked members can be reclaimed without a missing-base read failure");
            check(f.noteKey(id)&&f.audioKey(id)&&!f.barred(id)&&f.copies().contains(key)&&f.probe.commits==0&&f.probe.atomicReads==0&&!f.probe.stages.contains("before_decrypt"),"all-marked cleanup preserves aliases and neither processes plaintext nor invents whole deletion");
        }
    }
    private static void markedMixedFailureBoundaries(Context base)throws Exception {
        for(String scenario:new String[]{"io","cancel","changed_target","new_sibling"})try(Fixture f=new Fixture(base)){
            String[] ordered=orderedPair(f);String id=ordered[0],due=ordered[1];byte[] live=f.bytes(id,"");
            byte[] stale=f.pending(id,f.expired(id));String key=marker(id,stale);audioAlias(f,id);
            if(f.store.sweepExpired(()->false)!=0||!f.absent(id)||!f.copies().contains(key))throw new AssertionError("mixed failure authority fixture failed");
            Files.write(f.base(id).toPath(),live);String suffix=scenario.equals("new_sibling")?".bak":".new";
            Files.write(f.side(id,suffix).toPath(),stale);expireBase(f,due);f.probe.reset();boolean[] cancelled={false};
            if(scenario.equals("io")){f.probe.faultStage="marked_copy_before_remove";f.probe.failure=new IOException("synthetic marked-copy cleanup failure");}
            else{
                f.probe.actionStage="marked_copy_before_remove";
                if(scenario.equals("cancel"))f.probe.action=()->cancelled[0]=true;
                else if(scenario.equals("changed_target"))f.probe.action=()->Files.write(f.side(id,suffix).toPath(),live);
                else f.probe.action=()->Files.write(f.side(id,".new").toPath(),live);
            }
            Throwable failure=thrown(()->f.reopen().sweepExpired(()->cancelled[0]));
            check(scenario.equals("cancel")?failure instanceof InterruptedIOException:failure instanceof IOException,"mixed cleanup failure is reported with cancellation distinguished");
            if(scenario.equals("io"))check(failure==f.probe.failure&&f.probe.faultHits==1,"the original marked cleanup IOException survives later successful expiry");
            check(Arrays.equals(live,f.bytes(id,""))&&f.noteKey(id)&&f.audioKey(id)&&!f.barred(id)&&f.copies().contains(key)&&f.probe.commits==0,"failure preserves legitimate base shared aliases and exact authority");
            if(scenario.equals("new_sibling")){
                check(!f.side(id,".bak").exists()&&Arrays.equals(live,f.bytes(id,".new")),"a newly appearing sibling is retained while independently authorized exact target cleanup remains valid");
            }else{
                check(Arrays.equals(scenario.equals("changed_target")?live:stale,f.bytes(id,suffix)),"target mutation or rejected deletion leaves the actual target bytes intact");
            }
            check(scenario.equals("cancel")?!f.barred(due)&&f.base(due).exists():f.barred(due)&&f.absent(due),"cancellation stops the scan; a scoped copy IOException allows later independent expiry");
            f.probe.reset();cancelled[0]=false;
            if(scenario.equals("io")||scenario.equals("cancel")){
                int expected=scenario.equals("cancel")?1:0;
                check(f.reopen().sweepExpired(()->false)==expected&&!f.side(id,suffix).exists()&&Arrays.equals(live,f.bytes(id,"")),"healthy retry consumes exact prior authority and any still-due independent item");
            }else{
                rejects(IOException.class,()->f.reopen().sweepExpired(()->false),"different unmarked pending ciphertext remains unresolved on retry");
                check(Arrays.equals(live,f.bytes(id,""))&&Arrays.equals(live,f.bytes(id,".new"))&&f.noteKey(id)&&f.audioKey(id),"unknown replacement or sibling is never discarded merely because current base matches");
            }
        }
    }
    private static void mixedMarkerMustBecomeDurable(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String[] ordered=orderedPair(f);String id=ordered[0],due=ordered[1];byte[] live=f.bytes(id,"");
            byte[] stale=f.pending(id,f.expired(id));String key=marker(id,stale);audioAlias(f,id);f.probe.failCommit=true;
            rejects(IOException.class,()->f.store.sweepExpired(()->false),"failed pre-cache copy decision remains unconfirmed");
            Files.write(f.base(id).toPath(),live);expireBase(f,due);f.probe.stages.clear();
            rejects(IOException.class,()->f.reopen().sweepExpired(()->false),"mixed copy replay retries the original failed committer");
            check(f.copies().getAll().isEmpty()&&f.probe.commits==2&&!f.probe.stages.contains("marked_copy_before_remove")&&Arrays.equals(stale,f.bytes(id,".new"))&&Arrays.equals(live,f.bytes(id,"")),"pending registry visibility cannot authorize unlink before durable confirmation");
            check(f.noteKey(id)&&f.audioKey(id)&&!f.barred(id)&&f.barred(due)&&f.absent(due),"failed exact-copy confirmation does not revoke shared aliases or block independent due data");
            f.probe.failCommit=false;f.probe.stages.clear();
            check(f.reopen().sweepExpired(()->false)==0&&f.copies().contains(key)&&f.probe.commits==3&&!f.side(id,".new").exists()&&Arrays.equals(live,f.bytes(id,"")),"successful original committer confirmation precedes exact mixed-copy unlink");
            check(f.store.read(id).getString("id").equals(id)&&f.noteKey(id)&&f.audioKey(id)&&!f.barred(id),"confirmed cleanup leaves the legitimate current meeting and both aliases available");
        }
    }
    private static void clearBufferFailures(Context base)throws Exception {
        for(boolean fatal:new boolean[]{false,true})try(Fixture f=new Fixture(base)){
            String id=f.create("synthetic clear ownership");byte[] bytes=f.pending(id,f.expired(id));
            Throwable failure=fatal?new AssertionError("synthetic read clear error"):new IOException("synthetic read clear failure");
            f.probe.faultStage="owned:note_read_clear";f.probe.failure=failure;
            Throwable actual=thrown(()->f.store.sweepExpired(()->false));
            check(actual==failure&&f.probe.faultHits==1,"pending decode preserves injected Throwable identity");
            check(f.probe.clear!=null&&f.probe.clear.length>0&&f.probe.clearWasPopulated&&zero(f.probe.clear),"failed owned plaintext buffer is cleared before propagating");
            check(!f.probe.stages.contains("before_parse")&&f.copies().getAll().isEmpty()&&Arrays.equals(bytes,f.bytes(id,".new"))&&f.noteKey(id),"buffer failure parses no body and authorizes no disposal");
            f.probe.reset();check(f.store.sweepExpired(()->false)==0&&f.absent(id)&&f.noteKey(id),"retry after decode fault still disposes only the proven expired copy");
        }
    }
    public static int run(Context context)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        if(!keyguard.isDeviceSecure()||keyguard.isDeviceLocked())throw new SecurityException("authenticated unlocked fixture required");
        checks=0;copyOnlyAndReplay(context);unprovenCopiesStay(context);unknownClockGate(context);committedDecisionRetry(context);
        changedBeforeRemoval(context);commitRejectedBeforeWrite(context);clearBufferFailures(context);
        mixedUnprovenCopiesStay(context);markedMixedCopiesCanProceed(context);markedMixedFailureBoundaries(context);mixedMarkerMustBecomeDurable(context);return checks;
    }
}

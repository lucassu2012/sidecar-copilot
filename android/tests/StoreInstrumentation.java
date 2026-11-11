package local.sidecar.copilot;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;

/** Synthetic only; run on a disposable emulator after device credential auth. */
public final class StoreInstrumentation extends Instrumentation {
    private int checks=0;
    private String phase="";
    private void check(boolean result) { if(!result)throw new AssertionError("check "+checks); checks++; }
    @Override public void onCreate(Bundle args) { super.onCreate(args);if(args!=null)phase=args.getString("phase","");start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if("cleanup_only".equals(phase)){
                int verified=RetentionCleanupChecks.run(this);
                result.putString("stream",verified+" cleanup recovery checks passed; synthetic storage faults only\n");finish(-1,result);return;
            }
            if("ui_only".equals(phase)){
                int verified=UiResponsivenessChecks.run(this);
                result.putString("stream",verified+" UI response checks passed; synthetic fixture only\n");finish(-1,result);return;
            }
            if("anchor_only".equals(phase)){
                int verified=NoteAnchorFailureChecks.run(getTargetContext());
                result.putString("stream",verified+" note anchor durability checks passed; synthetic commit fault only\n");finish(-1,result);return;
            }
            if("range_barriers_only".equals(phase)){
                int verified=RangeBarrierChecks.run(this);
                result.putString("stream",verified+" range-barrier checks passed; synthetic old-row restoration only\n");finish(-1,result);return;
            }
            if("owned_buffers_only".equals(phase)){
                int verified=OwnedBufferChecks.run(this);
                result.putString("stream",verified+" owned-buffer checks passed; synthetic encrypted buffers only\n");finish(-1,result);return;
            }
            if("atomic_notes_only".equals(phase)){
                int verified=AtomicNoteFileChecks.run(this);
                result.putString("stream",verified+" atomic note file checks passed; synthetic companion layouts and injected file-operation faults only\n");finish(-1,result);return;
            }
            if("deletion_markers_only".equals(phase)){
                int verified=DeletionMarkerChecks.run(this);
                result.putString("stream",verified+" deletion-marker durability checks passed; synthetic cache/pre-cache commit faults only\n");finish(-1,result);return;
            }
            if("pending_copies_only".equals(phase)){
                int verified=PendingNoteCopyChecks.run(getTargetContext());
                result.putString("stream",verified+" pending note copy checks passed; synthetic encrypted copies and injected maintenance faults only\n");finish(-1,result);return;
            }
            if("meeting_isolation_only".equals(phase)){
                int verified=MeetingIsolationChecks.run(getTargetContext());
                result.putString("stream",verified+" meeting isolation checks passed; synthetic unresolved copies and injected faults only\n");finish(-1,result);return;
            }
            if("wal_configuration_only".equals(phase)){
                int verified=WalConfigurationChecks.run(this);
                result.putString("stream",verified+" WAL connection configuration checks passed; synthetic database fixtures only\n");finish(-1,result);return;
            }
            if("control_prepare".equals(phase)){ControlRecoveryChecks.prepareAbrupt(this);return;}
            if("control_verify".equals(phase)){
                int verified=ControlRecoveryChecks.verifyAbrupt(this);
                result.putString("stream",verified+" abrupt-process recovery checks passed; generated PCM only\n");finish(-1,result);return;
            }
            NoteStore store=new NoteStore(getTargetContext());
            try {store.create("synthetic",false);throw new AssertionError("consent bypass");}catch(SecurityException expected){checks++;}
            String id=store.create("synthetic-native-test",true);
            store.append(id,0,"SYNTHETIC_SECRET_12345","meeting_background");
            JSONObject before=store.read(id);
            check(before.getLong("revision")==1);
            check(store.visibleLifetime(before)>0 && store.visibleLifetime(before)<=300000);
            JSONObject timerFixture=new JSONObject(before.toString());
            timerFixture.getJSONArray("attachments").getJSONObject(0).put("expires_at",NoteRetentionChecks.fixtureNow(store)+100);
            check(store.visibleLifetime(timerFixture)<=100);
            File file=new File(getTargetContext().getNoBackupFilesDir(),"meetings/"+id+".enc");
            check(!new String(Files.readAllBytes(file.toPath()),StandardCharsets.ISO_8859_1).contains("SYNTHETIC_SECRET"));
            store.end(id,1);
            long ended=store.read(id).getLong("ended_at");
            store.append(id,2,"synthetic post-meeting note","unassigned");
            JSONObject after=store.read(id);
            check(after.getLong("ended_at")==ended);
            check(after.getLong("title_expires_at")==before.getLong("title_expires_at"));
            check(after.getJSONArray("attachments").getJSONObject(0).getLong("expires_at")==before.getJSONArray("attachments").getJSONObject(0).getLong("expires_at"));
            check(after.getJSONArray("attachments").getJSONObject(1).getLong("expires_at")>=before.getJSONArray("attachments").getJSONObject(0).getLong("expires_at"));
            check(after.getJSONArray("attachments").length()==2);
            try {store.append(id,2,"stale","unassigned");throw new AssertionError("stale write");}catch(IllegalStateException expected){checks++;}
            byte[] oldCiphertext=Files.readAllBytes(file.toPath());
            String noteId=after.getJSONArray("attachments").getJSONObject(0).getString("id");
            store.deleteNote(id,noteId,3);
            check(store.read(id).getJSONArray("attachments").length()==1);
            check(!store.read(id).toString().contains("SYNTHETIC_SECRET"));
            // Emulate an old encrypted active file returning after an interrupted cleanup.
            Files.write(file.toPath(),oldCiphertext);
            store=new NoteStore(getTargetContext());
            check(!store.read(id).toString().contains("SYNTHETIC_SECRET"));
            check(store.read(id).getJSONArray("attachments").length()==1);
            // Controlled synthetic expiry fixture; no actual system time change.
            JSONObject expiring=store.read(id);
            NoteRetentionChecks.expireNoteFixture(expiring,0,NoteRetentionChecks.fixtureNow(store)-1);
            java.lang.reflect.Method writer=NoteStore.class.getDeclaredMethod("write",String.class,JSONObject.class);
            writer.setAccessible(true);writer.invoke(store,id,expiring);
            check(store.read(id).getJSONArray("attachments").length()==0);
            check(store.read(id).getLong("ended_at")==ended);
            Files.write(file.toPath(),oldCiphertext);
            check(store.read(id).getJSONArray("attachments").length()==0);
            String keep=store.create("synthetic-remaining",true);
            store.delete(id);
            check(!store.ids().contains(id));
            check(!file.exists());
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            check(!keys.containsAlias("sidecar.note."+id));
            check(store.read(keep).getString("title").equals("synthetic-remaining"));
            String expired=store.create("synthetic-expired",true);
            JSONObject expiredMeeting=store.read(expired);
            NoteRetentionChecks.expireMeetingFixture(expiredMeeting,NoteRetentionChecks.fixtureNow(store)-1);
            writer.invoke(store,expired,expiredMeeting);
            try {store.read(expired);throw new AssertionError("expired read");}catch(NoteStore.Expired expected){checks++;}
            check(!store.ids().contains(expired));
            check(store.read(keep).getString("title").equals("synthetic-remaining"));
            try {store.read(id);throw new AssertionError("deleted read");}catch(SecurityException expected){checks++;}
            new NoteStore(getTargetContext());
            try {store.read(id);throw new AssertionError("deleted recovery");}catch(SecurityException expected){checks++;}
            store.delete(keep);
            int buffered=BufferedCaptureChecks.run(this);
            int controlRecovery=ControlRecoveryChecks.run(this);
            int cleanup=RetentionCleanupChecks.run(this);
            int quarantine=ClockQuarantineChecks.run(getTargetContext());
            int anchorDurability=NoteAnchorFailureChecks.run(getTargetContext());
            int recoveryProjection=RecoveryProjectionChecks.run(this);
            int readCompletion=ReadCompletionChecks.run(this);
            int ownedBuffers=OwnedBufferChecks.run(this);
            int rangeBarriers=RangeBarrierChecks.run(this);
            int sourceReferences=AudioSourceReferenceChecks.run(this);
            int atomicNotes=AtomicNoteFileChecks.run(this);
            int deletionMarkers=DeletionMarkerChecks.run(this);
            int pendingCopies=PendingNoteCopyChecks.run(getTargetContext());
            int meetingIsolation=MeetingIsolationChecks.run(getTargetContext());
            int walConfiguration=WalConfigurationChecks.run(this);
            int audio=AudioStoreChecks.run(this);
            int meeting=MeetingSessionChecks.run(this);
            int service=CaptureServiceChecks.run(this);
            int retention=AudioRetentionChecks.run(this);
            int concurrency=NoteConcurrencyChecks.run(getTargetContext());
            int replay=ReplayAdmissionChecks.run(getTargetContext());
            int noteRetention=NoteRetentionChecks.run(getTargetContext());
            int ui=UiResponsivenessChecks.run(this);
            result.putString("stream",sourceReferences+" audio source reference checks, "+buffered+" buffered capture checks, "+controlRecovery+" control recovery checks, "+cleanup+" cleanup recovery checks, "+quarantine+" clock quarantine checks, "+anchorDurability+" note anchor durability checks, "+recoveryProjection+" recovery projection checks, "+readCompletion+" read completion checks, "+ownedBuffers+" owned-buffer checks, "+rangeBarriers+" range-barrier checks, "+atomicNotes+" atomic note file checks, "+deletionMarkers+" deletion-marker durability checks, "+pendingCopies+" pending note copy checks, "+meetingIsolation+" meeting isolation checks, "+walConfiguration+" WAL connection configuration checks, "+checks+" native note checks, "+audio+" encrypted PCM checks, "+meeting+" meeting lifecycle checks, "+service+" capture service checks, "+retention+" retention job checks, "+concurrency+" note concurrency checks, "+replay+" replay admission checks, "+noteRetention+" note retention checks and "+ui+" UI response checks passed; synthetic emulator only\n");
            finish(-1,result);
        } catch(Throwable error) {
            java.io.StringWriter detail=new java.io.StringWriter();error.printStackTrace(new java.io.PrintWriter(detail));
            result.putString("stream","FAILED after "+checks+" base checks: "+detail.toString()+"\n");
            finish(0,result);
        }
    }
}

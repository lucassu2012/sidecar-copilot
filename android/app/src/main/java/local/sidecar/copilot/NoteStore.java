package local.sidecar.copilot;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.*;

/** Local-only native prototype. No transport, audio or model adapters. */
public final class NoteStore {
    // One process-wide order for read/modify/write and privacy revocation across instances.
    private static final Object MUTATIONS = MeetingPrivacy.LOCK;
    // A failed SharedPreferences commit can still replace its in-memory map. Keep the
    // candidate untrusted across NoteStore instances until a synchronous retry succeeds.
    // Both indexes are guarded by MUTATIONS; identity avoids calling proxy equals/hashCode.
    private static final Map<String,PendingAnchor> PENDING_CLOCK_ROOTS=new HashMap<>();
    private static final IdentityHashMap<SharedPreferences,PendingAnchor> PENDING_CLOCK_PREFERENCES=new IdentityHashMap<>();
    private static final class PendingAnchor {
        final String root;
        final SharedPreferences preferences;
        final long wall,elapsed;
        final int boot;
        PendingAnchor(String root,SharedPreferences preferences,long wall,long elapsed,int boot) {
            this.root=root;this.preferences=preferences;this.wall=wall;this.elapsed=elapsed;this.boot=boot;
        }
        boolean matches(Map<String,?> saved) {
            return Long.valueOf(wall).equals(saved.get("wall"))&&Long.valueOf(elapsed).equals(saved.get("elapsed"))&&Integer.valueOf(boot).equals(saved.get("boot"));
        }
    }
    interface MutationProbe {void afterRevisionCheck()throws Exception;}
    private final MutationProbe mutationProbe;
    // Package-private deterministic probes; no UI, IPC or production time override.
    interface AccessProbe {
        void at(String stage)throws IOException;
        default void ownedBuffer(String stage,byte[] buffer)throws IOException {}
        default boolean commitClock(SharedPreferences.Editor edit){return edit.commit();}
        default boolean commitDeletion(String preferences,String key,SharedPreferences.Editor edit)throws IOException{return edit.commit();}
        default AtomicFile atomicFile(File base){return new AtomicFile(base);}
    }
    private final AccessProbe accessProbe;
    public static final class Expired extends IllegalStateException {
        Expired() { super("会议资料已全部到期并清理"); }
    }
    static final class UnresolvedMeeting extends IOException {
        final String meetingId;
        UnresolvedMeeting(String id){super("meeting copies require explicit disposition");meetingId=id;}
    }
    static final class SweepResult {
        final int expired;
        final Set<String> unavailable;
        SweepResult(int expired,Set<String> unavailable){
            this.expired=expired;this.unavailable=Collections.unmodifiableSet(new LinkedHashSet<>(unavailable));
        }
    }
    private final Context context;
    private final File root;
    private final String clockRoot;
    private final KeyStore keys;
    private static final String EXPIRED_COPIES="note-expired-copies-v1";
    private final SharedPreferences clockState;
    public NoteStore(Context context) throws Exception {
        this(context,()->{});
    }
    NoteStore(Context context,MutationProbe probe) throws Exception {
        this(context,probe,stage->{});
    }
    NoteStore(Context context,MutationProbe probe,AccessProbe accessProbe) throws Exception {
        this(context,probe,accessProbe,true);
    }
    private NoteStore(Context context,MutationProbe probe,AccessProbe accessProbe,boolean recoverDeletes) throws Exception {
        mutationProbe=probe;
        this.accessProbe=Objects.requireNonNull(accessProbe,"access probe");
        this.context = context;
        root = new File(context.getNoBackupFilesDir(), "meetings");
        clockRoot=root.getCanonicalPath();
        if (!root.exists() && !root.mkdirs()) throw new IOException("无法创建私有存储");
        keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        clockState = context.getSharedPreferences("retention-clock", Context.MODE_PRIVATE);
        // Complete interrupted deletion before returning any meeting IDs.
        if(recoverDeletes)synchronized(MUTATIONS){for (String id : DurableDeletionMarkers.keys(context,"deletion-barriers")) cleanup(id);}
    }
    private long now() throws IOException {
        synchronized(MUTATIONS){
        long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
        int boot=Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        if(boot<0)throw new IllegalStateException("device boot clock unavailable");
        Map<String,?> saved=clockState.getAll();
        PendingAnchor rootPending=PENDING_CLOCK_ROOTS.get(clockRoot),preferencesPending=PENDING_CLOCK_PREFERENCES.get(clockState);
        if(rootPending!=null&&preferencesPending!=null&&rootPending!=preferencesPending)
            throw new IllegalStateException("conflicting pending retention anchors");
        PendingAnchor pending=rootPending!=null?rootPending:preferencesPending;
        if(pending!=null) {
            // A different backing/context must not hide or replace a failed candidate.
            if(pending.preferences!=clockState||!pending.root.equals(clockRoot))
                throw new IllegalStateException("pending retention anchor storage context changed");
            if(!saved.isEmpty()&&!pending.matches(saved))
                throw new IllegalStateException("pending retention anchor cache changed");
            requireEmptyClockStorage();
            long result=RetentionClock.conservativeNow(wall,elapsed,pending.wall,pending.elapsed,pending.boot==boot);
            commitAnchor(pending);
            return result;
        }
        boolean complete=saved.get("wall") instanceof Long&&saved.get("elapsed") instanceof Long&&saved.get("boot") instanceof Integer;
        if(complete) {
            long previousWall=(Long)saved.get("wall"),previousElapsed=(Long)saved.get("elapsed");
            int previousBoot=(Integer)saved.get("boot");
            if(previousWall<=0||previousElapsed<0||previousBoot<0)throw new IllegalStateException("invalid retention clock anchor");
            // This durable anchor already represents all same-boot elapsed time. Revalidation
            // must not add synchronous preference writes while holding the privacy lock.
            return RetentionClock.conservativeNow(wall,elapsed,previousWall,previousElapsed,previousBoot==boot);
        }
        // AtomicFile backups/new files also count: their existence is not a fresh install.
        if(!saved.isEmpty())throw new IllegalStateException("retention clock anchor missing or incomplete");
        requireEmptyClockStorage();
        if(wall<=0||elapsed<0)throw new IllegalStateException("device clock unavailable");
        pending=new PendingAnchor(clockRoot,clockState,wall,elapsed,boot);
        // Register before edit/commit: false, interruption and runtime exceptions all leave
        // this barrier in place, even if the preference cache now contains a complete triple.
        PENDING_CLOCK_ROOTS.put(clockRoot,pending);PENDING_CLOCK_PREFERENCES.put(clockState,pending);
        commitAnchor(pending);
        return wall;
        }
    }
    private void requireEmptyClockStorage()throws IOException {
        File[] contents=root.listFiles();
        if(contents==null)throw new IOException("meeting storage inventory unavailable");
        if(contents.length!=0)throw new IllegalStateException("retention clock cannot initialize over existing content");
    }
    private void commitAnchor(PendingAnchor pending)throws IOException {
        // A new token defensively ensures a changed edit on retry; the original time triple
        // is never moved to the retry time. Ordinary reads never enter this path.
        SharedPreferences.Editor edit=clockState.edit().putLong("wall",pending.wall).putLong("elapsed",pending.elapsed)
            .putInt("boot",pending.boot).putString("durability_nonce",UUID.randomUUID().toString());
        if(!accessProbe.commitClock(edit))throw new IOException("retention clock persistence failed");
        PENDING_CLOCK_ROOTS.remove(pending.root);PENDING_CLOCK_PREFERENCES.remove(pending.preferences);
    }
    private void requireUnlocked() {
        KeyguardManager km = context.getSystemService(KeyguardManager.class);
        if (!km.isDeviceSecure() || km.isDeviceLocked()) throw new SecurityException("请设置设备锁并解锁");
    }
    private String alias(String id) { return "sidecar.note." + id; }
    private void checkId(String id) {
        if (!id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("无效资料ID");
    }
    private NoteFileSet files(String id) { checkId(id); return new NoteFileSet(root,id); }
    private AtomicFile file(String id) { return accessProbe.atomicFile(files(id).base); }
    private SecretKey key(String id) throws Exception {
        SecretKey key = (SecretKey) keys.getKey(alias(id), null);
        if (key == null) throw new SecurityException("密钥不可用；不会自动重建覆盖旧资料");
        return key;
    }
    private void newKey(String id) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias(id),KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(300,KeyProperties.AUTH_DEVICE_CREDENTIAL|KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setUnlockedDeviceRequired(true).build());
        generator.generateKey();
    }
    public List<String> ids() throws IOException {
        synchronized(MUTATIONS){
        requireUnlocked();
        List<String> ids = new ArrayList<>();
        // Legacy backups are recoverable candidates. A lone .new is not admitted as saved.
        for(String id:NoteFileSet.ids(root,false))if(!DurableDeletionMarkers.isMarked(context,"deletion-barriers",id))ids.add(id);
        return ids;
        }
    }
    public JSONObject read(String id) throws Exception {
        return read(id,null);
    }
    private JSONObject read(String id,int[] expiredMeetings) throws Exception {
        synchronized(MUTATIONS){
        synchronized(MeetingPrivacy.LOCK){
        requireUnlocked(); checkId(id);
        if(DurableDeletionMarkers.isMarked(context,"deletion-barriers",id)) throw new SecurityException("会议已删除");
        now(); // Reject known clock uncertainty before reading or processing any private body.
        accessProbe.at("before_read");
        rejectKnownCopies(id); // Before AtomicFile can restore or discard any companion.
        try{files(id).requireRecoverableRead();}
        catch(NoteFileSet.UnresolvedCopy unresolved){throw new UnresolvedMeeting(id);}
        byte[] bytes=file(id).readFully();
        files(id).requireReadResolved(); // AtomicFile may only log a failed rename/unlink.
        rejectKnownCopy(id,bytes);
        JSONObject meeting=decodeMeeting(id,bytes);
        requireUnlocked();
        if(!id.equals(meeting.getString("id")))throw new SecurityException("meeting identity mismatch");
        boolean changed=ensureRetention(meeting),expired=false;
        for(;;) {
        requireUnlocked();
        if(DurableDeletionMarkers.isMarked(context,"deletion-barriers",id))throw new SecurityException("meeting deleted during read");
        long now=now();
        long containerExpiry=meeting.getLong("expires_at");
        if(now>=containerExpiry) { delete(id); throw new Expired(); }
        NotePolicy.checkReadable(now,meeting.getLong("created_at"),containerExpiry,false);
        // The verified deadline belongs to the title, never to later attachments.
        long titleExpiry=meeting.getLong("title_expires_at");
        JSONArray old=meeting.getJSONArray("attachments"),live=new JSONArray();
        for(int i=0;i<old.length();i++) {
            JSONObject note=old.getJSONObject(i);String nid=note.getString("id");checkId(nid);
            if(now>=note.getLong("expires_at") && !DurableDeletionMarkers.isMarked(context,"note-deletion-barriers",id+":"+nid)) {
                persistNoteBarrier(id,nid);expired=true;
            }
            if(DurableDeletionMarkers.isMarked(context,"note-deletion-barriers",id+":"+nid)) {
                DurableDeletionMarkers.requireDurable(context,"note-deletion-barriers",id+":"+nid);
                changed=true;
            }
            else live.put(note);
        }
        if(now>=titleExpiry && !meeting.optBoolean("title_expired",false)) {
            meeting.put("title","已到期会议标题").put("title_expired",true);changed=true;expired=true;
        }
        if(changed) {
            meeting.put("attachments",live).put("revision",meeting.getLong("revision")+1);
            write(id,meeting); // If cleanup cannot commit, do not return the old body.
            changed=false;
        }
        accessProbe.at("before_return");
        requireUnlocked();
        if(DurableDeletionMarkers.isMarked(context,"deletion-barriers",id))throw new SecurityException("meeting deleted during read");
        long returnNow=now();
        // A deadline can pass while rewriting. Each retry removes a note or expires the title;
        // it never refreshes a deadline or exposes the superseded in-memory body.
        if(hasUnavailableContent(id,meeting,returnNow))continue;
        requireUnlocked();
        if(DurableDeletionMarkers.isMarked(context,"deletion-barriers",id))throw new SecurityException("meeting deleted during read");
        if(expired&&expiredMeetings!=null)expiredMeetings[0]++;
        return meeting;
        }
        }
        }
    }
    private boolean hasUnavailableContent(String id,JSONObject meeting,long now)throws Exception {
        if(now>=meeting.getLong("expires_at"))return true;
        if(!meeting.optBoolean("title_expired",false)&&now>=meeting.getLong("title_expires_at"))return true;
        JSONArray notes=meeting.getJSONArray("attachments");
        for(int i=0;i<notes.length();i++) {
            JSONObject note=notes.getJSONObject(i);
            if(DurableDeletionMarkers.isMarked(context,"note-deletion-barriers",id+":"+note.getString("id"))||now>=note.getLong("expires_at"))return true;
        }
        return false;
    }
    private JSONObject decodeMeeting(String id,byte[] bytes)throws Exception {
        if(bytes.length<29||bytes.length>2*1024*1024)throw new IOException("存储格式或大小无效");
        accessProbe.at("before_decrypt");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(id),new GCMParameterSpec(128,Arrays.copyOfRange(bytes,0,12)));
        cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
        byte[] clear=cipher.doFinal(Arrays.copyOfRange(bytes,12,bytes.length));
        try{accessProbe.ownedBuffer("note_read_clear",clear);accessProbe.at("before_parse");return new JSONObject(new String(clear,StandardCharsets.UTF_8));}
        finally{Arrays.fill(clear,(byte)0);}
    }
    private String copyMarker(String id,byte[] bytes)throws Exception {
        checkId(id);StringBuilder marker=new StringBuilder(id).append(':').append(bytes.length).append(':');
        for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes)){
            marker.append(Character.forDigit((value&255)>>>4,16)).append(Character.forDigit(value&15,16));
        }
        return marker.toString();
    }
    private boolean hasCopyMarkers(String id)throws IOException {
        String prefix=id+":";for(String marker:DurableDeletionMarkers.keys(context,EXPIRED_COPIES))if(marker.startsWith(prefix))return true;return false;
    }
    private void rejectKnownCopy(String id,byte[] bytes)throws Exception {
        if(DurableDeletionMarkers.isMarked(context,EXPIRED_COPIES,copyMarker(id,bytes)))throw new SecurityException("expired note copy remains quarantined");
    }
    private void rejectKnownCopies(String id)throws Exception {
        if(!hasCopyMarkers(id))return;
        NoteFileSet family=files(id);
        for(File part:new File[]{family.base,family.pending,family.backup}){
            byte[] bytes=NoteFileSet.readIfPresent(part);if(bytes!=null)rejectKnownCopy(id,bytes);
        }
    }
    private void sweepMarkedCopies(String id,java.util.function.BooleanSupplier cancelled)throws Exception {
        if(!hasCopyMarkers(id))return;
        NoteFileSet family=files(id);
        for(File member:new File[]{family.base,family.pending,family.backup}){
            checkCancelled(cancelled);requireUnlocked();byte[] bytes=NoteFileSet.readIfPresent(member);
            if(bytes==null)continue;String marker=copyMarker(id,bytes);
            if(!DurableDeletionMarkers.isMarked(context,EXPIRED_COPIES,marker))continue;
            DurableDeletionMarkers.requireDurable(context,EXPIRED_COPIES,marker);
            checkCancelled(cancelled);requireUnlocked();accessProbe.at("marked_copy_before_remove");
            family.removeMarkedCopy(member,bytes,()->{checkCancelled(cancelled);requireUnlocked();});
        }
    }
    private void sweepPendingCopy(String id,java.util.function.BooleanSupplier cancelled)throws Exception {
        // Caller holds MUTATIONS. Copy authorization never authorizes whole-meeting/key deletion.
        checkCancelled(cancelled);requireUnlocked();NoteFileSet family=files(id);byte[] bytes=null;String marker=null;
        if(hasCopyMarkers(id)){
            // Previously authorized cleanup compares ciphertext only; it does not parse an untrusted-time body.
            bytes=family.readPendingOnly();marker=copyMarker(id,bytes);
            if(DurableDeletionMarkers.isMarked(context,EXPIRED_COPIES,marker)){
                DurableDeletionMarkers.requireDurable(context,EXPIRED_COPIES,marker);
                checkCancelled(cancelled);requireUnlocked();accessProbe.at("pending_copy_before_remove");
                family.removePendingOnly(bytes,()->{checkCancelled(cancelled);requireUnlocked();});return;
            }
        }
        now(); // Unknown copies require the original clock gate before body processing.
        if(bytes==null){accessProbe.at("before_pending_read");bytes=family.readPendingOnly();marker=copyMarker(id,bytes);}
        JSONObject body;
        try{body=decodeMeeting(id,bytes);}
        catch(javax.crypto.AEADBadTagException invalid){throw new IOException("pending note ciphertext authentication failed",invalid);}
        checkCancelled(cancelled);requireUnlocked();long decisionTime=now();
        boolean whollyExpired=PendingNotePolicy.isWhollyExpired(body,id,decisionTime);
        if(!MessageDigest.isEqual(bytes,family.readPendingOnly()))throw new IOException("pending note copy changed before its expiry decision");
        if(!whollyExpired)throw new UnresolvedMeeting(id);
        checkCancelled(cancelled);requireUnlocked();now();
        final String authorizedMarker=marker;
        DurableDeletionMarkers.mark(context,EXPIRED_COPIES,authorizedMarker,edit->accessProbe.commitDeletion(EXPIRED_COPIES,authorizedMarker,edit));
        accessProbe.at("pending_copy_after_barrier");checkCancelled(cancelled);requireUnlocked();
        accessProbe.at("pending_copy_before_remove");family.removePendingOnly(bytes,()->{checkCancelled(cancelled);requireUnlocked();});
        // Keep the exact-copy record, both aliases and the meeting-expiry count unchanged.
    }
    private static JSONObject envelope(RetentionEnvelope value) throws Exception {
        return new JSONObject().put("retention_policy_id",RetentionEnvelope.POLICY_ID)
            .put("policy_version",RetentionEnvelope.POLICY_VERSION).put("retention_origin",value.origin)
            .put("origin_time",value.originTime).put("expires_at",value.expiresAt)
            .put("policy_evidence",value.evidence).put("clock_confidence","device_clock_estimate");
    }
    private static boolean ensureEnvelope(JSONObject object,String field,String origin,long created,long expires)throws Exception {
        if(!object.has(field)) {
            object.put(field,envelope(RetentionEnvelope.legacy(origin,created,expires)));return true;
        }
        JSONObject saved=object.getJSONObject(field);
        RetentionEnvelope verified=RetentionEnvelope.verify(saved.getString("retention_policy_id"),saved.getInt("policy_version"),
            saved.getString("retention_origin"),saved.getLong("origin_time"),saved.getLong("expires_at"),saved.getString("policy_evidence"));
        if(!origin.equals(verified.origin)||created!=verified.originTime||expires!=verified.expiresAt||
                !"device_clock_estimate".equals(saved.getString("clock_confidence")))throw new SecurityException("retention metadata mismatch");
        return false;
    }
    private boolean ensureRetention(JSONObject meeting)throws Exception {
        long created=meeting.getLong("created_at"),containerExpiry=meeting.getLong("expires_at");
        boolean changed=false;
        if(!meeting.has("title_expires_at")) {
            // Old containers that grew after an append cannot prove a title deadline.
            RetentionEnvelope.legacy(RetentionEnvelope.TITLE_ORIGIN,created,containerExpiry);
            meeting.put("title_expires_at",containerExpiry);changed=true;
        }
        long titleExpiry=meeting.getLong("title_expires_at");
        changed|=ensureEnvelope(meeting,"title_retention",RetentionEnvelope.TITLE_ORIGIN,created,titleExpiry);
        long requiredExpiry=titleExpiry;
        JSONArray notes=meeting.getJSONArray("attachments");
        for(int i=0;i<notes.length();i++) {
            JSONObject note=notes.getJSONObject(i);String nid=note.getString("id");checkId(nid);
            if(DurableDeletionMarkers.isMarked(context,"note-deletion-barriers",meeting.getString("id")+":"+nid))continue; // Deletion overrides old body metadata.
            if(!"manual_note".equals(note.getString("source_type"))||note.has("source_refs"))
                throw new SecurityException("independent manual retention required");
            long noteCreated=note.getLong("created_at"),noteExpiry=note.getLong("expires_at");
            if(noteCreated<created)throw new SecurityException("note precedes meeting creation");
            changed|=ensureEnvelope(note,"retention",RetentionEnvelope.NOTE_ORIGIN,noteCreated,noteExpiry);
            requiredExpiry=Math.max(requiredExpiry,noteExpiry);
        }
        if(containerExpiry<requiredExpiry)throw new SecurityException("container precedes retained content deadline");
        return changed;
    }
    /** Authenticated background maintenance; count is meetings with successful expiry cleanup.
     * Each meeting is atomic, but the entire scan never holds the shared privacy lock.
     * Locked/expired authentication, uncertain clocks and invalid policy stop the scan.
     */
    public int sweepExpired(java.util.function.BooleanSupplier cancelled)throws Exception {
        SweepResult result=sweepForListing(cancelled);
        if(!result.unavailable.isEmpty())throw new IOException("meeting recovery incomplete; unresolved copies retained");
        return result.expired;
    }
    /** Partial availability only for positively identified unresolved families. Never masks
     * ordinary I/O, durable deletion failures, authentication, clock uncertainty or cancellation. */
    SweepResult sweepForListing(java.util.function.BooleanSupplier cancelled)throws Exception {
        Objects.requireNonNull(cancelled,"cancelled");
        checkCancelled(cancelled);
        List<String> candidates,revoked;
        synchronized(MUTATIONS){requireUnlocked();revoked=new ArrayList<>(DurableDeletionMarkers.keys(context,"deletion-barriers"));}
        int[] expired={0};IOException pendingFailure=null;Set<String> unavailable=new LinkedHashSet<>();
        try{
        // Already-authorized deletion precedes inventory and clock-dependent body processing.
        // This also retries alias cleanup when no file remains for a barrier's ID.
        for(String id:revoked){
            checkCancelled(cancelled);
            synchronized(MUTATIONS){
                checkCancelled(cancelled);requireUnlocked();
                try{cleanup(id);}catch(IOException failure){
                    checkCancelled(cancelled);if(failure instanceof InterruptedIOException)throw failure;
                    if(pendingFailure==null)pendingFailure=failure;
                    else if(pendingFailure!=failure)pendingFailure.addSuppressed(failure);
                }
            }
        }
        try{synchronized(MUTATIONS){checkCancelled(cancelled);requireUnlocked();candidates=NoteFileSet.ids(root,true);candidates.removeAll(revoked);}}
        catch(IOException failure){
            checkCancelled(cancelled);if(failure instanceof InterruptedIOException)throw failure;
            if(pendingFailure==null)throw failure;
            if(pendingFailure!=failure)pendingFailure.addSuppressed(failure);throw pendingFailure;
        }
        for(String id:candidates) {
            checkCancelled(cancelled);
            synchronized(MUTATIONS) {
                checkCancelled(cancelled);requireUnlocked();
                try{
                    // Existing deletion authority precedes clock checks and companion recovery.
                    if(DurableDeletionMarkers.isMarked(context,"deletion-barriers",id))cleanup(id);
                    else if(files(id).pendingOnly())sweepPendingCopy(id,cancelled);
                    else {
                        sweepMarkedCopies(id,cancelled);
                        if(files(id).empty())continue;
                        if(files(id).pendingOnly())sweepPendingCopy(id,cancelled);
                        else read(id,expired);
                    }
                }catch(Expired removed){expired[0]++;}
                catch(UnresolvedMeeting unresolved){
                    checkCancelled(cancelled);
                    if(!id.equals(unresolved.meetingId))throw new SecurityException("unresolved meeting scope mismatch");
                    unavailable.add(id);
                }
                catch(IOException failure){
                    checkCancelled(cancelled);if(failure instanceof InterruptedIOException)throw failure;
                    // An ambiguous lone .new is retained and reported, never silently promoted
                    // or discarded. Its I/O failure must not skip other independently due notes.
                    if(pendingFailure==null)pendingFailure=failure;
                    else if(pendingFailure!=failure)pendingFailure.addSuppressed(failure);
                }
            }
        }
        checkCancelled(cancelled);requireUnlocked();if(pendingFailure!=null)throw pendingFailure;
        if(!unavailable.isEmpty())revalidateListingAccess(cancelled);
        return new SweepResult(expired[0],unavailable);
        }catch(Exception|Error failure){
            if(pendingFailure!=null&&pendingFailure!=failure)failure.addSuppressed(pendingFailure);
            throw failure;
        }
    }
    /** Never exposes the unrecovered store; old cleanup I/O cannot skip unrelated due notes. */
    static int sweepExpired(Context context,java.util.function.BooleanSupplier cancelled)throws Exception {
        return sweepExpired(context,cancelled,stage->{});
    }
    static int sweepExpired(Context context,java.util.function.BooleanSupplier cancelled,AccessProbe probe)throws Exception {
        Objects.requireNonNull(cancelled,"cancelled");checkCancelled(cancelled);
        return new NoteStore(context,()->{},probe,false).sweepExpired(cancelled);
    }
    static SweepResult sweepForListing(Context context,java.util.function.BooleanSupplier cancelled)throws Exception {
        return sweepForListing(context,cancelled,stage->{});
    }
    static SweepResult sweepForListing(Context context,java.util.function.BooleanSupplier cancelled,AccessProbe probe)throws Exception {
        Objects.requireNonNull(cancelled,"cancelled");checkCancelled(cancelled);
        // This maintenance-only instance is never returned to callers. Ordinary construction
        // still requires all persisted whole-meeting deletion recovery to complete.
        return new NoteStore(context,()->{},probe,false).sweepForListing(cancelled);
    }
    void revalidateListingAccess(java.util.function.BooleanSupplier cancelled)throws IOException {
        synchronized(MUTATIONS){
            checkCancelled(cancelled);requireUnlocked();now();requireUnlocked();checkCancelled(cancelled);
        }
    }
    private static void checkCancelled(java.util.function.BooleanSupplier cancelled)throws InterruptedIOException {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("note cleanup cancelled");
    }
    private void write(String id, JSONObject meeting) throws Exception {
        synchronized(MeetingPrivacy.LOCK){
        requireUnlocked();
        if(DurableDeletionMarkers.isMarked(context,"deletion-barriers",id)) throw new SecurityException("删除屏障阻止写入");
        byte[] clear=meeting.toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher;byte[] encrypted;
        try {
            accessProbe.ownedBuffer("note_write_clear",clear);accessProbe.at("note_write_before_size");
            if(clear.length>1024*1024) throw new IllegalStateException("本开发版会议资料上限为1 MiB");
            cipher=Cipher.getInstance("AES/GCM/NoPadding");accessProbe.at("note_write_before_key");
            SecretKey encryptionKey=key(id);accessProbe.at("note_write_before_init");cipher.init(Cipher.ENCRYPT_MODE,encryptionKey);
            accessProbe.at("note_write_before_aad");cipher.updateAAD(id.getBytes(StandardCharsets.UTF_8));
            accessProbe.at("note_write_before_final");encrypted=cipher.doFinal(clear);
        } finally { Arrays.fill(clear,(byte)0); }
        AtomicFile target=file(id); FileOutputStream out=null;byte[] nonce=cipher.getIV();
        try {
            out=target.startWrite(); out.write(nonce); out.write(encrypted);
            out.flush(); out.getFD().sync(); target.finishWrite(out);
            // Inspect base directly: readFully could replay a stale backup or remove evidence.
            files(id).verifyCommit(nonce,encrypted);
        } catch(Exception|Error failure) {
            try{if(out!=null)target.failWrite(out);}catch(Throwable cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            throw failure;
        }
        }
    }
    public String create(String title, boolean consent) throws Exception {
        synchronized(MUTATIONS){
        requireUnlocked();
        if(!consent) throw new SecurityException("请确认有权保存笔记和保留期限");
        if(title.trim().isEmpty() || title.length()>120) throw new IllegalArgumentException("会议名须为1至120个字符");
        String id=UUID.randomUUID().toString(); long now=now();
        long expires=RetentionEnvelope.deadline(now);
        JSONObject m=new JSONObject().put("id",id).put("title",title).put("capture_mode","manual_note")
            .put("created_at",now).put("expires_at",expires)
            .put("title_expires_at",expires).put("title_retention",envelope(RetentionEnvelope.confirmed(RetentionEnvelope.TITLE_ORIGIN,now))).put("ended_at",0)
            .put("revision",0).put("attachments",new JSONArray());
        newKey(id);
        try { write(id,m); } catch(Exception e) { keys.deleteEntry(alias(id)); throw e; }
        return id;
        }
    }
    public void append(String id,long expected,String text,String scope) throws Exception {
        synchronized(MUTATIONS){
        NotePolicy.checkScope(scope);
        if(text.trim().isEmpty() || text.length()>10000) throw new IllegalArgumentException("笔记须为1至10000个字符");
        JSONObject m=read(id); NotePolicy.checkRevision(m.getLong("revision"),expected);
        mutationProbe.afterRevisionCheck();
        long now=now();
        long expires=RetentionEnvelope.deadline(now);
        // Each independent manual note starts its own deadline. Older title/notes stay fixed.
        m.getJSONArray("attachments").put(new JSONObject().put("id",UUID.randomUUID().toString())
            .put("source_type","manual_note").put("text",text).put("scope_kind",scope)
            .put("created_at",now).put("added_at",now).put("eligible_at",now+NotePolicy.BUFFER_MS)
            .put("expires_at",expires).put("retention",envelope(RetentionEnvelope.confirmed(RetentionEnvelope.NOTE_ORIGIN,now))));
        m.put("expires_at",Math.max(m.getLong("expires_at"),expires));
        m.put("revision",expected+1); write(id,m);
        }
    }
    public String createInPerson(String title,boolean consent,String input)throws Exception {
        synchronized(MUTATIONS){
        String id=create(title,consent);
        try{
            JSONObject meeting=read(id);meeting.put("capture_mode","in_person").put("audio_session_id",id).put("consents",new JSONArray());
            write(id,meeting);recordConsent(id,0,consent,input);return id;
        }catch(Exception failure){delete(id);throw failure;}
        }
    }
    public void recordConsent(String id,long expected,boolean declared,String input)throws Exception {
        synchronized(MUTATIONS){
        if(!declared)throw new SecurityException("explicit current-participant consent required");
        if(input==null||input.length()>256)throw new IllegalArgumentException("input name");
        JSONObject meeting=read(id);NotePolicy.checkRevision(meeting.getLong("revision"),expected);
        if(!meeting.getString("capture_mode").equals("in_person")||!id.equals(meeting.getString("audio_session_id")))throw new SecurityException("meeting audio binding");
        meeting.getJSONArray("consents").put(new JSONObject().put("declared_at",now()).put("scope","in_person_audio")
            .put("actor","local_identity").put("input",input).put("processing","local_only_no_transcription")
            .put("audio_retention_days",7).put("cloud_authorized",false).put("participant_consent_operator_declaration",true));
        meeting.put("revision",expected+1);write(id,meeting);
        }
    }
    public void end(String id,long expected) throws Exception {
        synchronized(MUTATIONS){
        JSONObject m=read(id); NotePolicy.checkRevision(m.getLong("revision"),expected);
        if(m.getLong("ended_at")!=0) return;
        m.put("ended_at",NotePolicy.endBoundary(0,now()));
        m.put("revision",expected+1); write(id,m);
        }
    }
    public JSONObject projectAudioEnd(String id,JSONObject boundary)throws Exception {
        synchronized(MUTATIONS){
        JSONObject meeting=read(id);
        if(!meeting.getString("capture_mode").equals("in_person"))throw new SecurityException("not audio meeting");
        if(boundary==null)return meeting; // Legacy ended records have no reconstructable timestamp.
        if(meeting.has("audio_end_boundary")){
            if(!meeting.getJSONObject("audio_end_boundary").toString().equals(boundary.toString()))throw new SecurityException("end boundary changed");
            return meeting;
        }
        long estimate=boundary.optLong("utc_estimate",0);
        meeting.put("audio_end_boundary",boundary).put("ended_at",estimate).put("ended_at_quality",estimate>0?"device_clock_estimate":"unknown")
            .put("revision",meeting.getLong("revision")+1);write(id,meeting);return meeting;
        }
    }
    public void delete(String id) throws Exception {
        checkId(id);
        // Stop input even if a prior encrypted append currently owns the durable privacy lock.
        CaptureService.requestPrivacyStop(id);
        synchronized(MUTATIONS){
        synchronized(MeetingPrivacy.LOCK){
        checkId(id);
        // No plaintext in barrier; persists before key/file cleanup.
        DurableDeletionMarkers.mark(context,"deletion-barriers",id,edit->accessProbe.commitDeletion("deletion-barriers",id,edit));
        cleanup(id);
        }
        }
    }
    private void persistNoteBarrier(String id,String noteId) throws IOException {
        checkId(id);checkId(noteId);
        String marker=id+":"+noteId;
        DurableDeletionMarkers.mark(context,"note-deletion-barriers",marker,edit->accessProbe.commitDeletion("note-deletion-barriers",marker,edit));
    }
    public void deleteNote(String id,String noteId,long expected) throws Exception {
        synchronized(MUTATIONS){
        JSONObject meeting=read(id);NotePolicy.checkRevision(meeting.getLong("revision"),expected);
        JSONArray notes=meeting.getJSONArray("attachments");boolean found=false;
        for(int i=0;i<notes.length();i++) if(notes.getJSONObject(i).getString("id").equals(noteId)) found=true;
        if(!found) throw new IllegalArgumentException("笔记不存在或已删除");
        persistNoteBarrier(id,noteId); // Survives failed rewrite, process restart and stale ciphertext.
        read(id); // All read paths filter before exposing any body and finish the active-file rewrite.
        }
    }
    public long visibleLifetime(JSONObject meeting) throws Exception {
        synchronized(MUTATIONS){
        long deadline=meeting.getLong("expires_at");
        if(!meeting.optBoolean("title_expired",false)) deadline=Math.min(deadline,meeting.getLong("title_expires_at"));
        JSONArray notes=meeting.getJSONArray("attachments");
        for(int i=0;i<notes.length();i++) deadline=Math.min(deadline,notes.getJSONObject(i).getLong("expires_at"));
        return Math.max(0,Math.min(300000,deadline-now()));
        }
    }
    private void cleanup(String id) throws Exception {
        checkId(id);DurableDeletionMarkers.requireDurable(context,"deletion-barriers",id);
        keys.deleteEntry(alias(id)); keys.deleteEntry("sidecar.audio."+id); file(id).delete();
        files(id).requireRemoved();
    }
}

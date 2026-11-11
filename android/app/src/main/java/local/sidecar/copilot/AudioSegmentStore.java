package local.sidecar.copilot;

import android.app.KeyguardManager;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import android.provider.Settings;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;

/** Native encrypted PCM persistence. Internal only; no real capture entry point is enabled. */
public final class AudioSegmentStore implements AutoCloseable {
    public interface Faults {
        void at(String stage) throws IOException;
        default void ownedBuffer(String stage,byte[] buffer)throws IOException {}
        default void ownedPcm(String stage,short[] pcm)throws IOException {}
    }
    private final Context context;
    private final File root;
    private final SQLiteDatabase db;
    private final KeyStore keys;
    private final RandomAccessFile lockFile;
    private final FileLock processLock;
    private final Faults faults;
    private boolean closed;
    private static final long BUFFER_NS=900_000_000_000L,RETENTION_MS=7L*24*60*60*1000;
    // A retained range tombstone remains authoritative even if an old live frame row reappears.
    private static final String RANGE_REVOKED="EXISTS (SELECT 1 FROM deletions d WHERE d.session=frames.session AND d.stream=frames.stream AND d.start_sample<frames.end_sample AND d.end_sample>frames.start_sample)";
    public AudioSegmentStore(Context context)throws Exception {this(context,stage->{},true);}
    // Package-private deterministic failure injection, inaccessible from UI/IPC.
    AudioSegmentStore(Context context,Faults faults)throws Exception {this(context,faults,true);}
    // The unrecovered store never escapes this scoped maintenance operation.
    static int sweepExpiredForMaintenance(Context context,java.util.function.BooleanSupplier cancelled)throws Exception {
        return sweepExpiredForMaintenance(context,stage->{},cancelled);
    }
    static int sweepExpiredForMaintenance(Context context,Faults faults,java.util.function.BooleanSupplier cancelled)throws Exception {
        checkCancelled(cancelled);
        try(AudioSegmentStore store=new AudioSegmentStore(context,faults,false)){return store.sweepExpired(cancelled);}
    }
    private AudioSegmentStore(Context context,Faults faults,boolean recoverStore)throws Exception {
        this.context=context;this.faults=faults;
        root=new File(context.getNoBackupFilesDir(),"audio-segments");
        if(!root.exists()&&!root.mkdirs())throw new IOException("audio private directory unavailable");
        lockFile=new RandomAccessFile(new File(root,"writer.lock"),"rw");
        FileLock acquired;
        try{acquired=lockFile.getChannel().tryLock();}catch(Exception|Error failure){
            try{lockFile.close();}catch(Throwable cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}throw failure;
        }
        if(acquired==null){lockFile.close();throw new IOException("audio store already open");}
        processLock=acquired;
        SQLiteDatabase opened=null;
        try {
            keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
            // Store synchronization policy in the pool configuration so replacement connections
            // inherit it; a one-off PRAGMA only changes its current native connection.
            SQLiteDatabase.OpenParams parameters=new SQLiteDatabase.OpenParams.Builder()
                .setOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY|SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
                .setSynchronousMode("FULL").build();
            opened=SQLiteDatabase.openDatabase(new File(root,"index.sqlite"),parameters);
            db=opened;
            db.execSQL("CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, state TEXT NOT NULL, generation INTEGER NOT NULL, stream TEXT NOT NULL, boot INTEGER NOT NULL, sample_rate INTEGER NOT NULL, next_sample INTEGER NOT NULL, seal_generation INTEGER NOT NULL, seal_end INTEGER NOT NULL, start_ns INTEGER NOT NULL, start_utc INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS segments(id TEXT PRIMARY KEY, session TEXT NOT NULL, stream TEXT NOT NULL, start_sample INTEGER NOT NULL, committed INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS frames(id TEXT PRIMARY KEY, session TEXT NOT NULL, stream TEXT NOT NULL, generation INTEGER NOT NULL, boot INTEGER NOT NULL, start_sample INTEGER NOT NULL, end_sample INTEGER NOT NULL, end_ns INTEGER NOT NULL, eligible_ns INTEGER NOT NULL, expires_utc INTEGER NOT NULL, health TEXT NOT NULL, segment TEXT NOT NULL, byte_offset INTEGER NOT NULL, byte_length INTEGER NOT NULL, hash BLOB NOT NULL, nonce BLOB NOT NULL, wrapped BLOB, aad BLOB NOT NULL, deleted INTEGER NOT NULL DEFAULT 0, corrupt INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE TABLE IF NOT EXISTS deletions(session TEXT NOT NULL, stream TEXT NOT NULL, start_sample INTEGER NOT NULL, end_sample INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS deletions_by_stream_end ON deletions(session,stream,end_sample,start_sample)");
            db.execSQL("CREATE TABLE IF NOT EXISTS gaps(session TEXT NOT NULL, stream TEXT NOT NULL, start_sample INTEGER NOT NULL, end_sample INTEGER NOT NULL, reason TEXT NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS clock(singleton INTEGER PRIMARY KEY CHECK(singleton=1), boot INTEGER NOT NULL, wall INTEGER NOT NULL, elapsed INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE IF NOT EXISTS meeting_links(session TEXT PRIMARY KEY)");
            db.execSQL("CREATE TABLE IF NOT EXISTS consent_records(session TEXT NOT NULL, revision INTEGER NOT NULL, declared INTEGER NOT NULL, generation INTEGER, PRIMARY KEY(session,revision))");
            db.execSQL("CREATE TABLE IF NOT EXISTS session_ends(session TEXT PRIMARY KEY, stream TEXT NOT NULL, sample_end INTEGER NOT NULL, boot INTEGER NOT NULL, control_ns INTEGER NOT NULL, utc_estimate INTEGER)");
            db.execSQL("CREATE TABLE IF NOT EXISTS capture_controls(event_id TEXT PRIMARY KEY,session TEXT NOT NULL,stream TEXT NOT NULL,generation INTEGER NOT NULL,boot INTEGER NOT NULL,kind TEXT NOT NULL,requested_ns INTEGER,accepted_ns INTEGER,accepted_end INTEGER,observed_end INTEGER,unknown_tail_from_sample INTEGER,UNIQUE(session,stream))");
            if(recoverStore)recover(true);
        } catch(Exception|Error failure){
            if(opened!=null)try{opened.close();}catch(Throwable cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            try{processLock.release();}catch(Throwable cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            try{lockFile.close();}catch(Throwable cleanup){if(cleanup!=failure)failure.addSuppressed(cleanup);}
            throw failure;
        }
    }
    /** Apply and verify on the transaction-bound writer, including replacement pool connections.
     * Do not use execPerConnectionSQL for this row-returning PRAGMA: older supported Android
     * releases reject SQLITE_ROW there. This is current-page hygiene, not WAL/flash erasure.
     */
    private void configureRevocationWriter(){
        if(!db.inTransaction())throw new IllegalStateException("revocation requires a writer transaction");
        try(Cursor pragma=db.rawQuery("PRAGMA secure_delete=ON",null)){
            if(!pragma.moveToFirst()||pragma.getInt(0)!=1||pragma.moveToNext())
                throw new IllegalStateException("secure deletion writer configuration unavailable");
        }
    }
    private void open(){if(closed)throw new IllegalStateException("store closed");}
    private int boot(){int value=Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,-1);if(value<0)throw new SecurityException("boot clock unavailable");return value;}
    private void unlocked(){KeyguardManager k=context.getSystemService(KeyguardManager.class);if(!k.isDeviceSecure()||k.isDeviceLocked())throw new SecurityException("unlock required");}
    private long trustedNow(){
        long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();int current=boot();
        try(Cursor c=db.rawQuery("SELECT * FROM clock WHERE singleton=1",null)){
            if(c.moveToFirst()){
                // A validated same-boot projection needs no new write or wall-clock adjustment.
                return RetentionClock.anchoredNow(wall,elapsed,c.getLong(2),c.getLong(3),current==c.getInt(1));
            }
        }
        try(Cursor existing=db.rawQuery("SELECT 1 FROM sessions UNION ALL SELECT 1 FROM frames UNION ALL SELECT 1 FROM segments LIMIT 1",null)){
            if(existing.moveToFirst())throw new IllegalStateException("retention anchor missing for existing audio");
        }
        db.execSQL("INSERT OR REPLACE INTO clock VALUES(1,?,?,?)",new Object[]{current,wall,elapsed});return wall;
    }
    private static String alias(String sid){return "sidecar.audio."+sid;}
    private static String id(){return UUID.randomUUID().toString();}
    private File segmentFile(String sid){if(!sid.matches("[0-9a-f-]{36}"))throw new SecurityException("invalid segment id");return new File(root,sid+".seg");}
    private static int col(Cursor c,String name){return c.getColumnIndexOrThrow(name);}
    private static long num(Cursor c,String name){return c.getLong(col(c,name));}
    private static String str(Cursor c,String name){return c.getString(col(c,name));}
    private static byte[] bytes(Cursor c,String name){return c.getBlob(col(c,name));}
    private Cursor session(String sid){Cursor c=db.rawQuery("SELECT * FROM sessions WHERE id=?",new String[]{sid});if(!c.moveToFirst()){c.close();throw new SecurityException("session unavailable");}return c;}
    private void createKey(String sid)throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(alias(sid),KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA1)
            .setUserAuthenticationRequired(true).setUserAuthenticationParameters(300,KeyProperties.AUTH_DEVICE_CREDENTIAL|KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setUnlockedDeviceRequired(true).build());generator.generateKeyPair();
    }
    private static OAEPParameterSpec oaep(){return new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT);}
    public synchronized String createSession(int rate)throws Exception {
        return createSession(rate,id(),false);
    }
    public synchronized String createSession(int rate,String sid)throws Exception {
        return createSession(rate,sid,true);
    }
    private String createSession(int rate,String sid,boolean linked)throws Exception {
        synchronized(MeetingPrivacy.LOCK){
        open();unlocked();if(rate<8000||rate>48000)throw new IllegalArgumentException("sample rate");
        if(sid==null||!sid.matches("[0-9a-f-]{36}")||isDeleted(sid))throw new SecurityException("invalid or deleted meeting");
        try(Cursor existing=db.rawQuery("SELECT 1 FROM sessions WHERE id=?",new String[]{sid})){if(existing.moveToFirst())throw new SecurityException("session already exists");}
        long now=trustedNow();createKey(sid);
        db.beginTransaction();try{db.execSQL("INSERT INTO sessions VALUES(?,'ready',0,'',?,?,0,-1,0,?,?)",new Object[]{sid,boot(),rate,SystemClock.elapsedRealtimeNanos(),now});
            if(linked)db.execSQL("INSERT INTO meeting_links VALUES(?)",new Object[]{sid});db.setTransactionSuccessful();
        }catch(Exception e){keys.deleteEntry(alias(sid));throw e;}finally{db.endTransaction();}return sid;
        }
    }
    synchronized void recordDeclaration(String sid,long revision,long declared){
        synchronized(MeetingPrivacy.LOCK){
        open();unlocked();if(isDeleted(sid))throw new SecurityException("meeting deleted");
        try(Cursor session=session(sid)){if(str(session,"state").equals("recording")||str(session,"state").equals("ended"))throw new SecurityException("declaration requires ready or paused session");}
        try(Cursor last=db.rawQuery("SELECT MAX(revision) FROM consent_records WHERE session=?",new String[]{sid})){if(last.moveToFirst()&&!last.isNull(0)&&revision<=last.getLong(0))throw new SecurityException("stale consent revision");}
        db.execSQL("INSERT INTO consent_records VALUES(?,?,?,NULL)",new Object[]{sid,revision,declared});
        }
    }
    public synchronized long start(String sid,boolean consent)throws Exception {
        synchronized(MeetingPrivacy.LOCK){
        open();unlocked();trustedNow();if(!consent||isDeleted(sid))throw new SecurityException("explicit consent required; deleted sessions forbidden");
        try(Cursor active=db.rawQuery("SELECT id FROM sessions WHERE state='recording'",null)){if(active.moveToFirst())throw new IllegalStateException("single active audio session");}
        db.beginTransaction();try(Cursor c=session(sid)){
            if(str(c,"state").equals("ended")||num(c,"boot")!=boot())throw new SecurityException("session requires new permission or boot mapping");
            long generation=num(c,"generation")+1;
            try(Cursor link=db.rawQuery("SELECT 1 FROM meeting_links WHERE session=?",new String[]{sid})){
                if(link.moveToFirst())try(Cursor declaration=db.rawQuery("SELECT revision,generation FROM consent_records WHERE session=? ORDER BY revision DESC LIMIT 1",new String[]{sid})){
                    if(!declaration.moveToFirst()||!declaration.isNull(1))throw new SecurityException("fresh recorded consent required");
                    db.execSQL("UPDATE consent_records SET generation=? WHERE session=? AND revision=?",new Object[]{generation,sid,declaration.getLong(0)});
                }
            }
            db.execSQL("UPDATE sessions SET state='recording',generation=?,stream=?,next_sample=0,seal_generation=-1,seal_end=0,start_ns=?,start_utc=? WHERE id=?",
                new Object[]{generation,id(),SystemClock.elapsedRealtimeNanos(),trustedNow(),sid});db.setTransactionSuccessful();return generation;
        }finally{db.endTransaction();}
        }
    }
    public synchronized String stream(String sid){try(Cursor c=session(sid)){return str(c,"stream");}}
    public synchronized String state(String sid){try(Cursor c=session(sid)){return str(c,"state");}}
    public synchronized int sampleRate(String sid){try(Cursor c=session(sid)){return (int)num(c,"sample_rate");}}
    public synchronized long sessionBoot(String sid){try(Cursor c=session(sid)){return num(c,"boot");}}
    public synchronized void end(String sid){end(sid,SystemClock.elapsedRealtimeNanos());}
    public synchronized void end(String sid,long requestedNs){synchronized(MeetingPrivacy.LOCK){
        if(requestedNs<=0||requestedNs>SystemClock.elapsedRealtimeNanos())throw new IllegalArgumentException("invalid end request clock");
        open();if(isDeleted(sid))throw new SecurityException("meeting deleted");db.beginTransaction();try(Cursor c=session(sid)){
            if(str(c,"state").equals("recording"))throw new IllegalStateException("stop source before ending");
            if(!str(c,"state").equals("ended")){
                long mono=requestedNs;int current=boot();
                Long estimate=current==num(c,"boot")&&mono>=num(c,"start_ns")?num(c,"start_utc")+(mono-num(c,"start_ns"))/1_000_000L:null;
                long coverageEnd=num(c,"next_sample");
                try(Cursor boundary=db.rawQuery("SELECT observed_end FROM capture_controls WHERE session=? AND stream=?",new String[]{sid,str(c,"stream")})){
                    if(boundary.moveToFirst()&&!boundary.isNull(0))coverageEnd=Math.max(coverageEnd,boundary.getLong(0));
                }
                db.execSQL("INSERT INTO session_ends VALUES(?,?,?,?,?,?)",new Object[]{sid,str(c,"stream"),coverageEnd,current,mono,estimate});
                db.execSQL("UPDATE sessions SET state='ended',generation=generation+1,seal_generation=-1,seal_end=0 WHERE id=?",new Object[]{sid});
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }}
    public synchronized JSONObject endMetadata(String sid)throws Exception {
        if(isDeleted(sid))throw new SecurityException("meeting deleted");
        try(Cursor c=db.rawQuery("SELECT * FROM session_ends WHERE session=?",new String[]{sid})){
            if(!c.moveToFirst())return null;
            return new JSONObject().put("stream",str(c,"stream")).put("sample_end",num(c,"sample_end")).put("boot",num(c,"boot"))
                .put("control_ns",num(c,"control_ns")).put("utc_estimate",c.isNull(col(c,"utc_estimate"))?JSONObject.NULL:num(c,"utc_estimate"));
        }
    }
    public synchronized void beginPause(String sid,long generation,long acceptedEnd)throws IOException {
        faults.at("before_pause_barrier");
        open();try(Cursor c=session(sid)){
            if(num(c,"generation")!=generation||!str(c,"state").equals("recording"))throw new SecurityException("stale pause");
            if(acceptedEnd<num(c,"next_sample"))throw new IllegalArgumentException("invalid accepted boundary");
            db.execSQL("UPDATE sessions SET state='paused',generation=generation+1,seal_generation=?,seal_end=? WHERE id=?",new Object[]{generation,acceptedEnd,sid});
        }
    }
    /** Closes one stream and durably records known coverage in the same transaction. */
    public synchronized void beginPause(String sid,CaptureBoundary boundary)throws IOException {
        open();faults.at("before_pause_barrier");
        if(boundary==null||boundary.eventId==null||boundary.eventId.isEmpty()||boundary.kind==null||boundary.kind.isEmpty()
            ||boundary.requestedNs<=0||boundary.acceptedNs<boundary.requestedNs||boundary.acceptedEnd<0||boundary.observedEnd<boundary.acceptedEnd)
            throw new IllegalArgumentException("invalid control boundary");
        synchronized(MeetingPrivacy.LOCK){
            if(isDeleted(sid))throw new SecurityException("meeting deleted before pause");
            db.beginTransaction();try(Cursor s=session(sid)){
                if(!str(s,"stream").equals(boundary.stream)||num(s,"boot")!=boundary.boot)throw new SecurityException("stale control stream");
                try(Cursor prior=db.rawQuery("SELECT * FROM capture_controls WHERE session=? AND stream=?",new String[]{sid,boundary.stream})){
                    if(prior.moveToFirst()){
                        if(!str(prior,"event_id").equals(boundary.eventId)||num(prior,"generation")!=boundary.generation
                            ||!str(prior,"kind").equals(boundary.kind)||num(prior,"requested_ns")!=boundary.requestedNs
                            ||num(prior,"accepted_ns")!=boundary.acceptedNs||num(prior,"accepted_end")!=boundary.acceptedEnd
                            ||num(prior,"observed_end")!=boundary.observedEnd)throw new SecurityException("conflicting control boundary");
                        db.setTransactionSuccessful();return;
                    }
                }
                if(!str(s,"state").equals("recording")||num(s,"generation")!=boundary.generation)throw new SecurityException("stale control generation");
                if(boundary.acceptedEnd<num(s,"next_sample"))throw new IllegalArgumentException("invalid admitted boundary");
                db.execSQL("INSERT INTO capture_controls VALUES(?,?,?,?,?,?,?,?,?,?,NULL)",new Object[]{boundary.eventId,sid,boundary.stream,boundary.generation,boundary.boot,boundary.kind,boundary.requestedNs,boundary.acceptedNs,boundary.acceptedEnd,boundary.observedEnd});
                db.execSQL("UPDATE sessions SET state='paused',generation=generation+1,seal_generation=?,seal_end=? WHERE id=?",new Object[]{boundary.generation,boundary.acceptedEnd,sid});
                if(boundary.observedEnd>boundary.acceptedEnd)db.execSQL("INSERT INTO gaps VALUES(?,?,?,?,?)",new Object[]{sid,boundary.stream,boundary.acceptedEnd,boundary.observedEnd,"pcm_backpressure"});
                faults.at("control_before_commit");db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            faults.at("control_after_commit");
        }
    }
    public synchronized void finishPause(String sid)throws IOException {
        synchronized(MeetingPrivacy.LOCK){
        open();db.beginTransaction();try(Cursor c=session(sid)){
            if(!isDeleted(sid)&&num(c,"seal_end")>num(c,"next_sample"))db.execSQL("INSERT INTO gaps VALUES(?,?,?,?,?)",new Object[]{sid,str(c,"stream"),num(c,"next_sample"),num(c,"seal_end"),"uncommitted_tail"});
            db.execSQL("UPDATE sessions SET seal_generation=-1,seal_end=0 WHERE id=?",new Object[]{sid});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        }
        faults.at("after_pause_finish");
    }
    /** Projects existing recovery controls without inventing an exact lost-tail endpoint. */
    public synchronized JSONArray recoveryUncertainties(String sid)throws Exception {
        synchronized(MeetingPrivacy.LOCK){
            open();unlocked();trustedNow();if(isDeleted(sid))throw new SecurityException("meeting deleted");
            try(Cursor ignored=session(sid)){/* Require a real session, including sessions with no marker. */}
            JSONArray result=new JSONArray();
            try(Cursor c=db.rawQuery("SELECT stream,generation,boot,unknown_tail_from_sample FROM capture_controls WHERE session=? AND kind='recovery' ORDER BY generation,stream",new String[]{sid})){
                while(c.moveToNext()){
                    if(c.isNull(3)||c.getLong(3)<0)throw new SecurityException("invalid recovery tail boundary");
                    result.put(new JSONObject().put("stream",c.getString(0)).put("generation",c.getLong(1)).put("boot",c.getLong(2))
                        .put("after_sample",c.getLong(3)).put("end_sample",JSONObject.NULL).put("reason","recovery_tail_unknown"));
                }
            }
            faults.at("recovery_projection_before_return");
            unlocked();trustedNow();if(isDeleted(sid))throw new SecurityException("meeting deleted");
            return result;
        }
    }
    public synchronized String append(String sid,long generation,PcmFrameAssembler.Frame frame)throws Exception {
        synchronized(MeetingPrivacy.LOCK){
        open();if(isDeleted(sid))throw new SecurityException("meeting deletion barrier");String stream;int rate,currentBoot;long expires;
        try(Cursor c=session(sid)){
            boolean recording=str(c,"state").equals("recording")&&num(c,"generation")==generation;
            boolean sealing=str(c,"state").equals("paused")&&num(c,"seal_generation")==generation&&frame.endSample<=num(c,"seal_end");
            if((!recording&&!sealing)||num(c,"boot")!=boot())throw new SecurityException("capture barrier");
            if(frame.startSample!=num(c,"next_sample")||frame.endSample<=frame.startSample||frame.endSample-frame.startSample!=frame.pcm.length)throw new SecurityException("sample sequence");
            stream=str(c,"stream");rate=(int)num(c,"sample_rate");currentBoot=(int)num(c,"boot");
            if(frame.pcm.length>rate*10)throw new SecurityException("frame exceeds configured boundary");
            long earliest=Math.max(num(c,"start_ns"),frame.receivedMonoNanos-(long)frame.pcm.length*1_000_000_000L/rate);
            expires=num(c,"start_utc")+(earliest-num(c,"start_ns"))/1_000_000L+RETENTION_MS;
        }
        try(Cursor deleted=db.rawQuery("SELECT 1 FROM deletions WHERE session=? AND stream=? AND start_sample<? AND end_sample>?",new String[]{sid,stream,Long.toString(frame.endSample),Long.toString(frame.startSample)})){
            if(deleted.moveToFirst())throw new SecurityException("deleted interval");
        }
        String fid=id();long eligible=Math.addExact(frame.receivedMonoNanos,BUFFER_NS);
        byte[] aad=metadata(fid,sid,stream,generation,currentBoot,frame.startSample,frame.endSample,frame.receivedMonoNanos,eligible,expires,frame.sourceHealth);
        byte[] clear=new byte[frame.pcm.length*2];byte[] dek=null;
        byte[] cipherText,nonce,wrapped;
        try {
            ByteBuffer.wrap(clear).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(frame.pcm);
            faults.ownedBuffer("append_clear",clear);faults.at("append_before_keygen");
            KeyGenerator kg=KeyGenerator.getInstance("AES");kg.init(256);dek=kg.generateKey().getEncoded();faults.ownedBuffer("append_dek",dek);
            Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");faults.at("append_before_aes_init");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(dek,"AES"));aes.updateAAD(aad);faults.at("append_before_aes_final");cipherText=aes.doFinal(clear);nonce=aes.getIV();
            PublicKey pub=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keys.getCertificate(alias(sid)).getPublicKey().getEncoded()));
            Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");rsa.init(Cipher.ENCRYPT_MODE,pub,oaep());faults.at("append_before_wrap");wrapped=rsa.doFinal(dek);
        }finally{Arrays.fill(clear,(byte)0);if(dek!=null)Arrays.fill(dek,(byte)0);}
        String segment=null;long offset=0;
        try(Cursor c=db.rawQuery("SELECT * FROM segments WHERE session=? AND stream=? ORDER BY rowid DESC LIMIT 1",new String[]{sid,stream})){
            if(c.moveToFirst()&&frame.startSample-num(c,"start_sample")<rate*900L&&num(c,"committed")+cipherText.length<=16*1024*1024){segment=str(c,"id");offset=num(c,"committed");}
        }
        if(segment==null){segment=id();db.execSQL("INSERT INTO segments VALUES(?,?,?,?,0)",new Object[]{segment,sid,stream,frame.startSample});}
        try(RandomAccessFile file=new RandomAccessFile(segmentFile(segment),"rw")){
            if(file.length()<offset)throw new IOException("committed segment truncated");
            file.setLength(offset);file.seek(offset);file.write(cipherText);faults.at("before_fsync");file.getFD().sync();faults.at("after_fsync");
        }
        db.beginTransaction();try {
            if(isDeleted(sid))throw new SecurityException("meeting deleted before commit");
            ContentValues v=new ContentValues();v.put("id",fid);v.put("session",sid);v.put("stream",stream);v.put("generation",generation);v.put("boot",currentBoot);
            v.put("start_sample",frame.startSample);v.put("end_sample",frame.endSample);v.put("end_ns",frame.receivedMonoNanos);v.put("eligible_ns",eligible);v.put("expires_utc",expires);
            v.put("health",frame.sourceHealth);v.put("segment",segment);v.put("byte_offset",offset);v.put("byte_length",cipherText.length);v.put("hash",sha(cipherText));v.put("nonce",nonce);v.put("wrapped",wrapped);v.put("aad",aad);
            db.insertOrThrow("frames",null,v);db.execSQL("UPDATE segments SET committed=? WHERE id=?",new Object[]{offset+cipherText.length,segment});
            db.execSQL("UPDATE sessions SET next_sample=? WHERE id=?",new Object[]{frame.endSample,sid});faults.at("before_index_commit");
            // Recheck after fallible file work and the final test seam, using the original stream identity.
            if(isDeleted(sid))throw new SecurityException("meeting deleted before index commit");
            try(Cursor current=session(sid)){
                boolean recording=str(current,"state").equals("recording")&&num(current,"generation")==generation;
                boolean sealing=str(current,"state").equals("paused")&&num(current,"seal_generation")==generation&&frame.endSample<=num(current,"seal_end");
                if((!recording&&!sealing)||!str(current,"stream").equals(stream)||num(current,"boot")!=currentBoot
                        ||currentBoot!=boot()||num(current,"next_sample")!=frame.endSample)
                    throw new SecurityException("capture changed before index commit");
            }
            try(Cursor deleted=db.rawQuery("SELECT 1 FROM deletions WHERE session=? AND stream=? AND start_sample<? AND end_sample>?",
                    new String[]{sid,stream,Long.toString(frame.endSample),Long.toString(frame.startSample)})){
                if(deleted.moveToFirst())throw new SecurityException("deleted interval before index commit");
            }
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}return fid;
        }
    }
    private static byte[] metadata(String fid,String sid,String stream,long gen,long boot,long start,long end,long endNs,long eligible,long expires,String health)throws Exception {
        return new JSONObject().put("id",fid).put("session",sid).put("stream",stream).put("generation",gen).put("boot",boot)
            .put("start",start).put("end",end).put("end_ns",endNs).put("eligible",eligible).put("expires",expires).put("health",health).put("format","pcm16le-v1").toString().getBytes("UTF-8");
    }
    private static byte[] sha(byte[] value)throws Exception{return MessageDigest.getInstance("SHA-256").digest(value);}
    private byte[] ciphertext(Cursor f)throws Exception {
        byte[] expected=metadata(str(f,"id"),str(f,"session"),str(f,"stream"),num(f,"generation"),num(f,"boot"),num(f,"start_sample"),num(f,"end_sample"),num(f,"end_ns"),num(f,"eligible_ns"),num(f,"expires_utc"),str(f,"health"));
        if(!Arrays.equals(expected,bytes(f,"aad")))throw new SecurityException("authenticated metadata mismatch");
        long offset=num(f,"byte_offset"),length=num(f,"byte_length"),committed;
        try(Cursor s=db.rawQuery("SELECT committed FROM segments WHERE id=?",new String[]{str(f,"segment")})){if(!s.moveToFirst())throw new IOException("segment unavailable");committed=s.getLong(0);}
        if(offset<0||length<16||length>960016||offset>committed-length)throw new SecurityException("invalid frame mapping");
        byte[] blob=new byte[(int)length];try(RandomAccessFile in=new RandomAccessFile(segmentFile(str(f,"segment")),"r")){in.seek(offset);in.readFully(blob);}
        if(!MessageDigest.isEqual(sha(blob),bytes(f,"hash")))throw new SecurityException("frame checksum mismatch");return blob;
    }
    public synchronized List<String> frameIds(String sid){open();if(isDeleted(sid))throw new SecurityException("meeting deleted");List<String> result=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT id FROM frames WHERE session=? AND deleted=0 AND NOT "+RANGE_REVOKED+" ORDER BY rowid",new String[]{sid})){while(c.moveToNext())result.add(c.getString(0));}return result;}
    public synchronized short[] read(String fid)throws Exception {
        synchronized(MeetingPrivacy.LOCK){
        open();unlocked();long now=trustedNow();
        byte[] dek=null,clear=null;short[] pcm=null;boolean delivered=false;
        try{
            try(Cursor f=db.rawQuery("SELECT * FROM frames WHERE id=? AND NOT "+RANGE_REVOKED,new String[]{fid})){
                if(!f.moveToFirst()||num(f,"deleted")!=0||num(f,"corrupt")!=0)throw new SecurityException("frame unavailable");
                if(isDeleted(str(f,"session")))throw new SecurityException("meeting deleted");
                if(now>=num(f,"expires_utc")){deleteRange(str(f,"session"),str(f,"stream"),num(f,"start_sample"),num(f,"end_sample"));throw new SecurityException("frame expired");}
                Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");rsa.init(Cipher.DECRYPT_MODE,keys.getKey(alias(str(f,"session")),null),oaep());dek=rsa.doFinal(bytes(f,"wrapped"));
                faults.ownedBuffer("read_dek",dek);Cipher aes=Cipher.getInstance("AES/GCM/NoPadding");aes.init(Cipher.DECRYPT_MODE,new SecretKeySpec(dek,"AES"),new GCMParameterSpec(128,bytes(f,"nonce")));aes.updateAAD(bytes(f,"aad"));clear=aes.doFinal(ciphertext(f));faults.ownedBuffer("read_clear",clear);pcm=new short[clear.length/2];
                faults.ownedPcm("read_pcm",pcm);ByteBuffer.wrap(clear).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
            }
            faults.at("read_before_return");requireReplayable(fid);delivered=true;return pcm;
        }finally{if(dek!=null)Arrays.fill(dek,(byte)0);if(clear!=null)Arrays.fill(clear,(byte)0);if(!delivered&&pcm!=null)Arrays.fill(pcm,(short)0);}
        }
    }
    public synchronized void requireReplayable(String fid){
        open();unlocked();long now=trustedNow();
        try(Cursor frame=db.rawQuery("SELECT session,deleted,corrupt,expires_utc FROM frames WHERE id=? AND NOT "+RANGE_REVOKED,new String[]{fid})){
            if(!frame.moveToFirst()||isDeleted(frame.getString(0))||frame.getInt(1)!=0||frame.getInt(2)!=0||now>=frame.getLong(3))throw new SecurityException("replay permission expired or deleted");
        }
    }
    /** Local immutable description only: this is neither a wire Manifest nor permission to send.
     * Preserve stream sample coordinates and the receive-time fence; historical sample start
     * timestamps are not available and must not be invented from the current session clock.
     */
    synchronized AudioSourceReference inspectUploadSource(String fid)throws Exception {
        synchronized(MeetingPrivacy.LOCK){
            open();unlocked();
            ReferenceSnapshot before=sourceReference(fid,false);
            short[] authenticated=null;
            try{
                // ciphertext() alone verifies a checksum/AAD consistency, not the GCM tag.
                // Reuse the existing authenticated read and clear its temporary PCM locally.
                authenticated=read(fid);
                faults.ownedPcm("upload_reference_pcm",authenticated);
                faults.at("upload_reference_after_auth");
                ReferenceSnapshot after=sourceReference(fid,true);
                if(!before.reference.equals(after.reference)||!before.cryptoFingerprint.equals(after.cryptoFingerprint))
                    throw new SecurityException("source changed during reference authentication");
                return after.reference;
            }finally{if(authenticated!=null)Arrays.fill(authenticated,(short)0);}
        }
    }
    private static final class ReferenceSnapshot {
        final AudioSourceReference reference;final String cryptoFingerprint;
        ReferenceSnapshot(AudioSourceReference reference,String cryptoFingerprint){this.reference=reference;this.cryptoFingerprint=cryptoFingerprint;}
    }
    private ReferenceSnapshot sourceReference(String fid,boolean verifyCurrentCiphertext)throws Exception {
        unlocked();long now=trustedNow();AudioSourceReference reference;String cryptoFingerprint;
        try(Cursor f=db.rawQuery("SELECT * FROM frames WHERE id=? AND NOT "+RANGE_REVOKED,new String[]{fid})){
            if(!f.moveToFirst()||num(f,"deleted")!=0||num(f,"corrupt")!=0||f.isNull(col(f,"wrapped")))
                throw new SecurityException("source unavailable for reference");
            if(isDeleted(str(f,"session"))||now>=num(f,"expires_utc"))throw new SecurityException("source deleted or expired");
            if(verifyCurrentCiphertext)ciphertext(f); // Detect storage corruption after the authenticated read.
            reference=new AudioSourceReference(str(f,"id"),str(f,"session"),str(f,"stream"),num(f,"generation"),num(f,"boot"),
                num(f,"start_sample"),num(f,"end_sample"),num(f,"end_ns"),num(f,"eligible_ns"),num(f,"expires_utc"),
                num(f,"byte_length"),hexDigest(bytes(f,"hash")),hexDigest(sha(bytes(f,"aad"))),"pcm16le-v1");
            // Keep encryption-parameter identity private. It is not part of the exported description.
            byte[] nonce=bytes(f,"nonce"),wrapped=bytes(f,"wrapped");
            if(nonce==null||nonce.length==0||wrapped==null||wrapped.length==0)throw new SecurityException("source encryption parameters unavailable");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(4).putInt(nonce.length).array());digest.update(nonce);
            digest.update(ByteBuffer.allocate(4).putInt(wrapped.length).array());digest.update(wrapped);
            cryptoFingerprint=hexDigest(digest.digest());
        }
        // Cursor closure and authentication can fail or take time. Recheck the current gates.
        unlocked();long completedNow=trustedNow();
        if(reference.bootCount!=boot()||SystemClock.elapsedRealtimeNanos()<reference.hostReceiveEndNs
                ||completedNow>=reference.expiresUtcMs||isDeleted(reference.sessionId))
            throw new SecurityException("source clock, deletion or access changed");
        return new ReferenceSnapshot(reference,cryptoFingerprint);
    }
    private static String hexDigest(byte[] digest){
        if(digest==null||digest.length!=32)throw new SecurityException("invalid source digest");
        char[] result=new char[64],alphabet="0123456789abcdef".toCharArray();
        for(int i=0;i<digest.length;i++){int value=digest[i]&255;result[2*i]=alphabet[value>>>4];result[2*i+1]=alphabet[value&15];}
        return new String(result);
    }
    /** Order admission and output with meeting/range revocation. Outputs must not wait for buffer space. */
    synchronized int writeReplayChunk(String fid,AudioFrameReplay.Output output,short[] pcm,int offset,int count)throws Exception {
        synchronized(MeetingPrivacy.LOCK){
            requireReplayable(fid);
            return output.write(pcm,offset,count);
        }
    }
    public void deleteRange(String sid,String stream,long start,long end){
        try{deleteRangeDurably(sid,stream,start,end);}
        catch(IOException failure){throw new UncheckedIOException("range deletion persistence failed",failure);}
    }
    /** Exact scope is fixed by the caller before waiting. No worker join or physical cleanup here. */
    synchronized void deleteRangeDurably(String sid,String stream,long start,long end)throws IOException{
        open();if(sid==null||sid.isEmpty()||stream==null||stream.isEmpty()||start<0||end<=start)throw new IllegalArgumentException("range");
        synchronized(MeetingPrivacy.LOCK){
            db.beginTransaction();try{
                configureRevocationWriter();
                db.execSQL("INSERT INTO deletions SELECT ?,?,?,? WHERE NOT EXISTS (SELECT 1 FROM deletions WHERE session=? AND stream=? AND start_sample=? AND end_sample=?)",
                    new Object[]{sid,stream,start,end,sid,stream,start,end});
                db.execSQL("UPDATE frames SET deleted=1,wrapped=NULL WHERE session=? AND stream=? AND start_sample<? AND end_sample>? AND (deleted!=1 OR wrapped IS NOT NULL)",new Object[]{sid,stream,end,start});
                faults.at("range_delete_before_commit");db.setTransactionSuccessful();
            }finally{db.endTransaction();}
            faults.at("range_delete_after_commit");
        }
    }
    public synchronized void compact()throws Exception {
        open();reapplyRangeRevocations();
        compact(()->false,false);
        cleanupOrphans(()->false);
    }
    private static void checkCancelled(java.util.function.BooleanSupplier cancelled)throws InterruptedIOException {
        if(cancelled.getAsBoolean())throw new InterruptedIOException("cleanup cancelled");
    }
    private void cleanupOrphans(java.util.function.BooleanSupplier cancelled)throws IOException {
        Set<String> known=new HashSet<>();
        try(Cursor c=db.rawQuery("SELECT id FROM segments",null)){while(c.moveToNext())known.add(segmentFile(c.getString(0)).getName());}
        File[] files=root.listFiles();if(files==null)throw new IOException("audio directory unavailable");
        for(File file:files){checkCancelled(cancelled);
            if(file.isFile()&&file.getName().matches("[0-9a-f-]{36}\\.seg")&&!known.contains(file.getName())){
                faults.at("cleanup_before_unlink");
                if(!file.delete())throw new IOException("orphan cleanup pending");
            }
        }
    }
    /** Replays existing authorization barriers before any fallible physical cleanup. No clock or key use. */
    private void reapplyRangeRevocations()throws IOException {
        db.beginTransaction();try{
            configureRevocationWriter();
            db.execSQL("UPDATE frames SET deleted=1,wrapped=NULL WHERE (deleted!=1 OR wrapped IS NOT NULL) AND (deleted=1 OR "+RANGE_REVOKED+")");
            faults.at("range_replay_before_commit");db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    /** Reclaims only durably revoked ciphertext; never adjudicates expiry or opens plaintext. */
    public synchronized void retryDeletedCleanup(java.util.function.BooleanSupplier cancelled)throws Exception {
        open();checkCancelled(cancelled);reapplyRangeRevocations();
        for(String sid:DurableDeletionMarkers.keys(context,"deletion-barriers")){
            checkCancelled(cancelled);purgeDeleted(sid);
        }
        cleanupOrphans(cancelled);
        compact(cancelled,true);
        cleanupOrphans(cancelled);
    }
    private void compact(java.util.function.BooleanSupplier cancelled,boolean dirtyOnly)throws Exception {
        open();List<String> oldIds=new ArrayList<>();String query=dirtyOnly?"SELECT DISTINCT segments.id FROM segments JOIN frames ON frames.segment=segments.id WHERE frames.deleted=1":"SELECT id FROM segments";
        try(Cursor c=db.rawQuery(query,null)){while(c.moveToNext())oldIds.add(c.getString(0));}
        for(String old:oldIds){String replacement=id();List<String> fids=new ArrayList<>();List<Long> offsets=new ArrayList<>();long size=0;
            checkCancelled(cancelled);String sid;
            try(Cursor s=db.rawQuery("SELECT session FROM segments WHERE id=?",new String[]{old})){if(!s.moveToFirst())continue;sid=s.getString(0);}
            if(isDeleted(sid)){purgeDeleted(sid);continue;}
            try(RandomAccessFile out=new RandomAccessFile(segmentFile(replacement),"rw");Cursor f=db.rawQuery("SELECT * FROM frames WHERE segment=? AND deleted=0 ORDER BY byte_offset",new String[]{old})){
                while(f.moveToNext()){checkCancelled(cancelled);byte[] blob=ciphertext(f);fids.add(str(f,"id"));offsets.add(size);out.write(blob);size+=blob.length;}faults.at("compaction_before_fsync");out.getFD().sync();
                out.seek(0);try(Cursor verify=db.rawQuery("SELECT * FROM frames WHERE segment=? AND deleted=0 ORDER BY byte_offset",new String[]{old})){while(verify.moveToNext()){byte[] blob=new byte[(int)num(verify,"byte_length")];out.readFully(blob);if(!MessageDigest.isEqual(sha(blob),bytes(verify,"hash")))throw new IOException("replacement verification failed");}}
            }
            checkCancelled(cancelled);
            synchronized(MeetingPrivacy.LOCK){
                if(isDeleted(sid)){purgeDeleted(sid);continue;}
                db.beginTransaction();try{
                    configureRevocationWriter();
                    if(!fids.isEmpty())db.execSQL("INSERT INTO segments SELECT ?,session,stream,start_sample,? FROM segments WHERE id=?",new Object[]{replacement,size,old});
                    for(int i=0;i<fids.size();i++){
                        ContentValues values=new ContentValues();values.put("segment",replacement);values.put("byte_offset",offsets.get(i));
                        if(db.update("frames",values,"id=? AND segment=? AND deleted=0",new String[]{fids.get(i),old})!=1)throw new IOException("frame mapping changed during cleanup");
                    }
                    if(db.delete("segments","id=?",new String[]{old})!=1)throw new IOException("segment mapping changed during cleanup");
                    faults.at("compaction_before_switch");db.setTransactionSuccessful();
                }finally{db.endTransaction();}
            }
            faults.at("compaction_after_switch");
            if(segmentFile(old).exists()&&!segmentFile(old).delete())throw new IOException("old segment cleanup pending");
            if(fids.isEmpty()&&segmentFile(replacement).exists()&&!segmentFile(replacement).delete())throw new IOException("empty replacement cleanup pending");
        }
    }
    public synchronized int sweepExpired(java.util.function.BooleanSupplier cancelled)throws Exception {
        open();checkCancelled(cancelled);long now;int count;
        try{now=trustedNow();}
        catch(RuntimeException clockFailure){
            // Uncertain time cannot create new expiry decisions, but existing revocations stand.
            try{retryDeletedCleanup(cancelled);}
            catch(InterruptedIOException cancellation){throw cancellation;}
            catch(Exception cleanupFailure){if(cleanupFailure!=clockFailure)clockFailure.addSuppressed(cleanupFailure);}
            throw clockFailure;
        }
        checkCancelled(cancelled);
        // Persist key revocation before any fallible physical cleanup of older segments.
        db.beginTransaction();try{
            configureRevocationWriter();
            try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM frames WHERE deleted=0 AND expires_utc<=?",new String[]{Long.toString(now)})){c.moveToFirst();count=c.getInt(0);}
            db.execSQL("INSERT INTO deletions SELECT session,stream,start_sample,end_sample FROM frames WHERE deleted=0 AND expires_utc<=?",new Object[]{now});
            db.execSQL("UPDATE frames SET deleted=1,wrapped=NULL WHERE deleted=0 AND expires_utc<=?",new Object[]{now});faults.at("expiry_before_commit");checkCancelled(cancelled);db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        retryDeletedCleanup(cancelled);return count;
    }
    public synchronized void recover()throws Exception {
        recover(true);
    }
    private void recover(boolean verifyAll)throws Exception {
        open();reapplyRangeRevocations();Set<String> purged=new HashSet<>();
        for(String sid:DurableDeletionMarkers.keys(context,"deletion-barriers")){purgeDeleted(sid);purged.add(sid);}
        try(Cursor c=db.rawQuery("SELECT * FROM segments",null)){while(c.moveToNext()){
            File file=segmentFile(str(c,"id"));
            if(file.exists()&&file.length()>num(c,"committed"))try(RandomAccessFile out=new RandomAccessFile(file,"rw")){out.setLength(num(c,"committed"));out.getFD().sync();}
        }}
        cleanupOrphans(()->false);
        if(verifyAll)try(Cursor f=db.rawQuery("SELECT * FROM frames WHERE deleted=0",null)){while(f.moveToNext())try{ciphertext(f);}catch(Exception bad){db.execSQL("UPDATE frames SET corrupt=1 WHERE id=?",new Object[]{str(f,"id")});}}
        faults.at("recovery_after_file_verification");
        List<String> pending=new ArrayList<>();try(Cursor c=db.rawQuery("SELECT id FROM sessions WHERE seal_generation>=0",null)){while(c.moveToNext())pending.add(c.getString(0));}
        for(String sid:pending)finishPause(sid);
        synchronized(MeetingPrivacy.LOCK){
            // Deletion may have arrived during file verification; never resurrect its controls.
            for(String sid:DurableDeletionMarkers.keys(context,"deletion-barriers"))if(!purged.contains(sid))purgeDeleted(sid);
            // A vanished process cannot supply the size of its RAM queue. Preserve that uncertainty.
            db.beginTransaction();try{
                try(Cursor recording=db.rawQuery("SELECT id,stream,generation,boot,next_sample FROM sessions WHERE state='recording'",null)){
                    while(recording.moveToNext()){
                        String sid=recording.getString(0);
                        if(isDeleted(sid))continue; // Recheck inside the marker transaction.
                        db.execSQL("INSERT OR IGNORE INTO capture_controls VALUES(?,?,?,?,?,'recovery',NULL,NULL,NULL,NULL,?)",
                            new Object[]{id(),sid,recording.getString(1),recording.getLong(2),recording.getLong(3),recording.getLong(4)});
                    }
                }
                db.execSQL("UPDATE sessions SET state='paused',generation=generation+1 WHERE state='recording'");
                faults.at("before_recovery_commit");db.setTransactionSuccessful();
            }finally{db.endTransaction();}
        }
    }
    public boolean isDeleted(String sid){
        try{return DurableDeletionMarkers.isMarked(context,"deletion-barriers",sid);}
        catch(IOException unavailable){throw new SecurityException("deletion marker state unavailable",unavailable);}
    }
    public synchronized void purgeDeleted(String sid)throws Exception {
        if(!isDeleted(sid))throw new SecurityException("deletion barrier required");
        DurableDeletionMarkers.requireDurable(context,"deletion-barriers",sid);
        db.beginTransaction();try{
            configureRevocationWriter();
            db.execSQL("UPDATE frames SET deleted=1,wrapped=NULL WHERE session=?",new Object[]{sid});
            db.execSQL("UPDATE sessions SET state='ended',generation=generation+1,seal_generation=-1,seal_end=0 WHERE id=? AND (state!='ended' OR seal_generation!=-1 OR seal_end!=0)",new Object[]{sid});
            db.execSQL("DELETE FROM consent_records WHERE session=?",new Object[]{sid});
            db.execSQL("DELETE FROM session_ends WHERE session=?",new Object[]{sid});
            db.execSQL("DELETE FROM capture_controls WHERE session=?",new Object[]{sid});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
        keys.deleteEntry(alias(sid));
        try(Cursor segments=db.rawQuery("SELECT id FROM segments WHERE session=?",new String[]{sid})){while(segments.moveToNext()){
            File file=segmentFile(segments.getString(0));if(file.exists()&&!file.delete())throw new IOException("deleted audio cleanup pending");
        }}
        db.beginTransaction();try{
            configureRevocationWriter();
            db.execSQL("DELETE FROM segments WHERE session=?",new Object[]{sid});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public synchronized long committedSamples(String sid){
        if(isDeleted(sid))throw new SecurityException("meeting deleted");
        try(Cursor c=db.rawQuery("SELECT COALESCE(SUM(end_sample-start_sample),0) FROM frames WHERE session=? AND deleted=0 AND corrupt=0 AND expires_utc>? AND NOT "+RANGE_REVOKED,new String[]{sid,Long.toString(trustedNow())})){c.moveToFirst();return c.getLong(0);}
    }
    @Override public synchronized void close()throws Exception{if(!closed){closed=true;db.close();processLock.release();lockFile.close();}}
}

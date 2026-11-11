package local.sidecar.copilot;

import android.app.Instrumentation;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.*;

/** Actual Android storage/AEAD reference checks with generated PCM only.
 * These are prepared instrumentation cases, not host substitutes for Keystore.
 */
public final class AudioSourceReferenceChecks {
    private static int checks;
    private interface Action {void run()throws Exception;}
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError("audio reference "+checks+": "+label);checks++;}
    private static Throwable failure(Action action){try{action.run();return null;}catch(Exception error){return error;}}
    private static boolean zero(short[] pcm){if(pcm==null)return false;for(short sample:pcm)if(sample!=0)return false;return true;}
    private static SQLiteDatabase database(AudioSegmentStore store)throws Exception {
        Field field=AudioSegmentStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);
    }
    private static byte[] changedHealthAad(SQLiteDatabase db,String fid,String health)throws Exception {
        Method metadata=AudioSegmentStore.class.getDeclaredMethod("metadata",String.class,String.class,String.class,long.class,long.class,long.class,long.class,long.class,long.class,long.class,String.class);
        metadata.setAccessible(true);
        try(Cursor f=db.rawQuery("SELECT id,session,stream,generation,boot,start_sample,end_sample,end_ns,eligible_ns,expires_utc FROM frames WHERE id=?",new String[]{fid})){
            if(!f.moveToFirst())throw new AssertionError("source fixture absent");
            return (byte[])metadata.invoke(null,f.getString(0),f.getString(1),f.getString(2),f.getLong(3),f.getLong(4),f.getLong(5),f.getLong(6),f.getLong(7),f.getLong(8),f.getLong(9),health);
        }
    }
    private static byte[] blob(SQLiteDatabase db,String column,String fid){
        try(Cursor row=db.rawQuery("SELECT "+column+" FROM frames WHERE id=?",new String[]{fid})){
            if(!row.moveToFirst())throw new AssertionError("source fixture absent");return row.getBlob(0);
        }
    }
    private static final class Scope extends ContextWrapper {
        final String prefix="audio-reference-"+UUID.randomUUID()+"-";
        final File directory;final Set<String> preferences=new HashSet<>();
        Scope(Context context)throws IOException{super(context);directory=new File(context.getNoBackupFilesDir(),prefix);if(!directory.mkdir())throw new IOException("fixture root unavailable");}
        @Override public File getNoBackupFilesDir(){return directory;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){String key=prefix+name;preferences.add(key);return getBaseContext().getSharedPreferences(key,mode);}
        void dispose()throws Exception{
            for(String name:preferences)getBaseContext().deleteSharedPreferences(name);
            File folder=new File(directory,"audio-segments");
            if(!folder.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new IOException("unsafe fixture path");
            File[] files=folder.listFiles();if(files!=null)for(File file:files){
                if(!file.isFile()||!file.getCanonicalFile().getParentFile().equals(folder.getCanonicalFile())||!file.delete())throw new IOException("fixture cleanup incomplete");
            }
            if(folder.exists()&&!folder.delete())throw new IOException("fixture directory cleanup incomplete");
            if(!directory.delete())throw new IOException("fixture root cleanup incomplete");
        }
    }
    private static final class Probe implements AudioSegmentStore.Faults {
        short[] pcm;boolean populated;Action afterAuth;
        @Override public void ownedPcm(String stage,short[] values){if(stage.equals("upload_reference_pcm")){pcm=values;populated=!zero(values);}}
        @Override public void at(String stage)throws IOException {
            if(stage.equals("upload_reference_after_auth")&&afterAuth!=null){
                Action action=afterAuth;afterAuth=null;
                try{action.run();}catch(IOException error){throw error;}catch(Exception error){throw new IOException("fixture action",error);}
            }
        }
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        checks=0;Context base=instrumentation.getTargetContext();KeyguardManager guard=base.getSystemService(KeyguardManager.class);
        check(guard.isDeviceSecure()&&!guard.isDeviceLocked(),"explicit unlocked credential fixture required");
        Scope scope=new Scope(base);Probe probe=new Probe();String sid=null;
        short[] input=new short[4000];Arrays.fill(input,(short)0x2345);
        try(AudioSegmentStore store=new AudioSegmentStore(scope,probe)){
            sid=store.createSession(8000);final String session=sid;long generation=store.start(sid,true);
            final String first=store.append(sid,generation,new PcmFrameAssembler.Frame(0,input.length,SystemClock.elapsedRealtimeNanos(),input,"healthy"));
            AudioSourceReference reference=store.inspectUploadSource(first);
            check(reference.frameId.equals(first)&&reference.sessionId.equals(sid),"source identity");
            check(reference.startSample==0&&reference.endSample==4000&&reference.ciphertextByteLength==8016,"sample units and ciphertext length");
            check(reference.eligibleMonoNs-reference.hostReceiveEndNs==900_000_000_000L,"original receive fence preserved");
            check(probe.populated&&zero(probe.pcm),"authenticated temporary PCM cleared on success");
            check(reference.equals(store.inspectUploadSource(first)),"stable immutable source");
            check(failure(()->reference.requireMature(reference.bootCount,reference.eligibleMonoNs-1,reference.expiresUtcMs-1)) instanceof SecurityException,"reference is not early-send permission");
            store.beginPause(sid,generation,4000);store.finishPause(sid);
            long nextGeneration=store.start(sid,true);
            final String second=store.append(sid,nextGeneration,new PcmFrameAssembler.Frame(0,input.length,SystemClock.elapsedRealtimeNanos(),input,"healthy"));
            AudioSourceReference next=store.inspectUploadSource(second);
            check(!reference.streamId.equals(next.streamId)&&reference.startSample==next.startSample,"restart creates separate sample coordinate domain");
            check(reference.equals(store.inspectUploadSource(first)),"old source does not inherit current session origin");
            SQLiteDatabase db=database(store);byte[] savedCipher,savedHash;File segment;long offset,length;
            try(Cursor row=db.rawQuery("SELECT segment,byte_offset,byte_length,hash FROM frames WHERE id=?",new String[]{first})){
                check(row.moveToFirst(),"ciphertext fault fixture row");segment=new File(new File(scope.directory,"audio-segments"),row.getString(0)+".seg");
                offset=row.getLong(1);length=row.getLong(2);savedHash=row.getBlob(3);
            }
            try(RandomAccessFile file=new RandomAccessFile(segment,"rw")){
                savedCipher=new byte[(int)length];file.seek(offset);file.readFully(savedCipher);
                byte[] changed=savedCipher.clone();changed[0]^=1;file.seek(offset);file.write(changed);file.getFD().sync();
                db.execSQL("UPDATE frames SET hash=? WHERE id=?",new Object[]{MessageDigest.getInstance("SHA-256").digest(changed),first});
                try{check(failure(()->store.inspectUploadSource(first)) instanceof javax.crypto.AEADBadTagException,"checksum alone cannot authenticate a changed GCM ciphertext");}
                finally{file.seek(offset);file.write(savedCipher);file.getFD().sync();db.execSQL("UPDATE frames SET hash=? WHERE id=?",new Object[]{savedHash,first});}
            }
            IOException marker=new IOException("synthetic post-auth failure");probe.afterAuth=()->{throw marker;};
            check(failure(()->store.inspectUploadSource(first))==marker,"post-auth failure preserved");
            check(probe.populated&&zero(probe.pcm),"temporary PCM cleared on post-auth failure");
            byte[] originalAad=blob(db,"aad",first);
            probe.afterAuth=()->db.execSQL("UPDATE frames SET health=?,aad=? WHERE id=?",new Object[]{"changed",changedHealthAad(db,first,"changed"),first});
            try{
                Throwable changed=failure(()->store.inspectUploadSource(first));
                check(changed instanceof SecurityException&&"source changed during reference authentication".equals(changed.getMessage()),"consistent post-auth metadata reaches immutable snapshot comparison");
            }finally{db.execSQL("UPDATE frames SET health='healthy',aad=? WHERE id=?",new Object[]{originalAad,first});}
            check(zero(probe.pcm),"temporary PCM cleared on changed metadata");
            for(String field:new String[]{"nonce","wrapped"}){
                byte[] original=blob(db,field,first),changed=original.clone();changed[0]^=1;
                probe.afterAuth=()->db.execSQL("UPDATE frames SET "+field+"=? WHERE id=?",new Object[]{changed,first});
                try{
                    Throwable changedState=failure(()->store.inspectUploadSource(first));
                    check(changedState instanceof SecurityException&&"source changed during reference authentication".equals(changedState.getMessage()),"private encryption identity change rejected: "+field);
                    check(probe.populated&&zero(probe.pcm),"temporary PCM cleared on changed "+field);
                }finally{db.execSQL("UPDATE frames SET "+field+"=? WHERE id=?",new Object[]{original,first});}
            }
            for(String flag:new String[]{"deleted","corrupt"}){
                db.execSQL("UPDATE frames SET "+flag+"=1 WHERE id=?",new Object[]{first});
                try{check(failure(()->store.inspectUploadSource(first)) instanceof SecurityException,"flagged source rejected: "+flag);}
                finally{db.execSQL("UPDATE frames SET "+flag+"=0 WHERE id=?",new Object[]{first});}
            }
            probe.afterAuth=()->store.deleteRange(session,next.streamId,0,4000);
            check(failure(()->store.inspectUploadSource(second)) instanceof SecurityException,"durable range after authentication rejects delivery");
            check(probe.populated&&zero(probe.pcm),"temporary PCM cleared after range deletion");
            check(reference.equals(store.inspectUploadSource(first)),"deleted stream does not invalidate legal old stream");
            check(failure(()->store.inspectUploadSource(second)) instanceof SecurityException,"old reference cannot bypass range barrier");
        }finally{
            probe.afterAuth=null;Arrays.fill(input,(short)0);
            if(sid!=null){KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("sidecar.audio."+sid);}
            scope.dispose();
        }
        return checks;
    }
}

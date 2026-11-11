package local.sidecar.copilot;

import android.app.Instrumentation;
import android.app.KeyguardManager;
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

/** Existing access/retention requirements: final direct-read admission with synthetic PCM.
 * Changes only isolated database fixtures, never system time, microphone or playback.
 */
public final class ReadCompletionChecks {
    private static int checks;
    private static void check(boolean value,String label){if(!value)throw new AssertionError("read completion "+checks+": "+label);checks++;}
    private static long number(SQLiteDatabase db,String sql,String... args){
        try(Cursor c=db.rawQuery(sql,args)){if(!c.moveToFirst())throw new AssertionError("missing fixture row");return c.getLong(0);}
    }
    private static void rejects(AudioSegmentStore store,String frame,Class<? extends Exception> expected,String label)throws Exception {
        try{
            short[] returned=store.read(frame);Arrays.fill(returned,(short)0);
            throw new AssertionError(label+" returned PCM");
        }catch(Exception failure){if(!expected.isInstance(failure))throw failure;checks++;}
    }
    private static boolean matches(AudioSegmentStore store,String frame,short value)throws Exception {
        short[] returned=store.read(frame);
        try{return returned.length==40000&&returned[0]==value&&returned[returned.length-1]==value;}
        finally{Arrays.fill(returned,(short)0);}
    }
    private static final class IsolatedContext extends ContextWrapper {
        final String prefix="read-completion-"+UUID.randomUUID()+"-";
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
            File segments=new File(directory,"audio-segments");File[] files=segments.listFiles();
            if(files!=null)for(File file:files){
                // The test owns this one flat, randomly named directory. Never traverse elsewhere.
                if(!file.isFile()||!file.delete())throw new IOException("fixture file cleanup pending");
            }
            if(segments.exists()&&!segments.delete())throw new IOException("fixture segment directory cleanup pending");
            if(!directory.delete())throw new IOException("fixture directory cleanup pending");
        }
    }
    private static final class CompletionProbe implements AudioSegmentStore.Faults {
        Runnable next;
        int reached;
        @Override public void at(String stage){
            if(!"read_before_return".equals(stage))return;
            reached++;Runnable action=next;next=null;if(action!=null)action.run();
        }
        void arm(Runnable action){next=action;reached=0;}
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        Context base=instrumentation.getTargetContext();KeyguardManager keyguard=base.getSystemService(KeyguardManager.class);
        if(!keyguard.isDeviceSecure()||keyguard.isDeviceLocked())throw new SecurityException("authenticated unlocked fixture required");
        checks=0;IsolatedContext context=new IsolatedContext(base);CompletionProbe probe=new CompletionProbe();String session=null;
        try(AudioSegmentStore store=new AudioSegmentStore(context,probe)){
            Field field=AudioSegmentStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(store);
            session=store.createSession(8000);final String sid=session;
            AudioCaptureCoordinator capture=new AudioCaptureCoordinator(store,session,8000);long token=capture.start(true);
            short[] pcm=new short[120000];
            for(int i=0;i<3;i++)Arrays.fill(pcm,i*40000,(i+1)*40000,(short)(811+i));
            try{capture.samples(token,pcm,pcm.length,SystemClock.elapsedRealtimeNanos());capture.pause();}
            finally{Arrays.fill(pcm,(short)0);}
            List<String> frames=store.frameIds(session);check(frames.size()==3,"three synthetic frames");
            check(matches(store,frames.get(0),(short)811),"normal direct read remains usable");
            long savedBoot=number(db,"SELECT boot FROM clock WHERE singleton=1");
            long savedWall=number(db,"SELECT wall FROM clock WHERE singleton=1");

            // The clock is valid at read entry. Only the completion probe makes it uncertain.
            probe.arm(()->db.execSQL("UPDATE clock SET boot=boot+1 WHERE singleton=1"));
            try{
                rejects(store,frames.get(0),IllegalStateException.class,"boot changed during read");
                check(probe.reached==1&&probe.next==null,"boot mutation occurs after decryption");
                rejects(store,frames.get(1),IllegalStateException.class,"subsequent read remains quarantined");
                check(probe.reached==1&&number(db,"SELECT boot FROM clock WHERE singleton=1")==savedBoot+1,"entry denial does not process PCM or repair anchor");
            }finally{db.execSQL("UPDATE clock SET boot=? WHERE singleton=1",new Object[]{savedBoot});}
            check(matches(store,frames.get(0),(short)811),"fixture boot restoration preserves original ciphertext");

            // An older stored wall anchor makes the current wall disagree with its projection.
            probe.arm(()->db.execSQL("UPDATE clock SET wall=wall-10000 WHERE singleton=1"));
            try{
                rejects(store,frames.get(0),IllegalStateException.class,"wall disagreement during read");
                check(probe.reached==1&&probe.next==null,"wall mutation reaches final admission");
                check(number(db,"SELECT wall FROM clock WHERE singleton=1")==savedWall-10000,"read does not rewrite uncertain anchor");
            }finally{db.execSQL("UPDATE clock SET wall=? WHERE singleton=1",new Object[]{savedWall});}
            check(matches(store,frames.get(2),(short)813),"unmodified frame remains usable after fixture restoration");

            // Metadata injection tests the last admission check, not naturally elapsed retention.
            probe.arm(()->db.execSQL("UPDATE frames SET expires_utc=0 WHERE id=?",new Object[]{frames.get(0)}));
            rejects(store,frames.get(0),SecurityException.class,"deadline changed during read");
            check(probe.reached==1&&probe.next==null,"expiry mutation occurs at completion");
            rejects(store,frames.get(0),SecurityException.class,"expired frame denied on next entry");
            check(probe.reached==1&&number(db,"SELECT deleted=1 AND wrapped IS NULL FROM frames WHERE id=?",frames.get(0))==1,"entry expiry revokes only the due frame");
            check(matches(store,frames.get(1),(short)812),"expiry preserves the neighboring frame");

            final String stream=store.stream(session);
            probe.arm(()->store.deleteRange(sid,stream,40000,80000));
            rejects(store,frames.get(1),SecurityException.class,"range deletion during read");
            check(probe.reached==1&&probe.next==null,"durable range deletion occurs before return");
            rejects(store,frames.get(1),SecurityException.class,"deleted frame denied on next entry");
            check(probe.reached==1&&number(db,"SELECT deleted=1 AND wrapped IS NULL FROM frames WHERE id=?",frames.get(1))==1,"range deletion remains durable without reprocessing PCM");
            check(matches(store,frames.get(2),(short)813),"range deletion preserves the final frame");
            return checks;
        }finally{
            try{if(session!=null){KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("sidecar.audio."+session);}}
            finally{context.dispose();}
        }
    }
}

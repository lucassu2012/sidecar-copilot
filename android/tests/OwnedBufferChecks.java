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
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Synthetic ownership checks using actual Android encryption, never microphone or playback.
 * Retained array references prove cleanup; this does not promise JVM/provider heap zeroization.
 */
public final class OwnedBufferChecks {
    private static int checks;
    private static final short SAMPLE=(short)0x2345;
    private interface Operation {void run()throws Throwable;}
    private static void check(boolean value,String label){if(!value)throw new AssertionError("owned buffer "+checks+": "+label);checks++;}
    private static Throwable thrown(Operation operation){
        try{operation.run();return null;}catch(Throwable failure){return failure;}
    }
    private static boolean zero(byte[] values){for(byte value:values)if(value!=0)return false;return true;}
    private static boolean zero(short[] values){for(short value:values)if(value!=0)return false;return true;}
    private static boolean matches(short[] values){for(short value:values)if(value!=SAMPLE)return false;return true;}
    private static Throwable injected(String stage,boolean error){return error?new AssertionError("synthetic "+stage):new IOException("synthetic "+stage);}
    private static long number(SQLiteDatabase db,String sql,String... args){
        try(Cursor cursor=db.rawQuery(sql,args)){if(!cursor.moveToFirst())throw new AssertionError("fixture row missing");return cursor.getLong(0);}
    }
    private static SQLiteDatabase database(AudioSegmentStore store)throws Exception {
        Field field=AudioSegmentStore.class.getDeclaredField("db");field.setAccessible(true);return (SQLiteDatabase)field.get(store);
    }
    private static void removeAlias(String alias)throws Exception {
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry(alias);
    }
    private static final class Scope extends ContextWrapper {
        final String prefix="owned-buffers-"+UUID.randomUUID()+"-";
        final File directory;
        final Set<String> preferences=new HashSet<>();
        Scope(Context base)throws IOException {
            super(base);directory=new File(base.getNoBackupFilesDir(),prefix);
            if(!directory.mkdir())throw new IOException("owned-buffer fixture directory unavailable");
        }
        @Override public File getNoBackupFilesDir(){return directory;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            String scoped=prefix+name;preferences.add(scoped);return getBaseContext().getSharedPreferences(scoped,mode);
        }
        void dispose()throws IOException {
            for(String name:preferences)getBaseContext().deleteSharedPreferences(name);
            for(String name:new String[]{"audio-segments","meetings"}){
                File child=new File(directory,name);
                if(!child.getCanonicalFile().getParentFile().equals(directory.getCanonicalFile()))throw new IOException("unsafe fixture cleanup path");
                File[] files=child.listFiles();
                if(files!=null)for(File file:files){
                    if(!file.isFile()||!file.getCanonicalFile().getParentFile().equals(child.getCanonicalFile())||!file.delete())
                        throw new IOException("owned-buffer fixture cleanup pending");
                }
                if(child.exists()&&!child.delete())throw new IOException("owned-buffer fixture directory cleanup pending");
            }
            if(!directory.delete())throw new IOException("owned-buffer fixture root cleanup pending");
        }
    }
    private static final class Probe implements AudioSegmentStore.Faults,NoteStore.AccessProbe {
        final Map<String,byte[]> buffers=new HashMap<>();
        final Map<String,Boolean> populated=new HashMap<>();
        final Map<String,short[]> samples=new HashMap<>();
        String failurePoint="";
        Throwable failure;
        int reached;
        boolean pcmPopulatedAtFault;
        void arm(String point,Throwable failure){
            buffers.clear();populated.clear();samples.clear();failurePoint=point;this.failure=failure;reached=0;pcmPopulatedAtFault=false;
        }
        private void hit(String point)throws IOException {
            if(!point.equals(failurePoint))return;
            reached++;
            short[] pcm=samples.get("read_pcm");if(pcm!=null)pcmPopulatedAtFault=!zero(pcm);
            if(failure instanceof IOException)throw (IOException)failure;
            if(failure instanceof Error)throw (Error)failure;
            throw new AssertionError("unsupported fixture failure");
        }
        @Override public void at(String stage)throws IOException {hit(stage);}
        @Override public void ownedBuffer(String stage,byte[] buffer)throws IOException {
            buffers.put(stage,buffer);populated.put(stage,!zero(buffer));hit("owned:"+stage);
        }
        @Override public void ownedPcm(String stage,short[] pcm)throws IOException {
            samples.put(stage,pcm);
            // read_pcm runs before decoding. Poison this owned destination so a throwing probe
            // cannot pass merely because a freshly allocated short[] happened to contain zeros.
            if(("owned:"+stage).equals(failurePoint))Arrays.fill(pcm,(short)0x4567);
            hit("owned:"+stage);
        }
        void cleared(String stage){
            byte[] value=buffers.get(stage);check(value!=null&&value.length>0,"captured "+stage);
            check(Boolean.TRUE.equals(populated.get(stage)),stage+" contained generated plaintext/key material");
            check(zero(value),stage+" cleared every owned byte");
        }
        void pcmCleared(){
            short[] value=samples.get("read_pcm");check(value!=null&&value.length>0,"captured unreturned PCM");
            check(pcmPopulatedAtFault,"fault follows populated or deliberately poisoned PCM");
            check(zero(value),"unreturned PCM cleared every sample");
        }
    }
    private static void audio(Context base)throws Exception {
        Scope scope=new Scope(base);Probe probe=new Probe();String session=null;
        short[] input=new short[4000];Arrays.fill(input,SAMPLE);
        try(AudioSegmentStore store=new AudioSegmentStore(scope,probe)){
            session=store.createSession(8000);final String id=session;long token=store.start(id,true);SQLiteDatabase db=database(store);
            String[] points={"append_before_keygen","append_before_aes_init","append_before_aes_final","append_before_wrap","owned:append_clear","owned:append_dek"};
            for(String point:points)for(boolean fatal:new boolean[]{false,true}){
                Throwable expected=injected(point,fatal);probe.arm(point,expected);
                Throwable actual=thrown(()->store.append(id,token,new PcmFrameAssembler.Frame(0,input.length,SystemClock.elapsedRealtimeNanos(),input,"healthy")));
                check(actual==expected,"append preserves original "+point+" failure identity");
                check(probe.reached==1,"append fault reached once");probe.cleared("append_clear");
                if(point.equals("append_before_keygen")||point.equals("owned:append_clear"))check(!probe.buffers.containsKey("append_dek"),"no DEK allocated before key generation");
                else probe.cleared("append_dek");
                check(number(db,"SELECT COUNT(*) FROM frames WHERE session=?",id)==0&&number(db,"SELECT next_sample FROM sessions WHERE id=?",id)==0,"failed append creates no frame or committed prefix");
                check(number(db,"SELECT COUNT(*) FROM segments WHERE session=?",id)==0,"failed encryption creates no segment mapping");
                check(matches(input),"append leaves caller-owned PCM intact");
            }
            probe.arm("",null);
            String frame=store.append(id,token,new PcmFrameAssembler.Frame(0,input.length,SystemClock.elapsedRealtimeNanos(),input,"healthy"));
            probe.cleared("append_clear");probe.cleared("append_dek");
            check(store.committedSamples(id)==input.length&&matches(input),"successful encryption commits and preserves caller input");
            store.beginPause(id,token,input.length);store.finishPause(id);
            String[] readPoints={"read_before_return","owned:read_dek","owned:read_clear","owned:read_pcm"};
            for(String point:readPoints)for(boolean fatal:new boolean[]{false,true}){
                Throwable expected=injected(point,fatal);probe.arm(point,expected);
                Throwable actual=thrown(()->{short[] unexpected=store.read(frame);Arrays.fill(unexpected,(short)0);});
                check(actual==expected,"read preserves original "+point+" failure identity");
                check(probe.reached==1,"read fault reached once");probe.cleared("read_dek");
                if(point.equals("owned:read_dek"))check(!probe.buffers.containsKey("read_clear")&&!probe.samples.containsKey("read_pcm"),"DEK-probe failure precedes further plaintext allocation");
                else{
                    probe.cleared("read_clear");
                    if(point.equals("owned:read_clear"))check(!probe.samples.containsKey("read_pcm"),"clear-probe failure precedes decoded PCM allocation");
                    else probe.pcmCleared();
                }
                check(number(db,"SELECT COUNT(*) FROM frames WHERE session=?",id)==1&&store.committedSamples(id)==input.length,"failed read does not alter committed audio");
            }
            probe.arm("",null);short[] delivered=store.read(frame);
            try{
                check(delivered==probe.samples.get("read_pcm"),"successful read transfers the captured PCM array to its caller");
                check(delivered.length==input.length&&matches(delivered)&&!zero(delivered),"delivered PCM is not cleared prematurely");
                probe.cleared("read_dek");probe.cleared("read_clear");
            }finally{Arrays.fill(delivered,(short)0);}
        }finally{
            Arrays.fill(input,(short)0);
            try{if(session!=null)removeAlias("sidecar.audio."+session);}finally{scope.dispose();}
        }
    }
    private static void privateWrite(NoteStore store,String id,JSONObject value)throws Throwable {
        Method write=NoteStore.class.getDeclaredMethod("write",String.class,JSONObject.class);write.setAccessible(true);
        try{write.invoke(store,id,value);}catch(InvocationTargetException wrapped){throw wrapped.getCause();}
    }
    private static void unchanged(NoteStore store,String id,File file,byte[] original)throws Exception {
        check(Arrays.equals(original,Files.readAllBytes(file.toPath())),"failed note write leaves ciphertext unchanged");
        JSONObject saved=store.read(id);
        check(saved.getLong("revision")==0&&saved.getJSONArray("attachments").length()==0&&saved.getString("title").equals("SYNTHETIC OWNED NOTE"),"failed note write commits no new body or revision");
    }
    private static void notes(Context base)throws Exception {
        Scope scope=new Scope(base);Probe probe=new Probe();String id=null;
        try{
            NoteStore store=new NoteStore(scope,()->{},probe);id=store.create("SYNTHETIC OWNED NOTE",true);final String meeting=id;
            probe.cleared("note_write_clear");File file=new File(scope.directory,"meetings/"+id+".enc");byte[] original=Files.readAllBytes(file.toPath());
            String[] points={"note_write_before_size","note_write_before_key","note_write_before_init","note_write_before_aad","note_write_before_final","owned:note_write_clear"};
            for(String point:points)for(boolean fatal:new boolean[]{false,true}){
                Throwable expected=injected(point,fatal);probe.arm(point,expected);
                Throwable actual=thrown(()->store.append(meeting,0,"SYNTHETIC REJECTED BODY","unassigned"));
                check(actual==expected,"note write preserves original "+point+" failure identity");
                check(probe.reached==1,"note write fault reached once");probe.cleared("note_write_clear");
                probe.failurePoint="";unchanged(store,id,file,original);
            }
            // Exercise the actual serialized byte-length guard, rather than append's smaller text validation.
            JSONObject oversized=store.read(id);char[] padding=new char[1024*1024+64];Arrays.fill(padding,'Z');
            try{
                oversized.put("synthetic_oversize",new String(padding));probe.arm("",null);
                Throwable rejected=thrown(()->privateWrite(store,meeting,oversized));
                check(rejected instanceof IllegalStateException,"serialized JSON over 1 MiB is rejected by private write");
                check(probe.buffers.get("note_write_clear")!=null&&probe.buffers.get("note_write_clear").length>1024*1024,"real serialization exceeds the byte limit");
                probe.cleared("note_write_clear");unchanged(store,id,file,original);
            }finally{Arrays.fill(padding,'\0');oversized.remove("synthetic_oversize");}
            probe.arm("",null);store.append(id,0,"SYNTHETIC CONFIRMED BODY","unassigned");probe.cleared("note_write_clear");
            JSONObject saved=store.read(id);check(saved.getLong("revision")==1&&saved.getJSONArray("attachments").length()==1,"successful note write still commits");
            check(saved.getJSONArray("attachments").getJSONObject(0).getString("text").equals("SYNTHETIC CONFIRMED BODY"),"successful note body remains readable");
        }finally{
            try{if(id!=null)removeAlias("sidecar.note."+id);}finally{scope.dispose();}
        }
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        Context context=instrumentation.getTargetContext();KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        if(!keyguard.isDeviceSecure()||keyguard.isDeviceLocked())throw new SecurityException("authenticated unlocked fixture required");
        checks=0;audio(context);notes(context);return checks;
    }
}

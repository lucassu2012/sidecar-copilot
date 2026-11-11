package local.sidecar.copilot;

import android.app.KeyguardManager;
import android.content.*;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.*;

/** Cache-first commit failures are an interface fault model, not an OS disk-fault experiment. */
public final class NoteAnchorFailureChecks {
    private static int checks;
    private interface Work {void run()throws Exception;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError("anchor durability "+checks+": "+message);checks++;}
    private static void ioRejects(Work work)throws Exception {
        try{work.run();throw new AssertionError("failed anchor persistence accepted");}catch(IOException expected){checks++;}
    }
    private static void uncertainRejects(Work work)throws Exception {
        try{work.run();throw new AssertionError("inconsistent pending anchor accepted");}
        catch(IOException|IllegalStateException expected){checks++;}
    }
    private static Map<String,Object> triple(Map<String,Object> state) {
        Map<String,Object> result=new HashMap<>();
        for(String key:new String[]{"wall","elapsed","boot"})result.put(key,state.get(key));
        return result;
    }
    private static final class PreferencesModel {
        static final int FAIL=0,SUCCEED=1,THROW=2;
        final Map<String,Object> memory=new HashMap<>(),disk=new HashMap<>();
        final List<Map<String,Object>> attempts=new ArrayList<>();
        int mode=FAIL,commits,writes;
        final SharedPreferences preferences=newProxy();
        SharedPreferences newProxy() {
            return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class<?>[]{SharedPreferences.class},(proxy,method,args)->{
                String name=method.getName();
                if(name.equals("getAll"))return new HashMap<>(memory);
                if(name.equals("contains"))return memory.containsKey(args[0]);
                if(name.equals("edit")) {
                    Map<String,Object> pending=new HashMap<>();boolean[] clear={false};
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class<?>[]{SharedPreferences.Editor.class},(editor,operation,values)->{
                        String op=operation.getName();
                        if(op.startsWith("put")){pending.put((String)values[0],values[1]);return editor;}
                        if(op.equals("clear")){clear[0]=true;return editor;}
                        if(op.equals("remove")){pending.put((String)values[0],null);return editor;}
                        if(op.equals("commit")) {
                            // SharedPreferences exposes the new memory map before the disk result.
                            commits++;if(clear[0])memory.clear();
                            for(Map.Entry<String,Object> entry:pending.entrySet()) {
                                if(entry.getValue()==null)memory.remove(entry.getKey());else memory.put(entry.getKey(),entry.getValue());
                            }
                            attempts.add(new HashMap<>(memory));
                            if(mode==THROW)throw new IllegalStateException("synthetic commit exception after memory update");
                            if(mode==FAIL)return false;
                            disk.clear();disk.putAll(memory);writes++;return true;
                        }
                        throw new UnsupportedOperationException("fixture editor "+op);
                    });
                }
                // Identity-based production bookkeeping must not depend on proxy equals/hashCode.
                throw new UnsupportedOperationException("fixture preferences "+name);
            });
        }
        void reload(){memory.clear();memory.putAll(disk);}
    }
    private static final class Scope extends ContextWrapper {
        final String prefix="note-anchor-check-"+UUID.randomUUID()+"-";
        final File directory;
        final PreferencesModel model;
        final List<String> ids=new ArrayList<>();
        final Set<String> preferenceNames=new HashSet<>();
        Scope(Context base,PreferencesModel model)throws IOException {
            super(base);this.model=model;directory=new File(base.getNoBackupFilesDir(),prefix);
            if(!directory.mkdir())throw new IOException("fixture directory unavailable");
        }
        @Override public File getNoBackupFilesDir(){return directory;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode) {
            if(name.equals("retention-clock"))return model.preferences;
            String scoped=prefix+name;preferenceNames.add(scoped);return getBaseContext().getSharedPreferences(scoped,mode);
        }
        String create(NoteStore store,String title)throws Exception {
            String id=store.create(title,true);ids.add(id);return id;
        }
        void close()throws Exception {
            NoteStore store=new NoteStore(this);for(String id:ids)store.delete(id);
            for(String name:preferenceNames)getBaseContext().deleteSharedPreferences(name);
            File meetings=new File(directory,"meetings");File[] files=meetings.listFiles();
            if(files!=null)for(File file:files)if(!file.isFile()||!file.delete())throw new IOException("fixture file cleanup pending");
            if(meetings.exists()&&!meetings.delete())throw new IOException("fixture meeting cleanup pending");
            if(!directory.delete())throw new IOException("fixture directory cleanup pending");
        }
    }
    public static int run(Context base)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        KeyguardManager keyguard=base.getSystemService(KeyguardManager.class);
        if(!keyguard.isDeviceSecure()||keyguard.isDeviceLocked())throw new SecurityException("unlocked authenticated fixture required");
        checks=0;PreferencesModel model=new PreferencesModel();Scope scope=new Scope(base,model);
        try {
            NoteStore first=new NoteStore(scope);
            ioRejects(()->scope.create(first,"synthetic failed checkpoint"));
            check(triple(model.memory).values().stream().allMatch(Objects::nonNull)&&model.disk.isEmpty(),"failed disk write leaves a complete cache only");
            Map<String,Object> original=triple(model.memory);
            // This is the original reproduction. It needs no new production fields or test hooks.
            try {
                scope.create(new NoteStore(scope),"synthetic cached-anchor retry");
                throw new AssertionError("cached anchor accepted after failed disk commit; no durable retention anchor");
            }catch(IOException expected){checks++;}
            check(model.commits==2&&model.writes==0&&model.disk.isEmpty(),"new instance retries persistence instead of trusting cache");
            check(original.equals(triple(model.memory)),"failed retry preserves the first anchor triple");
            check(model.attempts.get(0).get("durability_nonce") instanceof String,"initial attempt has a durability nonce");
            check(!Objects.equals(model.attempts.get(0).get("durability_nonce"),model.attempts.get(1).get("durability_nonce")),"retry changes only its durability token");

            Scope otherRoot=new Scope(base,model);
            try {
                int commits=model.commits;
                uncertainRejects(()->otherRoot.create(new NoteStore(otherRoot),"same cached preferences, different root"));
                check(model.commits==commits&&otherRoot.ids.isEmpty(),"same preferences cannot bypass pending state through a different directory");
            }finally{otherRoot.close();}
            SharedPreferences alias=model.newProxy();
            Context sameRootNewProxy=new ContextWrapper(scope){
                @Override public SharedPreferences getSharedPreferences(String name,int mode){return name.equals("retention-clock")?alias:scope.getSharedPreferences(name,mode);}
            };
            int commits=model.commits;
            uncertainRejects(()->new NoteStore(sameRootNewProxy).create("same root, different preference proxy",true));
            check(model.commits==commits,"different proxy cannot hide pending state for the same storage root");

            PreferencesModel independentModel=new PreferencesModel();independentModel.mode=PreferencesModel.SUCCEED;
            Scope independent=new Scope(base,independentModel);
            try {
                NoteStore separate=new NoteStore(independent);String id=independent.create(separate,"independent storage");
                check(separate.read(id).getString("title").equals("independent storage"),"unrelated preference storage is not quarantined");
            }finally{independent.close();}

            Map<String,Object> cached=new HashMap<>(model.memory);
            model.memory.put("wall",((Long)model.memory.get("wall"))+1);commits=model.commits;
            uncertainRejects(()->new NoteStore(scope).create("conflicting cache",true));
            check(model.commits==commits,"conflicting cached triple is not silently replaced");
            model.memory.clear();model.memory.putAll(cached);model.memory.remove("elapsed");
            uncertainRejects(()->new NoteStore(scope).create("partial pending cache",true));
            check(model.commits==commits,"partial cached triple is not silently repaired");
            model.memory.clear();model.memory.putAll(cached);
            File history=new File(scope.getNoBackupFilesDir(),"meetings/"+UUID.randomUUID()+".enc.new");
            Files.write(history.toPath(),new byte[]{1});
            try{uncertainRejects(()->new NoteStore(scope).create("unexpected existing content",true));check(model.commits==commits,"recovery does not initialize over historical content");}
            finally{if(!history.delete())throw new IOException("fixture history cleanup pending");}

            model.reload();check(model.memory.isEmpty(),"preference reload drops the failed cache");
            ioRejects(()->new NoteStore(scope).create("pending retry after empty reload",true));
            check(original.equals(triple(model.memory))&&model.disk.isEmpty(),"empty cache retry preserves original pending origin");
            String failedNonce=(String)model.memory.get("durability_nonce");
            model.mode=PreferencesModel.SUCCEED;NoteStore recovered=new NoteStore(scope);
            String id=scope.create(recovered,"synthetic recovered checkpoint");
            check(model.writes==1&&original.equals(triple(model.disk)),"successful recovery durably commits the original triple");
            check(!Objects.equals(failedNonce,model.disk.get("durability_nonce")),"successful retry performs a fresh token write");
            JSONObject saved=recovered.read(id);long origin=saved.getLong("created_at"),expires=saved.getLong("expires_at");
            commits=model.commits;for(int i=0;i<10;i++)new NoteStore(scope).read(id);
            check(model.commits==commits,"ordinary cross-instance reads remain free of checkpoint writes");
            model.reload();JSONObject reloaded=new NoteStore(scope).read(id);
            check(reloaded.getLong("created_at")==origin&&reloaded.getLong("expires_at")==expires,"reload from durable preference state preserves note deadlines");
            check(model.commits==commits,"successful pending state was cleared from both indexes");
        }finally{scope.close();}

        PreferencesModel throwingModel=new PreferencesModel();throwingModel.mode=PreferencesModel.THROW;
        Scope throwing=new Scope(base,throwingModel);
        try {
            try{throwing.create(new NoteStore(throwing),"synthetic throwing checkpoint");throw new AssertionError("throwing checkpoint accepted");}
            catch(IllegalStateException expected){check("synthetic commit exception after memory update".equals(expected.getMessage()),"fault occurs after cache update");}
            check(throwingModel.commits==1&&throwingModel.disk.isEmpty(),"exception leaves only the cached candidate");
            Map<String,Object> original=triple(throwingModel.memory);
            throwingModel.mode=PreferencesModel.FAIL;
            ioRejects(()->throwing.create(new NoteStore(throwing),"retry after commit exception"));
            check(throwingModel.commits==2&&original.equals(triple(throwingModel.memory)),"exception also leaves a cross-instance pending barrier");
            throwingModel.mode=PreferencesModel.SUCCEED;NoteStore recovered=new NoteStore(throwing);
            String id=throwing.create(recovered,"recovered after exception");
            check(original.equals(triple(throwingModel.disk)),"exception recovery keeps the first origin");
            throwingModel.reload();check(recovered.read(id).getString("title").equals("recovered after exception"),"recovered data remains readable after cache reload");
        }finally{throwing.close();}
        return checks;
    }
    private NoteAnchorFailureChecks() {}
}

package local.sidecar.copilot;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.*;

/** Existing clock/retention/privacy subsets. Isolated synthetic records, never changes OS time. */
public final class ClockQuarantineChecks {
    private static int checks;
    private interface Work {void run()throws Exception;}
    private static void check(boolean value){if(!value)throw new AssertionError("clock quarantine "+checks);checks++;}
    private static void clockRejects(Work work)throws Exception {
        try{work.run();throw new AssertionError("uncertain clock accepted");}
        catch(IllegalStateException expected){check(!(expected instanceof NoteStore.Expired));}
    }
    private static void ioRejects(Work work)throws Exception {
        try{work.run();throw new AssertionError("failed checkpoint accepted");}catch(IOException expected){checks++;}
    }
    private static void securityRejects(Work work)throws Exception {
        try{work.run();throw new AssertionError("revoked body returned");}catch(SecurityException expected){checks++;}
    }
    /** Keeps test preferences/files separate from every other runner and from application records. */
    private static final class IsolatedContext extends ContextWrapper {
        final String prefix="clock-check-"+UUID.randomUUID()+"-";
        final File storage;
        final Set<String> preferences=new HashSet<>();
        IsolatedContext(Context base)throws IOException {
            super(base);storage=new File(base.getNoBackupFilesDir(),prefix);
            if(!storage.mkdir())throw new IOException("test directory unavailable");
        }
        @Override public File getNoBackupFilesDir(){return storage;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            String scoped=prefix+name;preferences.add(scoped);return getBaseContext().getSharedPreferences(scoped,mode);
        }
        void dispose()throws IOException {
            for(String name:preferences)getBaseContext().deleteSharedPreferences(name);
            File meetings=new File(storage,"meetings");File[] remaining=meetings.listFiles();
            if(remaining!=null)for(File entry:remaining) {
                if(!entry.isFile()||!entry.delete())throw new IOException("test meeting cleanup pending");
            }
            if(meetings.exists()&&!meetings.delete())throw new IOException("test meeting directory cleanup pending");
            if(!storage.delete())throw new IOException("test directory cleanup pending");
        }
    }
    private static final class Probe implements NoteStore.AccessProbe {
        final List<String> stages=new ArrayList<>();
        int commits,failCommit;
        Runnable onReturn;
        long waitUntil;
        NoteStore waitStore;
        boolean waitedBeforeExpiry;
        @Override public void at(String stage)throws IOException {
            stages.add(stage);
            if("before_return".equals(stage)) {
                Runnable action=onReturn;onReturn=null;if(action!=null)action.run();
                if(waitUntil!=0) {
                    long deadline=waitUntil;waitUntil=0;
                    try {
                        if(waitStore==null)throw new IOException("test deadline clock unavailable");
                        waitedBeforeExpiry=NoteRetentionChecks.fixtureNow(waitStore)<deadline;
                        long remaining;
                        while((remaining=deadline+10-NoteRetentionChecks.fixtureNow(waitStore))>0)Thread.sleep(Math.min(50,remaining));
                    }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("test delay interrupted");}
                    catch(Exception failure){throw new IOException("test deadline clock failed",failure);}
                }
            }
        }
        @Override public boolean commitClock(SharedPreferences.Editor edit){
            commits++;return commits==failCommit?false:edit.commit();
        }
        void reset(){stages.clear();commits=0;failCommit=0;onReturn=null;waitUntil=0;waitStore=null;waitedBeforeExpiry=false;}
    }
    private static void seed(SharedPreferences clock,Context context,long offset) {
        int boot=Settings.Global.getInt(context.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        if(boot<0||!clock.edit().clear().putLong("wall",System.currentTimeMillis()+offset)
                .putLong("elapsed",SystemClock.elapsedRealtime()).putInt("boot",boot).commit())
            throw new AssertionError("clock fixture unavailable");
    }
    private static void unchanged(SharedPreferences clock,Map<String,?> before,Probe probe) {
        check(clock.getAll().equals(before));check(probe.stages.isEmpty());check(probe.commits==0);
    }
    private static void writeFixture(NoteStore store,String id,JSONObject value)throws Exception {
        Method writer=NoteStore.class.getDeclaredMethod("write",String.class,JSONObject.class);
        writer.setAccessible(true);writer.invoke(store,id,value);
    }
    public static int run(Context base)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        KeyguardManager keyguard=base.getSystemService(KeyguardManager.class);
        if(!keyguard.isDeviceSecure()||keyguard.isDeviceLocked())throw new SecurityException("authenticated unlocked fixture required");
        checks=0;IsolatedContext context=new IsolatedContext(base);List<String> ids=new ArrayList<>();
        Probe probe=new Probe();NoteStore store=new NoteStore(context,()->{},probe);
        SharedPreferences clock=context.getSharedPreferences("retention-clock",Context.MODE_PRIVATE);
        try {
            check(clock.getAll().isEmpty());
            String id=store.create("synthetic clock quarantine",true);ids.add(id);
            check(clock.getAll().get("wall") instanceof Long&&clock.getAll().get("elapsed") instanceof Long&&clock.getAll().get("boot") instanceof Integer);
            File encrypted=new File(context.getNoBackupFilesDir(),"meetings/"+id+".enc");
            byte[] original=Files.readAllBytes(encrypted.toPath());
            probe.reset();check(store.read(id).getString("title").equals("synthetic clock quarantine"));
            check(probe.stages.equals(Arrays.asList("before_read","before_decrypt","before_parse","before_return")));

            // A deliberately invalid file must not win exception ordering over an uncertain clock.
            Files.write(encrypted.toPath(),new byte[]{1,2,3});
            seed(clock,context,0);clock.edit().putInt("boot",clock.getInt("boot",0)+1).commit();
            Map<String,?> before=clock.getAll();probe.reset();clockRejects(()->store.read(id));unchanged(clock,before,probe);
            clockRejects(()->store.sweepExpired(()->false));check(probe.stages.isEmpty());
            check(Files.readAllBytes(encrypted.toPath()).length==3);
            seed(clock,context,0);probe.reset();ioRejects(()->store.read(id));
            check(probe.stages.equals(Collections.singletonList("before_read")));
            Files.write(encrypted.toPath(),original);

            for(long offset:new long[]{-10000,10000}) {
                seed(clock,context,offset);before=clock.getAll();probe.reset();
                clockRejects(()->store.read(id));unchanged(clock,before,probe);
            }
            seed(clock,context,0);clock.edit().putLong("elapsed",SystemClock.elapsedRealtime()+10000).commit();
            before=clock.getAll();probe.reset();clockRejects(()->store.read(id));unchanged(clock,before,probe);
            for(String absent:new String[]{"wall","elapsed","boot"}) {
                seed(clock,context,0);clock.edit().remove(absent).commit();before=clock.getAll();probe.reset();
                clockRejects(()->store.read(id));unchanged(clock,before,probe);
            }
            clock.edit().clear().commit();before=clock.getAll();probe.reset();
            clockRejects(()->store.read(id));unchanged(clock,before,probe);
            clockRejects(()->store.create("must not recreate missing anchor",true));check(clock.getAll().isEmpty());
            for(String invalid:new String[]{"wall","elapsed","boot"}) {
                seed(clock,context,0);clock.edit().putString(invalid,"invalid typed anchor").commit();
                before=clock.getAll();probe.reset();clockRejects(()->store.read(id));unchanged(clock,before,probe);
            }
            seed(clock,context,0);clock.edit().putLong("wall",-1).commit();
            before=clock.getAll();probe.reset();clockRejects(()->store.read(id));unchanged(clock,before,probe);

            // A complete same-boot anchor is read-only, even when writes would fail.
            seed(clock,context,0);before=clock.getAll();probe.reset();probe.failCommit=1;
            check(store.read(id).getString("title").equals("synthetic clock quarantine"));
            check(probe.stages.contains("before_parse")&&probe.stages.contains("before_return")&&probe.commits==0);
            check(clock.getAll().equals(before));
            probe.reset();
            for(int i=0;i<10;i++)store.read(id);
            check(probe.commits==0&&clock.getAll().equals(before));

            seed(clock,context,0);probe.reset();
            probe.onReturn=()->clock.edit().putInt("boot",clock.getInt("boot",0)+1).commit();
            clockRejects(()->store.read(id));check(probe.stages.contains("before_return"));
            seed(clock,context,0);probe.reset();check(store.read(id).getString("title").equals("synthetic clock quarantine"));

            store.append(id,0,"synthetic revoked note","unassigned");JSONObject meeting=store.read(id);
            String noteId=meeting.getJSONArray("attachments").getJSONObject(0).getString("id");
            SharedPreferences noteBarriers=context.getSharedPreferences("note-deletion-barriers",Context.MODE_PRIVATE);
            probe.reset();probe.onReturn=()->noteBarriers.edit().putBoolean(id+":"+noteId,true).commit();
            JSONObject filtered=store.read(id);check(filtered.getJSONArray("attachments").length()==0);
            check(filtered.getLong("revision")==meeting.getLong("revision")+1);
            check(Collections.frequency(probe.stages,"before_return")==2);

            String expiring=store.create("synthetic deadline",true);ids.add(expiring);
            store.append(expiring,0,"synthetic expiring note","unassigned");JSONObject expiryFixture=store.read(expiring);
            String expiringNote=expiryFixture.getJSONArray("attachments").getJSONObject(0).getString("id");
            long expires=NoteRetentionChecks.fixtureNow(store)+5000;
            NoteRetentionChecks.expireNoteFixture(expiryFixture,0,expires);writeFixture(store,expiring,expiryFixture);
            probe.reset();probe.waitUntil=expires;probe.waitStore=store;JSONObject afterDeadline=store.read(expiring);
            check(probe.waitedBeforeExpiry);check(afterDeadline.optBoolean("title_expired",false));
            check(afterDeadline.getJSONArray("attachments").length()==0);
            check(noteBarriers.contains(expiring+":"+expiringNote));
            check(Collections.frequency(probe.stages,"before_return")==2);
            check(store.read(expiring).getJSONArray("attachments").length()==0);

            String revoked=store.create("synthetic final barrier",true);ids.add(revoked);
            SharedPreferences barriers=context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE);
            probe.reset();probe.onReturn=()->barriers.edit().putBoolean(revoked,true).commit();
            securityRejects(()->store.read(revoked));check(probe.stages.contains("before_return"));

            // User deletion never requires repairing the clock or parsing the body.
            seed(clock,context,0);clock.edit().putInt("boot",clock.getInt("boot",0)+1).commit();probe.reset();
            store.delete(id);check(!encrypted.exists());check(probe.stages.isEmpty()&&probe.commits==0);
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);check(!keys.containsAlias("sidecar.note."+id));
        } finally {
            probe.reset();for(String id:ids)store.delete(id);context.dispose();
        }

        IsolatedContext fresh=new IsolatedContext(base);Probe freshProbe=new Probe();
        NoteStore empty=new NoteStore(fresh,()->{},freshProbe);List<String> freshIds=new ArrayList<>();
        SharedPreferences freshClock=fresh.getSharedPreferences("retention-clock",Context.MODE_PRIVATE);
        File orphan=new File(fresh.getNoBackupFilesDir(),"meetings/"+UUID.randomUUID()+".enc.bak");
        try {
            freshProbe.failCommit=1;ioRejects(()->empty.create("failed initial anchor",true));
            check(freshClock.getAll().isEmpty());check(new File(fresh.getNoBackupFilesDir(),"meetings").list().length==0);
            freshProbe.reset();freshClock.edit().putLong("elapsed",0).commit();
            clockRejects(()->empty.create("partial is not fresh",true));check(freshClock.getAll().size()==1);
            freshClock.edit().clear().commit();Files.write(orphan.toPath(),new byte[]{1});
            clockRejects(()->empty.create("backup is existing content",true));check(freshClock.getAll().isEmpty());
            check(orphan.delete());String freshId=empty.create("empty initialization",true);freshIds.add(freshId);
            check(empty.read(freshId).getString("title").equals("empty initialization"));
        } finally {
            freshProbe.reset();for(String id:freshIds)empty.delete(id);if(orphan.exists()&&!orphan.delete())throw new IOException("test backup cleanup pending");fresh.dispose();
        }
        return checks;
    }
    private ClockQuarantineChecks() {}
}

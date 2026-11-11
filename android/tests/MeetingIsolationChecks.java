package local.sidecar.copilot;

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
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Existing scoped recovery/privacy cases. Native files with synthetic records/faults only;
 * no OS lock change, real recording, power-loss or physical-erasure claim. */
public final class MeetingIsolationChecks {
    private static int checks;
    private interface Work {void run()throws Exception;}
    private static void check(boolean value,String label){if(!value)throw new AssertionError("meeting isolation "+checks+": "+label);checks++;}
    private static Throwable thrown(Work work){try{work.run();return null;}catch(Throwable failure){return failure;}}
    private static void local(Work work,String id,String label){
        Throwable failure=thrown(work);
        check(failure instanceof NoteStore.UnresolvedMeeting&&id.equals(((NoteStore.UnresolvedMeeting)failure).meetingId),label);
    }
    private static void globalIo(Throwable failure,String label){
        check(failure instanceof IOException&&!(failure instanceof NoteStore.UnresolvedMeeting),label);
    }
    private static final class Probe implements NoteStore.AccessProbe {
        final List<String> reads=new ArrayList<>(),stages=new ArrayList<>();
        File readFault,keepOnDelete;
        IOException ioFailure;
        Runnable onceBeforeRead;
        @Override public void at(String stage){
            stages.add(stage);
            if(stage.equals("before_read")&&onceBeforeRead!=null){Runnable action=onceBeforeRead;onceBeforeRead=null;action.run();}
        }
        @Override public AtomicFile atomicFile(File base){return new AtomicFile(base){
            @Override public byte[] readFully()throws IOException {
                reads.add(base.getName());if(base.equals(readFault))throw ioFailure;return super.readFully();
            }
            @Override public void delete(){if(!base.equals(keepOnDelete))super.delete();}
        };}
        void reset(){reads.clear();stages.clear();readFault=null;keepOnDelete=null;ioFailure=null;onceBeforeRead=null;}
    }
    private static final class Scope extends ContextWrapper {
        final String prefix="meeting-isolation-"+UUID.randomUUID()+"-";
        final File directory;
        final Set<String> preferences=new HashSet<>();
        Scope(Context base)throws IOException {
            super(base);directory=new File(base.getNoBackupFilesDir(),prefix);
            if(!directory.mkdir())throw new IOException("isolated fixture directory unavailable");
        }
        @Override public File getNoBackupFilesDir(){return directory;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){String scoped=prefix+name;preferences.add(scoped);return getBaseContext().getSharedPreferences(scoped,mode);}
        void dispose()throws IOException {
            for(String name:preferences)getBaseContext().deleteSharedPreferences(name);
            remove(directory);
        }
        private void remove(File file)throws IOException {
            String root=directory.getCanonicalPath(),path=file.getCanonicalPath();
            if(!path.equals(root)&&!path.startsWith(root+File.separator))throw new IOException("fixture cleanup escaped scope");
            if(Files.isSymbolicLink(file.toPath()))throw new IOException("unexpected fixture link");
            if(file.isDirectory()){
                File[] children=file.listFiles();if(children==null)throw new IOException("fixture cleanup inventory unavailable");
                for(File child:children)remove(child);
            }
            if(file.exists()&&!file.delete())throw new IOException("fixture cleanup incomplete");
        }
    }
    private static final class Fixture implements AutoCloseable {
        final Scope context;final Probe probe=new Probe();final NoteStore store;final KeyStore keys;
        final Set<String> ids=new LinkedHashSet<>();
        Fixture(Context base)throws Exception {context=new Scope(base);keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);store=new NoteStore(context,()->{},probe);}
        String create()throws Exception {String id=store.create("synthetic isolated meeting",true);ids.add(id);return id;}
        String[] ordered(int count)throws Exception {List<String> values=new ArrayList<>();for(int i=0;i<count;i++)values.add(create());Collections.sort(values);return values.toArray(new String[0]);}
        File root(){return new File(context.directory,"meetings");}
        File file(String id,String suffix){return new File(root(),id+".enc"+suffix);}
        byte[] bytes(String id,String suffix)throws IOException{return Files.readAllBytes(file(id,suffix).toPath());}
        boolean absent(String id){return !file(id,"").exists()&&!file(id,".new").exists()&&!file(id,".bak").exists();}
        boolean noteKey(String id)throws Exception{return keys.containsAlias("sidecar.note."+id);}
        boolean audioKey(String id)throws Exception{return keys.containsAlias("sidecar.audio."+id);}
        boolean barred(String id){return context.getSharedPreferences("deletion-barriers",0).contains(id);}
        SharedPreferences clock(){return context.getSharedPreferences("retention-clock",0);}
        void audioAlias(String id)throws Exception {try(AudioSegmentStore audio=new AudioSegmentStore(context)){audio.createSession(8000,id);}}
        void expire(String id)throws Exception {JSONObject body=store.read(id);NoteRetentionChecks.expireMeetingFixture(body,NoteRetentionChecks.fixtureNow(store)-1000);write(store,id,body);}
        @Override public void close()throws Exception {
            probe.reset();Throwable failure=null;
            for(String id:ids)for(String prefix:new String[]{"sidecar.note.","sidecar.audio."})try{keys.deleteEntry(prefix+id);}catch(Throwable next){failure=combine(failure,next);}
            try{context.dispose();}catch(Throwable next){failure=combine(failure,next);}
            if(failure instanceof Exception)throw (Exception)failure;if(failure instanceof Error)throw (Error)failure;
        }
        private Throwable combine(Throwable first,Throwable next){if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;}
    }
    private static void write(NoteStore store,String id,JSONObject body)throws Exception {
        Method writer=NoteStore.class.getDeclaredMethod("write",String.class,JSONObject.class);writer.setAccessible(true);
        synchronized(MeetingPrivacy.LOCK){try{writer.invoke(store,id,body);}catch(InvocationTargetException failure){
            Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw failure;
        }}
    }
    private static Map<String,byte[]> family(Fixture f,String id)throws IOException {
        Map<String,byte[]> result=new LinkedHashMap<>();for(String suffix:new String[]{"",".new",".bak"})if(f.file(id,suffix).exists())result.put(suffix,f.bytes(id,suffix));return result;
    }
    private static boolean sameFamily(Fixture f,String id,Map<String,byte[]> before)throws IOException {
        Map<String,byte[]> after=family(f,id);if(!after.keySet().equals(before.keySet()))return false;
        for(String suffix:before.keySet())if(!Arrays.equals(before.get(suffix),after.get(suffix)))return false;return true;
    }
    private static void mixed(Fixture f,String id,String suffix)throws Exception {
        byte[] previous=f.bytes(id,"");f.store.append(id,0,"synthetic current revision","unassigned");
        Files.write(f.file(id,suffix).toPath(),previous);
    }
    private static void makeLocal(Fixture f,String id,String layout)throws Exception {
        if(layout.equals("new")){mixed(f,id,".new");return;}
        if(layout.equals("bak")){mixed(f,id,".bak");return;}
        if(layout.equals("partial")){
            f.store.append(id,0,"synthetic still retained note","unassigned");JSONObject body=f.store.read(id);
            JSONObject note=new JSONObject(body.getJSONArray("attachments").getJSONObject(0).toString());
            NoteRetentionChecks.expireMeetingFixture(body,NoteRetentionChecks.fixtureNow(f.store)-1000);
            body.getJSONArray("attachments").put(0,note);body.put("expires_at",note.getLong("expires_at"));write(f.store,id,body);
        }
        if(!f.file(id,"").renameTo(f.file(id,".new")))throw new IOException("fixture pending-only rename failed");
    }
    private static void isolatedFamilies(Context base)throws Exception {
        for(String layout:new String[]{"new","bak","lone","partial"})try(Fixture f=new Fixture(base)){
            String[] ids=f.ordered(4);String before=ids[0],unknown=ids[1],after=ids[2],neighbor=ids[3];
            makeLocal(f,unknown,layout);f.audioAlias(unknown);f.expire(before);f.expire(after);
            Map<String,byte[]> retained=family(f,unknown);byte[] neighborBytes=f.bytes(neighbor,"");f.probe.reset();
            NoteStore.SweepResult result=NoteStore.sweepForListing(f.context,()->false,f.probe);
            check(result.expired==2&&result.unavailable.equals(Collections.singleton(unknown)),layout+" reports exactly affected ID and both independent expirations");
            check(f.absent(before)&&f.absent(after)&&f.barred(before)&&f.barred(after)&&!f.noteKey(before)&&!f.noteKey(after),layout+" cleans independently due meetings on both sides");
            check(sameFamily(f,unknown,retained)&&f.noteKey(unknown)&&f.audioKey(unknown)&&!f.barred(unknown),layout+" preserves unknown bytes and both aliases");
            check(Arrays.equals(neighborBytes,f.bytes(neighbor,""))&&f.store.read(neighbor).getString("id").equals(neighbor),layout+" permits unrelated read without rewrite");
            f.store.append(neighbor,0,"synthetic permitted neighbor edit","unassigned");
            check(f.store.read(neighbor).getJSONArray("attachments").length()==1,layout+" unrelated edit remains usable");
            f.probe.reset();local(()->f.store.read(unknown),unknown,layout+" direct read remains blocked");
            local(()->f.store.append(unknown,0,"must not commit","unassigned"),unknown,layout+" append cannot bypass local quarantine");
            check(sameFamily(f,unknown,retained)&&f.probe.reads.isEmpty()&&!f.probe.stages.contains("before_decrypt"),layout+" rejected direct access neither recovers nor decrypts");
            NoteStore.SweepResult repeated=f.store.sweepForListing(()->false);
            check(repeated.expired==0&&repeated.unavailable.equals(Collections.singleton(unknown)),layout+" instance scan is idempotent and retains incomplete status");
            globalIo(thrown(()->f.store.sweepExpired(()->false)),layout+" ordinary maintenance still reports incomplete");
            check(sameFamily(f,unknown,retained)&&f.noteKey(unknown)&&f.audioKey(unknown),layout+" no maintenance path discards unresolved evidence");
        }
    }
    private static void fatalIoBothOrders(Context base)throws Exception {
        for(boolean localFirst:new boolean[]{true,false})try(Fixture f=new Fixture(base)){
            String[] ids=f.ordered(3);String unknown=ids[localFirst?0:1],broken=ids[localFirst?1:0],due=ids[2];
            mixed(f,unknown,".new");f.expire(due);Map<String,byte[]> retained=family(f,unknown);byte[] original=f.bytes(broken,"");
            f.probe.reset();f.probe.readFault=f.file(broken,"");f.probe.ioFailure=new IOException("synthetic independent storage failure");
            Throwable failure=thrown(()->NoteStore.sweepForListing(f.context,()->false,f.probe));
            check(failure==f.probe.ioFailure,"ordinary I/O stays fatal with localFirst="+localFirst);
            check(f.absent(due)&&f.barred(due)&&sameFamily(f,unknown,retained)&&Arrays.equals(original,f.bytes(broken,"")),"fatal I/O still allows independent expiry but not data mutation");
            f.probe.reset();NoteStore.SweepResult retry=NoteStore.sweepForListing(f.context,()->false);
            check(retry.expired==0&&retry.unavailable.equals(Collections.singleton(unknown))&&f.store.read(broken).getString("id").equals(broken),"cleared I/O fault returns only the true local scope");
        }
    }
    private static void securityBothOrders(Context base)throws Exception {
        for(String failureKind:new String[]{"missing_key","cipher_authentication"})for(boolean localFirst:new boolean[]{true,false})try(Fixture f=new Fixture(base)){
            String[] ids=f.ordered(3);String unknown=ids[localFirst?0:1],broken=ids[localFirst?1:0],neighbor=ids[2];
            mixed(f,unknown,".new");Map<String,byte[]> retained=family(f,unknown);
            if(failureKind.equals("missing_key"))f.keys.deleteEntry("sidecar.note."+broken);
            else {byte[] corrupt=f.bytes(broken,"");corrupt[corrupt.length-1]^=1;Files.write(f.file(broken,"").toPath(),corrupt);}
            byte[] brokenBytes=f.bytes(broken,"");f.probe.reset();Throwable failure=thrown(()->f.store.sweepForListing(()->false));
            check(failureKind.equals("missing_key")?failure instanceof SecurityException:failure instanceof GeneralSecurityException,failureKind+" remains fatal with localFirst="+localFirst);
            check(sameFamily(f,unknown,retained)&&Arrays.equals(brokenBytes,f.bytes(broken,""))&&!f.barred(unknown)&&!f.barred(broken),"authentication failure never authorizes disposal or local success");
            check(f.store.read(neighbor).getString("id").equals(neighbor),"independent record can still be read directly after fatal scan");
        }
    }
    private static void clockAndCancellation(Context base)throws Exception {
        for(boolean afterLocal:new boolean[]{false,true})try(Fixture f=new Fixture(base)){
            String[] ids=f.ordered(3);String unknown=ids[0],due=ids[1];mixed(f,unknown,".new");f.expire(due);
            Map<String,byte[]> retained=family(f,unknown);byte[] dueBytes=f.bytes(due,"");f.probe.reset();
            Runnable changeClock=()->{SharedPreferences clock=f.clock();if(!clock.edit().putInt("boot",clock.getInt("boot",-1)+1).commit())throw new AssertionError("clock fixture commit failed");};
            if(afterLocal)f.probe.onceBeforeRead=changeClock;else changeClock.run();
            Throwable failure=thrown(()->f.store.sweepForListing(()->false));
            check(failure instanceof IllegalStateException&&!(failure instanceof NoteStore.Expired),"clock uncertainty remains fatal afterLocal="+afterLocal);
            check(sameFamily(f,unknown,retained)&&Arrays.equals(dueBytes,f.bytes(due,""))&&!f.barred(due),"uncertain clock authorizes no new expiry");
            check(Collections.frequency(f.probe.stages,"before_read")== (afterLocal?1:0),"known clock fault wins before processing the next private body");
        }
        for(boolean duringLocal:new boolean[]{false,true})try(Fixture f=new Fixture(base)){
            String[] ids=f.ordered(2);String unknown=ids[0],due=ids[1];mixed(f,unknown,".new");f.expire(due);
            Map<String,byte[]> retained=family(f,unknown);byte[] dueBytes=f.bytes(due,"");f.probe.reset();AtomicBoolean cancelled=new AtomicBoolean(!duringLocal);
            if(duringLocal)f.probe.onceBeforeRead=()->cancelled.set(true);
            Throwable failure=thrown(()->NoteStore.sweepForListing(f.context,cancelled::get,f.probe));
            check(failure instanceof InterruptedIOException,"cancellation remains fatal duringLocal="+duringLocal);
            check(sameFamily(f,unknown,retained)&&Arrays.equals(dueBytes,f.bytes(due,""))&&!f.barred(due),"cancelled scan cannot claim or perform later expiry");
        }
    }
    private static void unsafeInventory(Context base)throws Exception {
        for(String kind:new String[]{"short_pending","directory_pending","unsupported_lone"})try(Fixture f=new Fixture(base)){
            String[] ids=f.ordered(2);String unknown=ids[0],broken=ids[1];mixed(f,unknown,".new");Map<String,byte[]> retained=family(f,unknown);
            if(kind.equals("short_pending"))Files.write(f.file(broken,".new").toPath(),new byte[]{1,2,3});
            else if(kind.equals("directory_pending")){if(!f.file(broken,".new").mkdir())throw new IOException("fixture directory unavailable");}
            else {JSONObject body=f.store.read(broken);body.put("unsupported_asset","synthetic unverified policy");write(f.store,broken,body);if(!f.file(broken,"").renameTo(f.file(broken,".new")))throw new IOException("fixture pending rename failed");}
            f.probe.reset();globalIo(thrown(()->f.store.sweepForListing(()->false)),kind+" is not downgraded to scoped availability");
            check(sameFamily(f,unknown,retained)&&f.noteKey(unknown)&&f.noteKey(broken)&&!f.barred(broken),"invalid type/schema preserves evidence and keys");
        }
        try(Fixture f=new Fixture(base)){
            String id=f.create();byte[] original=f.bytes(id,"");File directory=f.root(),parked=new File(f.context.directory,"parked-meetings");
            if(!directory.renameTo(parked))throw new IOException("fixture directory park failed");
            try{
                Files.write(directory.toPath(),new byte[]{7,8,9});
                globalIo(thrown(()->NoteStore.sweepForListing(f.context,()->false,f.probe)),"non-directory inventory never becomes a partial listing");
                check(Arrays.equals(original,Files.readAllBytes(new File(parked,id+".enc").toPath()))&&f.noteKey(id),"inventory failure preserves parked records and aliases");
            }finally{
                if(directory.exists()&&!directory.delete())throw new IOException("fixture root-file removal failed");
                if(!parked.renameTo(directory))throw new IOException("fixture directory restore failed");
            }
        }
    }
    private static void finalListingGate(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String unknown=f.create();mixed(f,unknown,".new");Map<String,byte[]> retained=family(f,unknown);f.probe.reset();
            f.probe.onceBeforeRead=()->{SharedPreferences clock=f.clock();if(!clock.edit().putInt("boot",clock.getInt("boot",-1)+1).commit())throw new AssertionError("late clock fixture failed");};
            Throwable failure=thrown(()->f.store.sweepForListing(()->false));
            check(failure instanceof IllegalStateException&&!(failure instanceof NoteStore.Expired),"last unresolved family cannot return a partial result after the clock becomes untrusted");
            check(Collections.frequency(f.probe.stages,"before_read")==1&&sameFamily(f,unknown,retained)&&f.noteKey(unknown),"late clock failure has no later private read or destructive side effect");
        }
        try(Fixture f=new Fixture(base)){
            String pending=f.create();f.audioAlias(pending);f.expire(pending);
            byte[] original=f.bytes(pending,"");if(!f.file(pending,"").renameTo(f.file(pending,".new")))throw new IOException("authorized cleanup fixture rename failed");
            check(f.store.sweepExpired(()->false)==0&&f.absent(pending),"fixture records a real durable exact-copy decision first");
            Map<String,?> markers=new HashMap<>(f.context.getSharedPreferences("note-expired-copies-v1",0).getAll());
            Files.write(f.file(pending,".new").toPath(),original);SharedPreferences clock=f.clock();
            if(!clock.edit().putInt("boot",clock.getInt("boot",-1)+1).commit())throw new IOException("authorized cleanup clock fixture failed");
            f.probe.reset();NoteStore.SweepResult result=NoteStore.sweepForListing(f.context,()->false,f.probe);
            check(result.expired==0&&result.unavailable.isEmpty()&&f.absent(pending),"already authorized copy-only cleanup remains allowed with untrusted clock");
            check(f.noteKey(pending)&&f.audioKey(pending)&&!f.barred(pending)&&f.context.getSharedPreferences("note-expired-copies-v1",0).getAll().equals(markers),"copy-only cleanup preserves both aliases and does not invent new authority");
            check(!f.probe.stages.contains("before_decrypt")&&!f.probe.stages.contains("before_parse"),"known exact-copy retry needs no private body processing");
        }
        try(Fixture f=new Fixture(base)){
            f.create();AtomicInteger polls=new AtomicInteger();AtomicBoolean held=new AtomicBoolean(true);
            Throwable failure=thrown(()->f.store.revalidateListingAccess(()->{held.set(held.get()&&Thread.holdsLock(MeetingPrivacy.LOCK));return polls.incrementAndGet()==2;}));
            check(failure instanceof InterruptedIOException&&polls.get()==2,"listing-return guard detects cancellation arriving at its final check");
            check(held.get(),"listing access and cancellation checks retain the shared privacy lock");
        }
    }
    private static void strictDeleteRecovery(Context base)throws Exception {
        try(Fixture f=new Fixture(base)){
            String deleted=f.create();String[] ids=f.ordered(2);String unknown=ids[0],due=ids[1];mixed(f,unknown,".new");f.expire(due);
            byte[] old=f.bytes(deleted,"");Map<String,byte[]> retained=family(f,unknown);f.probe.keepOnDelete=f.file(deleted,"");
            globalIo(thrown(()->f.store.delete(deleted)),"failed durable deletion cleanup remains incomplete");
            globalIo(thrown(()->new NoteStore(f.context,()->{},f.probe)),"ordinary constructor still requires deletion recovery");
            check(f.barred(deleted)&&!f.noteKey(deleted)&&Arrays.equals(old,f.bytes(deleted,"")),"failed cleanup retains a durable barrier and its inaccessible bytes");
            globalIo(thrown(()->NoteStore.sweepForListing(f.context,()->false,f.probe)),"listing maintenance never hides deletion cleanup failure");
            check(f.absent(due)&&f.barred(due)&&sameFamily(f,unknown,retained),"maintenance still handles independently due notes around local copies");
            check(thrown(()->f.store.read(deleted)) instanceof SecurityException,"failed cleanup never readmits deleted content");
            f.probe.reset();NoteStore reopened=new NoteStore(f.context,()->{},f.probe);
            check(f.absent(deleted)&&reopened.sweepForListing(()->false).unavailable.equals(Collections.singleton(unknown)),"ordinary recovery succeeds only after the cleanup fault is removed");
        }
    }
    public static int run(Context context)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        if(!keyguard.isDeviceSecure()||keyguard.isDeviceLocked())throw new SecurityException("authenticated unlocked fixture required");
        checks=0;isolatedFamilies(context);fatalIoBothOrders(context);securityBothOrders(context);clockAndCancellation(context);unsafeInventory(context);finalListingGate(context);strictDeleteRecovery(context);return checks;
    }
}

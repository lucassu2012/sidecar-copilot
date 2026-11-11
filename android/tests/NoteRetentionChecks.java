package local.sidecar.copilot;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Existing TTL/migration/privacy subsets. Synthetic records; no wall-clock changes or capture. */
public final class NoteRetentionChecks {
    private static int checks;
    private interface Work {void run()throws Exception;}
    private static void check(boolean value){if(!value)throw new AssertionError("note retention "+checks);checks++;}
    private static void rejects(Work work)throws Exception {
        try{work.run();throw new AssertionError("invalid retention visible");}catch(SecurityException expected){checks++;}
    }
    private static JSONObject copy(JSONObject value)throws Exception{return new JSONObject(value.toString());}
    /** Fixture deadlines use the store's actual clock domain, not a tolerated wall-clock offset. */
    public static long fixtureNow(NoteStore store)throws Exception {
        Method clock=NoteStore.class.getDeclaredMethod("now");clock.setAccessible(true);
        synchronized(MeetingPrivacy.LOCK){return (Long)clock.invoke(store);}
    }
    private static void write(NoteStore store,String id,JSONObject fixture)throws Exception {
        Method writer=NoteStore.class.getDeclaredMethod("write",String.class,JSONObject.class);
        writer.setAccessible(true);writer.invoke(store,id,fixture);
    }
    private static JSONObject fixtureEnvelope(String origin,long created,long expires)throws Exception {
        return new JSONObject().put("retention_policy_id",RetentionEnvelope.POLICY_ID).put("policy_version",1)
            .put("retention_origin",origin).put("origin_time",created).put("expires_at",expires)
            .put("policy_evidence",RetentionEnvelope.DEVELOPMENT_SNAPSHOT).put("clock_confidence","device_clock_estimate");
    }
    /** Test-only metadata fixture: preserves the policy duration instead of inventing a short policy. */
    public static void expireNoteFixture(JSONObject meeting,int index,long expires)throws Exception {
        long created=expires-NotePolicy.RETENTION_MS;
        if(meeting.getLong("created_at")>created) {
            meeting.put("created_at",created).put("title_expires_at",expires)
                .put("title_retention",fixtureEnvelope(RetentionEnvelope.TITLE_ORIGIN,created,expires));
        }
        JSONObject note=meeting.getJSONArray("attachments").getJSONObject(index);
        note.put("created_at",created).put("added_at",created).put("eligible_at",created+NotePolicy.BUFFER_MS)
            .put("expires_at",expires).put("retention",fixtureEnvelope(RetentionEnvelope.NOTE_ORIGIN,created,expires));
    }
    public static void expireMeetingFixture(JSONObject meeting,long expires)throws Exception {
        long created=expires-NotePolicy.RETENTION_MS;
        meeting.put("created_at",created).put("title_expires_at",expires).put("expires_at",expires)
            .put("title_retention",fixtureEnvelope(RetentionEnvelope.TITLE_ORIGIN,created,expires));
        for(int i=0;i<meeting.getJSONArray("attachments").length();i++)expireNoteFixture(meeting,i,expires);
    }
    public static int run(Context context)throws Exception {
        checks=0;NoteStore store=new NoteStore(context);List<String> createdIds=new ArrayList<>();
        try {
            String id=store.create("synthetic retention",true);createdIds.add(id);
            store.append(id,0,"independent synthetic note","unassigned");
            JSONObject current=store.read(id);long origin=current.getLong("created_at"),titleDeadline=current.getLong("title_expires_at");
            JSONObject note=current.getJSONArray("attachments").getJSONObject(0);long noteDeadline=note.getLong("expires_at");
            check(current.getJSONObject("title_retention").getString("policy_evidence").equals(RetentionEnvelope.DEVELOPMENT_SNAPSHOT));
            check(note.getJSONObject("retention").getLong("origin_time")==note.getLong("created_at"));
            JSONObject legacy=copy(current);legacy.remove("title_retention");legacy.getJSONArray("attachments").getJSONObject(0).remove("retention");
            write(store,id,legacy);
            CountDownLatch start=new CountDownLatch(1);AtomicReference<Throwable> failed=new AtomicReference<>();
            JSONObject[] migrated=new JSONObject[2];Thread[] readers=new Thread[2];
            for(int i=0;i<2;i++){final int index=i;readers[i]=new Thread(()->{try{start.await();migrated[index]=new NoteStore(context).read(id);}catch(Throwable e){failed.set(e);}});readers[i].start();}
            start.countDown();for(Thread reader:readers)reader.join(5000);
            check(!readers[0].isAlive()&&!readers[1].isAlive()&&failed.get()==null);
            check(migrated[0].getLong("revision")==2&&migrated[1].getLong("revision")==2);
            current=store.read(id);
            check(current.getLong("created_at")==origin&&current.getLong("title_expires_at")==titleDeadline);
            check(current.getJSONArray("attachments").getJSONObject(0).getLong("expires_at")==noteDeadline);
            check(current.getJSONObject("title_retention").getString("policy_evidence").equals(RetentionEnvelope.LEGACY_DEADLINE_ONLY));
            check(current.getJSONArray("attachments").getJSONObject(0).getJSONObject("retention").getString("policy_evidence").equals(RetentionEnvelope.LEGACY_DEADLINE_ONLY));
            check(store.read(id).getLong("revision")==2); // A second migration does not invent a new content revision.

            JSONObject invalid=copy(current);invalid.getJSONArray("attachments").getJSONObject(0).put("expires_at",noteDeadline+1);write(store,id,invalid);
            rejects(()->store.read(id));write(store,id,current);
            invalid=copy(current);invalid.remove("title_retention");invalid.remove("title_expires_at");invalid.put("expires_at",titleDeadline+1);write(store,id,invalid);
            rejects(()->store.read(id));write(store,id,current);
            invalid=copy(current);invalid.remove("title_retention");invalid.put("title_expires_at",titleDeadline+1);write(store,id,invalid);
            rejects(()->store.read(id));write(store,id,current);
            invalid=copy(current);invalid.getJSONArray("attachments").getJSONObject(0).put("source_refs",new JSONArray().put("synthetic-media"));write(store,id,invalid);
            rejects(()->store.read(id));write(store,id,current);

            store.append(id,current.getLong("revision"),"retained later note","unassigned");current=store.read(id);
            check(current.getLong("title_expires_at")==titleDeadline);
            check(current.getJSONArray("attachments").getJSONObject(0).getLong("expires_at")==noteDeadline);
            expireNoteFixture(current,0,fixtureNow(store)-1000);write(store,id,current);
            File file=new File(context.getNoBackupFilesDir(),"meetings/"+id+".enc");byte[] stale=Files.readAllBytes(file.toPath());
            try{store.sweepExpired(()->true);throw new AssertionError("cancellation ignored");}catch(InterruptedIOException expected){checks++;}
            check(Arrays.equals(stale,Files.readAllBytes(file.toPath())));
            check(store.sweepExpired(()->false)>=1);
            JSONObject cleaned=store.read(id);check(cleaned.getJSONArray("attachments").length()==1);
            check(cleaned.getJSONArray("attachments").getJSONObject(0).getString("text").equals("retained later note"));
            check(cleaned.getBoolean("title_expired"));check(store.sweepExpired(()->false)==0);
            Files.write(file.toPath(),stale);check(store.read(id).getJSONArray("attachments").length()==1);

            String ended=store.create("synthetic whole expiry",true);createdIds.add(ended);
            JSONObject oldMeeting=store.read(ended);expireMeetingFixture(oldMeeting,fixtureNow(store)-1000);write(store,ended,oldMeeting);
            check(store.sweepExpired(()->false)>=1);check(!store.ids().contains(ended));rejects(()->store.read(ended));

            // Pause between meetings/listing and processing, not while holding the global privacy lock.
            String race=store.create("synthetic sweep race",true);createdIds.add(race);
            CountDownLatch paused=new CountDownLatch(1),resume=new CountDownLatch(1);AtomicInteger polls=new AtomicInteger();
            AtomicReference<Throwable> sweepFailure=new AtomicReference<>();
            Thread sweeper=new Thread(()->{try{store.sweepExpired(()->{
                if(!Thread.holdsLock(MeetingPrivacy.LOCK)&&polls.incrementAndGet()==2){paused.countDown();try{if(!resume.await(5,TimeUnit.SECONDS))return true;}catch(InterruptedException e){Thread.currentThread().interrupt();return true;}}
                return false;
            });}catch(Throwable error){sweepFailure.set(error);}});
            sweeper.start();check(paused.await(3,TimeUnit.SECONDS));
            AtomicReference<Throwable> editFailure=new AtomicReference<>();
            Thread writer=new Thread(()->{try{new NoteStore(context).append(race,0,"concurrent retained note","unassigned");new NoteStore(context).delete(id);}catch(Throwable error){editFailure.set(error);}});
            try{writer.start();writer.join(3000);check(!writer.isAlive()&&editFailure.get()==null);}finally{resume.countDown();sweeper.join(5000);writer.join(5000);}
            check(!sweeper.isAlive()&&sweepFailure.get()==null);
            check(store.read(race).getJSONArray("attachments").getJSONObject(0).getString("text").equals("concurrent retained note"));
            check(!store.ids().contains(id));
        } finally {for(String id:createdIds)store.delete(id);}
        return checks;
    }
}

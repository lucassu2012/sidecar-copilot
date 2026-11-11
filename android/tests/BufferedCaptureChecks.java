package local.sidecar.copilot;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.SystemClock;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Existing T06/T08/T22/T37/T39 subsets. Synthetic PCM and native storage only; no audio device. */
public final class BufferedCaptureChecks {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError("buffered capture check "+checks);checks++;}
    private static final int RATE=8000,CHUNK=800;
    private static final class Fixture implements AutoCloseable {
        final Context context;
        final File root;
        final AudioSegmentStore store;
        final String session;
        final AudioCaptureCoordinator capture;
        final long token;
        final BoundedPcmQueue queue;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),stopped=new CountDownLatch(1);
        final CountDownLatch privacyStopped=new CountDownLatch(1);
        final AtomicInteger signals=new AtomicInteger();
        final AtomicInteger privacySignals=new AtomicInteger();
        final AtomicReference<String> failStage=new AtomicReference<>("");
        final AtomicBoolean first=new AtomicBoolean(true);
        final short[] pcm=new short[CHUNK];
        Fixture(Context base,boolean failAfterRelease)throws Exception {
            root=new File(base.getNoBackupFilesDir(),"buffered-fixture-"+UUID.randomUUID());
            if(!root.mkdir())throw new IOException("fixture directory");
            context=new ContextWrapper(base){@Override public File getNoBackupFilesDir(){return root;}};
            store=new AudioSegmentStore(context,stage->{
                if(stage.equals(failStage.get()))throw new IOException("synthetic "+stage);
                if(stage.equals("after_fsync")&&first.compareAndSet(true,false)){
                    entered.countDown();
                    try{if(!release.await(10,TimeUnit.SECONDS))throw new IOException("blocked writer fixture timeout");}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                    if(failAfterRelease)throw new IOException("synthetic asynchronous append failure");
                }
            });
            session=store.createSession(RATE);capture=new AudioCaptureCoordinator(store,session,RATE);
            capture.setInputStop(()->{signals.incrementAndGet();stopped.countDown();});
            capture.setPrivacyInputStop(()->{privacySignals.incrementAndGet();privacyStopped.countDown();});
            token=capture.startBuffered(true);
            Field field=AudioCaptureCoordinator.class.getDeclaredField("queue");field.setAccessible(true);
            queue=(BoundedPcmQueue)field.get(capture);
            Arrays.fill(pcm,(short)1234);
        }
        long offer()throws Exception {
            long received=SystemClock.elapsedRealtimeNanos();
            check(capture.samples(token,pcm,CHUNK,received));return received;
        }
        void drainItem()throws Exception {
            long deadline=SystemClock.elapsedRealtime()+5000;
            while(queue.outstandingSamples()!=0&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(1);
            check(queue.outstandingSamples()==0&&capture.isActive());
        }
        long blockFirstFrame()throws Exception {
            // Let the assembler consume each short callback; never rely on scheduler speed or a >2s callback.
            for(int i=0;i<49;i++){offer();drainItem();}
            long received=offer();check(entered.await(5,TimeUnit.SECONDS));return received;
        }
        SQLiteDatabase inspect(){return SQLiteDatabase.openDatabase(new File(root,"audio-segments/index.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY);}
        @Override public void close()throws Exception {
            release.countDown();
            try{capture.pause();}catch(Exception expected){/* Failed native write already asserted by the scenario. */}
            store.close();
        }
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("synthetic tests require disposable emulator");
        checks=0;Context context=instrumentation.getTargetContext();
        overflowAndInterruptedPause(context);
        asynchronousFailure(context);
        deletionBeforeCommit(context);
        privacyDiscardWhileDiskBlocked(context);
        stopPublicationDuringStart(context);
        rangeDeleteBeforeDiscard(context);
        rangeDeleteCommitFailures(context);
        rangeDeleteDespitePauseFailure(context);
        synchronousRangePauseRetry(context);
        return checks;
    }
    private static void overflowAndInterruptedPause(Context context)throws Exception {
        try(Fixture f=new Fixture(context,false)){
            long firstReceived=f.blockFirstFrame();
            AtomicReference<Throwable> producerFailure=new AtomicReference<>();
            long[] lastReceived={firstReceived};
            Thread producer=new Thread(()->{
                try{
                    // First callback remains consumer-owned while fsync is blocked: 19 slots remain.
                    for(int i=0;i<19;i++)lastReceived[0]=f.offer();
                    if(f.capture.samples(f.token,f.pcm,CHUNK,SystemClock.elapsedRealtimeNanos()))throw new AssertionError("overflow admitted");
                }catch(Throwable e){producerFailure.set(e);}
            },"buffered-native-producer");
            producer.start();producer.join(2000);
            check(!producer.isAlive()&&producerFailure.get()==null);
            check(f.stopped.await(1,TimeUnit.SECONDS)&&f.signals.get()==1);
            check(f.release.getCount()==1&&!f.queue.isFinished());
            check(f.queue.acceptedSamples()==55200&&f.queue.outstandingSamples()==16000&&f.queue.highWaterSamples()==16000);
            check(!f.capture.isActive()&&!f.capture.samples(f.token,f.pcm,CHUNK,SystemClock.elapsedRealtimeNanos()));
            // Caller buffer reuse must not alter already accepted samples.
            Arrays.fill(f.pcm,(short)0);
            CountDownLatch pauseEntered=new CountDownLatch(1),pauseDone=new CountDownLatch(1);
            AtomicReference<Throwable> pauseError=new AtomicReference<>();
            AtomicBoolean restoredInterrupt=new AtomicBoolean();
            Thread pauser=new Thread(()->{
                Thread.currentThread().interrupt();pauseEntered.countDown();
                try{f.capture.pause();}catch(Throwable e){pauseError.set(e);}
                finally{restoredInterrupt.set(Thread.currentThread().isInterrupted());pauseDone.countDown();}
            },"buffered-native-pause");
            pauser.start();check(pauseEntered.await(1,TimeUnit.SECONDS));
            check(!pauseDone.await(100,TimeUnit.MILLISECONDS)); // Cannot transfer store ownership while writer lives.
            f.release.countDown();pauser.join(5000);
            check(!pauser.isAlive()&&pauseError.get() instanceof InterruptedException&&restoredInterrupt.get());
            check(f.queue.isFinished()&&f.queue.outstandingSamples()==0);
            List<String> frames=f.store.frameIds(f.session);check(frames.size()==2);
            short[] head=f.store.read(frames.get(0)),tail=f.store.read(frames.get(1));
            check(head.length==40000&&tail.length==15200&&head[0]==1234&&tail[tail.length-1]==1234);
            check(f.store.committedSamples(f.session)==55200&&f.store.state(f.session).equals("paused"));
            try(SQLiteDatabase db=f.inspect()){
                try(Cursor c=db.rawQuery("SELECT start_sample,end_sample,end_ns FROM frames WHERE session=? ORDER BY start_sample",new String[]{f.session})){
                    check(c.moveToFirst()&&c.getLong(0)==0&&c.getLong(1)==40000&&c.getLong(2)==firstReceived);
                    check(c.moveToNext()&&c.getLong(0)==40000&&c.getLong(1)==55200&&c.getLong(2)==lastReceived[0]);
                    check(!c.moveToNext());
                }
                try(Cursor c=db.rawQuery("SELECT start_sample,end_sample,reason FROM gaps WHERE session=?",new String[]{f.session})){
                    check(c.moveToFirst()&&c.getLong(0)==55200&&c.getLong(1)==56000&&c.getString(2).equals("pcm_backpressure"));
                    check(!c.moveToNext());
                }
                try(Cursor c=db.rawQuery("SELECT generation,kind,accepted_end,observed_end,requested_ns,accepted_ns FROM capture_controls WHERE session=?",new String[]{f.session})){
                    check(c.moveToFirst()&&c.getLong(0)==f.token&&c.getString(1).equals("pcm_backpressure"));
                    check(c.getLong(2)==55200&&c.getLong(3)==56000&&c.getLong(5)>=c.getLong(4));
                    check(!c.moveToNext());
                }
            }
            f.store.end(f.session,SystemClock.elapsedRealtimeNanos());
            check(f.store.endMetadata(f.session).getLong("sample_end")==56000);
            check(f.store.committedSamples(f.session)==55200); // Coverage includes the gap; saved samples do not.
        }
    }
    private static void asynchronousFailure(Context context)throws Exception {
        try(Fixture f=new Fixture(context,true)){
            f.blockFirstFrame();f.offer();
            check(f.queue.acceptedSamples()==40800);
            f.release.countDown();
            try{f.capture.pause();throw new AssertionError("asynchronous error lost");}catch(IOException expected){checks++;}
            check(f.stopped.await(1,TimeUnit.SECONDS)&&f.signals.get()==1);
            check(f.queue.isFinished()&&f.queue.outstandingSamples()==0&&!f.capture.isActive());
            check(f.store.frameIds(f.session).isEmpty()&&f.store.state(f.session).equals("paused"));
            try(SQLiteDatabase db=f.inspect();Cursor c=db.rawQuery("SELECT start_sample,end_sample,reason FROM gaps WHERE session=?",new String[]{f.session})){
                check(c.moveToFirst()&&c.getLong(0)==0&&c.getLong(1)==40800&&c.getString(2).equals("uncommitted_tail"));
                check(!c.moveToNext());
            }
            f.store.recover();check(f.store.frameIds(f.session).isEmpty());
            check(!f.capture.samples(f.token,f.pcm,CHUNK,SystemClock.elapsedRealtimeNanos()));
        }
    }
    private static void deletionBeforeCommit(Context context)throws Exception {
        try(Fixture f=new Fixture(context,false)){
            f.blockFirstFrame();f.offer();
            // Inject the durable meeting barrier before index commit; no real note/device content is involved.
            check(f.context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).edit().putBoolean(f.session,true).commit());
            f.capture.stopAdmission();f.release.countDown();
            try{f.capture.pause();throw new AssertionError("deleted queue finalized successfully");}catch(SecurityException expected){checks++;}
            check(f.queue.isFinished()&&f.queue.outstandingSamples()==0);
            f.store.purgeDeleted(f.session);f.store.recover();
            try{f.store.frameIds(f.session);throw new AssertionError("deleted audio visible");}catch(SecurityException expected){checks++;}
            try{f.capture.startBuffered(true);throw new AssertionError("deleted capture restarted");}catch(SecurityException expected){checks++;}
            try(SQLiteDatabase db=f.inspect();Cursor c=db.rawQuery("SELECT COUNT(*) FROM frames WHERE session=? AND deleted=0",new String[]{f.session})){
                check(c.moveToFirst()&&c.getLong(0)==0);
            }
        }
    }
    private static void privacyDiscardWhileDiskBlocked(Context context)throws Exception {
        try(Fixture f=new Fixture(context,false)){
            f.blockFirstFrame();f.offer();f.offer();
            check(f.queue.acceptedSamples()==41600&&f.queue.outstandingSamples()==2400);
            long requested=SystemClock.elapsedRealtimeNanos();
            f.capture.requestStop("privacy",requested,true);
            long deadline=SystemClock.elapsedRealtime()+3000;
            while(f.queue.outstandingSamples()!=CHUNK&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(1);
            check(f.release.getCount()==1&&f.queue.outstandingSamples()==CHUNK&&!f.queue.isFinished());
            check(f.queue.isDiscarding()&&!f.capture.samples(f.token,f.pcm,CHUNK,SystemClock.elapsedRealtimeNanos()));
            f.capture.requestStop("pause",SystemClock.elapsedRealtimeNanos(),false);
            check(f.queue.isDiscarding()&&f.queue.closedBoundary().kind.equals("privacy"));
            f.release.countDown();f.capture.pause();
            check(f.queue.outstandingSamples()==0&&f.store.committedSamples(f.session)==40000);
            check(f.store.frameIds(f.session).size()==1);
            try(SQLiteDatabase db=f.inspect()){
                try(Cursor c=db.rawQuery("SELECT start_sample,end_sample,reason FROM gaps WHERE session=?",new String[]{f.session})){
                    check(c.moveToFirst()&&c.getLong(0)==40000&&c.getLong(1)==41600&&c.getString(2).equals("uncommitted_tail"));
                    check(!c.moveToNext());
                }
                try(Cursor c=db.rawQuery("SELECT kind,requested_ns,accepted_ns,accepted_end,observed_end FROM capture_controls WHERE session=?",new String[]{f.session})){
                    check(c.moveToFirst()&&c.getString(0).equals("privacy")&&c.getLong(1)==requested&&c.getLong(2)>=requested);
                    check(c.getLong(3)==41600&&c.getLong(4)==41600&&!c.moveToNext());
                }
            }
        }
    }

    private static void stopPublicationDuringStart(Context base)throws Exception {
        for(boolean privacy:new boolean[]{false,true}){
            File root=new File(base.getNoBackupFilesDir(),"stop-publication-fixture-"+UUID.randomUUID());
            if(!root.mkdir())throw new IOException("stop publication fixture directory");
            Context context=new ContextWrapper(base){@Override public File getNoBackupFilesDir(){return root;}};
            CountDownLatch published=new CountDownLatch(1),resumeStop=new CountDownLatch(1);
            AtomicBoolean first=new AtomicBoolean(true);AtomicReference<Throwable> stopped=new AtomicReference<>();
            try(AudioSegmentStore store=new AudioSegmentStore(context)){
                String session=store.createSession(RATE);
                AudioCaptureCoordinator capture=new AudioCaptureCoordinator(store,session,RATE,()->{
                    if(first.compareAndSet(true,false)){
                        published.countDown();
                        try{if(!resumeStop.await(5,TimeUnit.SECONDS))throw new IllegalStateException("stop publication timeout");}
                        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    }
                });
                long requested=SystemClock.elapsedRealtimeNanos();String kind=privacy?"privacy":"end";
                Thread stopper=new Thread(()->{try{capture.requestStop(kind,requested,privacy);}catch(Throwable error){stopped.set(error);}},"stop-during-queue-publication");
                stopper.start();
                try{
                    check(published.await(2,TimeUnit.SECONDS));
                    long token=capture.startBuffered(true); // Clears shared request and discard flag before publishing queue.
                    check(capture.isActive());
                    Field qfield=AudioCaptureCoordinator.class.getDeclaredField("queue");qfield.setAccessible(true);
                    BoundedPcmQueue queue=(BoundedPcmQueue)qfield.get(capture);
                    short[] input=new short[CHUNK];Arrays.fill(input,(short)1234);
                    check(capture.samples(token,input,input.length,SystemClock.elapsedRealtimeNanos()));
                    long deadline=SystemClock.elapsedRealtime()+3000;
                    while(queue.outstandingSamples()!=0&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(1);
                    check(queue.outstandingSamples()==0&&store.frameIds(session).isEmpty());
                    Field afield=AudioCaptureCoordinator.class.getDeclaredField("assembler");afield.setAccessible(true);
                    Object assembler=afield.get(capture);Field pending=PcmFrameAssembler.class.getDeclaredField("pending");pending.setAccessible(true);
                    short[] held=(short[])pending.get(assembler);
                    check(held[0]==1234&&held[CHUNK-1]==1234); // Tail is already in assembler, outside queue's own arrays.
                    resumeStop.countDown();stopper.join(3000);
                    check(!stopper.isAlive()&&stopped.get()==null);
                    capture.pause();check(!capture.isActive());
                    check(queue.isDiscarding()==privacy);
                    check(store.committedSamples(session)==(privacy?0:CHUNK));
                    check(store.frameIds(session).size()==(privacy?0:1));
                    for(short value:held)if(value!=0)throw new AssertionError("assembler retained stopped plaintext");
                    check(true);
                    try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(root,"audio-segments/index.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                        try(Cursor c=db.rawQuery("SELECT kind,requested_ns,generation,accepted_end FROM capture_controls WHERE session=?",new String[]{session})){
                            check(c.moveToFirst()&&c.getString(0).equals(kind)&&c.getLong(1)==requested&&c.getLong(2)==token&&c.getLong(3)==CHUNK);
                            check(!c.moveToNext());
                        }
                        try(Cursor c=db.rawQuery("SELECT start_sample,end_sample,reason FROM gaps WHERE session=?",new String[]{session})){
                            if(privacy)check(c.moveToFirst()&&c.getLong(0)==0&&c.getLong(1)==CHUNK&&c.getString(2).equals("uncommitted_tail"));
                            check(!c.moveToNext());
                        }
                    }
                }finally{
                    resumeStop.countDown();stopper.join(3000);capture.pause();
                }
            }
        }
    }

    private static long count(Fixture f,String sql,String... args){
        try(SQLiteDatabase db=f.inspect();Cursor c=db.rawQuery(sql,args)){
            if(!c.moveToFirst())throw new AssertionError("missing range fixture result");return c.getLong(0);
        }
    }
    private static void committedFrame(Fixture f)throws Exception {
        f.first.set(false); // This scenario injects the named commit/control hook, not a slow append.
        for(int i=0;i<50;i++){f.offer();f.drainItem();}
        check(f.store.frameIds(f.session).size()==1);
    }
    private static void rangeDeleteBeforeDiscard(Context context)throws Exception {
        try(Fixture f=new Fixture(context,false)){
            String originalStream=f.store.stream(f.session);
            f.blockFirstFrame();f.offer();f.offer();
            AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread deletion=new Thread(()->{try{f.capture.deleteSamples(0,40000);}catch(Throwable error){failure.set(error);}},"buffered-range-delete");
            deletion.start();
            try{
                check(f.privacyStopped.await(2,TimeUnit.SECONDS));
                check(f.release.getCount()==1&&!f.queue.isDiscarding()&&f.privacySignals.get()==1);
                check(f.queue.outstandingSamples()==2400&&!f.capture.isActive());
                check(!f.capture.samples(f.token,f.pcm,CHUNK,SystemClock.elapsedRealtimeNanos()));
                check(count(f,"SELECT COUNT(*) FROM deletions WHERE session=?",f.session)==0);
                f.release.countDown();deletion.join(5000);
                check(!deletion.isAlive()&&failure.get()==null);
                check(f.queue.isFinished()&&f.queue.outstandingSamples()==0&&f.queue.isDiscarding());
                check(count(f,"SELECT COUNT(*) FROM deletions WHERE session=? AND stream=? AND start_sample=0 AND end_sample=40000",f.session,originalStream)==1);
                check(count(f,"SELECT COUNT(*) FROM frames WHERE session=? AND stream=? AND start_sample<40000 AND end_sample>0 AND (deleted!=1 OR wrapped IS NOT NULL)",f.session,originalStream)==0);
                check(f.signals.get()==0); // Range stop does not invoke the source-failure/shutdown callback.
                long next=f.capture.startBuffered(true);String resumedStream=f.store.stream(f.session);
                check(!resumedStream.equals(originalStream)&&next!=f.token);
                check(f.capture.samples(next,f.pcm,CHUNK,SystemClock.elapsedRealtimeNanos()));f.capture.pause();
                check(count(f,"SELECT COUNT(*) FROM frames WHERE session=? AND stream=? AND deleted=0",f.session,resumedStream)==1);
            }finally{f.release.countDown();deletion.join(5000);}
        }
    }
    private static void rangeDeleteCommitFailures(Context context)throws Exception {
        for(String stage:new String[]{"range_delete_before_commit","range_delete_after_commit"})try(Fixture f=new Fixture(context,false)){
            committedFrame(f);String originalStream=f.store.stream(f.session);
            f.failStage.set(stage);
            try{f.capture.deleteSamples(0,40000);throw new AssertionError("range commit error lost");}
            catch(IOException expected){check(expected.getMessage().equals("synthetic "+stage));}
            check(f.privacySignals.get()==1&&!f.capture.isActive()&&!f.queue.isDiscarding());
            check(!f.capture.samples(f.token,f.pcm,CHUNK,SystemClock.elapsedRealtimeNanos()));
            f.capture.pause();check(f.queue.isFinished());
            boolean persisted=stage.equals("range_delete_after_commit");
            check(count(f,"SELECT COUNT(*) FROM deletions WHERE session=? AND stream=?",f.session,originalStream)==(persisted?1:0));
            check(count(f,"SELECT COUNT(*) FROM frames WHERE session=? AND stream=? AND deleted=1 AND wrapped IS NULL",f.session,originalStream)==(persisted?1:0));
            try{f.capture.startBuffered(true);throw new AssertionError("incomplete range restarted capture");}
            catch(IllegalStateException expected){checks++;}
            try{f.capture.deleteSamples(1,40000);throw new AssertionError("pending range identity changed");}
            catch(IllegalStateException expected){checks++;}
            check(f.privacySignals.get()==1);
            f.failStage.set("");f.capture.deleteSamples(0,40000);
            check(f.privacySignals.get()==2&&f.queue.isDiscarding());
            check(count(f,"SELECT COUNT(*) FROM deletions WHERE session=? AND stream=?",f.session,originalStream)==1);
            check(count(f,"SELECT COUNT(*) FROM frames WHERE session=? AND stream=? AND deleted=1 AND wrapped IS NULL",f.session,originalStream)==1);
            check(f.store.state(f.session).equals("paused"));
            long resumed=f.capture.startBuffered(true);check(resumed!=f.token&&!f.store.stream(f.session).equals(originalStream));
            f.capture.pause();
        }
    }
    private static void rangeDeleteDespitePauseFailure(Context context)throws Exception {
        try(Fixture f=new Fixture(context,false)){
            committedFrame(f);String originalStream=f.store.stream(f.session);
            f.failStage.set("before_pause_barrier");
            try{f.capture.deleteSamples(0,40000);throw new AssertionError("queue pause failure lost");}
            catch(IOException expected){check(expected.getMessage().equals("synthetic before_pause_barrier"));}
            check(f.queue.isFinished()&&!f.capture.isActive()&&f.queue.outstandingSamples()==0);
            check(count(f,"SELECT COUNT(*) FROM deletions WHERE session=? AND stream=?",f.session,originalStream)==1);
            check(count(f,"SELECT COUNT(*) FROM frames WHERE session=? AND stream=? AND deleted=1 AND wrapped IS NULL",f.session,originalStream)==1);
            try{f.capture.startBuffered(true);throw new AssertionError("failed queue cleanup treated as complete");}
            catch(IllegalStateException expected){checks++;}
            f.failStage.set("");
            try{f.capture.deleteSamples(0,40000);throw new AssertionError("failed buffered owner retry claimed completion");}
            catch(IOException expected){check(expected.getMessage().equals("synthetic before_pause_barrier"));}
            check(count(f,"SELECT COUNT(*) FROM deletions WHERE session=? AND stream=?",f.session,originalStream)==1);
            try{f.capture.startBuffered(true);throw new AssertionError("cached buffered failure allowed restart");}
            catch(IllegalStateException expected){checks++;}
            f.store.recover();
            check(f.store.frameIds(f.session).isEmpty()&&f.store.state(f.session).equals("paused"));
            check(f.privacySignals.get()==2);
        }
    }

    private static void synchronousRangePauseRetry(Context base)throws Exception {
        for(String stage:new String[]{"before_pause_barrier","control_after_commit","after_pause_finish"}){
            File root=new File(base.getNoBackupFilesDir(),"sync-range-retry-"+UUID.randomUUID());
            if(!root.mkdir())throw new IOException("synchronous range retry fixture directory");
            Context context=new ContextWrapper(base){@Override public File getNoBackupFilesDir(){return root;}};
            AtomicReference<String> failed=new AtomicReference<>("");
            try(AudioSegmentStore store=new AudioSegmentStore(context,at->{if(at.equals(failed.get()))throw new IOException("synthetic "+at);})){
                String session=store.createSession(RATE);AudioCaptureCoordinator capture=new AudioCaptureCoordinator(store,session,RATE);
                long token=capture.start(true);String stream=store.stream(session);
                short[] full=new short[40000],tail=new short[CHUNK];Arrays.fill(full,(short)1234);Arrays.fill(tail,(short)4321);
                try{
                    check(capture.samples(token,full,full.length,SystemClock.elapsedRealtimeNanos()));
                    check(capture.samples(token,tail,tail.length,SystemClock.elapsedRealtimeNanos()));
                }finally{Arrays.fill(full,(short)0);Arrays.fill(tail,(short)0);}
                failed.set(stage);
                try{capture.deleteSamples(0,40000);throw new AssertionError("synchronous pause error lost");}
                catch(IOException expected){check(expected.getMessage().equals("synthetic "+stage));}
                check(!capture.isActive()&&store.frameIds(session).isEmpty());
                try{capture.start(true);throw new AssertionError("synchronous incomplete pause allowed restart");}
                catch(IllegalStateException expected){checks++;}
                try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(root,"audio-segments/index.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY);
                    Cursor c=db.rawQuery("SELECT COUNT(*) FROM deletions WHERE session=? AND stream=?",new String[]{session,stream})){
                    check(c.moveToFirst()&&c.getLong(0)==1);
                }
                failed.set("");capture.deleteSamples(0,40000);
                check(store.state(session).equals("paused")&&store.frameIds(session).isEmpty());
                try(SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(root,"audio-segments/index.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                    try(Cursor c=db.rawQuery("SELECT seal_generation,seal_end FROM sessions WHERE id=?",new String[]{session})){
                        check(c.moveToFirst()&&c.getLong(0)==-1&&c.getLong(1)==0);
                    }
                    try(Cursor c=db.rawQuery("SELECT kind,generation,accepted_end,observed_end FROM capture_controls WHERE session=? AND stream=?",new String[]{session,stream})){
                        check(c.moveToFirst()&&c.getString(0).equals("privacy")&&c.getLong(1)==token&&c.getLong(2)==40800&&c.getLong(3)==40800);
                        check(!c.moveToNext());
                    }
                    try(Cursor c=db.rawQuery("SELECT start_sample,end_sample,reason FROM gaps WHERE session=? AND stream=?",new String[]{session,stream})){
                        check(c.moveToFirst()&&c.getLong(0)==40000&&c.getLong(1)==40800&&c.getString(2).equals("uncommitted_tail"));
                        check(!c.moveToNext());
                    }
                    try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM deletions WHERE session=? AND stream=?",new String[]{session,stream})){
                        check(c.moveToFirst()&&c.getLong(0)==1);
                    }
                }
                long resumed=capture.start(true);check(resumed!=token&&!store.stream(session).equals(stream));capture.pause();
            }
        }
    }

}

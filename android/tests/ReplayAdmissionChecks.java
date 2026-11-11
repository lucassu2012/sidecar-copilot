package local.sidecar.copilot;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Existing privacy revocation: output admission and deletion have one order, synthetic output only. */
public final class ReplayAdmissionChecks {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError("replay admission "+checks);checks++;}
    public static int run(Context context)throws Exception {
        checks=0;NoteStore notes=new NoteStore(context);
        try(AudioSegmentStore audio=new AudioSegmentStore(context)){
            String id=new MeetingSessionRepository(context,audio).prepare(null,"replay race fixture",true,"fixture");
            try{
                short[] pcm=new short[80000];java.util.Arrays.fill(pcm,(short)71);
                AudioCaptureCoordinator recorder=new AudioCaptureCoordinator(audio,id,16000);long token=recorder.start(true);
                recorder.samples(token,pcm,pcm.length,SystemClock.elapsedRealtimeNanos());recorder.pause();String frame=audio.frameIds(id).get(0);
                java.lang.reflect.Field database=AudioSegmentStore.class.getDeclaredField("db");database.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)database.get(audio);
                // A controlled +1s anchor is within quantization tolerance, ahead of every retry below.
                db.execSQL("UPDATE clock SET wall=?,elapsed=? WHERE singleton=1",new Object[]{System.currentTimeMillis()+1000,SystemClock.elapsedRealtime()});
                long before;try(Cursor c=db.rawQuery("SELECT total_changes()",null)){c.moveToFirst();before=c.getLong(0);}
                for(int i=0;i<100;i++)audio.requireReplayable(frame);
                try(Cursor c=db.rawQuery("SELECT total_changes()",null)){c.moveToFirst();check(c.getLong(0)==before);}
                db.execSQL("UPDATE clock SET wall=? WHERE singleton=1",new Object[]{System.currentTimeMillis()+10000});
                try{audio.requireReplayable(frame);throw new AssertionError("rollback accepted");}catch(IllegalStateException expected){checks++;}
                db.execSQL("UPDATE clock SET wall=?,elapsed=? WHERE singleton=1",new Object[]{System.currentTimeMillis(),SystemClock.elapsedRealtime()});
                int boot;try(Cursor c=db.rawQuery("SELECT boot FROM clock",null)){c.moveToFirst();boot=c.getInt(0);}
                db.execSQL("UPDATE clock SET boot=boot-1");
                try{audio.requireReplayable(frame);throw new AssertionError("cross boot accepted");}catch(IllegalStateException expected){checks++;}
                db.execSQL("UPDATE clock SET boot=?",new Object[]{boot});
                long savedWall,savedElapsed;
                try(Cursor c=db.rawQuery("SELECT wall,elapsed FROM clock WHERE singleton=1",null)){c.moveToFirst();savedWall=c.getLong(0);savedElapsed=c.getLong(1);}
                long oldAnchor=System.currentTimeMillis()-10000,oldElapsed=SystemClock.elapsedRealtime();
                db.execSQL("UPDATE clock SET wall=?,elapsed=? WHERE singleton=1",new Object[]{oldAnchor,oldElapsed});
                // A large forward wall disagreement is uncertain, not permission to advance TTL.
                try{audio.requireReplayable(frame);throw new AssertionError("forward disagreement accepted");}catch(IllegalStateException expected){checks++;}
                try(Cursor c=db.rawQuery("SELECT wall,elapsed,boot FROM clock WHERE singleton=1",null)){
                    c.moveToFirst();check(c.getLong(0)==oldAnchor&&c.getLong(1)==oldElapsed&&c.getInt(2)==boot);
                }
                db.execSQL("UPDATE clock SET wall=?,elapsed=? WHERE singleton=1",new Object[]{savedWall,savedElapsed});
                audio.requireReplayable(frame);checks++; // Restoring only the test fixture re-enables the original replay path.
                AtomicInteger attempts=new AtomicInteger(),written=new AtomicInteger(),stops=new AtomicInteger();
                short[][] borrowed={null};
                AudioFrameReplay replay=new AudioFrameReplay(new AudioFrameReplay.Output(){
                    public void start(int rate){}
                    public int write(short[] data,int offset,int count){
                        borrowed[0]=data;if(attempts.getAndIncrement()<2)return 0;
                        int accepted=Math.min(731,count);written.addAndGet(accepted);return accepted;
                    }
                    public void stop(){stops.incrementAndGet();}
                });
                replay.play(audio,frame,16000);
                check(written.get()==80000);check(attempts.get()>2);check(stops.get()>0);check(borrowed[0][0]==0&&borrowed[0][79999]==0);
                CountDownLatch admitted=new CountDownLatch(1),release=new CountDownLatch(1),deleting=new CountDownLatch(1);
                AtomicBoolean deleted=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();AtomicInteger outputs=new AtomicInteger();
                // Deliberately stalled fake output exposes the precise check/write window; no device speaker.
                AudioFrameReplay.Output output=new AudioFrameReplay.Output(){
                    public void start(int rate){}
                    public int write(short[] data,int offset,int count)throws Exception {
                        admitted.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("release timeout");outputs.incrementAndGet();return count;
                    }
                    public void stop(){}
                };
                Thread writer=new Thread(()->{try{audio.writeReplayChunk(frame,output,pcm,0,1600);}catch(Throwable e){failure.set(e);}});
                Thread remover=new Thread(()->{deleting.countDown();try{notes.delete(id);deleted.set(true);}catch(Throwable e){failure.set(e);}});
                try{
                    writer.start();check(admitted.await(3,TimeUnit.SECONDS));remover.start();check(deleting.await(1,TimeUnit.SECONDS));SystemClock.sleep(100);check(!deleted.get());
                }finally{release.countDown();writer.join(5000);if(remover.getState()!=Thread.State.NEW)remover.join(5000);}
                check(!writer.isAlive()&&!remover.isAlive());check(failure.get()==null&&deleted.get());check(outputs.get()==1);
                try{audio.writeReplayChunk(frame,output,pcm,1600,1600);throw new AssertionError("output after deletion");}catch(SecurityException expected){checks++;}
                check(outputs.get()==1);audio.purgeDeleted(id);
            }finally{notes.delete(id);}
        }
        return checks;
    }
}

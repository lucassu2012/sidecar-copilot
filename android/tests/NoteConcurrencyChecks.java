package local.sidecar.copilot;

import android.content.Context;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Existing revision-conflict/deletion semantics, deterministic two-instance contention. */
public final class NoteConcurrencyChecks {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError("note concurrency "+checks);checks++;}
    public static int run(Context context)throws Exception {
        checks=0;CountDownLatch checked=new CountDownLatch(1),release=new CountDownLatch(1),secondStarted=new CountDownLatch(1),secondRead=new CountDownLatch(1);
        NoteStore first=new NoteStore(context,()->{checked.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");});
        NoteStore second=new NoteStore(context,secondRead::countDown);
        String id=first.create("concurrent fixture",true);AtomicInteger saved=new AtomicInteger(),conflicts=new AtomicInteger();AtomicReference<Throwable> unexpected=new AtomicReference<>();
        Thread one=new Thread(()->{try{first.append(id,0,"first winner","unassigned");saved.incrementAndGet();}catch(Throwable e){unexpected.set(e);}});
        Thread two=new Thread(()->{secondStarted.countDown();try{second.append(id,0,"second stale","unassigned");saved.incrementAndGet();}catch(IllegalStateException conflict){conflicts.incrementAndGet();}catch(Throwable e){unexpected.set(e);}});
        try{
            one.start();check(checked.await(3,TimeUnit.SECONDS));two.start();check(secondStarted.await(1,TimeUnit.SECONDS));SystemClock.sleep(100);
            check(secondRead.getCount()==1); // No second read-modify-write may enter the shared critical section.
        }finally{release.countDown();one.join(5000);if(two.getState()!=Thread.State.NEW)two.join(5000);}
        try{
            check(!one.isAlive()&&!two.isAlive());check(unexpected.get()==null);check(saved.get()==1&&conflicts.get()==1);
            JSONObject current=second.read(id);check(current.getLong("revision")==1);check(current.getJSONArray("attachments").length()==1);
            check(current.getJSONArray("attachments").getJSONObject(0).getString("text").equals("first winner"));
            second.append(id,1,"explicit retry","unassigned");check(first.read(id).getLong("revision")==2);
            second.delete(id);try{first.append(id,2,"resurrect","unassigned");throw new AssertionError("deleted write");}catch(SecurityException expected){checks++;}
        }finally{first.delete(id);}
        return checks;
    }
}

package local.sidecar.copilot;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** One synthetic interruption attempt, with independent original 2 s / 8 s deadlines. */
public final class InterruptionDiagnosticInstrumentation extends Instrumentation {
    private static final long WAIT_MS=8000;
    private static final String TITLE="SYNTHETIC CONTROL PRIVATE TITLE";
    private final AtomicReference<String> started=new AtomicReference<>();
    private final StringBuilder report=new StringBuilder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Context context;
    private FixtureCaptureService.Probe probe;
    private FixtureCaptureService capturedService;
    private RecordingActivity page;
    private Activity home;
    private String meeting,stage="setup",lastState="";
    private long origin;
    private int checks;
    private boolean startRequested;

    private interface Condition { boolean ready(State state)throws Exception; }
    private static final class State {
        final JSONObject details=new JSONObject();
        boolean serviceStopped,pageLoaded,pagePaused,pageRecording;
        Notification notice;
    }
    private static Object field(Object target,Class<?> owner,String name)throws Exception {
        Field field=owner.getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static boolean shows(View view,String value){
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(value))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)
            if(shows(((ViewGroup)view).getChildAt(i),value))return true;
        return false;
    }
    private <T>T onMain(Callable<T> work,long timeout)throws Exception {
        AtomicReference<T> answer=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        CountDownLatch complete=new CountDownLatch(1);
        main.post(()->{try{answer.set(work.call());}catch(Throwable failure){error.set(failure);}finally{complete.countDown();}});
        if(timeout<=0||!complete.await(timeout,TimeUnit.MILLISECONDS))throw new AssertionError(stage+": main-thread inspection timed out");
        Throwable failure=error.get();if(failure instanceof Error)throw (Error)failure;
        if(failure instanceof Exception)throw (Exception)failure;
        return answer.get();
    }
    private void event(String value){
        String line="t+"+(SystemClock.elapsedRealtime()-origin)+"ms "+value+"\n";
        report.append(line);Bundle status=new Bundle();status.putString("stream",line);sendStatus(1,status);
    }
    private void check(boolean value,String label){
        if(!value)throw new AssertionError(stage+": "+label);checks++;event("PASS "+label);
    }
    private Notification notice(){
        for(StatusBarNotification current:context.getSystemService(NotificationManager.class).getActiveNotifications())
            if(current.getId()==CaptureService.NOTIFICATION_ID)return current.getNotification();
        return null;
    }
    private State state(long timeout)throws Exception {
        return onMain(()->{
            State result=new State();String id=meeting!=null?meeting:started.get();
            CaptureService.Snapshot snapshot=id==null?null:CaptureService.snapshot(id);
            FixtureCaptureService current=FixtureCaptureService.instance;
            result.serviceStopped=current==null&&snapshot==null;
            result.details.put("broadcast",started.get()!=null).put("instance",current==null?0:current.noticeIdentity)
                .put("captured_instance",capturedService==null?0:capturedService.noticeIdentity)
                .put("snapshot",snapshot!=null).put("stopping",snapshot!=null&&snapshot.stopping)
                .put("samples",snapshot==null?-1:snapshot.samples);
            FixtureCaptureService owner=capturedService!=null?capturedService:current;
            if(owner!=null){
                for(String name:new String[]{"stopping","finished","destroyed","interrupted"})
                    result.details.put("service_"+name,field(owner,CaptureService.class,name));
                for(String name:new String[]{"storageWorker","controlWorker"}){
                    ExecutorService worker=(ExecutorService)field(owner,CaptureService.class,name);
                    result.details.put(name+"_shutdown",worker.isShutdown()).put(name+"_terminated",worker.isTerminated());
                }
            }
            if(probe!=null){
                BoundedPcmQueue queue=probe.coordinator==null?null:probe.queue();Throwable failure=probe.openFailure;
                result.details.put("input_stopped",probe.inputStopped.getCount()==0)
                    .put("destroyed",probe.destroyed.getCount()==0).put("open_stage",probe.openStage)
                    .put("open_failure",failure==null?"none":failure.getClass().getName()+": "+failure.getMessage())
                    .put("start_calls",probe.startCalls.get()).put("source_starts",probe.sourceStarts.get())
                    .put("sample_calls",probe.sampleCalls.get()).put("queue",queue!=null)
                    .put("queue_finished",queue!=null&&queue.isFinished())
                    .put("queue_outstanding",queue==null?0:queue.outstandingSamples());
            }
            if(page!=null){
                View root=page.getWindow().getDecorView();
                boolean loading=(Boolean)field(page,RecordingActivity.class,"loading");
                result.pageLoaded=shows(root,"\u4fdd\u5b58\u7b14\u8bb0");
                result.pagePaused=shows(root,"\u5df2\u6682\u505c");
                result.pageRecording=shows(root,"\u5f55\u5236\u4e2d");
                result.details.put("page_destroyed",page.isDestroyed())
                    .put("page_visible",field(page,RecordingActivity.class,"visible"))
                    .put("page_loading",loading).put("page_showing_live",field(page,RecordingActivity.class,"showingLive"))
                    .put("page_epoch",field(page,RecordingActivity.class,"epoch"))
                    .put("page_loaded",result.pageLoaded).put("page_paused",result.pagePaused)
                    .put("page_recording",result.pageRecording);
                for(Field f:RecordingActivity.class.getDeclaredFields())if(ExecutorService.class.isAssignableFrom(f.getType())){
                    f.setAccessible(true);ExecutorService worker=(ExecutorService)f.get(page);
                    result.details.put(f.getName()+"_shutdown",worker!=null&&worker.isShutdown())
                        .put(f.getName()+"_terminated",worker!=null&&worker.isTerminated());
                }
            }
            result.notice=notice();Notification n=result.notice;
            result.details.put("notice",n!=null).put("notice_title",n==null?"":n.extras.getString(Notification.EXTRA_TITLE,""))
                .put("notice_flags",n==null?0:n.flags).put("notice_actions",n==null||n.actions==null?0:n.actions.length);
            return result;
        },timeout);
    }
    private void recordState(State current){
        String text=current.details.toString();if(!text.equals(lastState)){lastState=text;event("STATE "+stage+" "+text);}
    }
    private State await(String name,Condition condition)throws Exception {
        stage=name;event("BEGIN "+name+" timeout_ms="+WAIT_MS);long until=SystemClock.elapsedRealtime()+WAIT_MS;
        while(true){
            long remaining=until-SystemClock.elapsedRealtime();
            if(remaining<=0)throw new AssertionError(name+": timeout after "+WAIT_MS+"ms; last="+lastState);
            State current=state(remaining);recordState(current);
            if(condition.ready(current)){event("DONE "+name);return current;}
            remaining=until-SystemClock.elapsedRealtime();if(remaining>0)Thread.sleep(Math.min(40,remaining));
        }
    }
    private void runAttempt()throws Exception {
        check(Build.HARDWARE.equals("ranchu")&&context.getPackageName().equals("local.sidecar.copilot.fixture"),"isolated emulator fixture");
        check(!CaptureEnvironment.supported(),"real-device capture gate remains closed");
        check(CaptureEnvironment.unlocked(context),"credential authenticated and unlocked");
        check(FixtureCaptureService.instance==null,"no prior fixture service");
        getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.RECORD_AUDIO);
        getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
        CaptureEnvironment.channel(context);
        home=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);
        started.set(null);String token=CaptureService.START_GATE.issue(true,true,true,true,CaptureEnvironment.unlocked(context),true,SystemClock.elapsedRealtime());
        AudioDeviceInfo input=CaptureEnvironment.input(context);check(input!=null,"synthetic fixture input descriptor available");
        stage="start_once";event("BEGIN start_once");
        startRequested=true;
        onMain(()->{context.startForegroundService(new Intent(context,FixtureCaptureService.class).setAction(CaptureService.START)
            .putExtra("token",token).putExtra("input",input.getId()).putExtra("title",TITLE));return null;},WAIT_MS);
        await("start_broadcast",s->started.get()!=null);meeting=started.get();capturedService=FixtureCaptureService.instance;
        await("positive_samples",s->s.details.getLong("samples")>0);
        await("foreground_notice",s->s.notice!=null&&(s.notice.flags&Notification.FLAG_FOREGROUND_SERVICE)!=0);
        page=(RecordingActivity)startActivitySync(new Intent(context,RecordingActivity.class).putExtra("meeting",meeting).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await("page_loaded",s->s.pageLoaded);
        // The same source Failure callback, after page load, as the task-5 control fixture.
        stage="input_stopped";event("BEGIN input_stopped timeout_ms=2000");
        PcmSource source=(PcmSource)field(capturedService,CaptureService.class,"source");
        PcmSource.Failure callback=(PcmSource.Failure)field(source,source.getClass(),"failure");
        callback.interrupted("synthetic OS input interruption");
        check(probe.inputStopped.await(2,TimeUnit.SECONDS),"injected interruption stops source");event("DONE input_stopped");
        await("service_stopped",s->s.serviceStopped);
        await("page_paused",s->s.pagePaused);
        State warned=await("warning_present",s->s.notice!=null);
        check(warned.notice.extras.getString(Notification.EXTRA_TITLE,"").contains("interrupted"),"warning identifies interruption");
        check((warned.notice.flags&Notification.FLAG_FOREGROUND_SERVICE)==0,"warning is not active foreground recording");
        check(!warned.pageRecording,"page no longer claims recording");
        check(!warned.notice.extras.toString().contains(TITLE),"warning excludes private meeting title");
        check(probe.sourceStarts.get()==1,"one source start without retry");
        check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty(),"no real AudioRecord configuration");
    }
    /** Destruction and executor termination must both finish before cleanup touches notes. */
    private void finishAndDrain(Activity activity)throws Exception {
        if(activity==null)return;long until=SystemClock.elapsedRealtime()+WAIT_MS;
        onMain(()->{if(!activity.isDestroyed())activity.finish();return null;},WAIT_MS);
        while(true){
            long remaining=until-SystemClock.elapsedRealtime();
            if(remaining<=0)throw new AssertionError("cleanup Activity destruction timed out");
            if(onMain(activity::isDestroyed,remaining))break;Thread.sleep(25);
        }
        for(Field f:activity.getClass().getDeclaredFields())if(ExecutorService.class.isAssignableFrom(f.getType())){
            f.setAccessible(true);ExecutorService worker=(ExecutorService)f.get(activity);
            if(worker==null||!worker.isShutdown())throw new AssertionError("cleanup worker not shut down: "+f.getName());
            long remaining=until-SystemClock.elapsedRealtime();
            if(remaining<=0||!worker.awaitTermination(remaining,TimeUnit.MILLISECONDS))throw new AssertionError("cleanup worker still owns storage: "+f.getName());
        }
    }
    private void saveBeforeCleanup()throws Exception {
        event("BEFORE_CLEANUP stage="+stage);
        try{recordState(state(WAIT_MS));}catch(Throwable failure){event("STATE_UNAVAILABLE "+android.util.Log.getStackTraceString(failure));}
        File file=new File(context.getFilesDir(),"interruption-diagnostic-"+origin+".txt");
        event("DIAGNOSTIC_FILE "+file.getAbsolutePath());
        try(FileOutputStream output=new FileOutputStream(file)){
            output.write(report.toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();
        }
    }
    private Throwable cleanup(Throwable failure){
        // Evidence has already been emitted and saved. Never open AudioSegmentStore in this runner.
        try{
            if(startRequested&&meeting==null)meeting=started.get();
            if(startRequested&&meeting!=null)CaptureService.control(meeting,false);
            else if(startRequested&&probe!=null&&FixtureCaptureService.lastProbe==probe)onMain(()->{
                FixtureCaptureService service=FixtureCaptureService.instance;
                if(service!=null)service.onTaskRemoved(new Intent());return null;
            },WAIT_MS);
        }catch(Throwable error){failure=cleanupFailure(failure,error);}
        boolean drained=false;
        try{finishAndDrain(page);drained=true;}catch(Throwable error){failure=cleanupFailure(failure,error);}
        boolean stopped=false;
        try{
            if(startRequested)await("cleanup_service_stopped",s->s.serviceStopped);
            stopped=true;
        }catch(Throwable error){failure=cleanupFailure(failure,error);}
        if(stopped&&drained&&startRequested&&meeting!=null)try{new NoteStore(context).delete(meeting);}catch(Throwable error){failure=cleanupFailure(failure,error);}
        if(stopped&&startRequested)try{context.getSystemService(NotificationManager.class).cancel(CaptureService.NOTIFICATION_ID);}catch(Throwable error){failure=cleanupFailure(failure,error);}
        try{finishAndDrain(home);}catch(Throwable error){failure=cleanupFailure(failure,error);}
        return failure;
    }
    private Throwable cleanupFailure(Throwable original,Throwable error){
        event("CLEANUP_ERROR "+android.util.Log.getStackTraceString(error));
        if(original==null)return error;if(original!=error)original.addSuppressed(error);return original;
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        origin=SystemClock.elapsedRealtime();context=getTargetContext();Throwable failure=null;BroadcastReceiver receiver=null;
        try{
            event("ONE_ATTEMPT synthetic_only=true no_start_retry=true");
            receiver=new BroadcastReceiver(){@Override public void onReceive(Context ignored,Intent intent){started.set(intent.getStringExtra("meeting"));}};
            context.registerReceiver(receiver,new IntentFilter("local.sidecar.CAPTURE_STARTED"),Context.RECEIVER_NOT_EXPORTED);
            runAttempt();event("DIAGNOSTIC_PASS checks="+checks);
        }catch(Throwable error){failure=error;event("FAILED stage="+stage+" "+android.util.Log.getStackTraceString(error));}
        finally{
            try{saveBeforeCleanup();}catch(Throwable error){failure=cleanupFailure(failure,error);}
            failure=cleanup(failure);
            if(receiver!=null)try{context.unregisterReceiver(receiver);}catch(Throwable error){failure=cleanupFailure(failure,error);}
        }
        Bundle result=new Bundle();result.putString("stream",report.toString()+"RESULT "+(failure==null?"PASS":"FAIL")+"; "+checks+" checks; one synthetic attempt\n");
        finish(failure==null?-1:0,result);
    }
}

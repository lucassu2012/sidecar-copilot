package local.sidecar.copilot;

import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.*;
import android.widget.TextView;
import java.util.concurrent.*;
import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Field;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Positive OS microphone-FGS lifecycle with synthetic PCM, never an AudioRecord microphone. */
public final class FixtureInstrumentation extends Instrumentation {
    private int checks;
    private Context context;
    private final AtomicReference<String> started=new AtomicReference<>();
    private long maximumStopResponseMs;
    private final List<Activity> ownedActivities=new ArrayList<>();
    private int heldStoreChecks;
    private String lastPageOwnership="not_inspected";
    private <T extends Activity> T own(T activity){ownedActivities.add(activity);return activity;}
    private static ExecutorService storage(Activity activity)throws Exception {
        Field field=activity.getClass().getDeclaredField("storage");field.setAccessible(true);return (ExecutorService)field.get(activity);
    }
    private String pageOwnership(Activity... activities){
        StringBuilder result=new StringBuilder();
        runOnMainSync(()->{for(Activity activity:activities){if(activity==null)continue;
            result.append(activity.getClass().getSimpleName()).append(" destroyed=").append(activity.isDestroyed());
            try{for(Field field:activity.getClass().getDeclaredFields()){
                if(!ExecutorService.class.isAssignableFrom(field.getType()))continue;
                field.setAccessible(true);ExecutorService worker=(ExecutorService)field.get(activity);
                result.append(" ").append(field.getName()).append(" shutdown=").append(worker!=null&&worker.isShutdown())
                    .append(" terminated=").append(worker!=null&&worker.isTerminated());
            }}catch(Exception failure){result.append(" inspection=").append(failure.getClass().getSimpleName());}
            result.append("; ");
        }});return result.toString();
    }
    /** finish() and main-loop idleness do not join page workers or release their storage ownership. */
    private void finishAndDrain(Activity... activities)throws Exception {
        runOnMainSync(()->{for(Activity activity:activities)if(activity!=null&&!activity.isDestroyed())activity.finish();});
        long deadline=SystemClock.elapsedRealtime()+8000;Handler main=new Handler(Looper.getMainLooper());boolean destroyed=false;
        lastPageOwnership=pageOwnership(activities);
        while(!destroyed&&SystemClock.elapsedRealtime()<deadline){
            AtomicBoolean allDestroyed=new AtomicBoolean(true);CountDownLatch inspected=new CountDownLatch(1);
            main.post(()->{try{for(Activity activity:activities)if(activity!=null&&!activity.isDestroyed())allDestroyed.set(false);}finally{inspected.countDown();}});
            long remaining=deadline-SystemClock.elapsedRealtime();
            if(remaining<=0||!inspected.await(remaining,TimeUnit.MILLISECONDS))throw new AssertionError("FGS teardown destruction inspection timed out");
            destroyed=allDestroyed.get();if(!destroyed)Thread.sleep(25);
        }
        if(!destroyed)throw new AssertionError("FGS teardown Activity destruction timed out");
        for(Activity activity:activities){if(activity==null)continue;
            for(Field field:activity.getClass().getDeclaredFields()){
                if(!ExecutorService.class.isAssignableFrom(field.getType()))continue;
                field.setAccessible(true);ExecutorService worker=(ExecutorService)field.get(activity);
                if(worker==null||!worker.isShutdown())throw new AssertionError("FGS teardown worker not shut down: "+field.getName());
                long remaining=deadline-SystemClock.elapsedRealtime();
                if(remaining<=0||!worker.awaitTermination(remaining,TimeUnit.MILLISECONDS))throw new AssertionError("FGS teardown worker still owns storage: "+field.getName());
            }
        }
        lastPageOwnership=pageOwnership(activities);
    }
    private String setupDiagnostic(){
        FixtureCaptureService.Probe probe=FixtureCaptureService.lastProbe;
        if(probe==null)return "no service probe";Throwable failure=probe.openFailure;
        return "openStage="+probe.openStage+", openAttemptNs="+probe.openAttemptNs+", openFailureNs="+probe.openFailureNs
            +", openFailure="+(failure==null?"none":failure.getClass().getName()+": "+failure.getMessage())
            +", openAt="+(failure==null||failure.getStackTrace().length==0?"none":failure.getStackTrace()[0])
            +", sourceStartCalls="+probe.startCalls.get()+", sourceStarts="+probe.sourceStarts.get();
    }
    private void check(boolean value){if(!value)throw new AssertionError("fixture check "+checks);checks++;}
    private void await(BooleanSupplier condition)throws Exception {long deadline=SystemClock.elapsedRealtime()+8000;while(!condition.getAsBoolean()){if(SystemClock.elapsedRealtime()>deadline)throw new AssertionError("fixture timeout "+checks);Thread.sleep(50);}checks++;}
    private void requestStart(String existing)throws Exception {
        await(()->FixtureCaptureService.instance==null);
        started.set(null);String token=CaptureService.START_GATE.issue(true,true,true,true,CaptureEnvironment.unlocked(context),true,SystemClock.elapsedRealtime());
        AudioDeviceInfo input=CaptureEnvironment.input(context);check(input!=null);
        runOnMainSync(()->context.startForegroundService(new Intent(context,FixtureCaptureService.class).setAction(CaptureService.START)
            .putExtra("token",token).putExtra("input",input.getId()).putExtra("meeting",existing).putExtra("title","SYNTHETIC FGS MEETING")));
    }
    private String start(String existing)throws Exception {
        requestStart(existing);await(()->started.get()!=null);String id=started.get();await(()->CaptureService.snapshot(id)!=null&&CaptureService.snapshot(id).samples>0);await(this::foregroundNotificationReady);return id;
    }
    private void heartbeat()throws Exception {
        CountDownLatch ran=new CountDownLatch(1);new Handler(Looper.getMainLooper()).post(ran::countDown);
        check(ran.await(2,TimeUnit.SECONDS));
    }
    private void awaitText(Activity activity,String value)throws Exception {
        AtomicBoolean found=new AtomicBoolean();
        await(()->{runOnMainSync(()->found.set(text(activity.getWindow().getDecorView(),value)));return found.get();});
    }
    private void preparationCancellation()throws Exception {
        await(()->FixtureCaptureService.instance==null);
        FixtureCaptureService.Probe probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.BLOCK_OPEN);
        try{
            requestStart(null);check(probe.entered.await(8,TimeUnit.SECONDS));heartbeat();
            await(this::foregroundNotificationReady);Notification preparing=notification();check((preparing.flags&Notification.FLAG_FOREGROUND_SERVICE)!=0);
            preparing.actions[0].actionIntent.send();
            await(()->notification().extras.getString(Notification.EXTRA_TITLE).contains("stopping"));
            heartbeat();check(probe.sourceStarts.get()==0);check(started.get()==null);
            check((notification().flags&Notification.FLAG_FOREGROUND_SERVICE)!=0);
        }finally{probe.release.countDown();}
        check(probe.destroyed.await(8,TimeUnit.SECONDS));check(probe.sourceStarts.get()==0);check(started.get()==null);
        FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);
    }
    private void sourceFactoryCancellation()throws Exception {
        await(()->FixtureCaptureService.instance==null);
        String id;try(AudioSegmentStore store=new AudioSegmentStore(context)){
            id=new MeetingSessionRepository(context,store).prepare(null,"SOURCE FACTORY CANCELLATION",true,"synthetic");
        }
        FixtureCaptureService.Probe probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.BLOCK_SOURCE_FACTORY);
        CountDownLatch mainEntered=new CountDownLatch(1),mainRelease=new CountDownLatch(1),mainExited=new CountDownLatch(1);
        AtomicReference<Throwable> mainFailure=new AtomicReference<>();
        try{
            requestStart(id);check(probe.entered.await(8,TimeUnit.SECONDS));await(this::foregroundNotificationReady);
            new Handler(Looper.getMainLooper()).post(()->{
                mainEntered.countDown();
                try{if(!mainRelease.await(4,TimeUnit.SECONDS))mainFailure.set(new AssertionError("main fixture release timed out"));}
                catch(InterruptedException failure){Thread.currentThread().interrupt();mainFailure.set(failure);}
                finally{mainExited.countDown();}
            });
            try{
                check(mainEntered.await(2,TimeUnit.SECONDS));
                // shutdown() is queued on main and cannot yet run. The direct stop intent must survive source publication.
                CaptureService.requestPrivacyStop(id);check(mainExited.getCount()==1);
                probe.release.countDown();check(probe.sourceSignalled.await(2,TimeUnit.SECONDS));
                check(mainExited.getCount()==1&&mainFailure.get()==null);
                check(probe.startCalls.get()==0&&probe.sourceStarts.get()==0&&probe.sampleCalls.get()==0);
                check(probe.destroyed.getCount()==1);check(started.get()==null);
            }finally{probe.release.countDown();mainRelease.countDown();}
            check(mainExited.await(2,TimeUnit.SECONDS)&&mainFailure.get()==null);heartbeat();
            check(probe.destroyed.await(8,TimeUnit.SECONDS));
            check(probe.inputStopped.getCount()==0&&probe.startCalls.get()==0);
            check(CaptureService.snapshot(id)==null&&FixtureCaptureService.instance==null);
            try(AudioSegmentStore store=new AudioSegmentStore(context)){
                check(!store.state(id).equals("recording"));check(store.committedSamples(id)==0&&store.frameIds(id).isEmpty());
            }
            Thread.sleep(150);check(probe.startCalls.get()==0&&started.get()==null);
            check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty());
        }finally{
            probe.release.countDown();mainRelease.countDown();
            probe.destroyed.await(8,TimeUnit.SECONDS);new NoteStore(context).delete(id);FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);
        }
    }
    private void blockedWriteStop(boolean end)throws Exception {
        await(()->FixtureCaptureService.instance==null);
        FixtureCaptureService.Probe probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.BLOCK_APPEND);String id=null;
        try{
            id=start(null);String meeting=id;check(probe.entered.await(8,TimeUnit.SECONDS));heartbeat();
            long requested=SystemClock.elapsedRealtimeNanos();
            notification().actions[end?1:0].actionIntent.send();
            check(probe.inputStopped.await(2,TimeUnit.SECONDS));
            int admittedCalls=probe.sampleCalls.get();long acceptedPrefix=probe.admittedSamples();
            long delay=(probe.stoppedNanos.get()-requested)/1_000_000L;maximumStopResponseMs=Math.max(maximumStopResponseMs,delay);check(delay>=0&&delay<2000);
            await(()->CaptureService.snapshot(meeting)!=null&&CaptureService.snapshot(meeting).stopping);
            await(()->{Notification notice=currentNotification();return notice!=null&&"Sidecar stopping and sealing".equals(notice.extras.getString(Notification.EXTRA_TITLE));});
            Notification stoppingNotice=notification();check(stoppingNotice.actions==null||stoppingNotice.actions.length==0);
            heartbeat();check((notification().flags&Notification.FLAG_FOREGROUND_SERVICE)!=0);
            check(probe.sampleCalls.get()==admittedCalls);check(probe.destroyed.getCount()==1);
            long released=SystemClock.elapsedRealtimeNanos();probe.release.countDown();await(()->CaptureService.snapshot(meeting)==null);check(probe.destroyed.await(8,TimeUnit.SECONDS));
            check(probe.sampleCalls.get()==admittedCalls);check(probe.lateSampleRejected());
            try(AudioSegmentStore store=new AudioSegmentStore(context)){
                check(store.state(id).equals(end?"ended":"paused"));check(acceptedPrefix>=80000&&store.committedSamples(id)==acceptedPrefix);check(store.frameIds(id).size()==(acceptedPrefix+79999)/80000);
                if(end){long frozen=store.endMetadata(id).getLong("control_ns");check(frozen>=requested&&frozen<released);}
            }
            Thread.sleep(150);check(probe.sourceStarts.get()==1);check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty());
        }finally{probe.release.countDown();if(id!=null)new NoteStore(context).delete(id);FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);}
    }
    private void failedWriteStopsBeforeRecovery()throws Exception {
        await(()->FixtureCaptureService.instance==null);
        FixtureCaptureService.Probe probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.FAIL_APPEND_BLOCK_RECOVERY);String id=null;
        try{
            id=start(null);String meeting=id;check(probe.entered.await(8,TimeUnit.SECONDS));
            // The recovery hook is blocked: the source stop must already have happened.
            check(probe.faultThrown.get());check(probe.inputStopped.getCount()==0);int admittedCalls=probe.sampleCalls.get();
            await(()->CaptureService.snapshot(meeting)!=null&&CaptureService.snapshot(meeting).stopping);
            heartbeat();check((notification().flags&Notification.FLAG_FOREGROUND_SERVICE)!=0);check(probe.destroyed.getCount()==1);
            probe.release.countDown();await(()->CaptureService.snapshot(meeting)==null);check(probe.destroyed.await(8,TimeUnit.SECONDS));
            check(probe.lateSampleRejected());check(probe.sampleCalls.get()==admittedCalls);
            try(AudioSegmentStore store=new AudioSegmentStore(context)){
                check(store.state(id).equals("paused"));check(store.committedSamples(id)==0);check(store.frameIds(id).isEmpty());
            }
            check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty());
        }finally{probe.release.countDown();if(id!=null)new NoteStore(context).delete(id);FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);}
    }
    private void stopNotificationFailure()throws Exception {
        await(()->FixtureCaptureService.instance==null);
        FixtureCaptureService.Probe probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.STOP_NOTICE_FAILURE);String id=null;
        try{
            id=start(null);String meeting=id;check(probe.notificationFailures.get()==0);
            notification().actions[0].actionIntent.send();check(probe.inputStopped.await(2,TimeUnit.SECONDS));heartbeat();
            await(()->CaptureService.snapshot(meeting)==null);check(probe.destroyed.await(8,TimeUnit.SECONDS));
            check(probe.notificationFailures.get()==1);check(probe.lateSampleRejected());
            try(AudioSegmentStore store=new AudioSegmentStore(context)){
                check(store.state(id).equals("paused"));check(store.committedSamples(id)>0);check(!store.frameIds(id).isEmpty());
            }
            Thread.sleep(150);check(probe.sourceStarts.get()==1);check(FixtureCaptureService.instance==null);
            check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty());
        }finally{if(id!=null)new NoteStore(context).delete(id);FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);}
    }
    private void privacyDeleteStopsBeforeDisk()throws Exception {
        await(()->FixtureCaptureService.instance==null);
        NoteStore notes=new NoteStore(context);
        FixtureCaptureService.Probe probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.BLOCK_APPEND);String id=null;Thread remover=null;
        AtomicBoolean complete=new AtomicBoolean();AtomicReference<Throwable> failure=new AtomicReference<>();CountDownLatch deleting=new CountDownLatch(1);
        try{
            id=start(null);String meeting=id;check(probe.entered.await(8,TimeUnit.SECONDS));
            await(()->probe.outstandingSamples()>=6400);check(probe.outstandingSamples()>1600);
            remover=new Thread(()->{deleting.countDown();try{notes.delete(meeting);complete.set(true);}catch(Throwable error){failure.set(error);}},"Sidecar-fixture-delete");
            remover.start();check(deleting.await(1,TimeUnit.SECONDS));check(probe.inputStopped.await(2,TimeUnit.SECONDS));
            int admittedCalls=probe.sampleCalls.get();
            // The writer still owns one callback; the other queued arrays must be discarded now.
            await(()->probe.outstandingSamples()==1600);check(probe.release.getCount()==1);
            await(()->CaptureService.snapshot(meeting)!=null&&CaptureService.snapshot(meeting).stopping);
            heartbeat();check(remover.isAlive()&&!complete.get());check(failure.get()==null);
            check(!context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).contains(id));
            check((notification().flags&Notification.FLAG_FOREGROUND_SERVICE)!=0);check(probe.sampleCalls.get()==admittedCalls);
            probe.release.countDown();remover.join(8000);check(!remover.isAlive()&&complete.get()&&failure.get()==null);
            await(()->CaptureService.snapshot(meeting)==null);check(probe.destroyed.await(8,TimeUnit.SECONDS));
            check(probe.lateSampleRejected());check(probe.sampleCalls.get()==admittedCalls);
            check(context.getSharedPreferences("deletion-barriers",Context.MODE_PRIVATE).contains(id));
            try{notes.read(id);throw new AssertionError("deleted meeting readable");}catch(SecurityException expected){checks++;}
            try(AudioSegmentStore store=new AudioSegmentStore(context)){
                try{store.frameIds(id);throw new AssertionError("deleted audio visible");}catch(SecurityException expected){checks++;}
                try{store.start(id,true);throw new AssertionError("deleted session resumed");}catch(SecurityException expected){checks++;}
            }
            java.security.KeyStore keys=java.security.KeyStore.getInstance("AndroidKeyStore");keys.load(null);check(!keys.containsAlias("sidecar.audio."+id));
            check(probe.sourceStarts.get()==1);check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty());
        }finally{probe.release.countDown();if(remover!=null)remover.join(8000);if(id!=null)notes.delete(id);FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);}
    }
    /** Deliberate lock contention proves the old teardown mechanism, not the cause of any earlier timeout. */
    private void heldStoreAfterLegacyFinish()throws Exception {
        String id;try(AudioSegmentStore audio=new AudioSegmentStore(context)){
            id=new MeetingSessionRepository(context,audio).prepare(null,"SYNTHETIC PAGE STORE OWNER",true,"synthetic");
        }
        Activity page=null;Future<?> holder=null;
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure=new AtomicReference<>();
        try{
            page=own(startActivitySync(new Intent(context,RecordingActivity.class).putExtra("meeting",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            awaitText(page,"保存笔记");ExecutorService pageWorker=storage(page);
            holder=pageWorker.submit(()->{
                try(AudioSegmentStore owned=new AudioSegmentStore(context)){
                    held.countDown();if(!release.await(30,TimeUnit.SECONDS))throw new AssertionError("held-store fixture release timed out");
                }catch(Throwable failure){holderFailure.set(failure);}
            });
            check(held.await(8,TimeUnit.SECONDS));check(holderFailure.get()==null);
            Activity legacyPage=page;runOnMainSync(legacyPage::finish);waitForIdleSync();
            lastPageOwnership=pageOwnership(page);
            check(release.getCount()==1&&!holder.isDone()&&!pageWorker.isTerminated());
            FixtureCaptureService.Probe rejected=FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);
            requestStart(id);check(rejected.openFailed.await(8,TimeUnit.SECONDS));
            check(rejected.openFailure instanceof OverlappingFileLockException
                ||(rejected.openFailure instanceof java.io.IOException&&"audio store already open".equals(rejected.openFailure.getMessage())));
            check("failed".equals(rejected.openStage)&&rejected.openFailureNs>=rejected.openAttemptNs);
            check(rejected.startCalls.get()==0&&rejected.sourceStarts.get()==0&&started.get()==null);
            check(rejected.destroyed.await(8,TimeUnit.SECONDS));
            check(CaptureService.snapshot(id)==null&&FixtureCaptureService.instance==null&&started.get()==null);
            release.countDown();holder.get(8,TimeUnit.SECONDS);check(holderFailure.get()==null);
            finishAndDrain(page);check(pageWorker.isTerminated());
            FixtureCaptureService.Probe resumed=FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);
            check(start(id).equals(id));check(resumed.openFailure==null&&"opened".equals(resumed.openStage)&&resumed.sourceStarts.get()==1);
            CaptureService.control(id,false);await(()->CaptureService.snapshot(id)==null);check(resumed.destroyed.await(8,TimeUnit.SECONDS));
            try(AudioSegmentStore audio=new AudioSegmentStore(context)){check(audio.state(id).equals("paused"));check(audio.committedSamples(id)>0);}
        }finally{
            // Release the deliberate owner before joining its executor; the reverse order would deadlock the fixture.
            release.countDown();CaptureService.control(id,false);
            try{
                if(holder!=null)holder.get(8,TimeUnit.SECONDS);finishAndDrain(page);
                await(()->CaptureService.snapshot(id)==null&&FixtureCaptureService.instance==null);
                new NoteStore(context).delete(id);try(AudioSegmentStore audio=new AudioSegmentStore(context)){audio.purgeDeleted(id);}
            }finally{FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);}
        }
    }
    private Notification twoActionNotification(){
        return new Notification.Builder(context,CaptureEnvironment.CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("synthetic adopted actions")
            .addAction(new Notification.Action.Builder(null,"Pause",null).build())
            .addAction(new Notification.Action.Builder(null,"End",null).build()).build();
    }
    /** API 35 mechanism check only: local objects, never posted and no PendingIntent is sent. */
    private void adoptedActionsMechanism(){
        Notification original=twoActionNotification();
        Notification legacy=Notification.Builder.recoverBuilder(context,original).setActions(new Notification.Action[0]).build();
        check(original.actions!=null&&original.actions.length==2&&legacy.actions!=null&&legacy.actions.length==2);
        Notification fresh=twoActionNotification();fresh.actions=null;
        Notification cleared=Notification.Builder.recoverBuilder(context,fresh).setActions(new Notification.Action[0]).build();
        check(cleared.actions==null||cleared.actions.length==0);
    }
    private boolean foregroundNotificationReady(){
        FixtureCaptureService service=FixtureCaptureService.instance;Notification notice=currentNotification();
        return service!=null&&notice!=null&&(notice.flags&Notification.FLAG_FOREGROUND_SERVICE)!=0&&notice.actions!=null&&notice.actions.length==2
            &&notice.extras.getInt("sidecar.fixture.instance",-1)==service.noticeIdentity;
    }
    private Notification currentNotification(){
        for(StatusBarNotification notice:context.getSystemService(NotificationManager.class).getActiveNotifications())if(notice.getId()==CaptureService.NOTIFICATION_ID)return notice.getNotification();return null;
    }
    private String notificationDiagnostic(){
        Notification notice=currentNotification();return notice==null?"none":"flags="+notice.flags+", instance="+notice.extras.getInt("sidecar.fixture.instance",-1)+", title="+notice.extras.getString(Notification.EXTRA_TITLE);
    }
    private Notification notification(){for(StatusBarNotification notice:context.getSystemService(NotificationManager.class).getActiveNotifications())if(notice.getId()==CaptureService.NOTIFICATION_ID)return notice.getNotification();throw new AssertionError("foreground notification absent");}
    private static boolean text(View view,String needle){if(view instanceof TextView&&((TextView)view).getText().toString().contains(needle))return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(text(((ViewGroup)view).getChildAt(i),needle))return true;return false;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Activity activity=null;BroadcastReceiver receiver=null;Throwable suiteFailure=null;
        try{
            context=getTargetContext();if(!Build.HARDWARE.equals("ranchu")||!context.getPackageName().equals("local.sidecar.copilot.fixture"))throw new SecurityException("isolated emulator fixture only");
            getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.RECORD_AUDIO);
            getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);CaptureEnvironment.channel(context);
            receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){started.set(i.getStringExtra("meeting"));}};
            context.registerReceiver(receiver,new IntentFilter("local.sidecar.CAPTURE_STARTED"),Context.RECEIVER_NOT_EXPORTED);
            activity=own(startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            check(!CaptureEnvironment.supported()); // Production gate remains closed even in this APK.
            adoptedActionsMechanism();
            String id=start(null);Notification first=notification();
            check((first.flags&Notification.FLAG_FOREGROUND_SERVICE)!=0);check(first.extras.getString(Notification.EXTRA_TITLE).contains("合成"));
            check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty());
            Activity live=own(startActivitySync(new Intent(context,RecordingActivity.class).putExtra("meeting",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            awaitText(live,"录制中");first.actions[0].actionIntent.send();await(()->CaptureService.snapshot(id)==null);
            finishAndDrain(live);
            try(AudioSegmentStore audio=new AudioSegmentStore(context)){check(audio.state(id).equals("paused"));check(audio.committedSamples(id)>0);check(audio.read(audio.frameIds(id).get(0))[0]==1234);}
            check(start(id).equals(id));Notification resumed=notification();first.actions[0].actionIntent.send();Thread.sleep(200);check(CaptureService.snapshot(id)!=null);
            resumed.actions[1].actionIntent.send();await(()->CaptureService.snapshot(id)==null);
            try(AudioSegmentStore audio=new AudioSegmentStore(context)){check(audio.state(id).equals("ended"));check(audio.frameIds(id).size()>=2);}
            check(new NoteStore(context).read(id).getJSONArray("consents").length()==2);
            String removed=start(null);new NoteStore(context).delete(removed);await(()->CaptureService.snapshot(removed)==null);
            try(AudioSegmentStore audio=new AudioSegmentStore(context)){try{audio.frameIds(removed);throw new AssertionError("deleted service data");}catch(SecurityException expected){checks++;}}
            preparationCancellation();sourceFactoryCancellation();blockedWriteStop(false);blockedWriteStop(true);failedWriteStopsBeforeRecovery();stopNotificationFailure();privacyDeleteStopsBeforeDisk();
            int beforeHeldStore=checks;heldStoreAfterLegacyFinish();heldStoreChecks=checks-beforeHeldStore;
            String blocked=start(null);context.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CaptureEnvironment.CHANNEL,"fixture blocked",NotificationManager.IMPORTANCE_NONE));
            await(()->CaptureService.snapshot(blocked)==null);
            try(AudioSegmentStore audio=new AudioSegmentStore(context)){check(audio.state(blocked).equals("paused"));}
            context.startService(new Intent(context,FixtureCaptureService.class));Thread.sleep(200);check(CaptureService.snapshot(blocked)==null);
            check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty());
            new NoteStore(context).delete(id);new NoteStore(context).delete(blocked);try(AudioSegmentStore cleanup=new AudioSegmentStore(context)){}
        }catch(Throwable failure){suiteFailure=failure;}
        finally{
            if(receiver!=null)context.unregisterReceiver(receiver);
            try{finishAndDrain(ownedActivities.toArray(new Activity[0]));}
            catch(Throwable teardown){if(suiteFailure==null)suiteFailure=teardown;else if(suiteFailure!=teardown)suiteFailure.addSuppressed(teardown);}
        }
        if(suiteFailure==null)result.putString("stream",checks+" positive foreground-service synthetic checks passed; held-store mechanism "+heldStoreChecks
            +" additional checks; original checks retained; max blocked-write software stop dispatch "+maximumStopResponseMs
            +" ms (<2000 ms fixture bound); no AudioRecord or speaker opened; held-store mechanism does not establish the cause of historical timeouts\n");
        else result.putString("stream","FAILED after "+checks+": "+suiteFailure.getClass().getSimpleName()+": "+suiteFailure.getMessage()
            +"; at "+(suiteFailure.getStackTrace().length==0?"unknown":suiteFailure.getStackTrace()[0])+"; notification "+notificationDiagnostic()
            +"; setup "+setupDiagnostic()+"; pages "+lastPageOwnership+"\n");
        finish(suiteFailure==null?-1:0,result);
    }
}

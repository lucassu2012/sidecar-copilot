package local.sidecar.copilot;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.AudioDeviceInfo;
import android.os.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Main-thread controls never wait for persistence. All starts remain explicit and non-sticky. */
public class CaptureService extends Service {
    public static final String START="local.sidecar.START",PAUSE="local.sidecar.PAUSE",END="local.sidecar.END";
    static final CaptureStartGate START_GATE=new CaptureStartGate();
    public static final int NOTIFICATION_ID=41;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService storageWorker=Executors.newSingleThreadExecutor(r->new Thread(r,"Sidecar-capture-storage"));
    private final ExecutorService controlWorker=Executors.newSingleThreadExecutor(r->new Thread(r,"Sidecar-input-stop"));
    private AudioSegmentStore store; // Owned by storageWorker; the coordinator serializes frame commits.
    private volatile AudioCaptureCoordinator capture;
    private volatile PcmSource source;
    private final Object stopTaskLock=new Object();
    private PcmSource stopTarget;
    private Future<?> stopTask;
    private static final class StopIntent {
        final String kind;final long requestedNs;
        StopIntent(String kind,long requestedNs){this.kind=kind;this.requestedNs=requestedNs;}
    }
    private final AtomicReference<StopIntent> pendingStop=new AtomicReference<>();
    private final AtomicBoolean discardPending=new AtomicBoolean();
    protected boolean supportedDevice(){return CaptureEnvironment.supported();}
    protected PcmSource createSource(AudioCaptureCoordinator coordinator){return new AndroidPcmSource(coordinator);}
    protected AudioSegmentStore openStore()throws Exception{return new AudioSegmentStore(this);}
    protected Notification captureNotification(String token,boolean started){return notification(this,token,started);}
    private String token;
    private volatile String meeting;
    private long previousSamples;
    private static volatile CaptureService active;
    private volatile Snapshot snapshot;
    private volatile boolean stopping;
    private boolean finished;
    private boolean destroyed;
    private boolean interrupted;
    private long endRequestedNs;
    public static final class Snapshot {
        public final String meeting;public final long samples;public final int level;public final boolean stopping;
        Snapshot(String meeting,long samples,int level,boolean stopping){this.meeting=meeting;this.samples=samples;this.level=level;this.stopping=stopping;}
    }
    public static Snapshot snapshot(String id){CaptureService service=active;Snapshot result=service==null?null:service.snapshot;return result!=null&&result.meeting.equals(id)?result:null;}
    public static void control(String id,boolean end){
        long requestedNs=SystemClock.elapsedRealtimeNanos();
        CaptureService service=active;
        if(service!=null&&id.equals(service.meeting)){
            service.signalInputStop(end?"end":"pause",requestedNs,false);
            service.handler.post(()->{if(id.equals(service.meeting))service.shutdown(end,requestedNs);});
        }
    }
    public static void requestPrivacyStop(String id){
        CaptureService service=active;
        if(service!=null&&id.equals(service.meeting)){
            service.signalInputStop("privacy",SystemClock.elapsedRealtimeNanos(),true);
            service.handler.post(()->{if(id.equals(service.meeting))service.shutdown(false);});
        }
    }
    private Future<?> stopInput(PcmSource target){
        target.signalStop();
        synchronized(stopTaskLock){
            if(stopTarget==target)return stopTask;
            stopTarget=target;stopTask=controlWorker.submit(target::stopInput);return stopTask;
        }
    }
    private void signalInputStop(){
        signalInputStop("interrupted",SystemClock.elapsedRealtimeNanos(),false);
    }
    private void signalInputStop(String kind,long requestedNs,boolean discardPending){
        if(discardPending)this.discardPending.set(true);
        pendingStop.compareAndSet(null,new StopIntent(kind,requestedNs));
        StopIntent first=pendingStop.get();
        AudioCaptureCoordinator current=capture;if(current!=null)current.requestStop(first.kind,first.requestedNs,this.discardPending.get());
        PcmSource currentSource=source;if(currentSource!=null)stopInput(currentSource);
    }
    private boolean meetingDeleted(){
        if(meeting==null)return false;
        try{return DurableDeletionMarkers.isMarked(getSharedPreferences("deletion-barriers",MODE_PRIVATE),meeting);}
        catch(RuntimeException unavailable){return true;}
    }
    private final Runnable visibilityCheck=new Runnable(){public void run(){
        if(stopping||finished)return;
        if(meetingDeleted()
                ||!CaptureEnvironment.visibleNotification(CaptureService.this)||!CaptureEnvironment.microphone(CaptureService.this)){
            interrupted=true;shutdown(false);return;
        }
        PcmSource current=source;
        if(current!=null&&snapshot!=null)snapshot=new Snapshot(meeting,previousSamples+current.acceptedSamples(),current.inputLevel(),false);
        handler.postDelayed(this,500);
    }};
    @Override public IBinder onBind(Intent intent){return null;}
    public static Notification notification(Context context,String token){
        return notification(context,token,true);
    }
    private static Notification notification(Context context,String token,boolean started){
        String meeting=context instanceof CaptureService?((CaptureService)context).meeting:null;
        Intent page=meeting==null?new Intent(context,MainActivity.class):new Intent(context,RecordingActivity.class).putExtra("meeting",meeting).setData(android.net.Uri.parse("sidecar://meeting/"+meeting));
        PendingIntent open=PendingIntent.getActivity(context,0,page,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(context,CaptureEnvironment.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(started?"Sidecar 正在记录线下会议":"Sidecar 正在准备录音")
            .setContentText(started?"内置麦克风 · 仅本机保存 · 音质尚未验证":"尚未确认收到声音 · 可随时暂停或结束")
            .setVisibility(Notification.VISIBILITY_PUBLIC).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null,"暂停",action(context,PAUSE,token,1)).build())
            .addAction(new Notification.Action.Builder(null,"结束",action(context,END,token,2)).build()).build();
    }
    private static PendingIntent action(Context c,String action,String token,int request){
        Class<?> service=c instanceof CaptureService?c.getClass():CaptureService.class;
        Intent intent=new Intent(c,service).setAction(action).setData(android.net.Uri.parse("sidecar://capture/"+token)).putExtra("token",token);
        return PendingIntent.getService(c,request,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(finished){stopSelf();return START_NOT_STICKY;}
        if(stopping)return START_NOT_STICKY;
        if(intent==null){shutdown(false);return START_NOT_STICKY;}
        String action=intent.getAction(),requested=intent.getStringExtra("token");
        if(PAUSE.equals(action)||END.equals(action)){
            if(token!=null&&token.equals(requested))shutdown(END.equals(action));
            else if(token==null)shutdown(false);
            return START_NOT_STICKY;
        }
        if(!START.equals(action)||token!=null){if(token==null)shutdown(false);return START_NOT_STICKY;}
        try {
            if(!START_GATE.consume(requested,SystemClock.elapsedRealtime())||!supportedDevice()
                    ||(active!=null&&active!=this)||!CaptureEnvironment.unlocked(this)||!CaptureEnvironment.microphone(this)
                    ||!CaptureEnvironment.visibleNotification(this))throw new SecurityException("start no longer permitted");
            AudioDeviceInfo input=CaptureEnvironment.input(this);
            if(input==null||input.getId()!=intent.getIntExtra("input",-1))throw new SecurityException("input changed");
            token=requested;active=this;
            startForeground(NOTIFICATION_ID,captureNotification(token,false),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            String existing=intent.getStringExtra("meeting"),title=intent.getStringExtra("title");
            meeting=existing;
            handler.post(visibilityCheck);
            storageWorker.execute(()->prepare(existing,title,input));
        }catch(Exception error){interrupted=token!=null;shutdown(false);}
        return START_NOT_STICKY;
    }
    private void prepare(String existing,String title,AudioDeviceInfo input){
        try{
            if(stopping)return;
            store=openStore();if(stopping)return;
            MeetingSessionRepository meetings=new MeetingSessionRepository(this,store);
            meeting=meetings.prepare(existing,title,true,input.getProductName().toString());
            previousSamples=store.committedSamples(meeting);if(stopping)return;
            capture=new AudioCaptureCoordinator(store,meeting,16000);
            PcmSource prepared=createSource(capture);source=prepared;
            capture.setPrivacyInputStop(()->stopInput(prepared));
            StopIntent stop=pendingStop.get();
            if(stop!=null){capture.requestStop(stop.kind,stop.requestedNs,discardPending.get());stopInput(prepared);return;}
            if(stopping){stopInput(prepared);return;}
            if(!CaptureEnvironment.unlocked(this)||!CaptureEnvironment.microphone(this)||!CaptureEnvironment.visibleNotification(this))
                throw new SecurityException("start permission changed during preparation");
            prepared.start(this,input,true,true,reason->{
                signalInputStop();handler.post(()->{interrupted=true;shutdown(false);});
            });
            if(stopping){stopInput(prepared);return;}
            handler.post(()->{
                if(stopping||finished)return;
                try{
                    snapshot=new Snapshot(meeting,previousSamples+prepared.acceptedSamples(),prepared.inputLevel(),false);
                    AudioRetentionJob.schedule(this);
                    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,captureNotification(token,true));
                    sendBroadcast(new Intent("local.sidecar.CAPTURE_STARTED").setPackage(getPackageName()).putExtra("meeting",meeting));
                }catch(Exception error){interrupted=true;shutdown(false);}
            });
        }catch(Exception error){signalInputStop();handler.post(()->{if(!stopping){interrupted=true;shutdown(false);}});}
    }
    private void shutdown(boolean ended){
        shutdown(ended,ended?SystemClock.elapsedRealtimeNanos():0);
    }
    private void shutdown(boolean ended,long requestedNs){
        if(stopping||finished)return;
        stopping=true;START_GATE.cancel();handler.removeCallbacks(visibilityCheck);
        signalInputStop(ended?"end":interrupted?"interrupted":"pause",requestedNs>0?requestedNs:SystemClock.elapsedRealtimeNanos(),false);
        if(ended)endRequestedNs=requestedNs;
        Snapshot current=snapshot;if(current!=null)snapshot=new Snapshot(current.meeting,current.samples,0,true);
        try{
            if(token!=null&&CaptureEnvironment.visibleNotification(this)){
                Notification stoppingNotice=captureNotification(token,false);
                // recoverBuilder can retain adopted actions even after setActions(empty).
                stoppingNotice.actions=null;
                getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
                    Notification.Builder.recoverBuilder(this,stoppingNotice).setActions(new Notification.Action[0])
                        .setContentTitle("Sidecar stopping and sealing")
                        .setContentText("Input stop requested; waiting for local storage. No automatic restart.").build());
            }
        }catch(Exception ignored){/* Notification failure cannot cancel storage cleanup. */}
        finally{storageWorker.execute(()->finishStorage(ended));}
    }
    private void finishStorage(boolean ended){
        boolean failed=false;
        PcmSource current=source;
        if(current!=null){
            try{stopInput(current).get();}catch(Exception error){failed=true;}
            try{current.close();}catch(Exception error){failed=true;}
        }else try{if(capture!=null)capture.pause();}catch(Exception error){failed=true;}
        try{if(ended&&store!=null&&meeting!=null&&!failed)store.end(meeting,endRequestedNs);}catch(Exception error){failed=true;}
        try{if(store!=null&&meeting!=null&&store.isDeleted(meeting))store.purgeDeleted(meeting);}catch(Exception error){failed=true;}
        try{if(store!=null)store.close();}catch(Exception error){failed=true;}
        boolean finalFailed=failed;handler.post(()->finishShutdown(finalFailed));
        controlWorker.shutdown();storageWorker.shutdown();
    }
    private void finishShutdown(boolean failed){
        if(finished)return;finished=true;
        if(destroyed){if(active==this)active=null;snapshot=null;}
        source=null;capture=null;store=null;token=null;
        stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        try{if((failed||interrupted)&&CaptureEnvironment.visibleNotification(this))getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,
            new Notification.Builder(this,CaptureEnvironment.CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(failed?"Stop or storage completion needs review":"Recording interrupted; check source, permission or storage")
            .setContentText(failed?"Input shutdown or final audio persistence was not fully confirmed.":"Audio may have gaps. Recording will not resume automatically.")
            .setVisibility(Notification.VISIBILITY_PUBLIC).build());}catch(Exception ignored){/* Cleanup already completed. */}
    }
    @Override public void onTaskRemoved(Intent root){shutdown(false);}
    @Override public void onDestroy(){destroyed=true;shutdown(false);if(finished){if(active==this)active=null;snapshot=null;}super.onDestroy();}
}

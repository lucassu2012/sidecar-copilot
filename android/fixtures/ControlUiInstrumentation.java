package local.sidecar.copilot;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
import android.service.notification.StatusBarNotification;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Existing T01/T07/T08/T29/T32/T36/T37 synthetic control checks.
 * Imported from task-5/control-ui-snapshot, source SHA256 020ED8B3C5D41E81B7BD4D07454209CD4FB0FDFD856AB00AA32E02B5B3D40E02.
 * Revocation remains a host-owned two-phase operation; this runner never revokes permission.
 */
public final class ControlUiInstrumentation extends Instrumentation {
 private static final String PROCESS_TOKEN=UUID.randomUUID().toString();
 private static final String MARKER="control-ui-revocation";
 private String phase="",runId="";private Context context;private int checks,failures;private String stage="initial";private final StringBuilder log=new StringBuilder();private final AtomicReference<String> started=new AtomicReference<>();
 private static final class BlockObservation {
  final FixtureCaptureService.Probe probe;final String stage,meeting,label;final java.util.Map<String,Object> environment;
  java.util.Map<String,Object> before,after;String outcome="not_waited",diagnosticError;long waitStartNs,waitEndNs;
  BlockObservation(FixtureCaptureService.Probe probe,String stage,String meeting,String label,java.util.Map<String,Object> environment){this.probe=probe;this.stage=stage;this.meeting=meeting;this.label=label;this.environment=environment;}
 }
 private final java.util.List<BlockObservation> blockObservations=new java.util.ArrayList<>();
 private java.util.Map<String,Object> blockEnvironment(){
  // Binder reads happen before unblocking/starting the eight-second wait, never at the timeout instant.
  java.util.Map<String,Object> values=new java.util.LinkedHashMap<>();values.put("sampling_begin_ns",SystemClock.elapsedRealtimeNanos());
  for(String name:new String[]{"microphone_granted","notifications_granted","notifications_enabled","channel_present","channel_importance"})values.put(name,null);
  try{values.put("microphone_granted",context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED);values.put("notifications_granted",context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED);}
  catch(Throwable error){values.put("permission_sampling_error_class",error.getClass().getName());}
  try{NotificationManager manager=context.getSystemService(NotificationManager.class);values.put("notifications_enabled",manager.areNotificationsEnabled());NotificationChannel channel=manager.getNotificationChannel(CaptureEnvironment.CHANNEL);values.put("channel_present",channel!=null);values.put("channel_importance",channel==null?null:channel.getImportance());}
  catch(Throwable error){values.put("channel_sampling_error_class",error.getClass().getName());}
  values.put("sampling_end_ns",SystemClock.elapsedRealtimeNanos());return values;
 }
 private java.util.Map<String,Object> blockMemory(FixtureCaptureService.Probe probe,String id){
  java.util.Map<String,Object> values=probe.diagnosticSnapshot();CaptureService.Snapshot current=CaptureService.snapshot(id);
  values.put("service_present",FixtureCaptureService.instance!=null);values.put("snapshot_present",current!=null);
  values.put("snapshot_samples",current==null?null:current.samples);values.put("snapshot_stopping",current==null?null:current.stopping);values.put("snapshot_level",current==null?null:current.level);return values;
 }
 private void awaitAppendBlock(FixtureCaptureService.Probe probe,String id,String label)throws Exception {
  probe.event("page_ready");BlockObservation observation=new BlockObservation(probe,stage,id,label,blockEnvironment());blockObservations.add(observation);
  try{observation.before=blockMemory(probe,id);}catch(Throwable error){observation.diagnosticError=error.getClass().getName();}
  probe.blocked.set(false);probe.event("block_enabled");observation.waitStartNs=SystemClock.elapsedRealtimeNanos();boolean entered;
  try{entered=probe.entered.await(8,TimeUnit.SECONDS);observation.outcome=entered?"entered":"timeout";}
  catch(InterruptedException error){observation.outcome="interrupted";throw error;}
  finally{
   observation.waitEndNs=SystemClock.elapsedRealtimeNanos();probe.event("await_"+observation.outcome);
   try{observation.after=blockMemory(probe,id);}catch(Throwable error){observation.diagnosticError=error.getClass().getName();}
  }
  check(entered,label); // The original result, label and deadline remain the sole pass condition.
 }
 private void cleanupBlock(FixtureCaptureService.Probe probe,RecordingActivity page,String id,Throwable primary)throws Exception {
  probe.event("cleanup_release");probe.release.countDown();Throwable cleanup=null;
  try{if(page!=null)close(page);}catch(Exception|Error error){cleanup=error;}
  try{if(id!=null)clear(id);}catch(Exception|Error error){if(cleanup==null)cleanup=error;else if(cleanup!=error)cleanup.addSuppressed(error);}
  if(cleanup!=null){if(primary!=null){if(primary!=cleanup)primary.addSuppressed(cleanup);}else if(cleanup instanceof Exception)throw(Exception)cleanup;else throw(Error)cleanup;}
 }
 private void emitBlockDiagnostics(){
  for(BlockObservation observation:blockObservations)try{
   // Formatting/IPC is deferred until teardown. The await snapshots were detached before release.
   JSONObject value=new JSONObject().put("schema","sidecar.fixture.block_append.v1").put("run_id",runId).put("phase",phase.isEmpty()?"combined":phase)
    .put("stage",observation.stage).put("meeting",observation.meeting).put("assertion",observation.label).put("outcome",observation.outcome)
    .put("deadline_seconds",8).put("wait_start_ns",observation.waitStartNs).put("wait_end_ns",observation.waitEndNs)
    .put("environment_before_wait",new JSONObject(observation.environment)).put("before_wait",observation.before==null?JSONObject.NULL:new JSONObject(observation.before))
    .put("at_wait_return",observation.after==null?JSONObject.NULL:new JSONObject(observation.after)).put("after_cleanup",new JSONObject(observation.probe.diagnosticSnapshot()))
    .put("diagnostic_error_class",observation.diagnosticError==null?JSONObject.NULL:observation.diagnosticError).put("root_cause","unknown");
   emit("BLOCK_APPEND_DIAGNOSTIC",value);
  }catch(Throwable error){log.append("BLOCK_APPEND_DIAGNOSTIC_ERROR class=").append(error.getClass().getName()).append('\n');}
 }
 private void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;log.append("PASS ").append(label).append('\n');}
 private void record(boolean value,String label){checks++;if(!value)failures++;log.append(value?"PASS ":"FAIL ").append(label).append("\n");}
 private void await(BooleanSupplier f)throws Exception{long until=SystemClock.elapsedRealtime()+12000;while(!f.getAsBoolean()){if(SystemClock.elapsedRealtime()>until)throw new AssertionError("timeout "+stage);Thread.sleep(40);}}
 private static Object field(Object o,Class<?> type,String name)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 private static TextView find(View v,String s){if(v instanceof TextView&&((TextView)v).getText().toString().contains(s))return(TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i),s);if(t!=null)return t;}return null;}
 private boolean shows(Activity a,String s){AtomicBoolean found=new AtomicBoolean();runOnMainSync(()->found.set(find(a.getWindow().getDecorView(),s)!=null));return found.get();}
 /** Same bounded destruction/worker drain as the existing UI privacy runner. */
 private void close(Activity... activities)throws Exception {
  runOnMainSync(()->{for(Activity activity:activities)if(activity!=null&&!activity.isDestroyed())activity.finish();});
  long deadline=SystemClock.elapsedRealtime()+10000;Handler main=new Handler(Looper.getMainLooper());boolean destroyed=false;
  while(!destroyed&&SystemClock.elapsedRealtime()<deadline){
   AtomicBoolean allDestroyed=new AtomicBoolean(true);CountDownLatch inspected=new CountDownLatch(1);
   main.post(()->{try{for(Activity activity:activities)if(activity!=null&&!activity.isDestroyed())allDestroyed.set(false);}finally{inspected.countDown();}});
   long remaining=deadline-SystemClock.elapsedRealtime();
   if(remaining<=0||!inspected.await(remaining,TimeUnit.MILLISECONDS))throw new AssertionError("control teardown destruction inspection timed out");
   destroyed=allDestroyed.get();if(!destroyed)Thread.sleep(25);
  }
  if(!destroyed)throw new AssertionError("control teardown Activity destruction timed out");
  for(Activity activity:activities){if(activity==null)continue;for(Field member:activity.getClass().getDeclaredFields()){
   if(!ExecutorService.class.isAssignableFrom(member.getType()))continue;
   member.setAccessible(true);ExecutorService worker=(ExecutorService)member.get(activity);
   if(worker==null||!worker.isShutdown())throw new AssertionError("control teardown worker not shut down: "+member.getName());
   long remaining=deadline-SystemClock.elapsedRealtime();
   if(remaining<=0||!worker.awaitTermination(remaining,TimeUnit.MILLISECONDS))throw new AssertionError("control teardown worker still owns storage: "+member.getName());
  }}
 }
 private JSONObject storedAudio(RecordingActivity page,String id)throws Exception {
  // Serialize with the page's own render/replay worker instead of competing for the writer lock.
  ExecutorService worker=(ExecutorService)field(page,RecordingActivity.class,"storage");
  return worker.submit(()->{try(AudioSegmentStore audio=new AudioSegmentStore(context)){
   return new JSONObject().put("state",audio.state(id)).put("committed_samples",audio.committedSamples(id));
  }}).get(12,TimeUnit.SECONDS);
 }
 private RecordingActivity open(String id)throws Exception{RecordingActivity a=(RecordingActivity)startActivitySync(new Intent(context,RecordingActivity.class).putExtra("meeting",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await(()->shows(a,"保存笔记"));return a;}
 private Notification notice(){for(StatusBarNotification n:context.getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==CaptureService.NOTIFICATION_ID&&n.getTag()==null)return n.getNotification();return null;}
 private void emit(String name,JSONObject value){String line=name+" "+value+"\n";log.append(line);Bundle status=new Bundle();status.putString("stream",line);sendStatus(1,status);}
 private static boolean uuid(String value){return value!=null&&value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
 private String bootId()throws IOException {
  // Fixed read-only shell command: untrusted-app access to this proc file differs by Android build.
  try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand("cat /proc/sys/kernel/random/boot_id"))){
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[128];int count;
   while((count=input.read(buffer))!=-1){if(bytes.size()+count>1024)throw new IOException("unexpected boot ID response");bytes.write(buffer,0,count);}
   String value=new String(bytes.toByteArray(),StandardCharsets.US_ASCII).trim();if(!uuid(value))throw new IOException("boot ID unavailable");return value;
  }
 }
 private JSONObject identity()throws Exception {
  long startedAt=android.os.Process.getStartElapsedRealtime();
  if(startedAt<=0||startedAt>SystemClock.elapsedRealtime())throw new IOException("process start identity unavailable");
  return new JSONObject().put("run_id",runId).put("phase",phase.isEmpty()?"combined":phase).put("pid",android.os.Process.myPid())
    .put("boot_id",bootId()).put("process_start_elapsed_ms",startedAt).put("process_token",PROCESS_TOKEN);
 }
 private JSONObject environment()throws Exception {
  NotificationManager manager=context.getSystemService(NotificationManager.class);NotificationChannel channel=manager.getNotificationChannel(CaptureEnvironment.CHANNEL);
  return identity().put("microphone_granted",context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
    .put("notifications_granted",context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
    .put("notifications_enabled",manager.areNotificationsEnabled()).put("channel_present",channel!=null)
    .put("channel_importance",channel==null?JSONObject.NULL:channel.getImportance());
 }
 private void interruptionState(String name,String id,FixtureCaptureService.Probe probe,RecordingActivity page,String durable)throws Exception {
  Notification warning=notice();
  emit(name,new JSONObject().put("meeting",id).put("notification_id",CaptureService.NOTIFICATION_ID)
    .put("warning_present",warning!=null).put("warning_foreground",warning!=null&&(warning.flags&Notification.FLAG_FOREGROUND_SERVICE)!=0)
    .put("snapshot_present",CaptureService.snapshot(id)!=null).put("service_present",FixtureCaptureService.instance!=null)
    .put("source_starts",probe.sourceStarts.get()).put("sample_calls",probe.sampleCalls.get()).put("input_stopped",probe.inputStopped.getCount()==0)
    .put("page_paused",shows(page,"已暂停")).put("page_recording",shows(page,"录制中")).put("durable_state",durable==null?JSONObject.NULL:durable));
 }
 private Intent request(String token){AudioDeviceInfo input=CaptureEnvironment.input(context);return new Intent(context,FixtureCaptureService.class).setAction(CaptureService.START).putExtra("token",token).putExtra("input",input.getId()).putExtra("title","SYNTHETIC CONTROL PRIVATE TITLE");}
 private String issue(){return CaptureService.START_GATE.issue(true,true,true,true,CaptureEnvironment.unlocked(context),true,SystemClock.elapsedRealtime());}
 private String start(FixtureCaptureService.Probe probe)throws Exception{Bundle checkpoint=new Bundle();checkpoint.putString("stream","START_CHECKPOINT "+stage+"\n");sendStatus(1,checkpoint);started.set(null);String token=issue();runOnMainSync(()->context.startForegroundService(request(token)));await(()->started.get()!=null);String id=started.get();await(()->CaptureService.snapshot(id)!=null&&CaptureService.snapshot(id).samples>0&&notice()!=null);probe.event("start_returned");return id;}
 private void stopped(String id)throws Exception{await(()->CaptureService.snapshot(id)==null&&FixtureCaptureService.instance==null);}
 private void clear(String id)throws Exception{CaptureService.control(id,false);stopped(id);new NoteStore(context).delete(id);context.getSystemService(NotificationManager.class).cancel(CaptureService.NOTIFICATION_ID);}
 private void deniedPreparation()throws Exception{
  stage="permissions denied";check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_DENIED,"actual microphone permission denied");check(context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_DENIED,"actual notification permission denied");
  MeetingPreparationActivity a=(MeetingPreparationActivity)startActivitySync(new Intent(context,MeetingPreparationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{check(shows(a,"麦克风权限：未允许")&&shows(a,"通知可见：未允许"),"preparation displays both refusals");FixtureCaptureService.Probe denied=FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);String token=issue();runOnMainSync(()->context.startService(request(token)));check(denied.destroyed.await(8,TimeUnit.SECONDS),"service rejects forged-ready request under actual denial");check(denied.sourceStarts.get()==0&&started.get()==null,"permission denial creates no synthetic source or meeting");
   getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.RECORD_AUDIO);getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
   CheckBox consent=(CheckBox)field(a,MeetingPreparationActivity.class,"consent");EditText title=(EditText)field(a,MeetingPreparationActivity.class,"title");
   runOnMainSync(()->{consent.setChecked(true);title.setText("SYNTHETIC PERMISSION DRAFT");a.onRequestPermissionsResult(20,new String[]{Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS},new int[]{PackageManager.PERMISSION_GRANTED,PackageManager.PERMISSION_GRANTED});});
   check(shows(a,"麦克风权限：已允许")&&shows(a,"通知可见：已允许"),"permission-result render reflects granted permissions");check(!((CheckBox)field(a,MeetingPreparationActivity.class,"consent")).isChecked(),"permission result does not reuse prior consent");check(((EditText)field(a,MeetingPreparationActivity.class,"title")).length()==0,"permission rerender clears private draft");Thread.sleep(600);check(FixtureCaptureService.instance==null&&started.get()==null,"permission grant never starts capture");
   String pending=issue();runOnMainSync(()->callActivityOnPause(a));check(!CaptureService.START_GATE.consume(pending,SystemClock.elapsedRealtime()),"page departure cancels pending start ticket");
  }finally{close(a);}
 }
 private void duplicatesAndBackground()throws Exception{
  stage="duplicate and background";FixtureCaptureService.Probe probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);String id=start(probe);RecordingActivity a=open(id);
  try{FixtureCaptureService service=FixtureCaptureService.instance;String token=(String)field(service,CaptureService.class,"token");int declarations=new NoteStore(context).read(id).getJSONArray("consents").length();
   runOnMainSync(()->{context.startService(request(token));context.startService(request(issue()));});Thread.sleep(600);check(probe.sourceStarts.get()==1,"duplicate same and new start tickets do not double capture");check(new NoteStore(context).read(id).getJSONArray("consents").length()==declarations,"duplicate start adds no consent declaration");
   runOnMainSync(()->callActivityOnStop(a));check(!shows(a,"SYNTHETIC CONTROL PRIVATE TITLE"),"background departure hides private title");check(CaptureService.snapshot(id)!=null,"page stop does not falsely end admitted synthetic recording");runOnMainSync(()->callActivityOnResume(a));await(()->shows(a,"录制中"));check(probe.sourceStarts.get()==1,"page resume reattaches without new source");
    runOnMainSync(()->service.onTaskRemoved(new Intent()));stopped(id);await(()->shows(a,"已暂停"));check(probe.sourceStarts.get()==1,"task removal stops and does not restart source");check(notice()==null,"normal task-removal stop removes active notification");check(storedAudio(a,id).getString("state").equals("paused"),"task removal persists paused state");
   runOnMainSync(()->context.startService(new Intent(context,FixtureCaptureService.class)));Thread.sleep(600);await(()->FixtureCaptureService.instance==null);check(CaptureService.snapshot(id)==null&&shows(a,"已暂停"),"actionless service recreation and page remain paused");
  }finally{close(a);clear(id);}
 }
 private void repeatedEndPending()throws Exception{
  stage="repeat end while sealing";FixtureCaptureService.Probe probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.BLOCK_APPEND);probe.blocked.set(true);String id=start(probe);RecordingActivity a=null;Throwable primary=null;
   try{a=open(id);RecordingActivity page=a;awaitAppendBlock(probe,id,"synthetic persistence is held before commit");Notification live=notice();check(live.actions.length==2&&shows(page,"录制中"),"recording page and actionable notification agree");live.actions[1].actionIntent.send();live.actions[1].actionIntent.send();check(probe.inputStopped.await(2,TimeUnit.SECONDS),"repeated end requests stop input before storage release");await(()->shows(page,"停止请求已收到"));await(()->notice()!=null&&notice().extras.getString(Notification.EXTRA_TITLE,"").contains("stopping"));check(!shows(page,"已结束"),"pending end does not claim durable completion");Notification stopping=notice();log.append("STOP_NOTICE title=").append(stopping.extras.getString(Notification.EXTRA_TITLE)).append(" actions=").append(stopping.actions==null?0:stopping.actions.length);if(stopping.actions!=null)for(Notification.Action action:stopping.actions)log.append(" [").append(action.title).append("]");log.append("\n");record(stopping.actions==null||stopping.actions.length==0,"stopping notification removes duplicate actions");check(!notice().extras.toString().contains("SYNTHETIC CONTROL PRIVATE TITLE"),"stopping notification does not disclose private title");probe.release.countDown();stopped(id);await(()->shows(page,"已结束"));check(notice()==null,"completed end removes foreground notification");JSONObject durable=storedAudio(page,id);check(durable.getString("state").equals("ended")&&durable.getLong("committed_samples")>0,"repeated end persists ended state and admitted audio");check(probe.sourceStarts.get()==1,"repeated end never restarts source");
  }catch(Exception|Error error){primary=error;throw error;}finally{cleanupBlock(probe,a,id,primary);}
 }
 private void interruptionCallback()throws Exception{
  stage="system interruption callback";FixtureCaptureService.Probe probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);String id=start(probe);RecordingActivity a=open(id);
   try{
    PcmSource source=(PcmSource)field(FixtureCaptureService.instance,CaptureService.class,"source");
    PcmSource.Failure callback=(PcmSource.Failure)field(source,source.getClass(),"failure");callback.interrupted("synthetic OS input interruption");
    check(probe.inputStopped.await(2,TimeUnit.SECONDS),"injected input-interruption callback stops source");
    stage="interruption service shutdown";stopped(id);
    stage="interruption page paused";await(()->shows(a,"已暂停"));check(!shows(a,"录制中"),"page reaches paused after interruption");
    stage="interruption warning notification";await(()->notice()!=null);Notification warning=notice();
    check(warning!=null&&warning.extras.getString(Notification.EXTRA_TITLE,"").contains("interrupted"),"interruption warning appears before explicit removal");
    check((warning.flags&Notification.FLAG_FOREGROUND_SERVICE)==0,"interruption warning is not an active foreground notice");
    check(!warning.extras.toString().contains("SYNTHETIC CONTROL PRIVATE TITLE"),"interruption notice has no private title");
    int startsBefore=probe.sourceStarts.get(),samplesBefore=probe.sampleCalls.get();
    check(startsBefore==1&&CaptureService.snapshot(id)==null&&FixtureCaptureService.instance==null&&!probe.coordinator.isActive(),"warning removal begins only after source and service stop");
    interruptionState("INTERRUPTION_BEFORE_CANCEL",id,probe,a,null);
    // Exact local warning ID with null tag. The foreground service has already stopped.
    context.getSystemService(NotificationManager.class).cancel(CaptureService.NOTIFICATION_ID);
    stage="explicit interruption warning removal";await(()->notice()==null);Thread.sleep(600);
    check(notice()==null,"explicit cancellation establishes missing warning");
    check(shows(a,"已暂停")&&!shows(a,"录制中"),"missing warning cannot make page claim recording");
    check(probe.sourceStarts.get()==startsBefore&&probe.sampleCalls.get()==samplesBefore&&CaptureService.snapshot(id)==null&&FixtureCaptureService.instance==null&&!probe.coordinator.isActive(),"missing warning does not start source or accept more samples");
    interruptionState("INTERRUPTION_AFTER_CANCEL",id,probe,a,null);
    stage="interruption page return";runOnMainSync(()->{callActivityOnStop(a);callActivityOnResume(a);});await(()->shows(a,"已暂停"));Thread.sleep(600);
    check(notice()==null,"warning remains absent after leaving and returning");
    check(probe.sourceStarts.get()==startsBefore&&probe.sampleCalls.get()==samplesBefore&&CaptureService.snapshot(id)==null&&FixtureCaptureService.instance==null&&!probe.coordinator.isActive(),"return after missing warning still requires explicit restart");
    check(!shows(a,"录制中"),"returned page remains paused without warning");
    String durable=storedAudio(a,id).getString("state");check(durable.equals("paused"),"interruption persists paused state after warning removal");
    interruptionState("INTERRUPTION_AFTER_RETURN",id,probe,a,durable);
  }finally{close(a);clear(id);}
 }
 private void prepareRevocation()throws Exception {
  stage="actual permission revocation prepare";SharedPreferences marker=context.getSharedPreferences(MARKER,0);
  check(!marker.contains("meeting")||marker.getBoolean("verified",false),"prepare cannot replace an unfinished revocation marker");
  getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.RECORD_AUDIO);getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
  check(CaptureEnvironment.microphone(context)&&CaptureEnvironment.visibleNotification(context),"revocation preparation has actual capture permissions");
  FixtureCaptureService.Probe probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);String id=start(probe);
  await(()->probe.sampleCalls.get()>=55);AudioSegmentStore store=(AudioSegmentStore)field(probe.coordinator,AudioCaptureCoordinator.class,"store");
  await(()->store.committedSamples(id)>0);long committed=store.committedSamples(id);
  check(probe.sourceStarts.get()==1,"one synthetic source before OS permission revocation");
  JSONObject ready=environment();long readyAt=SystemClock.elapsedRealtime();
  check(ready.getBoolean("microphone_granted")&&ready.getBoolean("notifications_granted")&&ready.getBoolean("notifications_enabled")&&ready.getInt("channel_importance")>=NotificationManager.IMPORTANCE_LOW&&probe.coordinator.isActive()&&CaptureService.snapshot(id)!=null&&FixtureCaptureService.instance!=null,"source remains admitted at revocation ready checkpoint");
  ready.put("meeting",id).put("ready_elapsed_ms",readyAt).put("source_starts",probe.sourceStarts.get()).put("sample_calls",probe.sampleCalls.get()).put("committed_samples",committed).put("verified",false);
  check(marker.edit().clear().putString("run_id",runId).putString("meeting",id).putInt("prepare_pid",ready.getInt("pid"))
    .putString("prepare_boot_id",ready.getString("boot_id")).putLong("prepare_process_start_elapsed_ms",ready.getLong("process_start_elapsed_ms"))
    .putString("prepare_process_token",ready.getString("process_token")).putLong("prepare_ready_elapsed_ms",readyAt)
    .putInt("source_starts",probe.sourceStarts.get()).putInt("sample_calls",probe.sampleCalls.get()).putLong("committed_samples",committed)
    .putBoolean("microphone_granted",ready.getBoolean("microphone_granted")).putBoolean("notifications_granted",ready.getBoolean("notifications_granted"))
    .putBoolean("verified",false).commit(),"revocation meeting and process identity marker durable");
  emit("REVOCATION_READY",ready);
  // Only the owning host may perform the OS revoke. A kill/timeout without verify is not a pass.
  new CountDownLatch(1).await();
 }
 private JSONObject verifyRevocation()throws Exception {
  stage="actual permission revocation verify";SharedPreferences marker=context.getSharedPreferences(MARKER,0);
  String id=marker.getString("meeting",null);check(uuid(id)&&runId.equals(marker.getString("run_id",null))&&!marker.getBoolean("verified",false),"matching unverified revocation marker recovered");
  JSONObject current=identity();String previousBoot=marker.getString("prepare_boot_id",null),previousToken=marker.getString("prepare_process_token",null);
  int previousPid=marker.getInt("prepare_pid",-1);long previousStart=marker.getLong("prepare_process_start_elapsed_ms",-1),readyAt=marker.getLong("prepare_ready_elapsed_ms",-1);
  check(previousPid>0&&previousStart>0&&readyAt>=previousStart&&uuid(previousBoot)&&uuid(previousToken),"prepared process identity is complete");
  check(current.getString("boot_id").equals(previousBoot),"permission revoke verification occurs in the same boot");
  // PID reuse is allowed only with a later OS process-start time; a fresh classloader alone cannot pass.
  check(current.getLong("process_start_elapsed_ms")>readyAt&&!current.getString("process_token").equals(previousToken),"verification runs in a genuinely new process after the durable ready marker");
  check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_DENIED,"OS microphone permission actually revoked");
  check(CaptureService.snapshot(id)==null&&FixtureCaptureService.instance==null,"new process has no active capture source");
  long committed;
  try(AudioSegmentStore audio=new AudioSegmentStore(context)){
   check(audio.state(id).equals("paused"),"post-revocation recovery remains paused");committed=audio.committedSamples(id);
   check(committed>=marker.getLong("committed_samples",Long.MAX_VALUE)&&committed>0,"committed synthetic audio survives permission revoke");
   check(audio.recoveryUncertainties(id).length()>0,"abrupt permission revoke exposes unknown tail");
  }
  RecordingActivity page=open(id);
  try{await(()->shows(page,"已暂停"));check(!shows(page,"录制中"),"restored page does not claim recording");check(shows(page,"恢复前尾段可能缺失"),"restored page exposes uncertain tail");check(notice()==null||(notice().flags&Notification.FLAG_FOREGROUND_SERVICE)==0,"no stale active foreground notification after revoke");}
  finally{close(page);}
  check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty(),"no actual AudioRecord configuration after revoke");
  new NoteStore(context).delete(id);
  current.put("meeting",id).put("prepare_pid",previousPid).put("prepare_boot_id",previousBoot).put("prepare_process_start_elapsed_ms",previousStart)
    .put("prepare_process_token",previousToken).put("prepare_ready_elapsed_ms",readyAt).put("committed_samples",committed).put("microphone_granted",false);
  return current;
 }
 private Button mixedControl(RecordingActivity page,boolean end)throws Exception {
  java.util.List<?> buttons=(java.util.List<?>)field(page,RecordingActivity.class,"captureControls");
  if(buttons.size()!=2)throw new AssertionError("expected two live page controls");
  return (Button)buttons.get(end?1:0);
 }
 private void mixedControlCase(boolean firstEnd,boolean pageFirst)throws Exception {
  stage="mixed controls first="+(firstEnd?"end":"pause")+" via="+(pageFirst?"page":"notification");
  FixtureCaptureService.Probe probe=FixtureCaptureService.arm(FixtureCaptureService.Mode.BLOCK_APPEND);
  probe.blocked.set(true);String id=null;RecordingActivity page=null;Throwable primary=null;
  try{
   id=start(probe);final String meeting=id;page=open(id);final RecordingActivity view=page;
   AtomicReference<Button> firstButton=new AtomicReference<>(),secondButton=new AtomicReference<>();
   runOnMainSync(()->{try{firstButton.set(mixedControl(view,firstEnd));secondButton.set(mixedControl(view,!firstEnd));}catch(Exception e){throw new RuntimeException(e);}});
   Notification live=notice();check(live!=null&&live.actions!=null&&live.actions.length==2,"mixed case begins with both notification controls");
   PendingIntent firstNotice=live.actions[firstEnd?1:0].actionIntent;
   PendingIntent secondNotice=live.actions[firstEnd?0:1].actionIntent;
   awaitAppendBlock(probe,id,"mixed controls retain blocked persistence until observations finish");
   long firstRequested=SystemClock.elapsedRealtimeNanos();
   if(pageFirst){runOnMainSync(()->{check(firstButton.get().isEnabled(),"first page control enabled");firstButton.get().performClick();});}
   else firstNotice.send();
   check(probe.inputStopped.await(2,TimeUnit.SECONDS),"mixed control stops input while persistence remains held");
   await(()->CaptureService.snapshot(meeting)!=null&&CaptureService.snapshot(meeting).stopping);
   final int samples=probe.sampleCalls.get();long secondAttempt=SystemClock.elapsedRealtimeNanos();
   if(pageFirst){
    secondNotice.send(); // cached pre-stop intent: delayed delivery must not override the admitted boundary.
   }else{
    // Real UI dispatch will not click a disabled button; do not call its listener reflectively.
    await(()->{AtomicBoolean disabled=new AtomicBoolean();runOnMainSync(()->disabled.set(!secondButton.get().isEnabled()));return disabled.get();});
    check(true,"opposite page action becomes disabled after notification stop");
   }
   await(()->notice()!=null&&notice().extras.getString(Notification.EXTRA_TITLE,"").contains("stopping"));
   Notification stopping=notice();check(stopping.actions==null||stopping.actions.length==0,"mixed stopping notification has no control actions");
   check(!shows(view,"\u5df2\u7ed3\u675f"),"mixed pending stop does not claim durable end");
   check(probe.sampleCalls.get()==samples&&probe.sourceStarts.get()==1,"mixed controls admit no additional samples or source");
   emit("MIXED_CONTROL_PENDING",new JSONObject().put("meeting",meeting).put("first_end",firstEnd).put("page_first",pageFirst)
    .put("first_requested_ns",firstRequested).put("second_attempt_ns",secondAttempt).put("second_kind",pageFirst?"cached_opposite_notification":"disabled_page_no_dispatch")
    .put("persistence_held",probe.release.getCount()==1).put("source_starts",probe.sourceStarts.get()).put("sample_calls",samples));
   probe.release.countDown();stopped(id);await(()->shows(view,firstEnd?"\u5df2\u7ed3\u675f":"\u5df2\u6682\u505c"));
   JSONObject durable=storedAudio(view,id);check(durable.getString("state").equals(firstEnd?"ended":"paused"),"first admitted control determines durable mixed-control outcome");
   check(notice()==null&&probe.sourceStarts.get()==1,"mixed completion removes foreground notice without restart");
   if(!firstEnd){
    check(shows(view,"\u6838\u5bf9\u8bb8\u53ef\u5e76\u7ee7\u7eed")&&shows(view,"\u7ed3\u675f\u4f1a\u8bae"),"paused mixed outcome offers explicit continue and end");
    runOnMainSync(()->{TextView end=find(view.getWindow().getDecorView(),"\u7ed3\u675f\u4f1a\u8bae");if(!(end instanceof Button)||!end.isEnabled())throw new AssertionError("paused end unavailable");end.performClick();});
    await(()->shows(view,"\u5df2\u7ed3\u675f"));check(storedAudio(view,id).getString("state").equals("ended"),"explicit end after mixed pause reaches durable ended");
    check(probe.sourceStarts.get()==1,"ending a paused meeting never restarts source");
   }
   check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty(),"mixed fixture has no real AudioRecord");
  }catch(Exception|Error error){primary=error;throw error;}finally{cleanupBlock(probe,page,id,primary);}
 }

 @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null){phase=args.getString("phase","");runId=args.getString("run_id","");}start();}
 @Override public void onStart(){
  Bundle result=new Bundle();BroadcastReceiver receiver=null;Activity home=null;Throwable failure=null;String failureStage=null,completion="";JSONObject verified=null;
  try{
   context=getTargetContext();if(!Build.HARDWARE.equals("ranchu")||!context.getPackageName().equals("local.sidecar.copilot.fixture"))throw new SecurityException("isolated fixture only");
   if(!phase.isEmpty()&&!phase.equals("mixed_controls")&&!phase.equals("combined")&&!phase.equals("interruption_only")&&!phase.equals("revocation_prepare")&&!phase.equals("revocation_verify"))throw new IllegalArgumentException("unknown control test phase");
   if((phase.startsWith("revocation_")||!runId.isEmpty())&&!uuid(runId))throw new IllegalArgumentException("run_id must be a UUID");
   JSONObject initial=environment();emit("CONTROL_ENVIRONMENT",initial);
   check(!CaptureEnvironment.supported(),"production capture gate remains closed");check(CaptureEnvironment.unlocked(context),"test credential authenticated and unlocked");
   if(initial.getBoolean("channel_present")&&initial.getInt("channel_importance")<NotificationManager.IMPORTANCE_LOW)throw new IllegalStateException("existing capture channel disabled; fixture precondition must be repaired by owning host");
   CaptureEnvironment.channel(context);
   receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){started.set(i.getStringExtra("meeting"));}};
   context.registerReceiver(receiver,new IntentFilter("local.sidecar.CAPTURE_STARTED"),Context.RECEIVER_NOT_EXPORTED);
   home=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   if(phase.equals("revocation_prepare")){prepareRevocation();throw new AssertionError("revocation prepare unexpectedly resumed");}
   if(phase.equals("revocation_verify")){
    verified=verifyRevocation();completion="actual-permission-revocation recovery checks passed; synthetic input only";
   }else if(phase.equals("mixed_controls")){
    getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.RECORD_AUDIO);getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
    mixedControlCase(false,true);mixedControlCase(true,true);mixedControlCase(false,false);mixedControlCase(true,false);completion="mixed control checks, "+failures+" failures; synthetic only";
   }else if(phase.equals("interruption_only")){
    getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.RECORD_AUDIO);getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.POST_NOTIFICATIONS);
    interruptionCallback();completion="diagnostic checks, "+failures+" failures; synthetic only";
   }else{
    deniedPreparation();duplicatesAndBackground();repeatedEndPending();interruptionCallback();check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty(),"no actual AudioRecord configuration");
    completion="checks, "+failures+" failures; synthetic only";
   }
  }catch(Throwable error){failure=error;failureStage=stage;}
  finally{
   if(receiver!=null)try{context.unregisterReceiver(receiver);}catch(Throwable error){if(failure==null){failure=error;failureStage="receiver cleanup";}else if(failure!=error)failure.addSuppressed(error);}
   if(home!=null)try{close(home);}catch(Throwable error){if(failure==null){failure=error;failureStage="home worker cleanup";}else if(failure!=error)failure.addSuppressed(error);}
  }
  // Neither a verified receipt nor a successful instrumentation result may precede teardown.
  emitBlockDiagnostics();
  if(failure==null&&verified!=null)try{
   check(context.getSharedPreferences(MARKER,0).edit().putBoolean("verified",true).putInt("verify_pid",verified.getInt("pid")).putString("verify_boot_id",verified.getString("boot_id"))
     .putLong("verify_process_start_elapsed_ms",verified.getLong("process_start_elapsed_ms")).putString("verify_process_token",verified.getString("process_token")).commit(),"verified process identity receipt durable");
   verified.put("verified",true);emit("REVOCATION_VERIFIED",verified);
  }catch(Throwable error){failure=error;failureStage="verified receipt after cleanup";}
  if(failure!=null){result.putString("stream",log+"FAILED stage "+failureStage+": "+android.util.Log.getStackTraceString(failure));finish(0,result);}
  else{result.putString("stream",log+"RESULT "+checks+" "+completion+"\n");finish(failures==0?-1:0,result);}
 }
}






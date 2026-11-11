package local.sidecar.copilot;
import android.app.*;
import android.content.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.service.notification.StatusBarNotification;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.security.KeyStore;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Existing confirmed-write and privacy behavior; synthetic emulator fixtures, no new PRD identifiers. */
public final class UiPrivacyInstrumentation extends Instrumentation {
 private Context context; private int checks,failures; private final StringBuilder report=new StringBuilder();
 private final AtomicReference<String> started=new AtomicReference<>();
 private void check(boolean ok,String label){checks++;report.append(ok?"PASS ":"FAIL ").append(label).append('\n');if(!ok)failures++;}
 private void await(BooleanSupplier f)throws Exception{long until=SystemClock.elapsedRealtime()+12000;while(!f.getAsBoolean()){if(SystemClock.elapsedRealtime()>until)throw new AssertionError("timeout");Thread.sleep(40);}}
 private static Object field(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
 private static void invoke(Object o,String name){try{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}catch(Exception e){throw new RuntimeException(e);}}
 private static TextView find(View v,String label){if(v instanceof TextView&&((TextView)v).getText().toString().contains(label))return(TextView)v;if(v instanceof ViewGroup)for(int j=0;j<((ViewGroup)v).getChildCount();j++){TextView f=find(((ViewGroup)v).getChildAt(j),label);if(f!=null)return f;}return null;}
 private static String dump(View v){String s=v instanceof TextView?((TextView)v).getText().toString()+" | ":"";if(v instanceof ViewGroup)for(int j=0;j<((ViewGroup)v).getChildCount();j++)s+=dump(((ViewGroup)v).getChildAt(j));return s;}
 private boolean shows(Activity a,String s){AtomicBoolean b=new AtomicBoolean();runOnMainSync(()->b.set(find(a.getWindow().getDecorView(),s)!=null));return b.get();}
 private void click(Activity a,String label){runOnMainSync(()->{TextView v=find(a.getWindow().getDecorView(),label);if(v==null)throw new AssertionError("button missing: "+label);v.performClick();});}
 private RecordingActivity open(String id)throws Exception{RecordingActivity a=(RecordingActivity)startActivitySync(new Intent(context,RecordingActivity.class).putExtra("meeting",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));try{await(()->shows(a,"补充手工")||shows(a,"保存笔记"));}catch(Exception|AssertionError e){runOnMainSync(()->report.append("VIEW ").append(dump(a.getWindow().getDecorView())).append("\n"));try{report.append(new NoteStore(context).read(id).getString("capture_mode"));}finally{close(a);}throw e;}return a;}
 /** Activity.finish only schedules destruction; drain workers before another fixture takes the store lock. */
 private void close(Activity... activities)throws Exception {
  runOnMainSync(()->{for(Activity activity:activities)if(activity!=null&&!activity.isDestroyed())activity.finish();});
  long deadline=SystemClock.elapsedRealtime()+10000;Handler main=new Handler(Looper.getMainLooper());boolean destroyed=false;
  while(!destroyed&&SystemClock.elapsedRealtime()<deadline){
   AtomicBoolean allDestroyed=new AtomicBoolean(true);CountDownLatch inspected=new CountDownLatch(1);
   main.post(()->{try{for(Activity activity:activities)if(activity!=null&&!activity.isDestroyed())allDestroyed.set(false);}finally{inspected.countDown();}});
   long remaining=deadline-SystemClock.elapsedRealtime();
   if(remaining<=0||!inspected.await(remaining,TimeUnit.MILLISECONDS))throw new AssertionError("UI privacy teardown: destruction check timed out");
   destroyed=allDestroyed.get();if(!destroyed)Thread.sleep(25);
  }
  if(!destroyed)throw new AssertionError("UI privacy teardown: Activity destruction timed out");
  for(Activity activity:activities){if(activity==null)continue;
   for(Field field:activity.getClass().getDeclaredFields()){
    if(!ExecutorService.class.isAssignableFrom(field.getType()))continue;
    field.setAccessible(true);ExecutorService worker=(ExecutorService)field.get(activity);
    if(worker==null||!worker.isShutdown())throw new AssertionError("UI privacy teardown: worker not shut down");
    long remaining=deadline-SystemClock.elapsedRealtime();
    if(remaining<=0||!worker.awaitTermination(remaining,TimeUnit.MILLISECONDS))throw new AssertionError("UI privacy teardown: worker still owns storage");
   }
  }
 }
 private CountDownLatch block(Activity a)throws Exception{CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);((ExecutorService)field(a,"storage")).execute(()->{entered.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});if(!entered.await(3,TimeUnit.SECONDS)){release.countDown();close(a);throw new AssertionError("queue block");}return release;}
 private String prepared()throws Exception{try(AudioSegmentStore audio=new AudioSegmentStore(context)){return new MeetingSessionRepository(context,audio).prepare(null,"SYNTHETIC UI PRIVATE TITLE",true,"synthetic");}}
 private void write(Activity a,String s){runOnMainSync(()->{ViewGroup root=(ViewGroup)a.getWindow().getDecorView();edit(root).setText(s);});}
 private static EditText edit(View v){if(v instanceof EditText)return(EditText)v;if(v instanceof ViewGroup)for(int j=0;j<((ViewGroup)v).getChildCount();j++){EditText f=edit(((ViewGroup)v).getChildAt(j));if(f!=null)return f;}return null;}
 private void saveLeaves()throws Exception{
  String id=prepared();RecordingActivity a=open(id);CountDownLatch release=block(a);
  try{write(a,"SYNTHETIC CONFIRMED NOTE");click(a,"保存笔记");check(shows(a,"正在保存"),"save reports pending before durable completion");runOnMainSync(()->callActivityOnStop(a));check(release.getCount()==1,"queue remains blocked at page stop");release.countDown();((ExecutorService)field(a,"storage")).submit(()->{}).get(8,TimeUnit.SECONDS);close(a);
   check(new NoteStore(context).read(id).getJSONArray("attachments").length()==1,"confirmed queued note survives leaving page");check(!shows(a,"SYNTHETIC CONFIRMED NOTE"),"late save callback cannot repaint stopped page");
  }finally{release.countDown();close(a);new NoteStore(context).delete(id);}
 }
 private Notification notification(){for(StatusBarNotification n:context.getSystemService(NotificationManager.class).getActiveNotifications())if(n.getId()==CaptureService.NOTIFICATION_ID)return n.getNotification();return null;}
 private String startSynthetic()throws Exception{await(()->FixtureCaptureService.instance==null);FixtureCaptureService.arm(FixtureCaptureService.Mode.NORMAL);started.set(null);String token=CaptureService.START_GATE.issue(true,true,true,true,CaptureEnvironment.unlocked(context),true,SystemClock.elapsedRealtime());AudioDeviceInfo input=CaptureEnvironment.input(context);runOnMainSync(()->context.startForegroundService(new Intent(context,FixtureCaptureService.class).setAction(CaptureService.START).putExtra("token",token).putExtra("input",input.getId()).putExtra("title","SYNTHETIC UI PRIVATE TITLE")));await(()->started.get()!=null);String id=started.get();await(()->CaptureService.snapshot(id)!=null&&CaptureService.snapshot(id).samples>0&&notification()!=null);return id;}
 private void liveControls()throws Exception{
  String id=startSynthetic();RecordingActivity a=open(id),reopened=null;CountDownLatch release=block(a);
  try{write(a,"SYNTHETIC LIVE NOTE");click(a,"保存笔记");runOnMainSync(()->{try{((Runnable)field(a,"update")).run();}catch(Exception failure){throw new RuntimeException(failure);}});check(shows(a,"正在保存"),"live refresh preserves pending save status");check(shows(a,"暂停录制")&&shows(a,"结束会议"),"pause and end remain available while save waits");
   Notification n=notification();check(!n.extras.toString().contains("SYNTHETIC UI PRIVATE TITLE")&&!n.extras.toString().contains("SYNTHETIC LIVE NOTE"),"notification carries no private title or note");
   click(a,"暂停录制");await(()->CaptureService.snapshot(id)==null&&FixtureCaptureService.instance==null);check(!shows(a,"已暂停"),"pending storage does not prematurely claim durable pause");release.countDown();await(()->shows(a,"已暂停"));
   try(AudioSegmentStore audio=new AudioSegmentStore(context)){check(audio.state(id).equals("paused"),"page pause reaches persisted pause");}
   click(a,"结束会议");await(()->shows(a,"已结束"));close(a);reopened=open(id);check(shows(reopened,"已结束")&&CaptureService.snapshot(id)==null,"ended meeting reopens without collection");close(reopened);
  }finally{release.countDown();CaptureService.control(id,false);await(()->CaptureService.snapshot(id)==null&&FixtureCaptureService.instance==null);close(a,reopened);new NoteStore(context).delete(id);}
 }
 private void deletedLateRead()throws Exception{
  String id=prepared();RecordingActivity a=open(id);CountDownLatch release=block(a);
  try{runOnMainSync(()->invoke(a,"render"));new NoteStore(context).delete(id);release.countDown();await(()->shows(a,"已隐藏"));check(!shows(a,"SYNTHETIC UI PRIVATE TITLE"),"delete blocks queued read from republishing title");check(CaptureService.snapshot(id)==null,"read and deletion never start capture");close(a);
  }finally{release.countDown();close(a);new NoteStore(context).delete(id);}
 }
 private void deleteLeaves()throws Exception{
  String id=prepared();RecordingActivity a=open(id);CountDownLatch release=block(a);
  try{runOnMainSync(()->invoke(a,"delete"));check(shows(a,"尚未确认删除完成"),"delete reports pending before barrier completion");runOnMainSync(()->callActivityOnStop(a));check(release.getCount()==1,"queue remains blocked at page stop");release.countDown();((ExecutorService)field(a,"storage")).submit(()->{}).get(8,TimeUnit.SECONDS);close(a);check(context.getSharedPreferences("deletion-barriers",0).contains(id),"confirmed delete survives page departure");try{new NoteStore(context).read(id);check(false,"deleted meeting remains inaccessible");}catch(SecurityException expected){check(true,"deleted meeting remains inaccessible");}}
  finally{release.countDown();close(a);new NoteStore(context).delete(id);}
 }
 private static CheckBox consentBox(View view){
  if(view instanceof CheckBox)return (CheckBox)view;
  if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){CheckBox found=consentBox(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
  return null;
 }
 private MainActivity manualPage(String id)throws Exception {
  MainActivity activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  try{
   runOnMainSync(()->{try{
    if(id==null)invoke(activity,"createPage");else{Method detail=MainActivity.class.getDeclaredMethod("detail",String.class);detail.setAccessible(true);detail.invoke(activity,id);}
   }catch(Exception failure){throw new RuntimeException(failure);}});
   await(()->shows(activity,id==null?"创建，不开启麦克风":"保存笔记"));return activity;
  }catch(Exception|AssertionError failure){close(activity);throw failure;}
 }
 private void stopPageAndDrainQueuedWrite(Activity activity,CountDownLatch release)throws Exception {
  runOnMainSync(()->callActivityOnStop(activity));check(release.getCount()==1,"manual write remains queued at page stop");
  release.countDown();((ExecutorService)field(activity,"storage")).submit(()->{}).get(8,TimeUnit.SECONDS);
 }
 private void manualCreateLeaves()throws Exception {
  NoteStore notes=new NoteStore(context);Set<String> before=new HashSet<>(notes.ids());String title="SYNTHETIC CONFIRMED MANUAL CREATE";
  MainActivity activity=manualPage(null);CountDownLatch release=block(activity);
  try{
   write(activity,title);runOnMainSync(()->{CheckBox agree=consentBox(activity.getWindow().getDecorView());if(agree==null)throw new AssertionError("manual consent missing");agree.setChecked(true);});
   click(activity,"创建，不开启麦克风");check(shows(activity,"正在创建"),"manual create reports pending before commit");stopPageAndDrainQueuedWrite(activity,release);
   Set<String> created=new HashSet<>(notes.ids());created.removeAll(before);check(created.size()==1,"confirmed manual create survives page departure exactly once");
   String createdId=created.size()==1?created.iterator().next():null;
   check(createdId!=null&&notes.read(createdId).getString("title").equals(title)&&CaptureService.snapshot(createdId)==null,"manual create retains explicit title without starting capture");
   check(!shows(activity,title),"manual create callback cannot repaint stopped page");
  }finally{
   release.countDown();close(activity);
   Set<String> created=new HashSet<>(notes.ids());created.removeAll(before);for(String id:created)notes.delete(id);
  }
 }
 private void manualWriteLeaves(boolean end)throws Exception {
  NoteStore notes=new NoteStore(context);String title=end?"SYNTHETIC MANUAL END":"SYNTHETIC MANUAL APPEND";
  String id=notes.create(title,true);MainActivity activity=null;CountDownLatch release=null;
  try{
   activity=manualPage(id);release=block(activity);
   if(!end)write(activity,"SYNTHETIC CONFIRMED MANUAL NOTE");click(activity,end?"结束会议":"保存笔记");
   check(shows(activity,"正在处理"),"manual write reports pending before commit");stopPageAndDrainQueuedWrite(activity,release);
   JSONObject saved=notes.read(id);
   if(end)check(saved.getLong("ended_at")>0,"confirmed manual end survives departure");
   else check(saved.getJSONArray("attachments").length()==1&&saved.getJSONArray("attachments").getJSONObject(0).getString("text").equals("SYNTHETIC CONFIRMED MANUAL NOTE"),"confirmed manual append survives departure exactly once");
   check(!shows(activity,title)&&!shows(activity,"SYNTHETIC CONFIRMED MANUAL NOTE"),"manual write callback cannot repaint stopped page");
   check(CaptureService.snapshot(id)==null,"manual write does not start capture");
  }finally{if(release!=null)release.countDown();close(activity);notes.delete(id);}
 }
 private static final String PARTIAL="部分会议资料暂不可访问，相关副本保留，清理未完成。";
 private boolean focused(Activity activity){AtomicBoolean answer=new AtomicBoolean();runOnMainSync(()->answer.set(activity.hasWindowFocus()));return answer.get();}
 private boolean activeWindowText(String text,boolean click){
  AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root==null)return false;
  try{java.util.List<AccessibilityNodeInfo> found=root.findAccessibilityNodeInfosByText(text);
   try{for(AccessibilityNodeInfo node:found)if(!click||node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;return false;}
   finally{for(AccessibilityNodeInfo node:found)node.recycle();}
  }finally{root.recycle();}
 }
 private void drain(Activity activity)throws Exception{((ExecutorService)field(activity,"storage")).submit(()->{}).get(8,TimeUnit.SECONDS);runOnMainSync(()->{});}
 private boolean hidden(Activity activity,String... secrets){for(String secret:secrets)if(shows(activity,secret))return false;return true;}
 private void unresolvedReadAndWrite(NoteStore notes,String id)throws Exception{
  try{notes.read(id);check(false,"unknown companion cannot be read directly");}
  catch(NoteStore.UnresolvedMeeting expected){check(id.equals(expected.meetingId),"unknown companion read refusal retains exact meeting scope");}
  try{notes.append(id,1,"SYNTHETIC MUST NOT COMMIT","unassigned");check(false,"unknown companion cannot accept a new write");}
  catch(NoteStore.UnresolvedMeeting expected){check(id.equals(expected.meetingId),"unknown companion write refusal retains exact meeting scope");}
 }
 /** A queued body has already passed storage gates when deletion occurs, but has not reached the UI. */
 private void listingDeleteBeforePublication(MainActivity activity,NoteStore notes,String id,boolean noteOnly)throws Exception{
  CountDownLatch releaseStorage=block(activity),enteredUi=new CountDownLatch(1),releaseUi=new CountDownLatch(1);
  AtomicBoolean uiTimedOut=new AtomicBoolean();
  try{
   runOnMainSync(()->{invoke(activity,"home");new Handler(Looper.getMainLooper()).post(()->{
    enteredUi.countDown();try{if(!releaseUi.await(10,TimeUnit.SECONDS))uiTimedOut.set(true);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();uiTimedOut.set(true);}
   });});
   if(!enteredUi.await(3,TimeUnit.SECONDS))throw new AssertionError("listing publication gate did not enter");
   releaseStorage.countDown();((ExecutorService)field(activity,"storage")).submit(()->{}).get(8,TimeUnit.SECONDS);
   if(uiTimedOut.get())throw new AssertionError("listing publication gate elapsed before deletion");
   if(noteOnly){JSONObject saved=notes.read(id);notes.deleteNote(id,saved.getJSONArray("attachments").getJSONObject(0).getString("id"),saved.getLong("revision"));}
   else notes.delete(id);
   if(uiTimedOut.get())throw new AssertionError("listing publication gate elapsed during deletion");
  }finally{releaseStorage.countDown();releaseUi.countDown();}
  runOnMainSync(()->{});await(()->shows(activity,PARTIAL));
 }
 /** Deliberately assembled encrypted layouts, not a claimed reproduction of a real power loss. */
 private void unresolvedListing(boolean lone)throws Exception{
  NoteStore notes=new NoteStore(context);String kind=lone?"LONE":"MIXED";
  String privateTitle="SYNTHETIC UNKNOWN "+kind+" TITLE",privateBody="SYNTHETIC UNKNOWN "+kind+" BODY";
  String liveTitle="SYNTHETIC NEIGHBOR "+kind+" TITLE",liveBody="SYNTHETIC NEIGHBOR "+kind+" BODY";
  String id=notes.create(privateTitle,true),neighbor=null;MainActivity activity=null;CountDownLatch release=null;
  File base=new File(new File(context.getNoBackupFilesDir(),"meetings"),id+".enc"),pending=new File(base.getPath()+".new"),backup=new File(base.getPath()+".bak");
  try{
   byte[] old=Files.readAllBytes(base.toPath());notes.append(id,0,privateBody,"unassigned");byte[] latest=Files.readAllBytes(base.toPath());
   neighbor=notes.create(liveTitle,true);notes.append(neighbor,0,liveBody,"unassigned");
   synchronized(MeetingPrivacy.LOCK){Files.write(pending.toPath(),latest);if(lone)Files.delete(base.toPath());else Files.write(base.toPath(),old);}
   check(!Arrays.equals(old,latest)&&pending.isFile()&&(lone?!base.exists():base.isFile()),"fixture establishes distinct valid encrypted versions for "+kind);
   activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity page=activity;
   runOnMainSync(()->invoke(page,"home"));await(()->shows(page,PARTIAL)&&shows(page,liveTitle));
   check(hidden(page,id,privateTitle,privateBody),"partial listing reveals no unknown meeting ID title or body for "+kind);
   check(focused(page)&&shows(page,"新建纯手工笔记会议"),"local quarantine leaves the actual home window interactive for "+kind);
   click(page,"新建纯手工笔记会议");await(()->shows(page,"创建，不开启麦克风"));
   check(focused(page)&&shows(page,"我有权保存这些笔记"),"manual preparation remains reachable beside unknown copies for "+kind);
   click(page,"返回");await(()->shows(page,PARTIAL)&&shows(page,liveTitle));click(page,liveTitle);await(()->shows(page,liveBody));
   check(focused(page)&&hidden(page,id,privateTitle,privateBody),"verified neighbor opens with its own body only for "+kind);
   check(CaptureService.snapshot(id)==null&&CaptureService.snapshot(neighbor)==null,"listing and neighbor navigation never start recording for "+kind);
   unresolvedReadAndWrite(notes,id);
   check(Arrays.equals(latest,Files.readAllBytes(pending.toPath()))&&(lone?!base.exists():Arrays.equals(old,Files.readAllBytes(base.toPath())))&&!backup.exists(),"read and rejected write neither promote nor discard unknown bytes for "+kind);
   listingDeleteBeforePublication(page,notes,neighbor,lone);
   check(hidden(page,liveTitle,liveBody,id,privateTitle,privateBody)&&focused(page),"already-read neighbor cannot republish after "+(lone?"note":"meeting")+" deletion beside quarantine");
   release=block(page);runOnMainSync(()->{invoke(page,"home");callActivityOnStop(page);});release.countDown();drain(page);
   check(hidden(page,liveTitle,liveBody,id,privateTitle,privateBody)&&!shows(page,PARTIAL),"late partial listing cannot repaint a stopped page for "+kind);
   KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
   check(Arrays.equals(latest,Files.readAllBytes(pending.toPath()))&&(lone?!base.exists():Arrays.equals(old,Files.readAllBytes(base.toPath())))&&!backup.exists()
     &&keys.containsAlias("sidecar.note."+id)&&!context.getSharedPreferences("deletion-barriers",0).contains(id),"neighbor deletion and departure preserve unknown bytes key and deletion scope for "+kind);
  }finally{if(release!=null)release.countDown();close(activity);notes.delete(id);if(neighbor!=null)notes.delete(neighbor);}
 }
 private void globalListingFailure()throws Exception{
  NoteStore notes=new NoteStore(context);String title="SYNTHETIC GLOBAL FAILURE PRIVATE TITLE",id=notes.create(title,true);MainActivity activity=null;
  AtomicInteger injected=new AtomicInteger();
  try{
   activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity page=activity;
   runOnMainSync(()->invoke(page,"home"));await(()->shows(page,title));
   // Inject an ordinary I/O failure through the existing storage probe, not the scoped type.
   NoteStore broken=new NoteStore(context,()->{},stage->{if(stage.equals("before_read")){injected.incrementAndGet();throw new IOException("SYNTHETIC GLOBAL STORAGE FAILURE "+id);}});
   ((ExecutorService)field(page,"storage")).submit(()->{try{Field store=MainActivity.class.getDeclaredField("store");store.setAccessible(true);store.set(page,broken);}catch(Exception failure){throw new RuntimeException(failure);}}).get(8,TimeUnit.SECONDS);
   runOnMainSync(()->invoke(page,"home"));await(()->activeWindowText("操作未完成",false));
   check(injected.get()>0&&activeWindowText("保存或读取未完成",false),"ordinary storage failure remains an explicit incomplete-operation dialog");
   check(hidden(page,title,id)&&!shows(page,PARTIAL)&&!shows(page,"新建纯手工笔记会议"),"global failure cannot become a partial-success listing or retain old private content");
   check(!activeWindowText(id,false)&&!activeWindowText(title,false)&&!activeWindowText("SYNTHETIC GLOBAL STORAGE FAILURE",false),"global error dialog exposes no private fault payload");
   if(!activeWindowText("知道了",true))throw new AssertionError("global failure dialog could not be dismissed");
  }finally{close(activity);notes.delete(id);}
 }
 private static void restoreClock(SharedPreferences clock,java.util.Map<String,?> saved)throws IOException{
  SharedPreferences.Editor edit=clock.edit().clear();
  for(java.util.Map.Entry<String,?> item:saved.entrySet()){
   Object value=item.getValue();String key=item.getKey();
   if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);
   else if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
   else if(value instanceof Float)edit.putFloat(key,(Float)value);else throw new IOException("unexpected fixture clock value type");
  }
  if(!edit.commit()||!clock.getAll().equals(saved))throw new IOException("fixture clock restoration did not commit exactly");
 }
 /** The storage sweep succeeds first; only the final enumeration read becomes locally unresolved. */
 private void listingTailClockFailure()throws Exception{
  NoteStore notes=new NoteStore(context);String titleA="SYNTHETIC TAIL CLOCK A",titleB="SYNTHETIC TAIL CLOCK B";
  String idA=notes.create(titleA,true),idB=null;MainActivity activity=null;SharedPreferences clock=context.getSharedPreferences("retention-clock",0);
  java.util.Map<String,?> savedClock=null;File pending=null;byte[] savedBytes=null;AtomicBoolean claimedPending=new AtomicBoolean();Throwable primary=null;
  try{
   idB=notes.create(titleB,true);
   activity=(MainActivity)startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));final MainActivity page=activity;
   runOnMainSync(()->invoke(page,"home"));await(()->shows(page,titleA)&&shows(page,titleB));
   if(shows(page,PARTIAL))throw new AssertionError("tail fixture needs a fully readable baseline");
   java.util.List<String> ids=notes.ids();if(ids.size()<2)throw new AssertionError("tail fixture needs an earlier valid entry");
   for(String id:ids)notes.read(id);
   if(!ids.equals(notes.ids()))throw new AssertionError("tail fixture inventory changed during baseline validation");
   final String target=ids.get(ids.size()-1),earlyTitle=idA.compareTo(idB)<0?titleA:titleB;
   final String targetTitle=notes.read(target).getString("title");final File base=new File(new File(context.getNoBackupFilesDir(),"meetings"),target+".enc");
   pending=new File(base.getPath()+".new");final File created=pending;
   if(created.exists()||new File(base.getPath()+".bak").exists())throw new AssertionError("tail fixture refuses existing companions");
   savedBytes=Files.readAllBytes(base.toPath());final byte[] exactBytes=savedBytes;
   savedClock=new java.util.HashMap<>(clock.getAll());final java.util.Map<String,?> originalClock=savedClock;
   if(!(originalClock.get("boot") instanceof Integer))throw new AssertionError("tail fixture needs a persisted boot anchor");
   final int expectedReads=2*ids.size();AtomicInteger reads=new AtomicInteger(),returns=new AtomicInteger(),injected=new AtomicInteger();AtomicBoolean committed=new AtomicBoolean();
   NoteStore probed=new NoteStore(context,()->{},stage->{
    if(stage.equals("before_return"))returns.incrementAndGet();
    if(!stage.equals("before_read")||reads.incrementAndGet()!=expectedReads)return;
    if(returns.get()!=expectedReads-1||!clock.getAll().equals(originalClock))throw new IOException("tail fixture phase proof failed");
    // before_read runs after this read's now(). The N sweep reads and N-1 listing reads already returned.
    Files.createFile(created.toPath());claimedPending.set(true);Files.write(created.toPath(),exactBytes,java.nio.file.StandardOpenOption.WRITE);
    if(!Arrays.equals(exactBytes,Files.readAllBytes(created.toPath())))throw new IOException("tail fixture pending bytes did not persist");
    if(!clock.edit().putInt("boot",((Integer)originalClock.get("boot"))+1).commit())throw new IOException("tail fixture clock change did not commit");
    committed.set(true);injected.incrementAndGet();
   });
   ((ExecutorService)field(page,"storage")).submit(()->{try{Field store=MainActivity.class.getDeclaredField("store");store.setAccessible(true);store.set(page,probed);}catch(Exception failure){throw new RuntimeException(failure);}}).get(8,TimeUnit.SECONDS);
   runOnMainSync(()->invoke(page,"home"));await(()->activeWindowText("操作未完成",false)||shows(page,PARTIAL));drain(page);
   check(reads.get()==expectedReads&&returns.get()==expectedReads-1&&injected.get()==1&&committed.get(),"tail fault occurs exactly after all sweep reads and earlier listing entries passed their gates");
   check(activeWindowText("操作未完成",false)&&activeWindowText("设备已重启",false)&&!shows(page,PARTIAL),"Activity final clock revalidation refuses partial success after late local quarantine");
   check(hidden(page,earlyTitle,targetTitle,target)&&!shows(page,"新建纯手工笔记会议"),"late clock distrust hides previously read and previously displayed titles");
   KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);
   check(Arrays.equals(exactBytes,Files.readAllBytes(base.toPath()))&&Arrays.equals(exactBytes,Files.readAllBytes(created.toPath()))
     &&keys.containsAlias("sidecar.note."+target)&&!context.getSharedPreferences("deletion-barriers",0).contains(target),"late gate failure preserves exact target ciphertext key and deletion scope");
   if(activeWindowText("操作未完成",false)&&!activeWindowText("知道了",true))throw new AssertionError("tail clock dialog could not be dismissed");
  }catch(Exception|Error failure){primary=failure;throw failure;}
  finally{
   Throwable cleanupFailure=null;try{close(activity);}catch(Exception|Error failure){cleanupFailure=failure;}
   boolean restored=savedClock==null;
   try{if(savedClock!=null){restoreClock(clock,savedClock);restored=true;}}
   catch(Exception|Error failure){if(cleanupFailure==null)cleanupFailure=failure;else cleanupFailure.addSuppressed(failure);}
   try{
    if(!restored)throw new IOException("tail cleanup withheld until original clock is restored");
    if(claimedPending.get()){
     synchronized(MeetingPrivacy.LOCK){
      if(pending==null||savedBytes==null||!Arrays.equals(savedBytes,Files.readAllBytes(pending.toPath())))throw new IOException("tail fixture refuses to remove changed or missing pending evidence");
      Files.delete(pending.toPath());
     }
    }else if(pending!=null&&pending.exists())throw new IOException("tail fixture preserves an unclaimed pending file");
    notes.delete(idA);if(idB!=null)notes.delete(idB);
   }catch(Exception|Error failure){if(cleanupFailure==null)cleanupFailure=failure;else cleanupFailure.addSuppressed(failure);}
   if(cleanupFailure!=null){if(primary!=null)primary.addSuppressed(cleanupFailure);else if(cleanupFailure instanceof Exception)throw (Exception)cleanupFailure;else throw (Error)cleanupFailure;}
  }
 }
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){
  Bundle result=new Bundle();Activity home=null;BroadcastReceiver receiver=null;int resultCode=0;
  try{
   context=getTargetContext();if(!Build.HARDWARE.equals("ranchu")||!context.getPackageName().equals("local.sidecar.copilot.fixture"))throw new SecurityException("isolated emulator only");
   getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.RECORD_AUDIO);getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);CaptureEnvironment.channel(context);
   receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){started.set(i.getStringExtra("meeting"));}};
   context.registerReceiver(receiver,new IntentFilter("local.sidecar.CAPTURE_STARTED"),Context.RECEIVER_NOT_EXPORTED);
   home=startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
   check(!CaptureEnvironment.supported(),"real-device capture gate remains closed");
   saveLeaves();liveControls();deletedLateRead();deleteLeaves();
   // A top MainActivity can be reused instead of emitting the launch event startActivitySync awaits.
   close(home);home=null;manualCreateLeaves();manualWriteLeaves(false);manualWriteLeaves(true);
   unresolvedListing(false);unresolvedListing(true);globalListingFailure();listingTailClockFailure();
   check(context.getSystemService(AudioManager.class).getActiveRecordingConfigurations().isEmpty(),"no AudioRecord active");resultCode=failures==0?-1:0;
  }catch(Throwable failure){report.append("ERROR ").append(android.util.Log.getStackTraceString(failure));resultCode=0;}
  finally{
   if(receiver!=null)context.unregisterReceiver(receiver);
   try{close(home);}catch(Throwable failure){report.append("TEARDOWN ERROR ").append(android.util.Log.getStackTraceString(failure));resultCode=0;}
  }
  result.putString("stream",report.toString()+"RESULT "+checks+" checks, "+failures+" failures; synthetic only\n");finish(resultCode,result);
 }
}

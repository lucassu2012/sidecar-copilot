package local.sidecar.copilot;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.*;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private LinearLayout page;
    private NoteStore store; // Confined to the storage executor.
    private final ExecutorService storage=Executors.newSingleThreadExecutor(r->new Thread(r,"Sidecar-notes-view"));
    private volatile long epoch;
    private volatile boolean visible=true,destroyed;
    private volatile SharedPreferences meetingBarriers,noteBarriers;
    private final Object listenerLock=new Object();
    private boolean sensitiveVisible;
    private final java.util.Set<String> shownMeetings=new java.util.HashSet<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener revocation=(preferences,key)->onRevocation(key);
    private void onRevocation(String key){
        Runnable hide=()->{if(destroyed||!visible||!sensitiveVisible)return;String meeting=key==null?null:key.split(":",2)[0];if(meeting==null||shownMeetings.contains(meeting)){selected=null;lockedPage();}};
        if(Looper.myLooper()==Looper.getMainLooper())hide.run();else privacyTimer.post(hide);
    }
    private void prepareBarriers(){
        if(meetingBarriers!=null)return;
        SharedPreferences meetings=getSharedPreferences("deletion-barriers",MODE_PRIVATE),notes=getSharedPreferences("note-deletion-barriers",MODE_PRIVATE);
        meetings.getAll();notes.getAll(); // Preload exclusively on the storage worker.
        synchronized(listenerLock){if(destroyed)return;meetingBarriers=meetings;noteBarriers=notes;meetings.registerOnSharedPreferenceChangeListener(revocation);notes.registerOnSharedPreferenceChangeListener(revocation);}
    }
    // Preloaded preferences only: no filesystem lookup or commit on the UI thread.
    private boolean marked(SharedPreferences preferences,String key){
        try{return DurableDeletionMarkers.isMarked(preferences,key);}
        catch(RuntimeException unavailable){return true;}
    }
    private boolean deletedNote(JSONObject meeting)throws Exception {
        JSONArray notes=meeting.getJSONArray("attachments");String id=meeting.getString("id");
        for(int i=0;i<notes.length();i++)if(marked(noteBarriers,id+":"+notes.getJSONObject(i).getString("id")))return true;
        return false;
    }
    private interface Work<T>{T run()throws Exception;}
    private interface Done<T>{void run(T result)throws Exception;}
    private boolean current(long ticket){return visible&&!destroyed&&epoch==ticket;}
    private <T> void background(Work<T> work,Done<T> done){background(work,done,false);}
    private <T> void background(Work<T> work,Done<T> done,boolean confirmedMutation){
        final long ticket=++epoch;
        storage.execute(()->{
            try{
                if(!current(ticket)&&!confirmedMutation)return;
                if(store==null){
                    NoteStore.sweepForListing(getApplicationContext(),()->Thread.currentThread().isInterrupted()||(!confirmedMutation&&!current(ticket)));
                    store=new NoteStore(getApplicationContext());
                }
                prepareBarriers();
                if(!current(ticket)&&!confirmedMutation)return;
                T result=work.run();
                privacyTimer.post(()->{
                    if(!current(ticket))return;
                    if(!CaptureEnvironment.unlocked(this)){lockedPage();return;}
                    try{done.run(result);}catch(Exception failure){error(failure);}
                });
            }catch(Exception failure){privacyTimer.post(()->{if(current(ticket))error(failure);});}
        });
    }
    // Confirmed writes remain authorized after departure; only reads and late UI work are cancelled.
    private void change(Work<Void> mutation,Runnable next){change(mutation,next,true);}
    private void change(Work<Void> mutation,Runnable next,boolean confirmedMutation){
        layout("正在处理，请等待确认");background(mutation,result->next.run(),confirmedMutation);
    }
    private static final class Entry{String id;JSONObject body;long deadline;Entry(String id,JSONObject body,long deadline){this.id=id;this.body=body;this.deadline=deadline;}}
    private static final class Listing {
        final List<Entry> entries=new ArrayList<>();
        final java.util.Set<String> unavailable=new java.util.HashSet<>();
    }

    private String selected;
    private final Handler privacyTimer=new Handler(Looper.getMainLooper());
    private final Runnable hideContent=()->{selected=null;lockedPage();};
    private long hideAt=Long.MAX_VALUE;
    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        public void onReceive(Context c,Intent i) { selected=null; lockedPage(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        storage.execute(()->AudioRetentionJob.schedule(getApplicationContext()));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        IntentFilter privacyEvents=new IntentFilter(Intent.ACTION_SCREEN_OFF);
        privacyEvents.addAction(Intent.ACTION_TIME_CHANGED);
        if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(screenOff,privacyEvents,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenOff,privacyEvents);
        lockedPage();
    }
    private void layout(String title) {
        sensitiveVisible=false;shownMeetings.clear();
        privacyTimer.removeCallbacks(hideContent);hideAt=Long.MAX_VALUE;
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int space=(int)(24*getResources().getDisplayMetrics().density);
        page.setPadding(space,space,space,space);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        scroll.requestApplyInsets();
        button("线下会议准备（当前未开放录音）",v->startActivity(new Intent(this,MeetingPreparationActivity.class)));
        text(title,25); text("开发验证版 · 本机加密资料 · 真实录音未开放",14);
        text("尚未通过设备安全验收，请仅用测试资料。录音仍关闭；可在会议准备页管理权限。无网络和相机权限。",14);
    }
    private void scheduleHide(long deadline) {
        hideAt=Math.min(hideAt,deadline);
        privacyTimer.removeCallbacks(hideContent);
        privacyTimer.postAtTime(hideContent,hideAt);
    }
    private void text(String value,int size) { TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setPadding(0,12,0,12);page.addView(t); }
    private Button button(String label,View.OnClickListener click) { Button b=new Button(this);b.setText(label);b.setOnClickListener(click);page.addView(b);return b; }
    private EditText editor(String hint,boolean multi) { EditText e=new EditText(this);e.setHint(hint);e.setSaveEnabled(false);e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);e.setInputType(multi?131073:1);page.addView(e);return e; }
    private void lockedPage() {
        epoch++;
        layout("Sidecar Copilot"); text("历史资料需设备认证。离开页面会清空未保存输入。",16);
        button("认证并打开本地空间",v->authenticate());
    }
    private void authenticate() {
        KeyguardManager km=getSystemService(KeyguardManager.class);
        if(!km.isDeviceSecure()) { new AlertDialog.Builder(this).setMessage("请先在系统设置中启用设备锁。本应用不会修改设备安全设置。").setPositiveButton("知道了",null).show(); return; }
        Intent intent=km.createConfirmDeviceCredentialIntent("打开 Sidecar 本地资料","资料只保存在这台设备；不启用录音");
        if(intent==null) { error(new IllegalStateException("设备认证入口不可用")); return; }
        startActivityForResult(intent,10);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==10 && result==RESULT_OK){visible=true;home();}else lockedPage();
    }
    private void home() {
        selected=null;layout("正在读取本地会议资料");
        background(()->{
            final long ticket=epoch;
            NoteStore.SweepResult maintenance=store.sweepForListing(()->!current(ticket)||Thread.currentThread().isInterrupted());
            Listing listing=new Listing();listing.unavailable.addAll(maintenance.unavailable);
            for(String id:store.ids()){
                if(!current(ticket))return listing;
                if(listing.unavailable.contains(id))continue;
                try{JSONObject meeting=store.read(id);listing.entries.add(new Entry(id,meeting,SystemClock.uptimeMillis()+store.visibleLifetime(meeting)));}
                catch(NoteStore.Expired expired){}
                catch(NoteStore.UnresolvedMeeting unresolved){
                    if(!id.equals(unresolved.meetingId))throw new SecurityException("unresolved meeting scope mismatch");
                    listing.unavailable.add(id);
                }
            }
            if(!listing.unavailable.isEmpty())store.revalidateListingAccess(()->!current(ticket)||Thread.currentThread().isInterrupted());
            return listing;
        },listing->{
            layout("本地会议资料");text("纯手工笔记不代表音频覆盖。真实录制仍待设备和安全验证。",16);
            button("新建纯手工笔记会议",v->createPage());
            if(!listing.unavailable.isEmpty())text("部分会议资料暂不可访问，相关副本保留，清理未完成。",16);
            for(Entry entry:listing.entries){
                if(marked(meetingBarriers,entry.id)||deletedNote(entry.body)||SystemClock.uptimeMillis()>=entry.deadline)continue;
                JSONObject meeting=entry.body;
                if(meeting.getString("capture_mode").equals("in_person"))button(meeting.getString("title")+" · 线下会议",v->startActivity(new Intent(this,RecordingActivity.class).putExtra("meeting",entry.id)));
                else button(meeting.getString("title")+" · "+(meeting.getLong("ended_at")==0?"进行中":"已结束"),v->detail(entry.id));
                shownMeetings.add(entry.id);sensitiveVisible=true;scheduleHide(entry.deadline);
            }
        });
    }
    private void createPage() {
        epoch++;layout("创建纯手工笔记会议"); EditText title=editor("会议名称",false);
        CheckBox agree=new CheckBox(this); agree.setText("我有权保存这些笔记；同意标题和每条笔记各自从创建起保留90天，到期不可访问并清理，不上传、不分享。");page.addView(agree);
        button("创建，不开启麦克风",v->{String value=title.getText().toString();boolean consent=agree.isChecked();layout("正在创建，请等待确认");background(()->store.create(value,consent),this::detail,true);});
        button("返回",v->home());
    }
    private String date(long value) {return DateFormat.getDateTimeInstance().format(new Date(value));}
    private void detail(String id) {
        layout("正在读取已获准的笔记");
        background(()->{JSONObject meeting=store.read(id);return new Entry(id,meeting,SystemClock.uptimeMillis()+store.visibleLifetime(meeting));},entry->{
            if(marked(meetingBarriers,id)||deletedNote(entry.body)||SystemClock.uptimeMillis()>=entry.deadline){lockedPage();return;}
            JSONObject m=entry.body;selected=id;long rev=m.getLong("revision");
            layout(m.getString("title"));shownMeetings.add(id);sensitiveVisible=true;scheduleHide(entry.deadline);
            text("纯手工笔记 · "+(m.getLong("ended_at")==0?"进行中":"已结束 · 可继续补充资料")+"\n本场最晚资料截止："+date(m.getLong("expires_at")),16);
            JSONArray entries=m.getJSONArray("attachments");
            for(int i=0;i<entries.length();i++) {
                JSONObject n=entries.getJSONObject(i);
                text((n.getString("scope_kind").equals("meeting_background")?"会议背景":"待归类")+" · "+date(n.getLong("created_at"))+"\n保留至："+date(n.getLong("expires_at"))+"\n"+n.getString("text"),16);
                String noteId=n.getString("id");
                button("删除这条笔记",v->new AlertDialog.Builder(this).setMessage("不可撤销。将屏蔽并清理这条笔记，保留其他资料；不保证闪存物理擦除。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{change(()->{store.deleteNote(id,noteId,rev);return null;},()->detail(id),true);}).show());
            }
            EditText note=editor("补充笔记（离开页面前请保存）",true);
            CheckBox background=new CheckBox(this);background.setText("作为整场会议背景（不证明某段发言或决定）");page.addView(background);
            button("保存笔记",v->{String body=note.getText().toString(),scope=background.isChecked()?"meeting_background":"unassigned";change(()->{store.append(id,rev,body,scope);return null;},()->detail(id));});
            if(m.getLong("ended_at")==0) button("结束会议",v->change(()->{store.end(id,rev);return null;},()->detail(id)));
            button("删除本场会议及全部笔记",v->new AlertDialog.Builder(this).setMessage("不可撤销。将先屏蔽资料并清理应用管理的密钥和文件；不保证闪存物理残留消失。").setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->{change(()->{store.delete(id);return null;},this::home,true);}).show());
            button("返回会议列表",v->home());
        });
    }
    private void error(Exception e) {
        selected=null;lockedPage(); // Inaccessible/stale content must not survive in the old view tree.
        // Exception classes only: cryptographic/library messages may include identifiers.
        String message = (e instanceof IllegalArgumentException || e instanceof IllegalStateException || e instanceof SecurityException)
            ? e.getMessage() : "保存或读取未完成，请重新认证后重试；没有声明保存成功。（"+e.getClass().getSimpleName()+"）";
        new AlertDialog.Builder(this).setTitle("操作未完成").setMessage(message).setPositiveButton("知道了",null).setNeutralButton("重新认证",(d,w)->authenticate()).show();
    }
    @Override protected void onStop() {
        super.onStop();visible=false;selected=null;
        // Never serialize titles, note bodies or editor drafts into saved-instance state.
        lockedPage();
    }
    @Override protected void onResume(){super.onResume();visible=true;}
    @Override protected void onDestroy(){destroyed=true;epoch++;synchronized(listenerLock){if(meetingBarriers!=null)meetingBarriers.unregisterOnSharedPreferenceChangeListener(revocation);if(noteBarriers!=null)noteBarriers.unregisterOnSharedPreferenceChangeListener(revocation);}privacyTimer.removeCallbacksAndMessages(null);storage.shutdown();unregisterReceiver(screenOff);super.onDestroy();}
}

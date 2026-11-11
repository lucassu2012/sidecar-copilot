package local.sidecar.copilot;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.*;

/** Meeting control/evidence view. Opening, returning, or restoring this page never starts capture. */
public final class RecordingActivity extends Activity {
    private String id;
    private LinearLayout page;
    private final java.util.List<Button> captureControls=new java.util.ArrayList<>();
    private TextView status;
    private final ExecutorService storage=Executors.newSingleThreadExecutor(r->new Thread(r,"Sidecar-meeting-view"));
    private final ExecutorService replayStops=Executors.newSingleThreadExecutor(r->new Thread(r,"Sidecar-replay-stop"));
    private volatile SharedPreferences barriers,noteBarriers;
    private final Object listenerLock=new Object();
    private boolean sensitiveVisible;
    private final SharedPreferences.OnSharedPreferenceChangeListener revocation=(preferences,key)->onRevocation(key);
    private void onRevocation(String key){
        Runnable clear=()->{if(!destroyed&&visible&&sensitiveVisible&&(key==null||key.equals(id)||key.startsWith(id+":")))hide();};
        if(Looper.myLooper()==Looper.getMainLooper())clear.run();else handler.post(clear);
    }
    private void prepareBarriers(){
        if(barriers!=null)return;
        SharedPreferences meetings=getSharedPreferences("deletion-barriers",MODE_PRIVATE),notes=getSharedPreferences("note-deletion-barriers",MODE_PRIVATE);
        meetings.getAll();notes.getAll();
        synchronized(listenerLock){if(destroyed)return;barriers=meetings;noteBarriers=notes;meetings.registerOnSharedPreferenceChangeListener(revocation);notes.registerOnSharedPreferenceChangeListener(revocation);}
    }
    // Preloaded preferences only: no filesystem lookup or commit on the UI thread.
    private boolean marked(SharedPreferences preferences,String key){
        try{return DurableDeletionMarkers.isMarked(preferences,key);}
        catch(RuntimeException unavailable){return true;}
    }
    private boolean deletedNote(JSONObject meeting)throws Exception {
        org.json.JSONArray entries=meeting.getJSONArray("attachments");
        for(int i=0;i<entries.length();i++)if(marked(noteBarriers,id+":"+entries.getJSONObject(i).getString("id")))return true;
        return false;
    }
    private volatile long epoch;
    private volatile boolean destroyed;
    private boolean loading;
    private String resources="";
    private interface Mutation {void run()throws Exception;}
    private boolean current(long ticket){return !destroyed&&visible&&epoch==ticket;}
    private boolean mayDisplay(long ticket){return current(ticket)&&CaptureEnvironment.unlocked(this)&&SystemClock.uptimeMillis()<hideAt&&(barriers==null||!marked(barriers,id));}
    private String liveText(CaptureService.Snapshot live){return (live.stopping?"停止请求已收到，正在封存；尚未确认保存完成。":"录制中")+"\n已接收音频："+(live.samples/16000)+" 秒\n输入电平："+live.level+" / 1000\n电平不证明拾音清晰完整；质量未知。"+resources;}
    private void controls(){
        CaptureService.Snapshot live=CaptureService.snapshot(id);if(live==null||live.stopping)return;
        captureControls.add(button("暂停录制",()->requestControl(false)));captureControls.add(button("结束会议",()->requestControl(true)));
    }
    private void requestControl(boolean end){CaptureService.control(id,end);for(Button control:captureControls)control.setEnabled(false);}
    private void stopReplay(){AudioFrameReplay current=replay;if(current!=null){current.requestStop();if(!replayStops.isShutdown())replayStops.execute(current::close);}}

    private volatile AudioFrameReplay replay;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long hideAt;
    private volatile boolean visible;
    private boolean showingLive;
    private final BroadcastReceiver privacy=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){hide();}};
    private final Runnable update=new Runnable(){public void run(){
        if(!visible)return;
        if(SystemClock.uptimeMillis()>=hideAt||!CaptureEnvironment.unlocked(RecordingActivity.this)){hide();return;}
        if(barriers!=null&&marked(barriers,id)){hide();return;}
        CaptureService.Snapshot live=CaptureService.snapshot(id);
        if(live==null){if(showingLive&&!loading)render();else handler.postDelayed(this,500);return;}
        if(status!=null&&!loading)status.setText(liveText(live));if(live.stopping)for(Button control:captureControls)control.setEnabled(false);
        handler.postDelayed(this,500);
    }};
    @Override public void onCreate(Bundle state){super.onCreate(state);id=getIntent().getStringExtra("meeting");getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_TIME_CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(privacy,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(privacy,filter);
    }
    private void layout(){sensitiveVisible=false;handler.removeCallbacks(update);captureControls.clear();page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(32,80,32,40);ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);}
    private TextView text(String value){TextView view=new TextView(this);view.setText(value);view.setTextSize(18);view.setPadding(0,16,0,16);page.addView(view);return view;}
    private Button button(String label,Runnable action){Button button=new Button(this);button.setText(label);button.setMinHeight(64);button.setOnClickListener(v->action.run());page.addView(button);return button;}
    private void hide(){visible=false;epoch++;loading=false;stopReplay();layout();text("会议内容已隐藏。录音状态请查看持续通知；此页面不会自动恢复录音。");button("返回身份验证",()->{startActivity(new Intent(this,MainActivity.class));finish();});}
    private void error(){hide();text("无法确认操作结果，请重新验证身份后检查。未宣告保存或清理成功。");}
    private void render(){
        final long ticket=++epoch;loading=true;layout();status=text("正在读取已获准的会议资料……");
        CaptureService.Snapshot live=CaptureService.snapshot(id);showingLive=live!=null;
        if(live!=null){status.setText(liveText(live));controls();}handler.post(update);
        storage.execute(()->{
            try{
                if(!current(ticket))return;if(id==null)throw new SecurityException("meeting missing");
                prepareBarriers();
                NoteStore notes=new NoteStore(getApplicationContext());JSONObject meeting=notes.read(id);
                if(!meeting.getString("capture_mode").equals("in_person"))throw new SecurityException("not audio meeting");
                long deadline=SystemClock.uptimeMillis()+notes.visibleLifetime(meeting);
                CaptureService.Snapshot snapshot=CaptureService.snapshot(id);String state;long saved;boolean recoveryUnknown=false;
                if(snapshot!=null){state="recording";saved=snapshot.samples;}
                else try(AudioSegmentStore audio=new AudioSegmentStore(getApplicationContext())){
                    state=audio.state(id);saved=audio.committedSamples(id);recoveryUnknown=audio.recoveryUncertainties(id).length()>0;
                    if(state.equals("ended"))meeting=notes.projectAudioEnd(id,audio.endMetadata(id));
                }
                final JSONObject result=meeting;final String currentState=state;final long samples=saved;final boolean hasRecoveryUncertainty=recoveryUnknown;
                final String sampledResources="\n最近读取的缓存："+(CaptureEnvironment.freeBytes(this)/1048576)+" MiB · 电量："+CaptureEnvironment.battery(this)+"%";
                handler.post(()->{if(!current(ticket))return;hideAt=Math.min(hideAt,deadline);if(!mayDisplay(ticket)){hide();return;}resources=sampledResources;renderLoaded(ticket,result,currentState,samples,hasRecoveryUncertainty);});
            }catch(Exception failure){handler.post(()->{if(current(ticket))error();});}
        });
    }
    private void renderLoaded(long ticket,JSONObject meeting,String state,long saved,boolean hasRecoveryUncertainty){
        if(!mayDisplay(ticket)){hide();return;}
        CaptureService.Snapshot live=CaptureService.snapshot(id);
        if(state.equals("recording")&&live==null){render();return;}
        loading=false;showingLive=live!=null;
        try{
            if(deletedNote(meeting)){hide();return;}
            layout();sensitiveVisible=true;text(meeting.getString("title"));text("线下会议 · 仅本地保存 · 音频来源质量未知");
            status=text(live!=null?liveText(live):(state.equals("ended")?"已结束":state.equals("paused")?"已暂停":"已准备但未录制")+"\n已提交音频："+(saved/16000)+" 秒\n未上传、未转写；缺口和质量需核查。");
            if(live==null&&hasRecoveryUncertainty)text("恢复前尾段可能缺失，长度未知。");
            if(state.equals("ended"))text(meeting.getLong("ended_at")>0?"停止后确认时间（设备时钟估计）："+java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(meeting.getLong("ended_at"))):"停止时间未知；不补写为当前时间。");
            if(live==null&&saved>0){
                button("回放首个音频片段（可能外放）",()->new AlertDialog.Builder(this).setMessage("仅回放首个可用微块，不代表整场会议。请确认周围环境适合播放；离开页面或锁屏会停止。")
                    .setNegativeButton("取消",null).setPositiveButton("播放",(d,w)->{if(mayDisplay(ticket))play();}).show());
                button("停止回放",()->{stopReplay();});
            }
            text("本次同意为操作者声明，不能证明参与者或组织许可已满足。\n声明次数："+meeting.getJSONArray("consents").length());
            if(live!=null){controls();handler.post(update);}
            else if(!state.equals("ended")){
                button("核对许可并继续",()->startActivity(new Intent(this,MeetingPreparationActivity.class).putExtra("meeting",id)));
                button("结束会议",()->{if(stopReplayBeforeEdit())return;mutate(()->{try(AudioSegmentStore audio=new AudioSegmentStore(getApplicationContext())){audio.end(id);}});});
            }
            EditText note=new EditText(this);note.setHint("补充手工笔记（不计为音频证据）");note.setSaveEnabled(false);note.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);page.addView(note);
            for(int i=0;i<meeting.getJSONArray("attachments").length();i++){
                JSONObject entry=meeting.getJSONArray("attachments").getJSONObject(i);
                text((entry.getString("scope_kind").equals("meeting_background")?"会议背景 · ":"待归类 · ")+entry.getString("text"));
            }
            CheckBox background=new CheckBox(this);background.setText("作为整场会议背景");page.addView(background);
            long revision=meeting.getLong("revision");button("保存笔记",()->{if(stopReplayBeforeEdit())return;String body=note.getText().toString(),scope=background.isChecked()?"meeting_background":"unassigned";mutate(()->new NoteStore(getApplicationContext()).append(id,revision,body,scope));});
            button("删除本场会议及音频",()->new AlertDialog.Builder(this).setMessage("将阻止继续录音和读取，删除会议密钥，并清理受管理媒体。此操作不可撤销；不承诺闪存物理擦除。")
                .setNegativeButton("取消",null).setPositiveButton("删除",(dialog,which)->{if(mayDisplay(ticket))delete();}).show());
            if(live==null)handler.postDelayed(update,500);
        }catch(Exception failure){error();}
    }
    private void mutate(Mutation action){
        final long ticket=++epoch;loading=true;layout();status=text("正在保存，请等待确认。");
        if(CaptureService.snapshot(id)!=null)controls();handler.post(update);
        storage.execute(()->{
            // Confirmed writes survive departure; storage still checks access, retention and revision.
            try{action.run();handler.post(()->{if(mayDisplay(ticket))render();else if(current(ticket))hide();});}
            catch(Exception failure){handler.post(()->{if(current(ticket))error();});}
        });
    }
    private void delete(){
        stopReplay();CaptureService.control(id,false);final long ticket=++epoch;loading=true;layout();status=text("正在阻断访问并清理；尚未确认删除完成。");
        storage.execute(()->{
            try{
                // A confirmed deletion remains authorized after leaving the page.
                new NoteStore(getApplicationContext()).delete(id);
                if(CaptureService.snapshot(id)==null&&replay==null)try(AudioSegmentStore audio=new AudioSegmentStore(getApplicationContext())){audio.purgeDeleted(id);}
                handler.post(()->{if(current(ticket)){hide();text("删除屏障已生效；未完成的媒体清理将在恢复时继续。");}});
            }catch(Exception failure){handler.post(()->{if(current(ticket))error();});}
        });
    }
    private boolean stopReplayBeforeEdit(){if(replay==null)return false;stopReplay();status.setText("正在停止回放，请稍后再次执行操作。未保存的笔记仍在输入框中。");return true;}
    private void play(){
        if(stopReplayBeforeEdit())return;final long ticket=epoch;AudioFrameReplay current=new AudioFrameReplay(AudioFrameReplay.speaker());replay=current;
        new Thread(()->{
            try(AudioSegmentStore audio=new AudioSegmentStore(getApplicationContext())){
                if(!current(ticket))return;java.util.List<String> frames=audio.frameIds(id);if(frames.isEmpty())throw new IllegalStateException("no audio");current.play(audio,frames.get(0),16000);
            }catch(Exception failure){handler.post(()->{if(mayDisplay(ticket))status.setText("回放已停止或不可用，请核对许可、有效期与设备状态。");});}
            finally{try{current.close();}finally{if(replay==current)replay=null;}}
        },"Sidecar-explicit-playback").start();
    }
    @Override protected void onResume(){super.onResume();visible=true;hideAt=Long.MAX_VALUE;render();}
    @Override protected void onStop(){super.onStop();hide();}
    @Override protected void onDestroy(){hide();destroyed=true;synchronized(listenerLock){if(barriers!=null)barriers.unregisterOnSharedPreferenceChangeListener(revocation);if(noteBarriers!=null)noteBarriers.unregisterOnSharedPreferenceChangeListener(revocation);}handler.removeCallbacksAndMessages(null);storage.shutdown();replayStops.shutdown();unregisterReceiver(privacy);super.onDestroy();}
}

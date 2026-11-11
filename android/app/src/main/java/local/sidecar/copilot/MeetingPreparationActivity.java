package local.sidecar.copilot;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.media.AudioDeviceInfo;
import android.os.*;
import android.view.WindowManager;
import android.widget.*;

/** Preparation never pre-listens. Permission grant returns here; a fresh explicit start is required. */
public final class MeetingPreparationActivity extends Activity {
    private LinearLayout page;
    private CheckBox consent;
    private EditText title;
    private final BroadcastReceiver started=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
        if(visible&&intent.getStringExtra("meeting")!=null){startActivity(new Intent(MeetingPreparationActivity.this,RecordingActivity.class).putExtra("meeting",intent.getStringExtra("meeting")));finish();}
    }};
    private volatile boolean visible,destroyed;
    private volatile long renderEpoch;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService resources=java.util.concurrent.Executors.newSingleThreadExecutor(r->new Thread(r,"Sidecar-preparation-checks"));
    private void text(String value){TextView view=new TextView(this);view.setText(value);view.setTextSize(17);view.setPadding(0,12,0,12);page.addView(view);}
    private void button(String label,Runnable click){Button button=new Button(this);button.setText(label);button.setOnClickListener(v->click.run());page.addView(button);}
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);CaptureEnvironment.channel(this);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(started,new IntentFilter("local.sidecar.CAPTURE_STARTED"),Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(started,new IntentFilter("local.sidecar.CAPTURE_STARTED"));render();}
    private void render(){
        long ticket=++renderEpoch;
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(32,80,32,40);ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);
        text("线下会议 · 开始前确认");
        title=new EditText(this);title.setHint("会议名称（1 至 120 字）");title.setSaveEnabled(false);title.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);page.addView(title);
        String existing=getIntent().getStringExtra("meeting");
        if(existing!=null){title.setText("\u7ee7\u7eed\u5df2\u6709\u4f1a\u8bae");title.setEnabled(false);} // Resolve identity/consent on the storage worker.
        text("此设备尚未通过录音验证，当前无法开始。可以继续使用纯手工笔记。没有预监听，也不会因授予权限自动录音。");
        AudioDeviceInfo input=CaptureEnvironment.input(this);
        text("输入："+(input==null?"未找到内置麦克风":input.getProductName()+"（内置麦克风，仍需实测确认）"));
        TextView resourceStatus=new TextView(this);resourceStatus.setText("Checking local space and battery...");page.addView(resourceStatus);
        resources.execute(()->{
            try{String status="Local private cache: "+(CaptureEnvironment.freeBytes(this)/1048576)+" MiB available; battery: "+CaptureEnvironment.battery(this)+"%. Device duration/temperature limits remain unverified.";
                handler.post(()->{if(!destroyed&&visible&&ticket==renderEpoch)resourceStatus.setText(status);});}
            catch(Exception failure){handler.post(()->{if(!destroyed&&visible&&ticket==renderEpoch)resourceStatus.setText("Space/battery check unavailable; verify before recording.");});}
        });
        text("音频保留 7 天；仅本机加密保存，暂不上传、转写或分享。后续处理需单独许可。锁屏时应持续显示录音通知与暂停、结束控制。");
        text("通知可见："+(CaptureEnvironment.visibleNotification(this)?"已允许（锁屏仍需验证）":"未允许")+"\n麦克风权限："+(CaptureEnvironment.microphone(this)?"已允许":"未允许"));
        consent=new CheckBox(this);consent.setText("我有权记录，已完成适用的参与者知情同意与组织许可，并了解记录、保留及处理边界。");page.addView(consent);
        button("设置录音与通知权限",()->{
            CaptureService.START_GATE.cancel();
            if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS},20);
            else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},20);
        });
        button("开始录音",()->{
            try{
                String token=CaptureService.START_GATE.issue(visible,consent.isChecked(),CaptureEnvironment.microphone(this),CaptureEnvironment.visibleNotification(this),CaptureEnvironment.unlocked(this),CaptureEnvironment.supported(),SystemClock.elapsedRealtime());
                AudioDeviceInfo chosen=CaptureEnvironment.input(this);if(chosen==null)throw new SecurityException("input unavailable");
                if(title.getText().toString().trim().isEmpty()||title.length()>120)throw new IllegalArgumentException("meeting title");
                startForegroundService(new Intent(this,CaptureService.class).setAction(CaptureService.START).putExtra("token",token).putExtra("input",chosen.getId())
                    .putExtra("title",title.getText().toString()).putExtra("meeting",getIntent().getStringExtra("meeting")));
            }catch(Exception e){CaptureService.START_GATE.cancel();new AlertDialog.Builder(this).setMessage("尚未满足录音条件。请确认许可、权限、设备解锁、通知可见及设备支持状态。麦克风未开启。").setPositiveButton("知道了",null).show();}
        });
        button("返回手工笔记",()->finish());
    }
    @Override protected void onResume(){super.onResume();visible=true;render();}
    @Override protected void onPause(){visible=false;CaptureService.START_GATE.cancel();if(consent!=null)consent.setChecked(false);if(title!=null)title.setText("");super.onPause();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);CaptureService.START_GATE.cancel();render();}
    @Override protected void onDestroy(){destroyed=true;renderEpoch++;handler.removeCallbacksAndMessages(null);resources.shutdown();unregisterReceiver(started);super.onDestroy();}
}

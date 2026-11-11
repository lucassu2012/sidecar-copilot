package local.sidecar.copilot;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.view.*;
import android.widget.CheckBox;

/** Existing lifecycle/visibility acceptance subsets. Negative starts only: no microphone use. */
public final class CaptureServiceChecks {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError("service check "+checks);checks++;}
    private static CheckBox consent(View view){
        if(view instanceof CheckBox)return (CheckBox)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){CheckBox found=consent(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        checks=0;Context context=instrumentation.getTargetContext();
        PackageManager pm=context.getPackageManager();
        ServiceInfo service=pm.getServiceInfo(new ComponentName(context,CaptureService.class),0);
        check(!service.exported);check(service.getForegroundServiceType()==ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        check(!pm.getActivityInfo(new ComponentName(context,MeetingPreparationActivity.class),0).exported);
        check(pm.checkPermission(android.Manifest.permission.INTERNET,context.getPackageName())==PackageManager.PERMISSION_DENIED);
        check(!CaptureEnvironment.supported());
        CaptureEnvironment.channel(context);
        instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.POST_NOTIFICATIONS);
        check(CaptureEnvironment.visibleNotification(context));
        Notification notice=CaptureService.notification(context,"fixture-token");
        check(notice.actions.length==2);check(notice.visibility==Notification.VISIBILITY_PUBLIC);
        check((notice.flags&Notification.FLAG_ONGOING_EVENT)!=0);
        check(notice.actions[0].actionIntent.isImmutable()&&notice.actions[1].actionIntent.isImmutable());
        check(!notice.actions[0].actionIntent.equals(notice.actions[1].actionIntent));
        check(!notice.actions[0].actionIntent.equals(CaptureService.notification(context,"other-session").actions[0].actionIntent));
        Activity activity=instrumentation.startActivitySync(new Intent(context,MeetingPreparationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            instrumentation.runOnMainSync(()->{check(consent(activity.getWindow().getDecorView())!=null);check(!consent(activity.getWindow().getDecorView()).isChecked());});
            instrumentation.getUiAutomation().grantRuntimePermission(context.getPackageName(),android.Manifest.permission.RECORD_AUDIO);
            check(CaptureEnvironment.microphone(context));
            instrumentation.runOnMainSync(()->{
                consent(activity.getWindow().getDecorView()).setChecked(true);
                activity.onRequestPermissionsResult(20,new String[]{android.Manifest.permission.RECORD_AUDIO},new int[]{PackageManager.PERMISSION_GRANTED});
                check(!consent(activity.getWindow().getDecorView()).isChecked());
            });
            check(context.getSystemService(android.media.AudioManager.class).getActiveRecordingConfigurations().isEmpty());
            // A valid ephemeral ticket still cannot bypass the unvalidated-device gate.
            String token=CaptureService.START_GATE.issue(true,true,true,true,true,true,SystemClock.elapsedRealtime());
            context.startService(new Intent(context,CaptureService.class).setAction(CaptureService.START).putExtra("token",token));
            instrumentation.waitForIdleSync();SystemClock.sleep(300);
            check(!CaptureService.START_GATE.consume(token,SystemClock.elapsedRealtime()));
            check(context.getSystemService(NotificationManager.class).getActiveNotifications().length==0);
            check(context.getSystemService(android.media.AudioManager.class).getActiveRecordingConfigurations().isEmpty());
            // A stale stop must not create any capture or notification.
            context.startService(new Intent(context,CaptureService.class).setAction(CaptureService.END).putExtra("token","stale"));
            instrumentation.waitForIdleSync();SystemClock.sleep(300);
            check(context.getSystemService(NotificationManager.class).getActiveNotifications().length==0);
            instrumentation.runOnMainSync(()->consent(activity.getWindow().getDecorView()).setChecked(true));
            Activity home=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.runOnMainSync(home::finish);instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->check(!consent(activity.getWindow().getDecorView()).isChecked()));
            NotificationManager manager=context.getSystemService(NotificationManager.class);
            NotificationChannel blocked=new NotificationChannel("sidecar_test_blocked","test blocked",NotificationManager.IMPORTANCE_NONE);
            manager.createNotificationChannel(blocked);check(!CaptureEnvironment.visibleNotification(context,"sidecar_test_blocked"));
        }finally{instrumentation.runOnMainSync(activity::finish);CaptureService.START_GATE.cancel();}
        return checks;
    }
}

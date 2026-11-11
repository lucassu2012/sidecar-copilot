package local.sidecar.copilot;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;

/** Read-only checks. Device support must come from measured support-matrix evidence. */
public final class CaptureEnvironment {
    public static final String CHANNEL="sidecar_capture";
    private CaptureEnvironment(){}
    public static boolean supported(){return false;} // No validated physical-device entry yet.
    public static boolean microphone(Context c){return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;}
    public static boolean unlocked(Context c){KeyguardManager k=c.getSystemService(KeyguardManager.class);return k.isDeviceSecure()&&!k.isDeviceLocked();}
    public static void channel(Context c){c.getSystemService(NotificationManager.class).createNotificationChannel(
        new NotificationChannel(CHANNEL,"录音状态与停止控制",NotificationManager.IMPORTANCE_LOW));}
    public static boolean visibleNotification(Context c){
        return visibleNotification(c,CHANNEL);
    }
    static boolean visibleNotification(Context c,String channelId){
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        NotificationChannel channel=manager.getNotificationChannel(channelId);
        return manager.areNotificationsEnabled()&&channel!=null&&channel.getImportance()>=NotificationManager.IMPORTANCE_LOW
            &&(Build.VERSION.SDK_INT<33||c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED);
    }
    public static AudioDeviceInfo input(Context c){
        for(AudioDeviceInfo device:c.getSystemService(AudioManager.class).getDevices(AudioManager.GET_DEVICES_INPUTS))
            if(device.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC)return device;
        return null;
    }
    public static long freeBytes(Context c){return new StatFs(c.getNoBackupFilesDir().getPath()).getAvailableBytes();}
    public static int battery(Context c){return c.getSystemService(BatteryManager.class).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);}
}

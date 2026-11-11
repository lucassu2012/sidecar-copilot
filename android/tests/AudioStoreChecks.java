package local.sidecar.copilot;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import java.io.*;
import java.nio.file.Files;
import java.util.*;

/** Existing T06/T07/T08/T22/T23/T39/T40 subsets with synthetic PCM only. */
public final class AudioStoreChecks {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError("audio check "+checks);checks++;}
    private static long time(){return SystemClock.elapsedRealtimeNanos();}
    private static void shell(Instrumentation instrumentation,String command)throws Exception {
        try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand(command))){byte[] buffer=new byte[1024];while(in.read(buffer)!=-1){}}
    }
    private static void awaitPin(Instrumentation instrumentation)throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo info=instrumentation.getUiAutomation().getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        for(int i=0;i<100;i++){
            android.view.accessibility.AccessibilityNodeInfo root=instrumentation.getUiAutomation().getRootInActiveWindow();
            if(root!=null){
                for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByViewId("com.android.systemui:id/pinEntry"))
                    if(node.isFocused()&&node.isVisibleToUser())return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("emulator credential UI was not ready");
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        File fixtureRoot=new File(instrumentation.getTargetContext().getNoBackupFilesDir(),"audio-fixture-"+UUID.randomUUID());
        if(!fixtureRoot.mkdir())throw new IOException("fixture directory");
        Context context=new android.content.ContextWrapper(instrumentation.getTargetContext()){
            @Override public File getNoBackupFilesDir(){return fixtureRoot;}
        };
        if(!android.os.Build.HARDWARE.equals("ranchu"))throw new SecurityException("synthetic tests require disposable emulator");
        checks=0;String[] failure={""};
        AudioSegmentStore store=new AudioSegmentStore(context,stage->{if(stage.equals(failure[0]))throw new IOException("synthetic "+stage);});
        File root=new File(context.getNoBackupFilesDir(),"audio-segments");
        try {
            String sid=store.createSession(8000);AudioCaptureCoordinator capture=new AudioCaptureCoordinator(store,sid,8000);
            try{capture.start(false);throw new AssertionError("consent bypass");}catch(SecurityException expected){checks++;}
            long generation=capture.start(true);short[] pcm=new short[41000];Arrays.fill(pcm,(short)1234);
            capture.samples(generation,pcm,pcm.length,time());capture.pause();
            List<String> frames=store.frameIds(sid);check(frames.size()==2);
            check(store.read(frames.get(0)).length==40000&&store.read(frames.get(1)).length==1000);
            check(store.read(frames.get(0))[0]==1234);
            String lockedSession=store.createSession(8000);AudioCaptureCoordinator lockedCapture=new AudioCaptureCoordinator(store,lockedSession,8000);
            long lockedToken=lockedCapture.start(true);
            android.app.KeyguardManager keyguard=context.getSystemService(android.app.KeyguardManager.class);
            try{
                shell(instrumentation,"input keyevent 223");
                for(int i=0;i<50&&!keyguard.isDeviceLocked();i++)SystemClock.sleep(100);
                check(keyguard.isDeviceLocked());
                try{store.read(frames.get(0));throw new AssertionError("locked plaintext");}catch(SecurityException expected){checks++;}
                check(lockedCapture.samples(lockedToken,pcm,40000,time()));lockedCapture.pause();
                check(store.frameIds(lockedSession).size()==1);
            }finally{
                shell(instrumentation,"input keyevent 224");
                // Ask SystemUI to present credentials; MENU can leave API 35 on the lock screen.
                // A secure keyguard remains locked until the PIN is entered below.
                shell(instrumentation,"wm dismiss-keyguard");awaitPin(instrumentation);
                shell(instrumentation,"input text 2468");shell(instrumentation,"input keyevent 66");
                for(int i=0;i<50&&keyguard.isDeviceLocked();i++)SystemClock.sleep(100);
                shell(instrumentation,"locksettings verify --old 2468");
            }
            check(!keyguard.isDeviceLocked());check(store.read(store.frameIds(lockedSession).get(0))[0]==1234);
            check(!capture.samples(generation,pcm,100,time()));check(store.state(sid).equals("paused"));
            try{store.append(sid,generation,new PcmFrameAssembler.Frame(41000,41010,time(),new short[10],"unknown"));throw new AssertionError("late sink write");}catch(SecurityException expected){checks++;}
            String stream=store.stream(sid);store.deleteRange(sid,stream,39999,40000);
            try{store.read(frames.get(0));throw new AssertionError("deleted read");}catch(SecurityException expected){checks++;}
            store.compact();check(store.read(frames.get(1))[0]==1234);
            capture.end();try{capture.start(true);throw new AssertionError("ended restart");}catch(SecurityException expected){checks++;}
            // Writer lock prevents two controllers with independent in-memory barriers.
            try{new AudioSegmentStore(context);throw new AssertionError("multiple writer");}catch(java.nio.channels.OverlappingFileLockException expected){checks++;}
            String failing=store.createSession(8000);AudioCaptureCoordinator writer=new AudioCaptureCoordinator(store,failing,8000);long token=writer.start(true);
            failure[0]="after_fsync";
            try{writer.samples(token,pcm,40000,time());throw new AssertionError("injected failure");}catch(IOException expected){checks++;}
            check(!writer.isActive()&&store.state(failing).equals("paused"));check(store.frameIds(failing).isEmpty());
            failure[0]="";store.recover();
            long resumed=writer.start(true);writer.samples(resumed,pcm,40000,time());writer.pause();
            String retained=store.frameIds(failing).get(0);check(store.read(retained).length==40000);
            // Failure before index COMMIT must roll back the frame and recover its written tail.
            String txn=store.createSession(8000);AudioCaptureCoordinator transaction=new AudioCaptureCoordinator(store,txn,8000);long tx=transaction.start(true);
            failure[0]="before_index_commit";
            try{transaction.samples(tx,pcm,40000,time());throw new AssertionError("transaction failure");}catch(IOException expected){checks++;}
            check(store.frameIds(txn).isEmpty());failure[0]="";store.recover();
            failure[0]="compaction_before_switch";
            try{store.compact();throw new AssertionError("compaction failure");}catch(IOException expected){checks++;}
            failure[0]="";store.recover();check(store.read(retained)[0]==1234);
            // A failed final tail write must still close admission and recover a gap.
            String tail=store.createSession(8000);AudioCaptureCoordinator tailCapture=new AudioCaptureCoordinator(store,tail,8000);
            long tailToken=tailCapture.start(true);tailCapture.samples(tailToken,pcm,1000,time());failure[0]="before_fsync";
            try{tailCapture.pause();throw new AssertionError("tail write failure");}catch(IOException expected){checks++;}
            check(!tailCapture.isActive()&&store.state(tail).equals("paused"));
            check(!tailCapture.samples(tailToken,pcm,1000,time()));check(store.frameIds(tail).isEmpty());
            failure[0]="";store.recover();
            // Reopen must pause persisted active capture, never start an input device.
            String interrupted=store.createSession(8000);AudioCaptureCoordinator running=new AudioCaptureCoordinator(store,interrupted,8000);long active=running.start(true);
            running.samples(active,pcm,40000,time());String recoverable=store.frameIds(interrupted).get(0);
            store.close();store=new AudioSegmentStore(context);
            check(store.state(interrupted).equals("paused"));check(store.read(recoverable)[0]==1234);
            try{store.read(frames.get(0));throw new AssertionError("deleted recovery");}catch(SecurityException expected){checks++;}
            // Inject an orphan physical tail; committed frames remain readable after recovery.
            File[] segments=root.listFiles((dir,name)->name.endsWith(".seg"));check(segments!=null&&segments.length>0);
            File target=segments[0];long length=target.length();try(FileOutputStream out=new FileOutputStream(target,true)){out.write(new byte[]{1,2,3,4});out.getFD().sync();}
            store.recover();check(target.length()==length);check(store.read(recoverable)[0]==1234);
            // Data files are AEAD ciphertext, not little-endian repeated PCM values.
            byte[] ciphertext=Files.readAllBytes(target.toPath());int run=0,max=0;
            for(byte b:ciphertext){run=(b==(byte)0xd2)?run+1:0;max=Math.max(max,run);}check(max<100);
            // Corrupt indexed bytes; checksum/authentication must fail before returning PCM.
            SQLiteDatabase inspect=SQLiteDatabase.openDatabase(new File(root,"index.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY);
            String seg;long offset;
            try(Cursor c=inspect.rawQuery("SELECT segment,byte_offset FROM frames WHERE id=?",new String[]{recoverable})){c.moveToFirst();seg=c.getString(0);offset=c.getLong(1);}finally{inspect.close();}
            try(RandomAccessFile corrupt=new RandomAccessFile(new File(root,seg+".seg"),"rw")){corrupt.seek(offset);int original=corrupt.readUnsignedByte();corrupt.seek(offset);corrupt.writeByte(original^1);corrupt.getFD().sync();}
            try{store.read(recoverable);throw new AssertionError("corrupt PCM returned");}catch(SecurityException expected){checks++;}
            store.recover();try{store.read(recoverable);throw new AssertionError("corrupt recovery");}catch(SecurityException expected){checks++;}
            return checks;
        }finally{store.close();}
    }
}

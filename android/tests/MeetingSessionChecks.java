package local.sidecar.copilot;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.security.KeyStore;

/** Existing T22/T36/T37/T38/T40 subsets: synthetic audio and native controls, no microphone. */
public final class MeetingSessionChecks {
    private static int checks;
    private static void check(boolean condition){if(!condition)throw new AssertionError("meeting check "+checks);checks++;}
    private static View find(View view,String text){
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private static void awaitText(Instrumentation instrumentation,Activity activity,String value)throws Exception {
        long deadline=SystemClock.elapsedRealtime()+8000;
        java.util.concurrent.atomic.AtomicBoolean visible=new java.util.concurrent.atomic.AtomicBoolean();
        do{
            instrumentation.runOnMainSync(()->visible.set(find(activity.getWindow().getDecorView(),value)!=null));
            if(visible.get())return;
            Thread.sleep(50);
        }while(SystemClock.elapsedRealtime()<deadline);
        throw new AssertionError("meeting UI did not finish loading: "+value);
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        checks=0;Context context=instrumentation.getTargetContext();NoteStore notes=new NoteStore(context);
        String id,frame;short[] pcm=new short[96000];java.util.Arrays.fill(pcm,(short)333);
        try(AudioSegmentStore audio=new AudioSegmentStore(context)){
            MeetingSessionRepository meetings=new MeetingSessionRepository(context,audio);
            try{meetings.prepare(null,"denied",false,"fixture");throw new AssertionError("no consent");}catch(SecurityException expected){checks++;}
            id=meetings.prepare(null,"SYNTHETIC_AUDIO_MEETING",true,"fixture built-in");
            JSONObject meeting=notes.read(id);check(meeting.getString("capture_mode").equals("in_person"));check(meeting.getString("audio_session_id").equals(id));
            JSONObject consent=meeting.getJSONArray("consents").getJSONObject(0);check(!consent.getBoolean("cloud_authorized"));check(consent.getString("processing").equals("local_only_no_transcription"));
            check(consent.getLong("declared_at")>=meeting.getLong("created_at"));
            byte[] encrypted=Files.readAllBytes(new File(context.getNoBackupFilesDir(),"meetings/"+id+".enc").toPath());
            check(!new String(encrypted,java.nio.charset.StandardCharsets.ISO_8859_1).contains("SYNTHETIC_AUDIO_MEETING"));
            AudioCaptureCoordinator capture=new AudioCaptureCoordinator(audio,id,16000);long generation=capture.start(true);
            capture.samples(generation,pcm,pcm.length,SystemClock.elapsedRealtimeNanos());capture.pause();frame=audio.frameIds(id).get(0);
            check(audio.committedSamples(id)==96000);check(audio.frameIds(id).size()==2);check(audio.read(frame)[0]==333);
            final int[] output={0,0};final short[][] borrowed={null};
            AudioFrameReplay preview=new AudioFrameReplay(new AudioFrameReplay.Output(){
                public void start(int rate){check(rate==16000);}
                public int write(short[] data,int offset,int count){if(count>1600)throw new AssertionError("unbounded playback chunk");borrowed[0]=data;output[0]+=count;return count;}
                public void stop(){output[1]++;}
            });
            preview.play(audio,frame,16000);check(output[0]==80000);check(output[1]>0);check(borrowed[0][0]==0&&borrowed[0][79999]==0);
            try{capture.start(true);throw new AssertionError("reused consent");}catch(SecurityException expected){checks++;}
            try{meetings.prepare(id,"ignored",false,"new fixture");throw new AssertionError("resume no consent");}catch(SecurityException expected){checks++;}
            check(notes.read(id).getJSONArray("consents").length()==1);
            check(meetings.prepare(id,"ignored",true,"new fixture").equals(id));check(notes.read(id).getJSONArray("consents").length()==2);
            check(notes.read(id).getString("title").equals("SYNTHETIC_AUDIO_MEETING"));
            long resumed=capture.start(true);check(!capture.samples(generation,pcm,100,SystemClock.elapsedRealtimeNanos()));
            capture.samples(resumed,pcm,1000,SystemClock.elapsedRealtimeNanos());capture.pause();check(audio.committedSamples(id)==97000);
        }
        Activity activity=instrumentation.startActivitySync(new Intent(context,RecordingActivity.class).putExtra("meeting",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            awaitText(instrumentation,activity,"核对许可并继续");
            instrumentation.runOnMainSync(()->{check(find(activity.getWindow().getDecorView(),"已暂停")!=null);check(find(activity.getWindow().getDecorView(),"核对许可并继续")!=null);});
            check(context.getSystemService(android.media.AudioManager.class).getActiveRecordingConfigurations().isEmpty());
            instrumentation.runOnMainSync(()->find(activity.getWindow().getDecorView(),"结束会议").performClick());awaitText(instrumentation,activity,"已结束");
            try(AudioSegmentStore audio=new AudioSegmentStore(context)){
                check(audio.state(id).equals("ended"));check(audio.committedSamples(id)==97000);
                JSONObject boundary=audio.endMetadata(id);check(boundary.getLong("sample_end")==1000);long frozen=boundary.getLong("control_ns");audio.end(id);check(audio.endMetadata(id).getLong("control_ns")==frozen);
                check(new MeetingSessionRepository(context,audio).meeting(id).getLong("ended_at")>0);
                JSONObject changed=new JSONObject(boundary.toString()).put("control_ns",frozen+1);
                try{notes.projectAudioEnd(id,changed);throw new AssertionError("end projection rewrite");}catch(SecurityException expected){checks++;}
                try{new MeetingSessionRepository(context,audio).prepare(id,"ignored",true,"fixture");throw new AssertionError("ended resume");}catch(SecurityException expected){checks++;}
            }
            JSONObject ended=notes.read(id);notes.append(id,ended.getLong("revision"),"POST_MEETING_NOTE","meeting_background");
            check(notes.read(id).getLong("ended_at")==ended.getLong("ended_at"));
            try(AudioSegmentStore audio=new AudioSegmentStore(context)){check(audio.committedSamples(id)==97000);check(audio.state(id).equals("ended"));}
            check(notes.read(id).getJSONArray("attachments").getJSONObject(0).getString("scope_kind").equals("meeting_background"));
            byte[] old=Files.readAllBytes(new File(context.getNoBackupFilesDir(),"meetings/"+id+".enc").toPath());notes.delete(id);
            SystemClock.sleep(700);instrumentation.waitForIdleSync();
            instrumentation.runOnMainSync(()->check(find(activity.getWindow().getDecorView(),"SYNTHETIC_AUDIO_MEETING")==null));
            KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);check(!keys.containsAlias("sidecar.audio."+id));
            Files.write(new File(context.getNoBackupFilesDir(),"meetings/"+id+".enc").toPath(),old);
            try{notes.read(id);throw new AssertionError("restored meeting");}catch(SecurityException expected){checks++;}
            try(AudioSegmentStore audio=new AudioSegmentStore(context)){
                try{audio.read(frame);throw new AssertionError("deleted audio");}catch(SecurityException expected){checks++;}
                try{audio.start(id,true);throw new AssertionError("deleted restart");}catch(SecurityException expected){checks++;}
                try{audio.createSession(16000,id);throw new AssertionError("deleted reprovision");}catch(SecurityException expected){checks++;}
                String active=new MeetingSessionRepository(context,audio).prepare(null,"active deletion",true,"fixture");
                AudioCaptureCoordinator capture=new AudioCaptureCoordinator(audio,active,16000);long token=capture.start(true);
                capture.samples(token,pcm,80000,SystemClock.elapsedRealtimeNanos());String activeFrame=audio.frameIds(active).get(0);
                capture.samples(token,pcm,1000,SystemClock.elapsedRealtimeNanos());notes.delete(active);
                try{capture.samples(token,pcm,1000,SystemClock.elapsedRealtimeNanos());throw new AssertionError("delete admission");}catch(SecurityException expected){checks++;}
                check(!capture.isActive());try{audio.read(activeFrame);throw new AssertionError("live delete read");}catch(SecurityException expected){checks++;}
                audio.purgeDeleted(active);
                String replayId=new MeetingSessionRepository(context,audio).prepare(null,"replay deletion",true,"fixture");
                AudioCaptureCoordinator recorder=new AudioCaptureCoordinator(audio,replayId,16000);long replayToken=recorder.start(true);
                recorder.samples(replayToken,pcm,80000,SystemClock.elapsedRealtimeNanos());recorder.pause();String replayFrame=audio.frameIds(replayId).get(0);
                final int[] count={0};final boolean[] stopped={false};
                AudioFrameReplay preview=new AudioFrameReplay(new AudioFrameReplay.Output(){
                    public void start(int rate){}
                    public int write(short[] data,int offset,int length)throws Exception {count[0]+=length;notes.delete(replayId);return length;}
                    public void stop(){stopped[0]=true;}
                });
                try{preview.play(audio,replayFrame,16000);throw new AssertionError("playback ignored delete");}catch(SecurityException expected){checks++;}
                check(count[0]==1600);check(stopped[0]);audio.purgeDeleted(replayId);
            }
        }finally{instrumentation.runOnMainSync(activity::finish);notes.delete(id);}
        java.util.concurrent.CountDownLatch synced=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),deleting=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean deleted=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> threadFailure=new java.util.concurrent.atomic.AtomicReference<>();
        try(AudioSegmentStore audio=new AudioSegmentStore(context,stage->{if(stage.equals("after_fsync")){
            synced.countDown();try{if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("fixture timeout");}catch(InterruptedException e){throw new IOException(e);}
        }})){
            String concurrent=new MeetingSessionRepository(context,audio).prepare(null,"concurrent delete",true,"fixture");
            AudioCaptureCoordinator capture=new AudioCaptureCoordinator(audio,concurrent,16000);long token=capture.start(true);
            Thread writer=new Thread(()->{try{capture.samples(token,pcm,80000,SystemClock.elapsedRealtimeNanos());}catch(Throwable failure){threadFailure.set(failure);}});
            Thread remover=new Thread(()->{deleting.countDown();try{notes.delete(concurrent);deleted.set(true);}catch(Throwable failure){threadFailure.set(failure);}});
            writer.start();
            try{check(synced.await(3,java.util.concurrent.TimeUnit.SECONDS));remover.start();check(deleting.await(1,java.util.concurrent.TimeUnit.SECONDS));SystemClock.sleep(100);check(!deleted.get());}
            finally{release.countDown();writer.join(3000);if(remover.getState()!=Thread.State.NEW)remover.join(3000);}
            check(!writer.isAlive()&&!remover.isAlive()&&threadFailure.get()==null&&deleted.get());
            try{audio.frameIds(concurrent);throw new AssertionError("post-delete commit visible");}catch(SecurityException expected){checks++;}
            capture.pause();audio.purgeDeleted(concurrent);
        }
        return checks;
    }
}

package local.sidecar.copilot;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.SystemClock;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** G2 adapter. Device support remains disabled until physical-device gates are validated.
 * Permission alone never opens the microphone. Start/close belong to one storage executor.
 */
public final class AndroidPcmSource implements PcmSource {
    private volatile PcmSourceLifecycle lifecycle;
    private volatile Thread worker;
    private final AtomicBoolean stopRequested=new AtomicBoolean(),startCalled=new AtomicBoolean(),failureSent=new AtomicBoolean();
    private volatile Failure failure;
    private volatile boolean running;
    private volatile int level;
    private volatile long acceptedSamples;
    public int inputLevel(){return level;}
    public long acceptedSamples(){return acceptedSamples;}
    private final AudioCaptureCoordinator capture;
    public AndroidPcmSource(AudioCaptureCoordinator capture){
        this.capture=capture;
        capture.setInputStop(()->notifyInterruption("interrupted"));
    }
    private void requireNotStopped(){if(stopRequested.get())throw new IllegalStateException("source start cancelled");}
    @Override public void signalStop(){stopRequested.set(true);running=false;level=0;capture.stopAdmission();}
    @Override public void stopInput(){
        signalStop();PcmSourceLifecycle current=lifecycle;if(current!=null)current.stop();
    }
    private void notifyInterruption(String reason){
        capture.requestStop("interrupted",SystemClock.elapsedRealtimeNanos(),false);
        signalStop();
        Failure callback=failure;if(callback!=null&&failureSent.compareAndSet(false,true))callback.interrupted(reason);
        // CaptureService owns native stop on its independent control executor.
    }
    private void interrupt(String reason){
        try{notifyInterruption(reason);}finally{stopInput();}
    }
    public void start(Context context,AudioDeviceInfo confirmedInput,
            boolean consent,boolean validatedVisibilityAndStorage,Failure failure)throws Exception {
        if(!startCalled.compareAndSet(false,true))throw new IllegalStateException("explicit restart requires a new source");
        this.failure=failure;requireNotStopped();
        if(!consent||!validatedVisibilityAndStorage)throw new SecurityException("capture gates not validated");
        if(capture.sampleRate!=16000)throw new IllegalArgumentException("source and frame sample rates differ");
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("microphone permission unavailable");
        if(confirmedInput==null||!confirmedInput.isSource()||confirmedInput.getType()!=AudioDeviceInfo.TYPE_BUILTIN_MIC)
            throw new SecurityException("explicit built-in microphone required");
        int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minimum<=0)throw new IllegalStateException("PCM configuration unsupported");
        requireNotStopped();
        AudioRecord candidate=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(minimum,32000)).build();
        PcmSourceLifecycle candidateLifecycle=new PcmSourceLifecycle(candidate::stop,candidate::release);
        lifecycle=candidateLifecycle;
        try{
            requireNotStopped();
            if(candidate.getState()!=AudioRecord.STATE_INITIALIZED||!candidate.setPreferredDevice(confirmedInput))
                throw new IllegalStateException("input route unavailable");
            long token=capture.startBuffered(consent);
            requireNotStopped();running=true;
            if(!candidateLifecycle.startIfAllowed(candidate::startRecording,stopRequested::get))
                throw new IllegalStateException("source start cancelled");
            requireNotStopped();
            worker=new Thread(()->{
                short[] data=new short[1600];
                String reason="unknown";
                try {
                    while(running&&!stopRequested.get()) {
                        int count=candidate.read(data,0,data.length,AudioRecord.READ_BLOCKING);
                        long receivedNs=SystemClock.elapsedRealtimeNanos();
                        if(!running||stopRequested.get())break;
                        if(count<=0){reason="interrupted";throw new IllegalStateException("audio read interrupted");}
                        AudioDeviceInfo routed=candidate.getRoutedDevice();
                        if(routed==null||routed.getId()!=confirmedInput.getId()){reason="interrupted";throw new IllegalStateException("input route changed or unknown");}
                        AudioRecordingConfiguration config=candidate.getActiveRecordingConfiguration();
                        if(config!=null&&config.isClientSilenced()){reason="system_silenced";throw new IllegalStateException("system silenced microphone");}
                        // Bytes and low energy do not prove healthy sound; retain unknown quality.
                        if(!capture.samples(token,data,count,receivedNs)){running=false;break;}
                        int peak=0;for(int i=0;i<count;i++)peak=Math.max(peak,Math.abs((int)data[i]));
                        level=stopRequested.get()?0:peak*1000/32768;acceptedSamples+=count;
                        Arrays.fill(data,(short)0);
                    }
                } catch(Exception e) {
                    interrupt(reason); // Service owns sealing; source errors must not wait for its disk work.
                } finally {
                    running=false;level=0;Arrays.fill(data,(short)0);candidateLifecycle.release();
                }
            },"Sidecar-PCM-source");worker.start();
        }catch(Exception error){
            try{stopInput();}catch(RuntimeException stopFailure){error.addSuppressed(stopFailure);}
            Thread current=worker;if(current==null||!current.isAlive())try{candidateLifecycle.release();}
            catch(RuntimeException releaseFailure){error.addSuppressed(releaseFailure);}
            try{capture.pause();}catch(Exception pauseFailure){error.addSuppressed(pauseFailure);}
            throw error;
        }
    }
    @Override public synchronized void close()throws Exception {
        Exception failure=null;
        try{stopInput();}catch(RuntimeException e){failure=e;}
        try{capture.pause();}catch(Exception e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        Thread current=worker;
        if(current!=null&&current!=Thread.currentThread())try{current.join(1000);}
        catch(InterruptedException e){Thread.currentThread().interrupt();if(failure==null)failure=e;else failure.addSuppressed(e);}
        if(current==null||!current.isAlive()) {
            PcmSourceLifecycle owned=lifecycle;if(owned!=null)owned.release();
            lifecycle=null;worker=null;
        } else {
            // A blocked driver retains its worker's release owner; cleanup must report failure.
            Exception timeout=new IllegalStateException("source worker has not stopped");
            if(failure==null)failure=timeout;else failure.addSuppressed(timeout);
        }
        if(failure!=null)throw failure;
    }
}

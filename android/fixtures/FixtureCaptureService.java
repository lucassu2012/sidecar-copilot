package local.sidecar.copilot;

import android.app.Notification;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.os.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Compiled only into the separately identified fixture APK. Never opens AudioRecord. */
public final class FixtureCaptureService extends CaptureService {
    enum Mode {NORMAL,BLOCK_OPEN,BLOCK_SOURCE_FACTORY,BLOCK_APPEND,FAIL_APPEND_BLOCK_RECOVERY,STOP_NOTICE_FAILURE}
    static final class Probe {
        final Mode mode;
        final CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),inputStopped=new CountDownLatch(1),sourceSignalled=new CountDownLatch(1),destroyed=new CountDownLatch(1);
        final AtomicInteger startCalls=new AtomicInteger(),sourceStarts=new AtomicInteger(),sampleCalls=new AtomicInteger(),notificationFailures=new AtomicInteger();
        final AtomicLong stoppedNanos=new AtomicLong();
        final AtomicBoolean faultThrown=new AtomicBoolean(),blocked=new AtomicBoolean();
        final CountDownLatch openFailed=new CountDownLatch(1);
        volatile Throwable openFailure;
        volatile String openStage="not_attempted";
        volatile long openAttemptNs,openFailureNs;
        volatile AudioCaptureCoordinator coordinator;
        volatile long generation;
        // BLOCK_APPEND diagnostics only: bounded, lock-free, synthetic metadata; never PCM or exception text.
        static final int TRACE_CAPACITY=64;
        static final class TraceEvent {
            final long sequence,elapsedNs;final String name;
            TraceEvent(long sequence,String name){this.sequence=sequence;this.name=name;elapsedNs=SystemClock.elapsedRealtimeNanos();}
        }
        final AtomicLong traceSequence=new AtomicLong();
        final AtomicReferenceArray<TraceEvent> trace=new AtomicReferenceArray<>(TRACE_CAPACITY);
        final AtomicInteger afterFsyncSeen=new AtomicInteger(),afterFsyncSkipped=new AtomicInteger();
        volatile String lastStorageStage="not_observed",sourceStage="not_created",sourceFailureClass;
        volatile long lastStorageNs,lastSampleAttemptNs,lastSampleAcceptedNs,acceptedSampleCount;
        volatile Thread sourceThread;
        Probe(Mode mode){this.mode=mode;event("armed");}
        void event(String name){if(mode==Mode.BLOCK_APPEND){long sequence=traceSequence.getAndIncrement();trace.set((int)(sequence%TRACE_CAPACITY),new TraceEvent(sequence,name));}}
        java.util.Map<String,Object> diagnosticSnapshot(){
            // Only atomics/volatile fields and immutable service snapshots. No queue monitor, SQL, Binder or main-thread work.
            java.util.Map<String,Object> values=new java.util.LinkedHashMap<>();
            values.put("captured_elapsed_ns",SystemClock.elapsedRealtimeNanos());values.put("non_atomic_observation",true);
            values.put("start_calls",startCalls.get());values.put("source_starts",sourceStarts.get());values.put("sample_calls",sampleCalls.get());
            values.put("accepted_samples",acceptedSampleCount);values.put("last_sample_attempt_ns",lastSampleAttemptNs);values.put("last_sample_accepted_ns",lastSampleAcceptedNs);
            values.put("source_stage",sourceStage);values.put("source_failure_class",sourceFailureClass);
            Thread worker=sourceThread;values.put("source_thread_state",worker==null?null:worker.getState().name());
            values.put("blocked_flag",blocked.get());values.put("entered_count",entered.getCount());values.put("release_count",release.getCount());
            values.put("input_stopped_count",inputStopped.getCount());values.put("source_signalled_count",sourceSignalled.getCount());values.put("destroyed_count",destroyed.getCount());
            values.put("stopped_ns",stoppedNanos.get());values.put("open_stage",openStage);values.put("open_attempt_ns",openAttemptNs);values.put("open_failure_ns",openFailureNs);
            Throwable openingFailure=openFailure;values.put("open_failure_class",openingFailure==null?null:openingFailure.getClass().getName());
            values.put("last_storage_stage",lastStorageStage);values.put("last_storage_ns",lastStorageNs);
            values.put("after_fsync_seen",afterFsyncSeen.get());values.put("after_fsync_skipped",afterFsyncSkipped.get());
            AudioCaptureCoordinator current=coordinator;values.put("coordinator_present",current!=null);values.put("coordinator_active",current==null?null:current.isActive());
            long until=traceSequence.get(),from=Math.max(0,until-TRACE_CAPACITY);int missing=0;
            java.util.List<Object> events=new java.util.ArrayList<>();
            for(long sequence=from;sequence<until;sequence++){
                TraceEvent event=trace.get((int)(sequence%TRACE_CAPACITY));
                if(event==null||event.sequence!=sequence){missing++;continue;}
                java.util.Map<String,Object> item=new java.util.LinkedHashMap<>();item.put("sequence",event.sequence);item.put("elapsed_ns",event.elapsedNs);item.put("event",event.name);events.add(item);
            }
            values.put("events",events);values.put("trace_next_sequence",until);values.put("trace_overwritten",from);values.put("trace_unavailable_slots",missing);
            return values;
        }
        void block()throws IOException {
            event("block_entered");
            entered.countDown();
            try{if(!release.await(20,TimeUnit.SECONDS))throw new IOException("fixture block was not released");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException(interrupted);}
        }
        void at(String stage)throws IOException {
            if(mode==Mode.BLOCK_APPEND){
                lastStorageNs=SystemClock.elapsedRealtimeNanos();lastStorageStage=stage;event("storage_"+stage);
                if(stage.equals("after_fsync")){
                    afterFsyncSeen.incrementAndGet();
                    if(blocked.compareAndSet(false,true)){event("after_fsync_blocking");block();}
                    else{afterFsyncSkipped.incrementAndGet();event("after_fsync_skipped");}
                }
            }
            if(mode==Mode.FAIL_APPEND_BLOCK_RECOVERY){
                if(stage.equals("after_fsync")&&faultThrown.compareAndSet(false,true))throw new IOException("synthetic append failure");
                if(stage.equals("before_pause_barrier")&&faultThrown.get()&&blocked.compareAndSet(false,true))block();
            }
        }
        boolean lateSampleRejected()throws Exception {return coordinator!=null&&!coordinator.samples(generation,new short[]{1},1,SystemClock.elapsedRealtimeNanos());}
        BoundedPcmQueue queue() {
            try{java.lang.reflect.Field f=AudioCaptureCoordinator.class.getDeclaredField("queue");f.setAccessible(true);return (BoundedPcmQueue)f.get(coordinator);}
            catch(Exception failure){throw new IllegalStateException(failure);}
        }
        int outstandingSamples(){BoundedPcmQueue q=queue();return q==null?0:q.outstandingSamples();}
        long admittedSamples(){BoundedPcmQueue q=queue();return q==null?0:q.acceptedSamples();}
    }
    private static final AtomicInteger instanceSequence=new AtomicInteger();
    final int noticeIdentity=instanceSequence.incrementAndGet();
    private static volatile Probe nextProbe=new Probe(Mode.NORMAL);
    static volatile FixtureCaptureService instance;
    static volatile Probe lastProbe;
    static Probe arm(Mode mode){Probe probe=new Probe(mode);nextProbe=probe;return probe;}
    private final Probe probe=nextProbe;
    @Override public void onCreate(){super.onCreate();instance=this;lastProbe=probe;probe.openFailure=null;probe.openStage="not_attempted";probe.openAttemptNs=0;probe.openFailureNs=0;probe.event("service_created");}
    @Override public void onDestroy(){try{super.onDestroy();}finally{if(instance==this)instance=null;probe.destroyed.countDown();probe.event("service_destroyed");}}
    @Override protected boolean supportedDevice(){return Build.HARDWARE.equals("ranchu")&&getPackageName().equals("local.sidecar.copilot.fixture");}
    @Override protected Notification captureNotification(String token,boolean started){
        if(probe.mode==Mode.STOP_NOTICE_FAILURE&&!started&&probe.sourceStarts.get()>0){
            probe.notificationFailures.incrementAndGet();throw new IllegalStateException("synthetic stop notification failure");
        }
        Bundle metadata=new Bundle();metadata.putInt("sidecar.fixture.instance",noticeIdentity);
        return Notification.Builder.recoverBuilder(this,super.captureNotification(token,started)).addExtras(metadata)
            .setContentTitle("合成 PCM 测试 · 未使用麦克风").setContentText("模拟器专用，不是真实录音验收").build();
    }
    @Override protected AudioSegmentStore openStore()throws Exception {
        probe.openAttemptNs=SystemClock.elapsedRealtimeNanos();probe.openStage="opening";
        try{
            if(probe.mode==Mode.BLOCK_OPEN)probe.block();
            AudioSegmentStore opened=new AudioSegmentStore(this,probe::at);probe.openStage="opened";return opened;
        }catch(Exception|Error failure){
            probe.openFailure=failure;probe.openFailureNs=SystemClock.elapsedRealtimeNanos();probe.openStage="failed";
            probe.openFailed.countDown();throw failure; // Diagnostics never replace the actual failure.
        }
    }
    @Override protected PcmSource createSource(AudioCaptureCoordinator capture){
        probe.coordinator=capture;probe.sourceStage="created";probe.event("source_created");
        PcmSource prepared=new PcmSource(){
            private final AtomicBoolean cancelled=new AtomicBoolean();
            private volatile boolean running;
            private volatile long samples;
            private volatile Failure failure;
            private Thread worker;
            {capture.setInputStop(()->{stopInput();Failure current=failure;if(current!=null)current.interrupted("synthetic persistence failure");});}
            public void start(Context context,AudioDeviceInfo input,boolean consent,boolean ready,Failure failure)throws Exception {
                probe.startCalls.incrementAndGet();this.failure=failure;probe.sourceStage="starting";probe.event("source_start");
                if(!supportedDevice()||!ready||cancelled.get())throw new SecurityException("fixture only or cancelled");
                long token=capture.startBuffered(consent);probe.generation=token;
                if(cancelled.get())throw new SecurityException("cancelled during preparation");
                running=true;
                if(cancelled.get()){running=false;throw new SecurityException("cancelled before synthetic input");}
                probe.sourceStarts.incrementAndGet();probe.sourceStage="running";probe.event("source_running");
                worker=new Thread(()->{
                    short[] pcm=new short[1600];Arrays.fill(pcm,(short)1234);
                    try{while(running&&!cancelled.get()){
                        probe.sampleCalls.incrementAndGet();probe.lastSampleAttemptNs=SystemClock.elapsedRealtimeNanos();
                        if(!capture.samples(token,pcm,pcm.length,SystemClock.elapsedRealtimeNanos())){probe.event("sample_rejected");break;}
                        samples+=pcm.length;probe.acceptedSampleCount=samples;probe.lastSampleAcceptedNs=SystemClock.elapsedRealtimeNanos();SystemClock.sleep(100);
                    }}
                    catch(Exception error){probe.sourceFailureClass=error.getClass().getName();probe.event("source_exception");stopInput();failure.interrupted("synthetic interruption");}
                    finally{Arrays.fill(pcm,(short)0);running=false;probe.sourceStage="worker_exited";probe.event("worker_exited");}
                },"Sidecar-synthetic-source");probe.sourceThread=worker;worker.start();
            }
            public void signalStop(){cancelled.set(true);running=false;capture.stopAdmission();probe.sourceSignalled.countDown();probe.sourceStage="stop_signalled";probe.event("source_stop_signalled");}
            public void stopInput(){signalStop();probe.stoppedNanos.compareAndSet(0,SystemClock.elapsedRealtimeNanos());probe.inputStopped.countDown();}
            public int inputLevel(){return running?38:0;}
            public long acceptedSamples(){return samples;}
            public void close()throws Exception {
                stopInput();capture.pause();
                if(worker!=null){worker.join(1000);if(worker.isAlive())throw new IllegalStateException("synthetic worker still active");}
            }
        };
        if(probe.mode==Mode.BLOCK_SOURCE_FACTORY){
            try{probe.block();}catch(IOException failure){throw new IllegalStateException(failure);}
        }
        return prepared;
    }
}

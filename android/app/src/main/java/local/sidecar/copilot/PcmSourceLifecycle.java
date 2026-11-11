package local.sidecar.copilot;

import java.util.function.BooleanSupplier;

/** Stops recorder input before potentially slow persistence, with one release owner at a time. */
public final class PcmSourceLifecycle {
    public interface Seal {void run()throws Exception;}
    private final Runnable stopRecorder,releaseRecorder;
    private boolean started,stopped,released;
    public PcmSourceLifecycle(Runnable stopRecorder,Runnable releaseRecorder){
        this.stopRecorder=stopRecorder;this.releaseRecorder=releaseRecorder;
    }
    /** Only serializes device calls. Cancellation itself never acquires this monitor. */
    public synchronized boolean startIfAllowed(Runnable startRecorder,BooleanSupplier cancelled){
        if(stopped||released||cancelled.getAsBoolean())return false;
        if(started)throw new IllegalStateException("recorder already started");
        started=true;startRecorder.run();
        // A cancellation during the platform call must stop input before reporting success.
        if(cancelled.getAsBoolean()){stop();return false;}
        return true;
    }
    public synchronized void stop(){
        if(stopped)return;
        stopped=true;
        // AudioRecord also throws when recording has already stopped after a source failure.
        try{stopRecorder.run();}catch(IllegalStateException ignored){}
    }
    public void stopAndSeal(Seal seal)throws Exception {
        stop();
        // Do not hold this monitor during disk I/O: another caller may need to stop input.
        seal.run();
    }
    public synchronized void release(){
        if(released)return;
        try{stop();}finally{released=true;releaseRecorder.run();}
    }
}

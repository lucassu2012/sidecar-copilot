package local.sidecar.copilot;

import android.content.Context;
import android.media.AudioDeviceInfo;

/** Source ownership contract. Production uses AudioRecord; synthetic implementations are fixture-only. */
public interface PcmSource extends AutoCloseable {
    interface Failure {void interrupted(String reason);}
    void start(Context context,AudioDeviceInfo input,boolean consent,boolean ready,Failure failure)throws Exception;
    int inputLevel();
    long acceptedSamples();
    /** Latches cancellation immediately; no monitor, device call, or persistence is permitted. */
    void signalStop();
    /** Control-executor operation: stops physical input without sealing or touching storage. */
    void stopInput();
    /** Storage-executor cleanup, serialized after start; may wait for sealing and worker exit. */
    void close()throws Exception;
}

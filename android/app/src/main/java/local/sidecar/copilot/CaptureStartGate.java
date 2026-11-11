package local.sidecar.copilot;

import java.util.UUID;

/** Ephemeral, single-use permission to start. Never persisted or restored from an Intent alone. */
public final class CaptureStartGate {
    private String pending;
    private long issued;
    public synchronized String issue(boolean visible,boolean consent,boolean microphone,boolean notification,
            boolean unlocked,boolean supported,long now) {
        cancel();
        if(!visible||!consent||!microphone||!notification||!unlocked||!supported)
            throw new SecurityException("capture prerequisites not satisfied");
        pending=UUID.randomUUID().toString();issued=now;return pending;
    }
    public synchronized boolean consume(String token,long now){
        boolean valid=pending!=null&&pending.equals(token)&&now>=issued&&now-issued<=10000;
        // A mismatched or stale request must never authorize a subsequent retry.
        cancel();return valid;
    }
    public synchronized void cancel(){pending=null;issued=0;}
}

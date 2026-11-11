package local.sidecar.copilot;

import java.util.Arrays;

/** Raw PCM boundaries only. Sample positions are not proof of audiovisual calibration. */
public final class PcmFrameAssembler {
    public interface Sink { void write(Frame frame) throws Exception; }
    public static final class Frame {
        public final long startSample,endSample,receivedMonoNanos;
        public final short[] pcm;
        public final String sourceHealth;
        Frame(long start,long end,long received,short[] pcm,String health) {
            startSample=start;endSample=end;receivedMonoNanos=received;this.pcm=pcm;sourceHealth=health;
        }
    }
    private final short[] pending;
    private final Sink sink;
    public final int sampleRate;
    private int used;
    private long sequence,generation,lastReceive;
    private boolean recording;
    private String health="unknown";
    public PcmFrameAssembler(int sampleRate,int seconds,Sink sink) {
        if(sampleRate<8000||sampleRate>48000||seconds<5||seconds>10)throw new IllegalArgumentException("unsupported PCM experiment parameters");
        pending=new short[Math.multiplyExact(sampleRate,seconds)];this.sink=sink;this.sampleRate=sampleRate;
    }
    public synchronized long start(boolean explicitConsent) {
        if(!explicitConsent)throw new SecurityException("explicit consent required");
        if(recording)throw new IllegalStateException("already recording");
        recording=true;return ++generation;
    }
    public synchronized boolean accept(long token,short[] samples,int count,long receivedMonoNanos) throws Exception {
        if(!recording||token!=generation)return false;
        if(count<0||count>samples.length||receivedMonoNanos<lastReceive)throw new IllegalArgumentException("invalid samples or clock");
        lastReceive=receivedMonoNanos;
        try {
            for(int offset=0;offset<count;) {
                int copy=Math.min(count-offset,pending.length-used);
                System.arraycopy(samples,offset,pending,used,copy);used+=copy;offset+=copy;
                if(used==pending.length)seal();
            }
        } catch(Exception failure) {
            recording=false;generation++;Arrays.fill(pending,(short)0);used=0;throw failure;
        }
        return true;
    }
    public synchronized void health(String value) throws Exception {
        if(!Arrays.asList("healthy","quiet","system_silenced","interrupted","unknown").contains(value))throw new IllegalArgumentException("source health");
        if(!health.equals(value)) { seal();health=value; }
    }
    private void seal() throws Exception {
        if(used==0)return;
        short[] payload=Arrays.copyOf(pending,used);
        try {sink.write(new Frame(sequence,sequence+used,lastReceive,payload,health));sequence+=used;}
        catch(Exception failure){recording=false;generation++;throw failure;}
        finally {Arrays.fill(payload,(short)0);Arrays.fill(pending,(short)0);used=0;}
    }
    public synchronized long pause() throws Exception {
        recording=false;generation++; // Barrier exists even if final storage write fails.
        seal();return generation;
    }
    public synchronized boolean isRecording(){return recording;}
    public synchronized void discard(){recording=false;generation++;Arrays.fill(pending,(short)0);used=0;}
}

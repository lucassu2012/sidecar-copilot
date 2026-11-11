package local.sidecar.copilot;

import android.media.*;
import java.util.Arrays;

/** One explicitly selected frame, with access rechecked between <=100 ms output writes. */
public final class AudioFrameReplay implements AutoCloseable {
    public interface Output {void start(int rate)throws Exception;/** Nonblocking; return zero when the output buffer is full. */int write(short[] samples,int offset,int count)throws Exception;default int pending(){return 0;}void stop();}
    private final Output output;
    private volatile boolean cancelled;
    public AudioFrameReplay(Output output){this.output=output;}
    public void play(AudioSegmentStore store,String frame,int rate)throws Exception {
        short[] samples=null;
        try{
            store.requireReplayable(frame);samples=store.read(frame);
            synchronized(this){if(cancelled)throw new SecurityException("replay cancelled");output.start(rate);}
            long progressDeadline=System.nanoTime()+2_000_000_000L;
            for(int offset=0;offset<samples.length;){
                if(cancelled)throw new SecurityException("replay cancelled");
                int count=Math.min(rate/10,samples.length-offset),written=store.writeReplayChunk(frame,output,samples,offset,count);
                if(written<0||written>count)throw new IllegalStateException("audio output interrupted");
                if(written==0){
                    if(System.nanoTime()>progressDeadline)throw new IllegalStateException("audio output stalled");
                    Thread.sleep(5);
                }else{offset+=written;progressDeadline=System.nanoTime()+2_000_000_000L;}
            }
            long deadline=System.nanoTime()+2_000_000_000L;
            while(output.pending()>0){
                if(cancelled||System.nanoTime()>deadline)throw new SecurityException("playback stopped or stalled");
                store.requireReplayable(frame);Thread.sleep(20);
            }
        }finally{if(samples!=null)Arrays.fill(samples,(short)0);close();}
    }
    public void requestStop(){cancelled=true;}
    @Override public synchronized void close(){requestStop();output.stop();}
    public static Output speaker(){return new Output(){
        private volatile AudioTrack track;
        private volatile int written;
        public synchronized void start(int rate){
            int size=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(size<=0)throw new IllegalStateException("playback unsupported");
            track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(size,rate/5*2)).build();track.play();
        }
        public int write(short[] data,int offset,int count){AudioTrack current=track;if(current==null)return -1;int result=current.write(data,offset,count,AudioTrack.WRITE_NON_BLOCKING);if(result>0)written+=result;return result;}
        public int pending(){AudioTrack current=track;return current==null?0:Math.max(0,written-current.getPlaybackHeadPosition());}
        public synchronized void stop(){AudioTrack current=track;track=null;if(current!=null){try{current.pause();current.flush();current.stop();}catch(IllegalStateException ignored){}finally{current.release();}}}
    };}
}

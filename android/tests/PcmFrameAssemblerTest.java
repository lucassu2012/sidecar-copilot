import local.sidecar.copilot.PcmFrameAssembler;
import java.util.*;
public final class PcmFrameAssemblerTest {
    static int checks;
    static void check(boolean result){if(!result)throw new AssertionError();checks++;}
    public static void main(String[] args)throws Exception {
        List<long[]> ranges=new ArrayList<>();List<String> health=new ArrayList<>();
        PcmFrameAssembler assembler=new PcmFrameAssembler(8000,5,f->{
            check(f.pcm.length==f.endSample-f.startSample);ranges.add(new long[]{f.startSample,f.endSample});health.add(f.sourceHealth);
        });
        try{assembler.start(false);throw new AssertionError();}catch(SecurityException expected){checks++;}
        long first=assembler.start(true);short[] samples=new short[45000];Arrays.fill(samples,(short)17);
        check(assembler.accept(first,samples,samples.length,100));
        check(ranges.size()==1&&ranges.get(0)[1]==40000);
        assembler.pause();check(ranges.size()==2&&ranges.get(1)[0]==40000&&ranges.get(1)[1]==45000);
        check(!assembler.accept(first,samples,1,101));
        long second=assembler.start(true);check(!assembler.accept(first,samples,1,102));
        assembler.accept(second,samples,5,103);assembler.health("system_silenced");
        assembler.accept(second,samples,5,104);assembler.pause();
        check(health.get(2).equals("unknown")&&health.get(3).equals("system_silenced"));
        PcmFrameAssembler failing=new PcmFrameAssembler(8000,5,f->{throw new java.io.IOException("synthetic storage failure");});
        long token=failing.start(true);
        try{failing.accept(token,samples,40000,100);throw new AssertionError();}catch(java.io.IOException expected){checks++;}
        check(!failing.isRecording()&&!failing.accept(token,samples,1,101));
        System.out.println(checks+" PCM boundary checks passed (T08/T22/T32 synthetic samples; no microphone opened)");
    }
}

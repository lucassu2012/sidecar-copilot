import local.sidecar.copilot.CaptureStartGate;

/** Existing T22/T32/T36/T37 lifecycle subsets, not new acceptance IDs. */
public final class CaptureStartGateTest {
    private static int checks;
    private static void check(boolean value){if(!value)throw new AssertionError("gate "+checks);checks++;}
    public static void main(String[] args){
        CaptureStartGate gate=new CaptureStartGate();
        for(int missing=0;missing<6;missing++){
            boolean[] flags={true,true,true,true,true,true};flags[missing]=false;
            try{gate.issue(flags[0],flags[1],flags[2],flags[3],flags[4],flags[5],100);throw new AssertionError("missing prerequisite accepted");}
            catch(SecurityException expected){checks++;}
            check(!gate.consume("forged",101));
        }
        String token=gate.issue(true,true,true,true,true,true,100);
        check(gate.consume(token,101));check(!gate.consume(token,102));
        token=gate.issue(true,true,true,true,true,true,100);check(!gate.consume(token,10101));
        token=gate.issue(true,true,true,true,true,true,100);check(!gate.consume(token,99));
        token=gate.issue(true,true,true,true,true,true,100);gate.cancel();check(!gate.consume(token,101));
        token=gate.issue(true,true,true,true,true,true,100);check(!gate.consume("other",101));check(!gate.consume(token,102));
        String old=gate.issue(true,true,true,true,true,true,100);String current=gate.issue(true,true,true,true,true,true,101);
        check(!old.equals(current));check(gate.consume(current,102));
        check(!new CaptureStartGate().consume(current,103));
        System.out.println(checks+" capture-start gate checks passed; no device capture");
    }
}

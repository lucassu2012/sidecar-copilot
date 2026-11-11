import local.sidecar.copilot.NotePolicy;
import local.sidecar.copilot.RetentionClock;
public final class NotePolicyTest {
    static int count;
    static void check(boolean value) { if(!value)throw new AssertionError();count++; }
    static void rejects(Runnable action) {try {action.run();throw new AssertionError("expected rejection");}catch(IllegalArgumentException|IllegalStateException expected){count++;}}
    public static void main(String[] args) {
        check(NotePolicy.BUFFER_MS==900000L);
        rejects(()->NotePolicy.checkRevision(2,1));
        NotePolicy.checkRevision(2,2); count++;
        rejects(()->NotePolicy.checkReadable(100,0,100,false));
        rejects(()->NotePolicy.checkReadable(0,1,100,false));
        rejects(()->NotePolicy.checkReadable(2,1,100,true));
        NotePolicy.checkReadable(2,1,100,false);count++;
        check(NotePolicy.endBoundary(50,100)==50);
        check(NotePolicy.endBoundary(0,100)==100);
        NotePolicy.checkScope("meeting_background");count++;
        NotePolicy.checkScope("unassigned");count++;
        rejects(()->NotePolicy.checkScope("invented"));
        check(RetentionClock.conservativeNow(1000,500,900,400,true)==1000);
        check(RetentionClock.conservativeNow(901,600,900,400,true)==1100);
        rejects(()->RetentionClock.conservativeNow(1000,500,5000,400,true));
        rejects(()->RetentionClock.conservativeNow(1000,500,900,400,false));
        rejects(()->RetentionClock.conservativeNow(1000,399,900,400,true));
        // An unchanged anchor remains valid after an hour, including small clock quantization.
        check(RetentionClock.anchoredNow(4600000,3610000,1000000,10000,true)==4600000);
        check(RetentionClock.anchoredNow(4599999,3610000,1000000,10000,true)==4600000);
        check(RetentionClock.anchoredNow(4598000,3610000,1000000,10000,true)==4600000);
        // This wall time is newer than the old anchor, but has rolled back beyond tolerance.
        rejects(()->RetentionClock.anchoredNow(4597999,3610000,1000000,10000,true));
        rejects(()->RetentionClock.anchoredNow(4900000,3610000,1000000,10000,true));
        // A checkpoint of the observed forward jump preserves its lower bound on later reads.
        check(RetentionClock.anchoredNow(4909999,3620000,4900000,3610000,true)==4910000);
        rejects(()->RetentionClock.anchoredNow(4600000,3610000,1000000,10000,false));
        rejects(()->RetentionClock.anchoredNow(1000000,9999,1000000,10000,true));
        try{RetentionClock.anchoredNow(Long.MAX_VALUE,200,Long.MAX_VALUE-50,100,true);throw new AssertionError("projection overflow accepted");}
        catch(ArithmeticException expected){count++;}
        // Sleep can hide rollback even when wall time is newer than the last checkpoint.
        rejects(()->RetentionClock.conservativeNow(4597999,3610000,1000000,10000,true));
        rejects(()->RetentionClock.conservativeNow(4602001,3610000,1000000,10000,true));
        check(RetentionClock.conservativeNow(4602000,3610000,1000000,10000,true)==4600000);
        check(RetentionClock.conservativeNow(4598000,3610000,1000000,10000,true)==4600000);
        rejects(()->RetentionClock.conservativeNow(1,0,1,-1,true));
        rejects(()->RetentionClock.conservativeNow(-1,0,1,0,true));
        // A new boot is not safe merely because its uptime exceeds the old one.
        rejects(()->RetentionClock.conservativeNow(4600000,3610000,1000000,10000,false));
        // Failed observations do not mutate the anchor; correction can retry against it.
        check(RetentionClock.conservativeNow(4600000,3610000,1000000,10000,true)==4600000);
        long anchor=1000000,elapsed=10000;
        for(int i=0;i<100;i++){
            long next=RetentionClock.conservativeNow(anchor+100+1999,elapsed+100,anchor,elapsed,true);
            check(next==anchor+100);anchor=next;elapsed+=100;
        }
        final long savedAnchor=anchor,savedElapsed=elapsed;
        rejects(()->RetentionClock.conservativeNow(savedAnchor+3998,savedElapsed,savedAnchor,savedElapsed,true));
        long expiry=4600000;
        for(int offset=-2000;offset<=2000;offset+=100){
            final long now=RetentionClock.conservativeNow(expiry+offset,3610000,1000000,10000,true);
            rejects(()->NotePolicy.checkReadable(now,1,expiry,false));
        }
        System.out.println(count+" native domain checks passed (T34/T35/T38/T42 subset; not device acceptance)");
    }
}

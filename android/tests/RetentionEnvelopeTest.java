import local.sidecar.copilot.NotePolicy;
import local.sidecar.copilot.RetentionEnvelope;

/** Existing immutable text deadlines, using synthetic timestamps only. */
public final class RetentionEnvelopeTest {
    private static int checks;
    private static void check(boolean result){if(!result)throw new AssertionError("retention envelope "+checks);checks++;}
    private static void rejects(Runnable work){try{work.run();throw new AssertionError("invalid retention accepted");}catch(SecurityException|ArithmeticException expected){checks++;}}
    public static void main(String[] args){
        long origin=1_000_000L,deadline=origin+NotePolicy.RETENTION_MS;
        RetentionEnvelope value=RetentionEnvelope.confirmed(RetentionEnvelope.NOTE_ORIGIN,origin);
        check(value.originTime==origin&&value.expiresAt==deadline);
        check(value.evidence.equals(RetentionEnvelope.DEVELOPMENT_SNAPSHOT));
        RetentionEnvelope old=RetentionEnvelope.legacy(RetentionEnvelope.NOTE_ORIGIN,origin,deadline);
        check(old.originTime==origin&&old.expiresAt==deadline);
        check(old.evidence.equals(RetentionEnvelope.LEGACY_DEADLINE_ONLY));
        check(!old.evidence.equals(value.evidence));
        RetentionEnvelope verified=RetentionEnvelope.verify(RetentionEnvelope.POLICY_ID,1,RetentionEnvelope.TITLE_ORIGIN,origin,deadline,old.evidence);
        check(verified.expiresAt==deadline);
        rejects(()->RetentionEnvelope.legacy(RetentionEnvelope.NOTE_ORIGIN,origin,deadline+1));
        rejects(()->RetentionEnvelope.legacy(RetentionEnvelope.NOTE_ORIGIN,origin,deadline-1));
        rejects(()->RetentionEnvelope.legacy(RetentionEnvelope.NOTE_ORIGIN,origin+1,deadline));
        rejects(()->RetentionEnvelope.confirmed("media_capture",origin));
        rejects(()->RetentionEnvelope.verify("production-default",1,RetentionEnvelope.NOTE_ORIGIN,origin,deadline,old.evidence));
        rejects(()->RetentionEnvelope.verify(RetentionEnvelope.POLICY_ID,2,RetentionEnvelope.NOTE_ORIGIN,origin,deadline,old.evidence));
        rejects(()->RetentionEnvelope.verify(RetentionEnvelope.POLICY_ID,1,RetentionEnvelope.NOTE_ORIGIN,origin,deadline,"assumed_user_choice"));
        rejects(()->RetentionEnvelope.confirmed(RetentionEnvelope.NOTE_ORIGIN,0));
        rejects(()->RetentionEnvelope.confirmed(RetentionEnvelope.NOTE_ORIGIN,-1));
        rejects(()->RetentionEnvelope.confirmed(RetentionEnvelope.NOTE_ORIGIN,Long.MAX_VALUE));
        System.out.println(checks+" retention envelope checks passed; synthetic policy only");
    }
}

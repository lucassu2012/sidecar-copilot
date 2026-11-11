package local.sidecar.copilot;

/** Existing synthetic-development text policy, not an approved production default. */
public final class RetentionEnvelope {
    public static final String POLICY_ID="synthetic-manual-text-90d";
    public static final int POLICY_VERSION=1;
    public static final String TITLE_ORIGIN="manual_title_creation",NOTE_ORIGIN="manual_note_creation";
    public static final String DEVELOPMENT_SNAPSHOT="development_policy_snapshot";
    public static final String LEGACY_DEADLINE_ONLY="legacy_deadline_only";
    public final String origin,evidence;
    public final long originTime,expiresAt;

    private RetentionEnvelope(String origin,long created,long expires,String evidence){
        if(!TITLE_ORIGIN.equals(origin)&&!NOTE_ORIGIN.equals(origin))throw new SecurityException("unsupported retention origin");
        if(!DEVELOPMENT_SNAPSHOT.equals(evidence)&&!LEGACY_DEADLINE_ONLY.equals(evidence))throw new SecurityException("retention evidence unavailable");
        if(created<=0||expires!=deadline(created))throw new SecurityException("retention deadline cannot be verified");
        this.origin=origin;originTime=created;expiresAt=expires;this.evidence=evidence;
    }
    public static long deadline(long created){
        if(created<=0)throw new SecurityException("retention origin unavailable");
        return Math.addExact(created,NotePolicy.RETENTION_MS);
    }
    public static RetentionEnvelope confirmed(String origin,long created){
        return new RetentionEnvelope(origin,created,deadline(created),DEVELOPMENT_SNAPSHOT);
    }
    public static RetentionEnvelope legacy(String origin,long created,long expires){
        // Verifies the earlier implementation's deadline, never invents prior user selection.
        return new RetentionEnvelope(origin,created,expires,LEGACY_DEADLINE_ONLY);
    }
    public static RetentionEnvelope verify(String policy,int version,String origin,long created,long expires,String evidence){
        if(!POLICY_ID.equals(policy)||version!=POLICY_VERSION)throw new SecurityException("retention policy unavailable");
        return new RetentionEnvelope(origin,created,expires,evidence);
    }
}

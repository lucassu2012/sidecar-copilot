package local.sidecar.copilot;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Proof for one authenticated pending copy, never authority to delete a meeting or key. */
final class PendingNotePolicy {
    private static final String[] MEETING_FIELDS={"id","title","capture_mode","created_at",
        "expires_at","title_expires_at","title_retention","revision","ended_at","attachments"};
    private static final String[] NOTE_FIELDS={"id","source_type","text","scope_kind",
        "created_at","added_at","eligible_at","expires_at","retention"};
    private static final String[] ENVELOPE_FIELDS={"retention_policy_id","policy_version",
        "retention_origin","origin_time","expires_at","policy_evidence","clock_confidence"};

    private PendingNotePolicy() {}

    /**
     * The caller supplies an authenticated body and trusted time. This method neither changes
     * the body nor infers missing policy evidence. False means valid but not wholly expired;
     * an IOException means the copy's complete retention scope could not be proved.
     */
    static boolean isWhollyExpired(JSONObject body,String id,long now)throws IOException {
        try {
            if(now<=0)throw invalid("trusted time unavailable");
            requireId(id);
            fields(body,MEETING_FIELDS,"title_expired");
            if(!id.equals(string(body,"id"))||!"manual_note".equals(string(body,"capture_mode")))
                throw invalid("unsupported meeting identity or mode");
            boundedText(body,"title",120);
            if(body.has("title_expired")&&!(body.get("title_expired") instanceof Boolean))
                throw invalid("invalid title marker");
            long created=integer(body,"created_at"),expires=integer(body,"expires_at");
            long titleExpires=integer(body,"title_expires_at"),ended=integer(body,"ended_at");
            if(integer(body,"revision")<0||ended<0||ended>now||(ended!=0&&ended<created))
                throw invalid("invalid meeting metadata");
            // end() obtains its timestamp after read(); it can cross the existing expiry.
            // It does not create a new retention origin or extend any stored deadline.
            envelope(body.getJSONObject("title_retention"),RetentionEnvelope.TITLE_ORIGIN,created,titleExpires);
            long requiredExpiry=titleExpires;
            boolean expired=now>=expires&&now>=titleExpires;
            JSONArray notes=body.getJSONArray("attachments");
            Set<String> noteIds=new HashSet<>();
            for(int i=0;i<notes.length();i++) {
                JSONObject note=notes.getJSONObject(i);
                fields(note,NOTE_FIELDS);
                String noteId=string(note,"id");requireId(noteId);
                if(!noteIds.add(noteId))throw invalid("duplicate note identity");
                if(!"manual_note".equals(string(note,"source_type")))
                    throw invalid("unsupported note source");
                boundedText(note,"text",10000);
                NotePolicy.checkScope(string(note,"scope_kind"));
                long noteCreated=integer(note,"created_at"),noteExpires=integer(note,"expires_at");
                if(noteCreated<created||integer(note,"added_at")!=noteCreated||
                        integer(note,"eligible_at")!=Math.addExact(noteCreated,NotePolicy.BUFFER_MS))
                    throw invalid("inconsistent note origin");
                envelope(note.getJSONObject("retention"),RetentionEnvelope.NOTE_ORIGIN,noteCreated,noteExpires);
                requiredExpiry=Math.max(requiredExpiry,noteExpires);
                expired&=now>=noteExpires;
            }
            if(expires<requiredExpiry)throw invalid("container precedes content deadline");
            return expired;
        } catch(IOException failure) {
            throw failure;
        } catch(JSONException failure) {
            throw new IOException("pending copy metadata is incomplete",failure);
        } catch(RuntimeException failure) {
            throw new IOException("pending copy retention cannot be verified",failure);
        }
    }

    private static void envelope(JSONObject saved,String origin,long created,long expires)throws IOException,JSONException {
        fields(saved,ENVELOPE_FIELDS);
        long version=integer(saved,"policy_version");
        if(version<Integer.MIN_VALUE||version>Integer.MAX_VALUE)throw invalid("invalid policy version");
        RetentionEnvelope verified=RetentionEnvelope.verify(string(saved,"retention_policy_id"),(int)version,
            string(saved,"retention_origin"),integer(saved,"origin_time"),integer(saved,"expires_at"),
            string(saved,"policy_evidence"));
        if(!origin.equals(verified.origin)||created!=verified.originTime||expires!=verified.expiresAt||
                !"device_clock_estimate".equals(string(saved,"clock_confidence")))
            throw invalid("retention envelope mismatch");
    }

    private static void fields(JSONObject object,String[] required,String... optional)throws IOException {
        if(object==null)throw invalid("missing object");
        Set<String> allowed=new HashSet<>(Arrays.asList(required));
        allowed.addAll(Arrays.asList(optional));
        Iterator<String> keys=object.keys();
        while(keys.hasNext())if(!allowed.contains(keys.next()))throw invalid("unknown content field");
        for(String key:required)if(!object.has(key)||object.isNull(key))throw invalid("missing content field");
    }

    private static String string(JSONObject object,String key)throws IOException,JSONException {
        Object value=object.get(key);
        if(!(value instanceof String))throw invalid("invalid text field");
        return (String)value;
    }

    private static long integer(JSONObject object,String key)throws IOException,JSONException {
        Object value=object.get(key);
        if(!(value instanceof Integer)&&!(value instanceof Long))throw invalid("invalid integer field");
        return ((Number)value).longValue();
    }

    private static void boundedText(JSONObject object,String key,int limit)throws IOException,JSONException {
        String value=string(object,key);
        if(value.trim().isEmpty()||value.length()>limit)throw invalid("invalid content text");
    }

    private static void requireId(String id)throws IOException {
        if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw invalid("invalid content identity");
    }

    private static IOException invalid(String reason) { return new IOException(reason); }
}

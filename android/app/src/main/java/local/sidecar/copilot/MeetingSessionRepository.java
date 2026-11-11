package local.sidecar.copilot;

import android.content.Context;
import org.json.JSONObject;

/** Shared UUID is the meeting/audio evidence binding. A note write precedes any capture admission. */
public final class MeetingSessionRepository {
    private final NoteStore notes;
    private final AudioSegmentStore audio;
    public MeetingSessionRepository(Context context,AudioSegmentStore audio)throws Exception {notes=new NoteStore(context);this.audio=audio;}
    public String prepare(String existing,String title,boolean consent,String input)throws Exception {
        if(existing==null){
            String id=notes.createInPerson(title,consent,input);
            try{audio.createSession(16000,id);bindDeclaration(id);return id;}
            catch(Exception failure){notes.delete(id);audio.purgeDeleted(id);throw failure;}
        }
        JSONObject meeting=notes.read(existing);
        if(audio.state(existing).equals("ended"))throw new SecurityException("ended meeting cannot resume");
        notes.recordConsent(existing,meeting.getLong("revision"),consent,input);bindDeclaration(existing);return existing;
    }
    private void bindDeclaration(String id)throws Exception {
        JSONObject meeting=notes.read(id);org.json.JSONArray declarations=meeting.getJSONArray("consents");
        audio.recordDeclaration(id,meeting.getLong("revision"),declarations.getJSONObject(declarations.length()-1).getLong("declared_at"));
    }
    public JSONObject meeting(String id)throws Exception{return audio.state(id).equals("ended")?notes.projectAudioEnd(id,audio.endMetadata(id)):notes.read(id);}
    public void delete(String id)throws Exception{notes.delete(id);audio.purgeDeleted(id);}
}

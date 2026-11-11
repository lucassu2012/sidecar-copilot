package local.sidecar.copilot;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Confirms process-local uncertain deletion commits before callers perform irreversible cleanup. */
final class DurableDeletionMarkers {
    interface Committer { boolean commit(SharedPreferences.Editor editor) throws IOException; }
    private static final Committer DEFAULT_COMMITTER=SharedPreferences.Editor::commit;
    private static final Object LOCK=new Object();
    private static final List<Pending> PENDING=new ArrayList<>();
    private static final List<Claim> ACTIVE=new ArrayList<>();
    private DurableDeletionMarkers() {}

    private static final class Location {
        final String root,name,key;
        final SharedPreferences preferences;
        Location(Context context,String name,String key)throws IOException {this(context,name,key,false);}
        Location(Context context,String name,String key,boolean inventory)throws IOException {
            Objects.requireNonNull(context,"context");
            if(name==null||name.isEmpty()||(!inventory&&(key==null||key.isEmpty())))throw new IllegalArgumentException("marker name and key required");
            File directory=context.getNoBackupFilesDir();
            if(directory==null)throw new IOException("deletion marker root unavailable");
            this.root=directory.getCanonicalPath();this.name=name;this.key=key;
            this.preferences=Objects.requireNonNull(context.getSharedPreferences(name,Context.MODE_PRIVATE),"preferences");
        }
        boolean sameScope(Location other){return root.equals(other.root)&&name.equals(other.name);}
        boolean sameKey(Location other){return sameScope(other)&&key.equals(other.key);}
    }
    private static final class Pending {
        final Location location;
        final Committer committer;
        String previousToken,attemptToken;
        Pending(Location location,Committer committer){this.location=location;this.committer=committer;}
    }
    private static final class Claim {
        final Location location;
        Pending pending;
        Claim(Location location,Pending pending){this.location=location;this.pending=pending;}
    }
    private static void identity(Location requested,Location recorded)throws IOException {
        if(requested.sameScope(recorded)!=(requested.preferences==recorded.preferences))
            throw new IOException("pending deletion preference identity changed");
    }
    private static Claim claim(Location location)throws IOException {
        synchronized(LOCK){
            Pending found=null;
            for(Pending pending:PENDING){identity(location,pending.location);if(location.sameKey(pending.location))found=pending;}
            for(Claim active:ACTIVE){
                identity(location,active.location);
                if(location.sameScope(active.location))throw new IOException("deletion preference confirmation already in progress");
            }
            Claim claim=new Claim(location,found);ACTIVE.add(claim);return claim;
        }
    }
    private static void own(Claim claim)throws IOException {
        if(!ACTIVE.contains(claim))throw new IOException("deletion confirmation ownership lost");
        if(claim.pending!=null&&!PENDING.contains(claim.pending))throw new IOException("pending deletion confirmation lost");
    }
    static void mark(Context context,String preferenceName,String key)throws IOException {
        mark(context,preferenceName,key,DEFAULT_COMMITTER);
    }
    static void mark(Context context,String preferenceName,String key,Committer committer)throws IOException {
        Objects.requireNonNull(committer,"committer");
        confirm(new Location(context,preferenceName,key),committer,true);
    }
    static void requireDurable(Context context,String preferenceName,String key)throws IOException {
        confirm(new Location(context,preferenceName,key),null,false);
    }
    /** Hide/discover markers only. These queries never authorize irreversible cleanup. */
    static boolean isMarked(Context context,String preferenceName,String key)throws IOException {
        return lookup(new Location(context,preferenceName,key)).contains(key);
    }
    static Set<String> keys(Context context,String preferenceName)throws IOException {
        return lookup(new Location(context,preferenceName,null,true));
    }
    /** UI/service hiding with an already-loaded instance; no Context or filesystem resolution. */
    static boolean isMarked(SharedPreferences preferences,String key) {
        Objects.requireNonNull(preferences,"preferences");
        if(key==null||key.isEmpty())throw new IllegalArgumentException("marker key required");
        Set<String> result=pendingKeys(preferences);
        result.addAll(preferences.getAll().keySet());
        result.addAll(pendingKeys(preferences));
        return result.contains(key);
    }
    private static Set<String> pendingKeys(SharedPreferences preferences) {
        synchronized(LOCK){
            Set<String> result=new HashSet<>();
            for(Pending pending:PENDING)if(pending.location.preferences==preferences)result.add(pending.location.key);
            return result;
        }
    }
    private static Set<String> pendingKeys(Location location)throws IOException {
        synchronized(LOCK){
            Set<String> result=new HashSet<>();
            for(Pending pending:PENDING){
                identity(location,pending.location);
                if(location.sameScope(pending.location))result.add(pending.location.key);
            }
            // In-flight confirmation remains visible; only an identity conflict rejects a lookup.
            for(Claim active:ACTIVE)identity(location,active.location);
            return result;
        }
    }
    private static Set<String> lookup(Location location)throws IOException {
        Set<String> result=pendingKeys(location);
        result.addAll(location.preferences.getAll().keySet()); // Never call preferences under LOCK.
        // A commit may publish the cache and remove pending after the cache snapshot was taken.
        // Keeping both pending snapshots prevents that transition from temporarily unhiding a key.
        result.addAll(pendingKeys(location));
        return result;
    }
    private static void confirm(Location location,Committer committer,boolean marking)throws IOException {
        Claim claim=claim(location);
        try{
            // Pending wins even if a failed edit/commit left no value in the current cache.
            if(claim.pending==null){
                if(location.preferences.contains(location.key))return; // Existing legacy boolean/string marker: no write.
                if(!marking)throw new IOException("durable deletion marker required");
                Pending pending=new Pending(location,committer);
                synchronized(LOCK){own(claim);PENDING.add(pending);claim.pending=pending;}
            }
            persist(claim);
        }finally{
            synchronized(LOCK){ACTIVE.remove(claim);}
        }
    }
    private static void persist(Claim claim)throws IOException {
        Pending pending=claim.pending;
        Map<String,?> cache=pending.location.preferences.getAll();
        String previous=null;
        if(cache.containsKey(pending.location.key)){
            Object value=cache.get(pending.location.key);
            if(!(value instanceof String)||(!value.equals(pending.previousToken)&&!value.equals(pending.attemptToken)))
                throw new IOException("pending deletion cache value changed unexpectedly");
            previous=(String)value;
        }
        // Always change the same barrier value: an equal retry can otherwise be a no-op commit.
        // Register both possible cache states before edit, including failures before commitToMemory.
        String token;
        do{token=UUID.randomUUID().toString();}while(token.equals(pending.previousToken)||token.equals(pending.attemptToken));
        synchronized(LOCK){own(claim);pending.previousToken=previous;pending.attemptToken=token;}
        SharedPreferences.Editor editor=pending.location.preferences.edit();
        editor.putString(pending.location.key,token);
        // No registry lock is held across preference methods or arbitrary committer/listener code.
        // A concurrent or reentrant operation on this preference scope fails instead of waiting.
        if(!pending.committer.commit(editor))throw new IOException("deletion marker commit not confirmed");
        if(!token.equals(pending.location.preferences.getAll().get(pending.location.key)))
            throw new IOException("committed deletion marker is absent or changed");
        synchronized(LOCK){own(claim);PENDING.remove(pending);claim.pending=null;}
    }
}

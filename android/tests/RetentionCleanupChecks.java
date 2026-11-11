package local.sidecar.copilot;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.lang.reflect.Field;
import java.security.KeyStore;
import java.util.*;

/** Existing retention/recovery requirements: synthetic PCM, injected metadata and I/O faults only.
 * A mismatched stored boot marker is not an emulator reboot or a trusted-time recovery test.
 */
public final class RetentionCleanupChecks {
    private static int checks;
    private interface Action {void run()throws Exception;}
    private static void check(boolean condition,String label){if(!condition)throw new AssertionError("retention cleanup "+checks+": "+label);checks++;}
    private static void failure(Class<? extends Exception> type,Action action,String label)throws Exception {
        try{action.run();throw new AssertionError(label+" unexpectedly succeeded");}
        catch(Exception expected){if(!type.isInstance(expected))throw expected;checks++;}
    }
    private static String rows(SQLiteDatabase db,String sql,String... args){
        StringBuilder result=new StringBuilder();
        try(Cursor c=db.rawQuery(sql,args)){
            while(c.moveToNext()){
                for(int i=0;i<c.getColumnCount();i++){
                    int type=c.getType(i);result.append(type).append(':');
                    if(type==Cursor.FIELD_TYPE_BLOB)result.append(android.util.Base64.encodeToString(c.getBlob(i),android.util.Base64.NO_WRAP));
                    else if(type!=Cursor.FIELD_TYPE_NULL)result.append(c.getString(i));
                    result.append('|');
                }
                result.append('\n');
            }
        }
        return result.toString();
    }
    private static long number(SQLiteDatabase db,String sql,String... args){
        try(Cursor c=db.rawQuery(sql,args)){if(!c.moveToFirst())throw new AssertionError("missing numeric fixture row");return c.getLong(0);}
    }
    private static final class Fixture implements AutoCloseable {
        final AudioSegmentStore store;
        final SQLiteDatabase db;
        final Context context;
        final File root;
        final String session;
        final List<String> frames;
        String failAt="";
        AudioSegmentStore.Faults additionalFault=stage->{};
        Fixture(Context base,int count)throws Exception {this(base,count,false);}
        Fixture(Context base,int count,boolean lastAlreadyDue)throws Exception {
            File directory=new File(base.getNoBackupFilesDir(),"retention-cleanup-"+UUID.randomUUID());
            if(!directory.mkdir())throw new IOException("fixture directory unavailable");
            context=new ContextWrapper(base){
                @Override public File getNoBackupFilesDir(){return directory;}
                @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){return base.getSharedPreferences(directory.getName()+"-"+name,mode);}
            };
            root=new File(directory,"audio-segments");
            store=new AudioSegmentStore(context,stage->{if(stage.equals(failAt))throw new IOException("synthetic "+stage);additionalFault.at(stage);});
            Field field=AudioSegmentStore.class.getDeclaredField("db");field.setAccessible(true);db=(SQLiteDatabase)field.get(store);
            session=store.createSession(8000);AudioCaptureCoordinator capture=new AudioCaptureCoordinator(store,session,8000);long token=capture.start(true);
            short[] pcm=new short[count*40000];
            for(int i=0;i<count;i++)Arrays.fill(pcm,i*40000,(i+1)*40000,(short)(701+i));
            try{
                if(lastAlreadyDue){
                    // Test-only origin fixture BEFORE encryption, adapted from task-3/main-validation.
                    // The final frame goes through normal append, keeping its expiry, AAD and tag consistent.
                    capture.samples(token,pcm,(count-1)*40000,SystemClock.elapsedRealtimeNanos());
                    long received=SystemClock.elapsedRealtimeNanos();
                    long start=number(db,"SELECT start_ns FROM sessions WHERE id=?",session);
                    long earliest=Math.max(start,received-5_000_000_000L);
                    long origin=System.currentTimeMillis()-10000-7L*24*60*60*1000-(earliest-start)/1_000_000L;
                    db.execSQL("UPDATE sessions SET start_utc=? WHERE id=?",new Object[]{origin,session});
                    short[] tail=Arrays.copyOfRange(pcm,(count-1)*40000,count*40000);
                    try{capture.samples(token,tail,tail.length,received);}finally{Arrays.fill(tail,(short)0);}
                }else capture.samples(token,pcm,pcm.length,SystemClock.elapsedRealtimeNanos());
                capture.pause();
            }
            finally{Arrays.fill(pcm,(short)0);}
            frames=store.frameIds(session);
            if(frames.size()!=count)throw new AssertionError("fixture frame count");
            if(number(db,"SELECT COUNT(*) FROM segments")!=1)throw new AssertionError("fixture must use one mixed segment");
        }
        void expire(int index){db.execSQL("UPDATE frames SET expires_utc=0 WHERE id=?",new Object[]{frames.get(index)});}
        void revokeFirst(){store.deleteRange(session,store.stream(session),0,40000);}
        boolean revoked(int index){return number(db,"SELECT deleted=1 AND wrapped IS NULL FROM frames WHERE id=?",frames.get(index))==1;}
        boolean stillWrapped(int index){return number(db,"SELECT deleted=0 AND wrapped IS NOT NULL FROM frames WHERE id=?",frames.get(index))==1;}
        String envelope(int index){
            return rows(db,"SELECT id,session,stream,generation,boot,start_sample,end_sample,end_ns,eligible_ns,expires_utc,health,byte_length,hash,nonce,wrapped,aad,deleted,corrupt FROM frames WHERE id=?",frames.get(index));
        }
        String mapping(){return rows(db,"SELECT id,session,stream,start_sample,committed FROM segments ORDER BY id")+rows(db,"SELECT id,segment,byte_offset FROM frames ORDER BY id");}
        String sessions(){return rows(db,"SELECT * FROM sessions ORDER BY id");}
        String segment(int index){try(Cursor c=db.rawQuery("SELECT segment FROM frames WHERE id=?",new String[]{frames.get(index)})){if(!c.moveToFirst())throw new AssertionError("frame absent");return c.getString(0);}}
        File segmentFile(String id){return new File(root,id+".seg");}
        byte[] ciphertext(int index)throws Exception {
            try(Cursor c=db.rawQuery("SELECT segment,byte_offset,byte_length FROM frames WHERE id=?",new String[]{frames.get(index)})){
                if(!c.moveToFirst())throw new AssertionError("frame absent");
                byte[] bytes=new byte[c.getInt(2)];try(RandomAccessFile file=new RandomAccessFile(segmentFile(c.getString(0)),"r")){file.seek(c.getLong(1));file.readFully(bytes);}return bytes;
            }
        }
        Set<String> physical(){
            Set<String> names=new TreeSet<>();File[] files=root.listFiles();
            if(files!=null)for(File file:files)if(file.getName().endsWith(".seg"))names.add(file.getName());
            return names;
        }
        boolean onlyIndexedFiles(){
            Set<String> expected=new TreeSet<>();try(Cursor c=db.rawQuery("SELECT id FROM segments",null)){while(c.moveToNext())expected.add(c.getString(0)+".seg");}
            return physical().equals(expected);
        }
        boolean plaintextMatches(int index,short value)throws Exception {
            short[] pcm=store.read(frames.get(index));try{return pcm.length==40000&&pcm[0]==value&&pcm[pcm.length-1]==value;}finally{Arrays.fill(pcm,(short)0);}
        }
        String originalSessionMapping(){return rows(db,"SELECT * FROM segments WHERE session=? ORDER BY id",session)+rows(db,"SELECT id,segment,byte_offset FROM frames WHERE session=? ORDER BY id",session);}
        boolean hasUnindexedCopy(byte[] expected)throws IOException {
            Set<String> known=new HashSet<>();try(Cursor c=db.rawQuery("SELECT id FROM segments",null)){while(c.moveToNext())known.add(c.getString(0)+".seg");}
            File[] files=root.listFiles();if(files==null)throw new IOException("fixture directory unavailable");
            for(File file:files)if(file.getName().endsWith(".seg")&&!known.contains(file.getName())&&file.length()==expected.length){
                byte[] actual=new byte[expected.length];try(RandomAccessFile in=new RandomAccessFile(file,"r")){in.readFully(actual);}
                if(Arrays.equals(actual,expected))return true;
            }
            return false;
        }
        @Override public void close()throws Exception {
            try{store.close();}finally{KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("sidecar.audio."+session);}
        }
    }
    /** A separate segment with one legitimately encrypted expired frame; never read it before SQL assertions. */
    private static final class DueSession implements AutoCloseable {
        final Fixture fixture;
        final String session,frame;
        DueSession(Fixture f)throws Exception {
            fixture=f;session=f.store.createSession(8000);
            AudioCaptureCoordinator capture=new AudioCaptureCoordinator(f.store,session,8000);long token=capture.start(true);
            long received=SystemClock.elapsedRealtimeNanos(),start=number(f.db,"SELECT start_ns FROM sessions WHERE id=?",session);
            long earliest=Math.max(start,received-5_000_000_000L);
            long origin=System.currentTimeMillis()-10000-7L*24*60*60*1000-(earliest-start)/1_000_000L;
            // Synthetic age is established before normal AEAD encryption, never by editing frame metadata.
            f.db.execSQL("UPDATE sessions SET start_utc=? WHERE id=?",new Object[]{origin,session});
            short[] pcm=new short[40000];Arrays.fill(pcm,(short)811);
            try{capture.samples(token,pcm,pcm.length,received);capture.pause();}finally{Arrays.fill(pcm,(short)0);}
            List<String> ids=f.store.frameIds(session);if(ids.size()!=1)throw new AssertionError("independent due frame count");frame=ids.get(0);
        }
        boolean wrapped(){return number(fixture.db,"SELECT deleted=0 AND wrapped IS NOT NULL FROM frames WHERE id=?",frame)==1;}
        boolean revoked(){return number(fixture.db,"SELECT deleted=1 AND wrapped IS NULL FROM frames WHERE id=?",frame)==1;}
        long tombstones(){return number(fixture.db,"SELECT COUNT(*) FROM deletions WHERE session=?",session);}
        @Override public void close()throws Exception {KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("sidecar.audio."+session);}
    }
    private static boolean authenticDue(SQLiteDatabase db,String frame)throws Exception {
        try(Cursor c=db.rawQuery("SELECT expires_utc,aad FROM frames WHERE id=?",new String[]{frame})){
            if(!c.moveToFirst())throw new AssertionError("due fixture absent");
            return c.getLong(0)<System.currentTimeMillis()&&new org.json.JSONObject(new String(c.getBlob(1),java.nio.charset.StandardCharsets.UTF_8)).getLong("expires")==c.getLong(0);
        }
    }
    private static void clockIndependentRetry(Context context)throws Exception {
        try(Fixture f=new Fixture(context,3)){
            String firstSegment=f.segment(0),sessionBefore=f.sessions();byte[] keptCipher=f.ciphertext(1);String keptEnvelope=f.envelope(1);
            f.expire(0);f.failAt="compaction_before_fsync";
            failure(IOException.class,()->f.store.sweepExpired(()->false),"expiry cleanup before fsync");f.failAt="";
            check(f.revoked(0),"expiry transaction survives later cleanup failure");
            check(number(f.db,"SELECT COUNT(*) FROM deletions")==1,"one durable deletion interval");
            check(f.segmentFile(firstSegment).exists(),"old mixed segment remains before retry");
            int boot=(int)number(f.db,"SELECT boot FROM clock WHERE singleton=1");
            f.db.execSQL("UPDATE clock SET boot=? WHERE singleton=1",new Object[]{boot-1});
            f.store.retryDeletedCleanup(()->false);
            // Inject expiry only after copying authenticated ciphertext. Editing expires_utc alone
            // deliberately invalidates AAD, so it must never stand in for a valid frame during copy.
            f.expire(2);String pendingEnvelope=f.envelope(2);byte[] pendingCipher=f.ciphertext(2);
            check(!f.segmentFile(firstSegment).exists(),"old revoked bytes reclaimed despite boot mismatch");
            check(f.onlyIndexedFiles(),"failed replacement and old segment no longer orphaned");
            check(f.stillWrapped(2),"clock-free cleanup does not infer new expiry");
            check(f.envelope(1).equals(keptEnvelope)&&Arrays.equals(f.ciphertext(1),keptCipher),"retained ciphertext and all non-location metadata unchanged");
            check(f.envelope(2).equals(pendingEnvelope)&&Arrays.equals(f.ciphertext(2),pendingCipher),"pending-expiry fixture remains unchanged before trusted adjudication");
            check(f.sessions().equals(sessionBefore),"cleanup does not advance session generation");
            String mapping=f.mapping(),clock=rows(f.db,"SELECT * FROM clock");
            failure(IllegalStateException.class,()->f.store.read(f.frames.get(1)),"plaintext still requires trusted boot");
            failure(IllegalStateException.class,()->f.store.sweepExpired(()->false),"new expiry still requires trusted boot");
            check(f.stillWrapped(2)&&number(f.db,"SELECT COUNT(*) FROM deletions")==1,"failed clock check cannot commit new expiry");
            check(f.mapping().equals(mapping)&&rows(f.db,"SELECT * FROM clock").equals(clock),"repeated pre-clock cleanup and denied access preserve mapping and clock");
            f.db.execSQL("UPDATE clock SET boot=? WHERE singleton=1",new Object[]{boot});
            check(f.plaintextMatches(1,(short)702),"retained frame decrypts after fixture anchor restored");
            check(f.store.sweepExpired(()->false)==1,"trusted sweep now expires exactly the pending frame");
            check(f.revoked(2)&&f.plaintextMatches(1,(short)702),"new expiry preserves remaining frame");
            mapping=f.mapping();f.store.retryDeletedCleanup(()->false);f.store.retryDeletedCleanup(()->false);
            check(f.mapping().equals(mapping)&&f.sessions().equals(sessionBefore)&&f.onlyIndexedFiles(),"clock-free retries are idempotent");
        }
    }
    private static void authenticatedDueFrameCopy(Context context)throws Exception {
        try(Fixture f=new Fixture(context,3,true)){
            String due=f.frames.get(2),old=f.segment(2),sessionBefore=f.sessions();
            byte[] pendingCipher=f.ciphertext(2),keptCipher=f.ciphertext(1);
            String pendingEnvelope=f.envelope(2),keptEnvelope=f.envelope(1);
            try(Cursor c=f.db.rawQuery("SELECT expires_utc,aad FROM frames WHERE id=?",new String[]{due})){
                if(!c.moveToFirst())throw new AssertionError("due fixture missing");
                long expiry=c.getLong(0);org.json.JSONObject aad=new org.json.JSONObject(new String(c.getBlob(1),java.nio.charset.StandardCharsets.UTF_8));
                check(expiry<System.currentTimeMillis()&&aad.getLong("expires")==expiry,"due frame was encrypted with matching expiry AAD");
            }
            f.revokeFirst();int boot=(int)number(f.db,"SELECT boot FROM clock WHERE singleton=1");
            f.db.execSQL("UPDATE clock SET boot=? WHERE singleton=1",new Object[]{boot-1});String clock=rows(f.db,"SELECT * FROM clock");
            f.store.retryDeletedCleanup(()->false);
            check(f.revoked(0)&&f.stillWrapped(2)&&number(f.db,"SELECT COUNT(*) FROM deletions")==1,"clock-free copy keeps authenticated due frame pending");
            check(!f.segment(2).equals(old)&&!f.segmentFile(old).exists()&&f.onlyIndexedFiles(),"due frame really passes through replacement copy");
            check(f.envelope(2).equals(pendingEnvelope)&&Arrays.equals(f.ciphertext(2),pendingCipher),"authenticated due ciphertext and envelope copied unchanged");
            check(f.envelope(1).equals(keptEnvelope)&&Arrays.equals(f.ciphertext(1),keptCipher),"nonexpired neighbor copied unchanged");
            check(f.sessions().equals(sessionBefore)&&rows(f.db,"SELECT * FROM clock").equals(clock),"copy does not change generation or clock anchor");
            failure(IllegalStateException.class,()->f.store.read(due),"due frame cannot bypass boot trust on read");
            failure(IllegalStateException.class,()->f.store.sweepExpired(()->false),"authenticity alone cannot authorize new expiry");
            check(f.stillWrapped(2)&&number(f.db,"SELECT COUNT(*) FROM deletions")==1,"clock denial leaves due frame unadjudicated");
            f.db.execSQL("UPDATE clock SET boot=? WHERE singleton=1",new Object[]{boot});
            check(f.store.sweepExpired(()->false)==1,"restored fixture anchor adjudicates exactly the authenticated due frame");
            check(f.revoked(2)&&f.plaintextMatches(1,(short)702),"authenticated due expiry leaves valid neighbor readable");
        }
    }
    private static void committedSwitchRetry(Context context)throws Exception {
        try(Fixture f=new Fixture(context,2)){
            f.revokeFirst();String old=f.segment(1),metadata=f.envelope(1),sessionBefore=f.sessions();byte[] ciphertext=f.ciphertext(1);
            f.failAt="compaction_after_switch";
            failure(IOException.class,()->f.store.retryDeletedCleanup(()->false),"failure after mapping commit");f.failAt="";
            check(!f.segment(1).equals(old),"replacement mapping is already committed");
            check(f.segmentFile(old).exists(),"old physical segment remains for retry");
            check(f.revoked(0),"committed deletion cannot revive its DEK");
            String mapping=f.mapping();f.store.retryDeletedCleanup(()->false);
            check(!f.segmentFile(old).exists()&&f.onlyIndexedFiles(),"same-instance retry removes unindexed old file");
            check(f.mapping().equals(mapping)&&f.sessions().equals(sessionBefore),"orphan retry does not compact again or advance generation");
            check(f.envelope(1).equals(metadata)&&Arrays.equals(f.ciphertext(1),ciphertext)&&f.plaintextMatches(1,(short)702),"switch failure/retry preserves surviving evidence");
            f.store.retryDeletedCleanup(()->false);check(f.mapping().equals(mapping)&&f.onlyIndexedFiles(),"completed switch retry remains idempotent");
        }
    }
    private static void cancelAndUncommittedSwitch(Context context)throws Exception {
        try(Fixture f=new Fixture(context,2)){
            f.revokeFirst();String mapping=f.mapping(),sessionBefore=f.sessions(),metadata=f.envelope(1);Set<String> physical=f.physical();byte[] ciphertext=f.ciphertext(1);
            failure(InterruptedIOException.class,()->f.store.retryDeletedCleanup(()->true),"cancelled cleanup");
            check(f.mapping().equals(mapping)&&f.physical().equals(physical)&&f.sessions().equals(sessionBefore),"pre-cancel leaves storage and generation unchanged");
            check(f.revoked(0)&&f.stillWrapped(1),"cancellation never rolls back a durable revocation");
            f.failAt="compaction_before_switch";
            failure(IOException.class,()->f.store.retryDeletedCleanup(()->false),"failure before mapping commit");f.failAt="";
            check(f.mapping().equals(mapping),"mapping transaction rolls back before switch");
            check(f.envelope(1).equals(metadata)&&Arrays.equals(f.ciphertext(1),ciphertext),"rollback leaves surviving evidence unchanged");
            f.store.retryDeletedCleanup(()->false);
            check(f.onlyIndexedFiles()&&f.physical().size()==1,"retry reclaims failed replacement and old segment");
            check(f.revoked(0)&&f.plaintextMatches(1,(short)702)&&f.sessions().equals(sessionBefore),"retry retains deletion and usable surviving evidence");
        }
    }
    private static void expiryTransactionRollback(Context context)throws Exception {
        try(Fixture f=new Fixture(context,2)){
            f.expire(0);String mapping=f.mapping(),metadata=f.envelope(0),kept=f.envelope(1),sessionBefore=f.sessions();Set<String> physical=f.physical();
            f.failAt="expiry_before_commit";
            failure(IOException.class,()->f.store.sweepExpired(()->false),"expiry transaction before commit");f.failAt="";
            check(f.stillWrapped(0)&&number(f.db,"SELECT COUNT(*) FROM deletions")==0,"failed expiry has no partial tombstone or DEK revocation");
            check(f.envelope(0).equals(metadata)&&f.envelope(1).equals(kept),"expiry rollback restores all frame metadata");
            check(f.mapping().equals(mapping)&&f.physical().equals(physical)&&f.sessions().equals(sessionBefore),"expiry rollback leaves segments and generation unchanged");
            check(f.store.sweepExpired(()->false)==1,"successful retry counts the one newly expired frame");
            check(f.revoked(0)&&number(f.db,"SELECT COUNT(*) FROM deletions")==1,"successful expiry has one deletion interval");
            check(f.envelope(1).equals(kept)&&f.plaintextMatches(1,(short)702),"expiry retry preserves surviving evidence");
            mapping=f.mapping();check(f.store.sweepExpired(()->false)==0,"repeat expiry has no new work");
            check(f.mapping().equals(mapping)&&number(f.db,"SELECT COUNT(*) FROM deletions")==1&&f.sessions().equals(sessionBefore),"repeat expiry preserves mapping/generation and does not duplicate tombstones");
        }
    }
    private static void oldCompactionCannotBlockExpiry(Context context)throws Exception {
        try(Fixture f=new Fixture(context,2);DueSession due=new DueSession(f)){
            check(authenticDue(f.db,due.frame)&&number(f.db,"SELECT COUNT(*) FROM segments")==2,"independent due frame has valid expiry AAD in its own segment");
            f.revokeFirst();String old=f.segment(1),mapping=f.originalSessionMapping(),sessionBefore=f.sessions(),metadata=f.envelope(1);byte[] cipher=f.ciphertext(1);
            check(f.revoked(0)&&due.wrapped()&&due.tombstones()==0,"old deletion and newly due DEK are initially independent");
            int[] hits={0};
            f.additionalFault=stage->{
                // Only A's nonempty replacement fails; B's fully revoked segment may be visited first.
                if(stage.equals("compaction_before_fsync")&&f.hasUnindexedCopy(cipher)){hits[0]++;throw new IOException("synthetic old-segment copy failure");}
            };
            failure(IOException.class,()->f.store.sweepExpired(()->false),"old segment copy failure during expiry sweep");
            check(hits[0]==1,"failure reached the old segment replacement");
            check(due.revoked()&&due.tombstones()==1,"old compaction failure cannot block independent expired DEK revocation");
            check(f.revoked(0)&&f.originalSessionMapping().equals(mapping)&&f.segmentFile(old).exists(),"failed old compaction preserves its mapping and prior deletion");
            check(f.envelope(1).equals(metadata)&&Arrays.equals(f.ciphertext(1),cipher),"old copy failure preserves surviving evidence");
            check(f.sessions().equals(sessionBefore),"expiry and failed cleanup do not advance either session generation");
            failure(IOException.class,()->f.store.sweepExpired(()->false),"repeat old segment copy failure");
            check(hits[0]==2&&due.revoked()&&due.tombstones()==1&&f.originalSessionMapping().equals(mapping)&&f.sessions().equals(sessionBefore),"repeat failure never restores a DEK or duplicates its tombstone");
            f.additionalFault=stage->{};f.store.retryDeletedCleanup(()->false);
            check(!f.segmentFile(old).exists()&&f.onlyIndexedFiles()&&f.physical().size()==1,"retry reclaims both revoked segments and failed replacements");
            check(f.envelope(1).equals(metadata)&&Arrays.equals(f.ciphertext(1),cipher)&&f.plaintextMatches(1,(short)702),"retry preserves the unrelated surviving frame");
            mapping=f.mapping();check(f.store.sweepExpired(()->false)==0,"new expiry was already adjudicated despite prior copy failures");
            check(f.mapping().equals(mapping)&&f.sessions().equals(sessionBefore)&&due.revoked()&&number(f.db,"SELECT COUNT(*) FROM deletions")==2,"completed cleanup keeps mappings generations and tombstones stable");
        }
    }
    private static void corruptNeighborCannotBlockExpiry(Context context)throws Exception {
        try(Fixture f=new Fixture(context,2);DueSession due=new DueSession(f)){
            check(authenticDue(f.db,due.frame)&&due.wrapped()&&number(f.db,"SELECT COUNT(*) FROM segments")==2,"corruption counterexample has an independent authentic due frame");
            f.revokeFirst();String old=f.segment(1),mapping=f.originalSessionMapping(),sessionBefore=f.sessions(),metadata=f.envelope(1);byte[] original=f.ciphertext(1);
            long offset=number(f.db,"SELECT byte_offset FROM frames WHERE id=?",f.frames.get(1));
            try(RandomAccessFile file=new RandomAccessFile(f.segmentFile(old),"rw")){file.seek(offset);file.write(original[0]^1);file.getFD().sync();}
            byte[] damaged=f.ciphertext(1);
            check(!Arrays.equals(original,damaged)&&f.envelope(1).equals(metadata),"fixture changes ciphertext only and leaves authenticated metadata and hash untouched");
            failure(SecurityException.class,()->f.store.sweepExpired(()->false),"corrupt old neighbor prevents verified copy");
            check(due.revoked()&&due.tombstones()==1,"corrupt old neighbor cannot block independent expired DEK revocation");
            check(f.revoked(0)&&f.originalSessionMapping().equals(mapping)&&f.segmentFile(old).exists(),"copy verification failure cannot fabricate replacement mapping");
            check(f.envelope(1).equals(metadata)&&Arrays.equals(f.ciphertext(1),damaged)&&f.sessions().equals(sessionBefore),"failed copy neither repairs nor silently discards corrupt surviving evidence");
            f.store.recover();String recoveredMetadata=f.envelope(1);
            check(number(f.db,"SELECT corrupt FROM frames WHERE id=?",f.frames.get(1))==1&&due.revoked()&&due.tombstones()==1,"recovery marks the damaged neighbor without reviving the expired frame");
            failure(SecurityException.class,()->f.store.sweepExpired(()->false),"corrupt neighbor still reports cleanup failure after recovery");
            check(f.originalSessionMapping().equals(mapping)&&due.revoked()&&due.tombstones()==1&&f.envelope(1).equals(recoveredMetadata)&&Arrays.equals(f.ciphertext(1),damaged),"repeat corrupt cleanup preserves revocation metadata and source mapping");
            check(f.sessions().equals(sessionBefore)&&number(f.db,"SELECT COUNT(*) FROM deletions")==2,"corrupt retries neither advance generations nor duplicate deletions");
        }
    }
    private static void expiryCancellationBoundaries(Context context)throws Exception {
        try(Fixture f=new Fixture(context,2,true)){
            check(authenticDue(f.db,f.frames.get(1))&&f.stillWrapped(1),"cancellation fixture starts with authentic pending expiry");
            String mapping=f.mapping(),metadata=f.envelope(1),kept=f.envelope(0),sessionBefore=f.sessions();Set<String> physical=f.physical();
            boolean[] cancelled={false},entered={false};
            f.additionalFault=stage->{if(stage.equals("expiry_before_commit")){entered[0]=true;cancelled[0]=true;}};
            failure(InterruptedIOException.class,()->f.store.sweepExpired(()->true),"expiry cancelled before admission");
            check(!entered[0]&&f.stillWrapped(1)&&number(f.db,"SELECT COUNT(*) FROM deletions")==0&&f.mapping().equals(mapping),"pre-cancel never starts expiry transaction");
            // The callback does not throw: production must observe cancellation again before commit.
            failure(InterruptedIOException.class,()->f.store.sweepExpired(()->cancelled[0]),"expiry cancellation immediately before commit");
            check(entered[0]&&f.stillWrapped(1)&&number(f.db,"SELECT COUNT(*) FROM deletions")==0,"precommit cancellation rolls back DEK revocation and tombstone together");
            check(f.envelope(1).equals(metadata)&&f.envelope(0).equals(kept),"cancelled transaction restores complete frame metadata");
            check(f.mapping().equals(mapping)&&f.physical().equals(physical)&&f.sessions().equals(sessionBefore),"precommit cancellation does not alter physical files mapping or generation");
            cancelled[0]=false;f.additionalFault=stage->{};
            check(f.store.sweepExpired(()->false)==1,"retry after cancellation adjudicates exactly one expiry");
            check(f.revoked(1)&&number(f.db,"SELECT COUNT(*) FROM deletions")==1&&f.envelope(0).equals(kept)&&f.plaintextMatches(0,(short)701),"cancel retry preserves surviving evidence and commits one deletion");
        }
        try(Fixture f=new Fixture(context,2,true)){
            String mapping=f.mapping(),sessionBefore=f.sessions(),kept=f.envelope(0);byte[] cipher=f.ciphertext(0);boolean[] cancelled={false},entered={false};
            f.additionalFault=stage->{if(stage.equals("compaction_before_fsync")){entered[0]=true;cancelled[0]=true;}};
            failure(InterruptedIOException.class,()->f.store.sweepExpired(()->cancelled[0]),"cleanup cancellation after expiry commit");
            check(entered[0]&&f.revoked(1)&&number(f.db,"SELECT COUNT(*) FROM deletions")==1,"postcommit cancellation retains durable DEK revocation");
            check(f.mapping().equals(mapping)&&f.sessions().equals(sessionBefore)&&f.envelope(0).equals(kept)&&Arrays.equals(f.ciphertext(0),cipher),"postcommit cancellation leaves original mapping and survivor unchanged");
            cancelled[0]=false;f.additionalFault=stage->{};f.store.retryDeletedCleanup(()->false);
            check(f.revoked(1)&&f.onlyIndexedFiles()&&f.plaintextMatches(0,(short)701),"cleanup retry finishes without reopening revoked evidence");
            mapping=f.mapping();check(f.store.sweepExpired(()->false)==0&&f.mapping().equals(mapping)&&f.sessions().equals(sessionBefore)&&number(f.db,"SELECT COUNT(*) FROM deletions")==1,"postcommit retry remains idempotent");
        }
    }
    private static void maintenanceOpenCannotBlockExpiry(Context context)throws Exception {
        String interrupted=null;
        try(Fixture f=new Fixture(context,2,true)){
            String due=f.frames.get(1),kept=f.frames.get(0);
            check(authenticDue(f.db,due)&&f.stillWrapped(1),"maintenance fixture starts with authentic pending expiry");
            interrupted=f.store.createSession(8000);final String interruptedSession=interrupted;
            AudioCaptureCoordinator capture=new AudioCaptureCoordinator(f.store,interruptedSession,8000);long token=capture.start(true);
            short[] pcm=new short[40000];Arrays.fill(pcm,(short)912);
            try{capture.samples(token,pcm,pcm.length,SystemClock.elapsedRealtimeNanos());}finally{Arrays.fill(pcm,(short)0);}
            capture.stopAdmission();
            long generation=number(f.db,"SELECT generation FROM sessions WHERE id=?",interruptedSession);
            String sessions=f.sessions(),clock=rows(f.db,"SELECT * FROM clock"),frames=rows(f.db,"SELECT * FROM frames ORDER BY id");
            // Store close without pause models interrupted-process metadata; no real process or microphone is used.
            f.store.close();
            File index=new File(f.root,"index.sqlite"),orphan=new File(f.root,UUID.randomUUID()+".seg");
            try(FileOutputStream out=new FileOutputStream(orphan)){out.write(new byte[]{11,12,13});out.getFD().sync();}
            Set<String> physical=f.physical();int[] hits={0};
            AudioSegmentStore.Faults failUnlink=stage->{if(stage.equals("cleanup_before_unlink")){hits[0]++;throw new IOException("synthetic maintenance orphan unlink");}};
            Context mustNotOpen=new ContextWrapper(f.context){@Override public File getNoBackupFilesDir(){throw new AssertionError("pre-cancel opened maintenance storage");}};
            failure(InterruptedIOException.class,()->AudioSegmentStore.sweepExpiredForMaintenance(mustNotOpen,failUnlink,()->true),"maintenance pre-cancel before store construction");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(index.getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                check(hits[0]==0&&f.physical().equals(physical)&&rows(db,"SELECT * FROM sessions ORDER BY id").equals(sessions)&&rows(db,"SELECT * FROM clock").equals(clock)&&rows(db,"SELECT * FROM frames ORDER BY id").equals(frames),"pre-cancel leaves files frames sessions and clock untouched");
            }
            failure(IOException.class,()->{try(AudioSegmentStore ignored=new AudioSegmentStore(f.context,failUnlink)){}},"ordinary constructor still performs orphan recovery");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(index.getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                check(hits[0]==1&&number(db,"SELECT deleted=0 AND wrapped IS NOT NULL FROM frames WHERE id=?",due)==1&&number(db,"SELECT COUNT(*) FROM deletions")==0&&orphan.exists(),"ordinary constructor cleanup failure occurs before any expiry adjudication");
            }
            failure(IOException.class,()->AudioSegmentStore.sweepExpiredForMaintenance(f.context,failUnlink,()->false),"maintenance reports cleanup failure after expiry adjudication");
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(index.getPath(),null,SQLiteDatabase.OPEN_READONLY)){
                check(hits[0]==2&&number(db,"SELECT deleted=1 AND wrapped IS NULL FROM frames WHERE id=?",due)==1&&number(db,"SELECT COUNT(*) FROM deletions WHERE session=?",f.session)==1,"maintenance entry revokes expired DEK before failing orphan cleanup");
                check(rows(db,"SELECT * FROM sessions ORDER BY id").equals(sessions)&&number(db,"SELECT COUNT(*) FROM capture_controls WHERE session=?",interruptedSession)==0&&number(db,"SELECT next_sample FROM sessions WHERE id=?",interruptedSession)==40000,"maintenance preserves interrupted stream for later full recovery");
            }
            failure(IOException.class,()->{try(AudioSegmentStore ignored=new AudioSegmentStore(f.context,failUnlink)){}},"ordinary reopen cannot bypass its recovery failure");
            try(AudioSegmentStore reopened=new AudioSegmentStore(f.context)){
                Field field=AudioSegmentStore.class.getDeclaredField("db");field.setAccessible(true);SQLiteDatabase db=(SQLiteDatabase)field.get(reopened);
                check(hits[0]==3&&!orphan.exists()&&reopened.state(interruptedSession).equals("paused"),"failed maintenance and ordinary opens release writer lock for successful full recovery");
                check(number(db,"SELECT generation FROM sessions WHERE id=?",interruptedSession)==generation+1&&number(db,"SELECT COUNT(*) FROM capture_controls WHERE session=? AND kind='recovery' AND unknown_tail_from_sample=40000 AND accepted_end IS NULL AND observed_end IS NULL",interruptedSession)==1,"ordinary recovery retains unknown tail at durable sample frontier");
                check(number(db,"SELECT deleted=1 AND wrapped IS NULL FROM frames WHERE id=?",due)==1&&number(db,"SELECT COUNT(*) FROM deletions WHERE session=?",f.session)==1,"reopen cannot resurrect expired DEK or duplicate its tombstone");
                short[] clear=reopened.read(kept);try{check(clear.length==40000&&clear[0]==701&&clear[clear.length-1]==701,"full recovery preserves unrelated readable evidence");}finally{Arrays.fill(clear,(short)0);}
                String controls=rows(db,"SELECT * FROM capture_controls WHERE session=?",interruptedSession),recoveredSessions=rows(db,"SELECT * FROM sessions ORDER BY id");
                reopened.recover();
                check(rows(db,"SELECT * FROM capture_controls WHERE session=?",interruptedSession).equals(controls)&&rows(db,"SELECT * FROM sessions ORDER BY id").equals(recoveredSessions),"repeated full recovery preserves unknown-tail identity and generation");
            }
            check(AudioSegmentStore.sweepExpiredForMaintenance(f.context,()->false)==0,"production maintenance entry retries completed expiry without fresh revocation");
        }finally{
            if(interrupted!=null){KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("sidecar.audio."+interrupted);}
        }
    }
    public static int run(Instrumentation instrumentation)throws Exception {
        if(!Build.HARDWARE.equals("ranchu"))throw new SecurityException("disposable emulator required");
        checks=0;Context context=instrumentation.getTargetContext();
        clockIndependentRetry(context);authenticatedDueFrameCopy(context);committedSwitchRetry(context);cancelAndUncommittedSwitch(context);expiryTransactionRollback(context);
        oldCompactionCannotBlockExpiry(context);corruptNeighborCannotBlockExpiry(context);expiryCancellationBoundaries(context);
        maintenanceOpenCannotBlockExpiry(context);
        return checks;
    }
}

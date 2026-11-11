package local.sidecar.copilot;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Real host temporary-file checks. No Android AtomicFile, Keystore or device acceptance is claimed. */
public final class NoteFileSetTest {
    private static final String A="00000000-0000-0000-0000-000000000001";
    private static final String B="00000000-0000-0000-0000-000000000002";
    private static final String C="00000000-0000-0000-0000-000000000003";
    private static int checks;
    private static int skipped;
    private interface Action {void run()throws Exception;}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    private static void io(Action action,String label)throws Exception {
        try{action.run();throw new AssertionError(label+" unexpectedly succeeded");}catch(IOException expected){checks++;}
    }
    private static void invalid(Action action,String label)throws Exception {
        try{action.run();throw new AssertionError(label+" unexpectedly succeeded");}catch(IllegalArgumentException expected){checks++;}
    }
    private static File directory(Path root,String name)throws IOException {return Files.createDirectory(root.resolve(name)).toFile();}
    private static byte[] bytes(int count,int seed){byte[] result=new byte[count];for(int i=0;i<count;i++)result[i]=(byte)(seed+i*31);return result;}
    private static byte[] join(byte[] first,byte[] second){byte[] result=Arrays.copyOf(first,first.length+second.length);System.arraycopy(second,0,result,first.length,second.length);return result;}
    private static void write(File file,byte[] content)throws IOException {Files.write(file.toPath(),content);}
    private static void unchanged(File file,byte[] expected)throws IOException {check(Arrays.equals(expected,Files.readAllBytes(file.toPath())),"read-only helper changed "+file.getName());}
    private static void inventoryCombinations(Path root)throws Exception {
        for(int mask=1;mask<8;mask++){
            File directory=directory(root,"combination-"+mask);NoteFileSet files=new NoteFileSet(directory,A);
            File[] family={files.base,files.pending,files.backup};byte[][] contents={bytes(19,1),bytes(23,2),bytes(29,3)};
            for(int part=0;part<3;part++)if((mask&(1<<part))!=0)write(family[part],contents[part]);
            List<String> visible=(mask&5)!=0?Collections.singletonList(A):Collections.emptyList();
            check(NoteFileSet.ids(directory,false).equals(visible),"public inventory covers base/backup combination "+mask);
            check(NoteFileSet.ids(directory,true).equals(Collections.singletonList(A)),"maintenance inventory includes and deduplicates pending combination "+mask);
            if(mask==1){files.requireReadResolved();checks++;}else io(files::requireReadResolved,"unresolved file combination "+mask);
            io(files::requireRemoved,"remaining file combination "+mask);
            for(int part=0;part<3;part++){
                if((mask&(1<<part))!=0)unchanged(family[part],contents[part]);
                else check(!Files.exists(family[part].toPath(),LinkOption.NOFOLLOW_LINKS),"helper did not restore/create absent family member");
            }
        }
        File empty=directory(root,"empty");NoteFileSet absent=new NoteFileSet(empty,A);
        check(NoteFileSet.ids(empty,false).isEmpty()&&NoteFileSet.ids(empty,true).isEmpty(),"empty inventory");
        absent.requireRemoved();checks++;io(absent::requireReadResolved,"absent base cannot be read");
        io(()->absent.verifyCommit(new byte[0],new byte[0]),"missing base is not an empty commit");
    }
    private static void inventoryValidation(Path root)throws Exception {
        File directory=directory(root,"sorted");
        write(new File(directory,C+".enc.bak"),bytes(2,1));write(new File(directory,A+".enc"),bytes(2,2));
        write(new File(directory,A+".enc.bak"),bytes(2,3));write(new File(directory,A+".enc.new"),bytes(2,4));
        write(new File(directory,B+".enc.new"),bytes(2,5));write(new File(directory,"ignored.enc.tmp"),bytes(2,6));
        Files.createDirectory(new File(directory,"unrelated-directory").toPath());
        check(NoteFileSet.ids(directory,false).equals(Arrays.asList(A,C)),"public inventory sorts and deduplicates base plus backup");
        check(NoteFileSet.ids(directory,true).equals(Arrays.asList(A,B,C)),"maintenance includes new-only id and remains sorted");
        for(String suffix:new String[]{".enc",".enc.bak",".enc.new"}){
            File invalidRoot=directory(root,"invalid-"+suffix.substring(1).replace('.','-'));
            File invalidFile=new File(invalidRoot,"INVALID-ID"+suffix);write(invalidFile,bytes(3,1));
            if(suffix.equals(".enc.new"))check(NoteFileSet.ids(invalidRoot,false).isEmpty(),"unrecognized pending suffix stays out of public inventory");
            else io(()->NoteFileSet.ids(invalidRoot,false),"public inventory rejects invalid recognized id");
            io(()->NoteFileSet.ids(invalidRoot,true),"maintenance rejects invalid recognized id");
            unchanged(invalidFile,bytes(3,1));
        }
        File upper=directory(root,"uppercase");write(new File(upper,"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA.enc"),bytes(2,3));
        io(()->NoteFileSet.ids(upper,false),"uppercase id does not satisfy the existing lower-case contract");
        File notDirectory=root.resolve("inventory-is-file").toFile();write(notDirectory,new byte[]{1});
        io(()->NoteFileSet.ids(notDirectory,false),"null listFiles from a regular file must fail");
        io(()->NoteFileSet.ids(root.resolve("missing-directory").toFile(),true),"missing inventory directory must fail");
        NoteFileSet names=new NoteFileSet(directory,A);
        check(names.base.getName().equals(A+".enc")&&names.pending.getName().equals(A+".enc.new")&&names.backup.getName().equals(A+".enc.bak"),"exact family names");
        for(String id:new String[]{null,"short","../"+A,"AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA","00000000-0000-0000-0000-00000000000g"})
            invalid(()->new NoteFileSet(directory,id),"invalid constructor id");
        check(new NoteFileSet(directory,"------------------------------------").base.getName().equals("------------------------------------.enc"),"constructor preserves the specified existing regex without adding a UUID policy");
    }
    private static void residualDirectories(Path root)throws Exception {
        for(int member=0;member<3;member++)for(boolean nonempty:new boolean[]{false,true}){
            File directory=directory(root,"directory-residue-"+member+"-"+nonempty);NoteFileSet files=new NoteFileSet(directory,A);
            File target=new File[]{files.base,files.pending,files.backup}[member];Files.createDirectory(target.toPath());
            if(nonempty)write(new File(target,"retained-child"),new byte[]{7});
            io(files::requireRemoved,"directory residue must not count as deletion");
            io(files::requireReadResolved,"directory residue must not count as a resolved read");
            check(NoteFileSet.ids(directory,true).equals(Collections.singletonList(A)),"recognized directory residue remains discoverable for maintenance");
            check(Files.isDirectory(target.toPath(),LinkOption.NOFOLLOW_LINKS),"read-only checks leave residue intact");
        }
        for(boolean pending:new boolean[]{false,true}){
            File directory=directory(root,"base-with-companion-directory-"+pending);NoteFileSet files=new NoteFileSet(directory,A);
            write(files.base,new byte[]{1,2,3});File companion=pending?files.pending:files.backup;
            Files.createDirectory(companion.toPath());write(new File(companion,"retained-child"),new byte[]{9});
            io(files::requireReadResolved,"regular base cannot mask an unresolved companion directory");
            io(()->files.verifyCommit(new byte[]{1},new byte[]{2,3}),"commit cannot ignore a companion directory");
            unchanged(files.base,new byte[]{1,2,3});
        }
    }
    private static void commitBytes(Path root)throws Exception {
        File directory=directory(root,"commit");NoteFileSet files=new NoteFileSet(directory,A);
        byte[] nonce=bytes(12,128),ciphertext=bytes(20003,193),expected=join(nonce,ciphertext);
        byte[] originalNonce=nonce.clone(),originalCiphertext=ciphertext.clone();
        write(files.base,expected);files.verifyCommit(nonce,ciphertext);checks++;unchanged(files.base,expected);
        check(Arrays.equals(nonce,originalNonce)&&Arrays.equals(ciphertext,originalCiphertext),"verification preserves caller byte arrays");
        byte[] old=bytes(expected.length,77);write(files.base,old);
        io(()->files.verifyCommit(nonce,ciphertext),"same-length previous contents are not a committed replacement");unchanged(files.base,old);
        for(int offset:new int[]{0,nonce.length-1,nonce.length,8191,8192,nonce.length+8191,expected.length-1}){
            byte[] corrupt=expected.clone();corrupt[offset]^=0x55;write(files.base,corrupt);
            io(()->files.verifyCommit(nonce,ciphertext),"mismatch at prefix, chunk boundary or final byte");unchanged(files.base,corrupt);
        }
        byte[] multiple=expected.clone();multiple[2]^=3;multiple[nonce.length+100]^=0x7f;write(files.base,multiple);
        io(()->files.verifyCommit(nonce,ciphertext),"multiple unequal bytes cannot pass length equality");unchanged(files.base,multiple);
        for(int length:new int[]{0,nonce.length-1,nonce.length,expected.length-1}){
            byte[] truncated=Arrays.copyOf(expected,length);write(files.base,truncated);
            io(()->files.verifyCommit(nonce,ciphertext),"truncated nonce or ciphertext");unchanged(files.base,truncated);
        }
        byte[] trailing=Arrays.copyOf(expected,expected.length+2);write(files.base,trailing);
        io(()->files.verifyCommit(nonce,ciphertext),"trailing bytes must fail exact length verification");unchanged(files.base,trailing);
        write(files.base,expected);write(files.pending,new byte[]{99});
        io(()->files.verifyCommit(nonce,ciphertext),"matching base with pending file is not resolved");Files.delete(files.pending.toPath());
        write(files.backup,new byte[]{88});io(()->files.verifyCommit(nonce,ciphertext),"matching base with backup is not resolved");unchanged(files.base,expected);
        Files.delete(files.base.toPath());io(files::requireReadResolved,"backup-only never silently restores a base");
        check(!Files.exists(files.base.toPath())&&Files.exists(files.backup.toPath()),"backup-only rejection does not mutate recovery state");
        Files.delete(files.backup.toPath());files.requireRemoved();checks++;
        write(files.base,ciphertext);files.verifyCommit(new byte[0],ciphertext);checks++;
        write(files.base,nonce);files.verifyCommit(nonce,new byte[0]);checks++;
        write(files.base,new byte[0]);files.verifyCommit(new byte[0],new byte[0]);checks++;
    }
    private static void boundedReads(Path root)throws Exception {
        File directory=directory(root,"bounded-reads");File target=new File(directory,"ciphertext");
        final int maximum=2*1024*1024;
        for(int length:new int[]{0,1,28,29,30,8193,maximum-1,maximum,maximum+1}){
            byte[] content=bytes(length,41);write(target,content);
            if(length<29||length>maximum)io(()->NoteFileSet.readBounded(target),"out-of-range ciphertext length "+length);
            else {
                byte[] actual=NoteFileSet.readBounded(target);
                check(Arrays.equals(actual,content),"bounded read returns every byte at supported length "+length);
                actual[0]^=0x7f;
                unchanged(target,content);
                check(Arrays.equals(NoteFileSet.readBounded(target),content),"caller buffer mutation cannot alter a later read");
            }
            unchanged(target,content);
        }
        io(()->NoteFileSet.readBounded(new File(directory,"missing")),"missing bounded-read file");
        File emptyDirectory=directory(root,"bounded-empty-directory");
        io(()->NoteFileSet.readBounded(emptyDirectory),"directory cannot be read as ciphertext");
        File nonemptyDirectory=directory(root,"bounded-nonempty-directory");
        File child=new File(nonemptyDirectory,"child");write(child,bytes(29,4));
        io(()->NoteFileSet.readBounded(nonemptyDirectory),"nonempty directory cannot be read as ciphertext");unchanged(child,bytes(29,4));
    }
    private static void pendingLayouts(Path root)throws Exception {
        byte[] content=bytes(97,51);
        for(int mask=0;mask<8;mask++){
            File directory=directory(root,"pending-layout-"+mask);NoteFileSet files=new NoteFileSet(directory,A);
            File[] family={files.base,files.pending,files.backup};
            for(int member=0;member<3;member++)if((mask&(1<<member))!=0)write(family[member],content);
            check(files.pendingOnly()==(mask==2),"isolated pending layout detection "+mask);
            if(mask==2){
                byte[] first=files.readPendingOnly();check(Arrays.equals(first,content),"isolated pending read returns exact bytes");
                first[0]^=1;
                check(Arrays.equals(files.readPendingOnly(),content),"isolated pending read does not reuse a caller-mutated buffer");
            }else {
                io(files::readPendingOnly,"base/backup or missing pending rejects read for layout "+mask);
                int[] calls={0};byte[] authorized=content.clone();
                io(()->files.removePendingOnly(authorized,()->calls[0]++),"unresolved layout rejects pending cleanup "+mask);
                check(calls[0]==0,"layout rejection happens before deletion authorization");
                check(Arrays.equals(authorized,content),"layout rejection preserves caller bytes");
            }
            for(int member=0;member<3;member++){
                if((mask&(1<<member))!=0)unchanged(family[member],content);
                else check(!Files.exists(family[member].toPath(),LinkOption.NOFOLLOW_LINKS),"pending checks do not create family files");
            }
        }
        for(int member=0;member<3;member++)for(boolean nonempty:new boolean[]{false,true}){
            File directory=directory(root,"pending-directory-"+member+"-"+nonempty);NoteFileSet files=new NoteFileSet(directory,A);
            File residue=new File[]{files.base,files.pending,files.backup}[member];Files.createDirectory(residue.toPath());
            File child=new File(residue,"retained-child");if(nonempty)write(child,content);
            if(member!=1){write(files.pending,content);check(!files.pendingOnly(),"companion directory prevents isolated pending layout");}
            io(files::readPendingOnly,"directory layout rejects pending read");
            int[] calls={0};byte[] authorized=content.clone();
            io(()->files.removePendingOnly(authorized,()->calls[0]++),"directory layout rejects pending cleanup");
            check(calls[0]==0,"directory rejection does not invoke gate");
            check(Files.isDirectory(residue.toPath(),LinkOption.NOFOLLOW_LINKS),"pending cleanup retains directory residue");
            if(nonempty)unchanged(child,content);
            if(member!=1)unchanged(files.pending,content);
            check(Arrays.equals(authorized,content),"directory rejection preserves caller bytes");
        }
        for(int length:new int[]{28,29,2*1024*1024,2*1024*1024+1}){
            File directory=directory(root,"pending-length-"+length);NoteFileSet files=new NoteFileSet(directory,A);
            byte[] value=bytes(length,73);write(files.pending,value);
            check(files.pendingOnly(),"length does not change pending-only file inventory");
            if(length==29||length==2*1024*1024)check(Arrays.equals(files.readPendingOnly(),value),"pending read accepts inclusive boundary "+length);
            else {
                io(files::readPendingOnly,"pending read rejects out-of-range length "+length);
                int[] calls={0};io(()->files.removePendingOnly(value,()->calls[0]++),"cleanup cannot authorize invalid-length pending data");
                check(calls[0]==0,"invalid length prevents gate invocation");
            }
            unchanged(files.pending,value);
        }
    }
    private static void pendingAuthorization(Path root)throws Exception {
        File directory=directory(root,"pending-authorization");NoteFileSet files=new NoteFileSet(directory,A);
        byte[] content=bytes(17003,91);write(files.pending,content);
        for(int offset:new int[]{0,8191,8192,content.length-1}){
            byte[] authorized=content.clone();authorized[offset]^=0x55;byte[] before=authorized.clone();int[] calls={0};
            io(()->files.removePendingOnly(authorized,()->calls[0]++),"different authorized byte prevents cleanup at offset "+offset);
            check(calls[0]==0,"mismatch prevents gate invocation");unchanged(files.pending,content);
            check(Arrays.equals(authorized,before),"mismatch preserves caller authorization bytes");
        }
        for(byte[] authorized:new byte[][]{null,new byte[0],Arrays.copyOf(content,content.length-1),Arrays.copyOf(content,content.length+1)}){
            byte[] before=authorized==null?null:authorized.clone();int[] calls={0};
            io(()->files.removePendingOnly(authorized,()->calls[0]++),"missing or wrong-length authorization prevents cleanup");
            check(calls[0]==0,"missing or wrong-length authorization prevents gate invocation");unchanged(files.pending,content);
            check(Arrays.equals(authorized,before),"length rejection preserves caller bytes");
        }
        byte[] authorized=files.readPendingOnly(),originalAuthorization=authorized.clone(),replacement=content.clone();replacement[30]^=1;
        write(files.pending,replacement);int[] calls={0};
        io(()->files.removePendingOnly(authorized,()->calls[0]++),"file replaced after authorization must be preserved");
        check(calls[0]==0,"replacement detected before gate");unchanged(files.pending,replacement);
        check(Arrays.equals(authorized,originalAuthorization),"stale authorization bytes remain unchanged");
    }
    private static void pendingDeletionGates(Path root)throws Exception {
        File directory=directory(root,"pending-gates");NoteFileSet files=new NoteFileSet(directory,A);
        byte[] content=bytes(4097,113);write(files.pending,content);
        for(IOException failure:new IOException[]{new IOException("injected persistence failure"),new InterruptedIOException("cancelled before deletion")}){
            byte[] authorized=content.clone();int[] calls={0};
            try{
                files.removePendingOnly(authorized,()->{calls[0]++;throw failure;});
                throw new AssertionError("failed gate allowed deletion");
            }catch(IOException actual){check(actual==failure,"gate IOException identity retained");}
            check(calls[0]==1,"checked failure invokes gate once");unchanged(files.pending,content);
            check(files.pendingOnly(),"checked failure leaves pending available for later maintenance");
            check(Arrays.equals(authorized,content),"checked gate failure preserves caller bytes");
        }
        CancellationException cancellation=new CancellationException("caller cancellation");byte[] authorized=content.clone();int[] calls={0};
        try{
            files.removePendingOnly(authorized,()->{calls[0]++;throw cancellation;});
            throw new AssertionError("runtime cancellation allowed deletion");
        }catch(CancellationException actual){check(actual==cancellation,"runtime cancellation propagates unchanged");}
        check(calls[0]==1,"runtime cancellation invokes gate once");unchanged(files.pending,content);
        check(Arrays.equals(authorized,content),"runtime cancellation preserves caller bytes");
        try{files.removePendingOnly(authorized,null);throw new AssertionError("null gate allowed deletion");}
        catch(NullPointerException expected){checks++;}
        unchanged(files.pending,content);check(Arrays.equals(authorized,content),"null gate rejection preserves caller bytes");
        // A failed or cancelled gate does not poison a later, explicitly authorized retry.
        files.removePendingOnly(authorized,()->{check(Files.exists(files.pending.toPath()),"file still exists at retry gate");});
        files.requireRemoved();checks++;check(Arrays.equals(authorized,content),"successful retry preserves caller bytes");
    }
    private static void pendingExactDeletion(Path root)throws Exception {
        File directory=directory(root,"pending-exact-deletion");NoteFileSet files=new NoteFileSet(directory,A),other=new NoteFileSet(directory,B);
        byte[] content=bytes(29,17),neighbor=bytes(43,23);write(files.pending,content);
        write(other.pending,neighbor);write(other.base,neighbor);write(other.backup,neighbor);
        File unrelated=new File(directory,A+".enc.new.other");write(unrelated,neighbor);
        File unrelatedDirectory=new File(directory,"keep-directory");Files.createDirectory(unrelatedDirectory.toPath());
        byte[] authorized=files.readPendingOnly(),before=authorized.clone();int[] calls={0};
        files.removePendingOnly(authorized,()->{
            calls[0]++;check(files.pendingOnly(),"successful gate runs before file deletion");unchanged(files.pending,content);
        });
        check(calls[0]==1,"successful deletion invokes gate exactly once");
        check(!Files.exists(files.pending.toPath(),LinkOption.NOFOLLOW_LINKS)&&!files.pendingOnly(),"exact pending member deleted");
        files.requireRemoved();checks++;
        check(Arrays.equals(authorized,before),"successful cleanup preserves caller authorization bytes");
        for(File preserved:new File[]{other.pending,other.base,other.backup,unrelated})unchanged(preserved,neighbor);
        check(Files.isDirectory(unrelatedDirectory.toPath()),"unrelated directory remains");
        io(()->files.removePendingOnly(authorized,()->calls[0]++),"duplicate deletion does not claim a second success");
        check(calls[0]==1,"missing pending fails before a second gate call");
    }
    private static void pendingSymlinks(Path root)throws Exception {
        File directory=directory(root,"pending-symlinks");NoteFileSet files=new NoteFileSet(directory,A);
        File target=new File(directory,"target");byte[] content=bytes(61,37);write(target,content);
        try{Files.createSymbolicLink(files.pending.toPath(),target.toPath());}
        catch(UnsupportedOperationException|IOException|SecurityException unavailable){
            skipped++;System.out.println("SKIP symbolic-link cases: host could not create fixture: "+unavailable);return;
        }
        check(Files.isSymbolicLink(files.pending.toPath()),"real symbolic-link fixture created");
        io(()->NoteFileSet.readBounded(files.pending),"bounded read refuses a symbolic link");
        io(files::readPendingOnly,"pending read refuses a symbolic link");
        int[] calls={0};io(()->files.removePendingOnly(content,()->calls[0]++),"cleanup refuses a symbolic-link pending member");
        check(calls[0]==0,"pending link rejection occurs before gate");
        check(Files.isSymbolicLink(files.pending.toPath()),"pending link remains after rejection");unchanged(target,content);
        Files.delete(target.toPath());check(files.pendingOnly(),"dangling pending link remains discoverable");
        io(files::readPendingOnly,"dangling pending link cannot be read");
        io(()->files.removePendingOnly(content,()->calls[0]++),"dangling pending link cannot be cleaned as a regular file");
        check(calls[0]==0&&Files.isSymbolicLink(files.pending.toPath()),"dangling link rejection leaves link intact and gate unused");
        Files.delete(files.pending.toPath());write(files.pending,content);write(target,content);
        for(File companion:new File[]{files.base,files.backup}){
            Files.createSymbolicLink(companion.toPath(),target.toPath());
            check(!files.pendingOnly(),"companion link prevents isolated pending classification");
            io(files::readPendingOnly,"companion link blocks pending read");
            io(()->files.removePendingOnly(content,()->calls[0]++),"companion link blocks cleanup");
            check(calls[0]==0&&Files.isSymbolicLink(companion.toPath()),"companion link remains and gate not called");
            unchanged(files.pending,content);unchanged(target,content);Files.delete(companion.toPath());
        }
    }
    private static void recoveryAdmission(Path root)throws Exception {
        for(int mask=0;mask<8;mask++){
            File directory=directory(root,"admission-"+mask);NoteFileSet files=new NoteFileSet(directory,A);
            File[] members={files.base,files.pending,files.backup};byte[] value=bytes(97,11);
            for(int i=0;i<3;i++)if((mask&(1<<i))!=0)write(members[i],value);
            if((mask&2)!=0)io(files::requireRecoverableRead,"pending file never disposed by a read");
            else{files.requireRecoverableRead();checks++;}
            check(files.empty()==(mask==0),"file-family empty status is exact");
            for(int i=0;i<3;i++)if((mask&(1<<i))!=0)unchanged(members[i],value);
            if(mask==5){
                byte[] different=bytes(97,19);write(files.backup,different);
                io(files::requireRecoverableRead,"distinct base and backup cannot overwrite each other");
                unchanged(files.base,value);unchanged(files.backup,different);
            }
        }
    }
    private static void markedFamilyDeletion(Path root)throws Exception {
        for(int selected=0;selected<3;selected++){
            File directory=directory(root,"marked-member-"+selected);NoteFileSet files=new NoteFileSet(directory,A);
            File[] members={files.base,files.pending,files.backup};byte[][] content={bytes(71,3),bytes(83,5),bytes(89,7)};
            for(int i=0;i<3;i++)write(members[i],content[i]);
            File target=members[selected];byte[] authorized=content[selected].clone(),before=authorized.clone();
            io(()->files.removeMarkedCopy(target,bytes(authorized.length,77),()->{}),"different ciphertext is not authorized");
            io(()->files.removeMarkedCopy(target,authorized,()->{throw new java.io.InterruptedIOException("cancelled");}),"final cancellation leaves all family files");
            for(int i=0;i<3;i++)unchanged(members[i],content[i]);
            files.removeMarkedCopy(target,authorized,()->{});checks++;
            check(!Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS),"only selected marked member is removed");
            check(Arrays.equals(authorized,before),"marked deletion preserves caller bytes");
            for(int i=0;i<3;i++)if(i!=selected)unchanged(members[i],content[i]);
            check(!files.empty(),"other copies remain discoverable");
            File unrelated=new File(directory,B+".enc");write(unrelated,bytes(97,9));
            invalid(()->files.removeMarkedCopy(unrelated,bytes(97,9),()->{}),"cannot authorize another family path");
            unchanged(unrelated,bytes(97,9));
        }
    }
    private static void isolationClassification(Path root)throws Exception {
        for(String suffix:new String[]{".new",".bak"}){
            File directory=directory(root,"isolation-valid-"+suffix.substring(1));NoteFileSet files=new NoteFileSet(directory,A);
            byte[] saved=bytes(70,1),unknown=bytes(81,2);write(files.base,saved);
            File companion=suffix.equals(".new")?files.pending:files.backup;write(companion,unknown);
            try{files.requireRecoverableRead();throw new AssertionError("unknown family admitted");}
            catch(NoteFileSet.UnresolvedCopy expected){checks++;}
            unchanged(files.base,saved);unchanged(companion,unknown);
        }
        // A recognizable .new cannot turn another unsafe member into a local-only error.
        for(int invalid=0;invalid<3;invalid++){
            File directory=directory(root,"isolation-invalid-"+invalid);NoteFileSet files=new NoteFileSet(directory,A);
            byte[] unknown=bytes(81,3);write(files.pending,unknown);
            if(invalid==0)Files.createDirectory(files.base.toPath());
            else if(invalid==1)write(files.backup,bytes(8,4));
            else write(files.pending,bytes(8,5));
            try{files.requireRecoverableRead();throw new AssertionError("unsafe family admitted");}
            catch(NoteFileSet.UnresolvedCopy wrong){throw new AssertionError("unsafe family misclassified as local",wrong);}
            catch(IOException expected){checks++;}
            if(invalid<2)unchanged(files.pending,unknown);
        }
        File missing=root.resolve("isolation-missing-root").toFile();
        try{new NoteFileSet(missing,A).requireRecoverableRead();throw new AssertionError("missing root admitted");}
        catch(NoteFileSet.UnresolvedCopy wrong){throw new AssertionError("root failure misclassified as local",wrong);}
        catch(IOException expected){checks++;}
    }
    private static void cleanup(Path ownedRoot)throws IOException {
        final Path root=ownedRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if(!root.equals(ownedRoot)||!root.getFileName().toString().startsWith("sidecar-note-files-"))throw new IOException("unsafe host fixture root");
        // Only this newly allocated temporary directory is traversed; links are never followed.
        Files.walkFileTree(root,new SimpleFileVisitor<Path>(){
            private void inside(Path value)throws IOException {
                if(!value.toAbsolutePath().normalize().startsWith(root))throw new IOException("cleanup escaped host fixture root");
            }
            @Override public FileVisitResult preVisitDirectory(Path directory,BasicFileAttributes attrs)throws IOException {
                inside(directory);if(!directory.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(root))throw new IOException("unsafe host fixture directory");return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException {inside(file);Files.delete(file);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path directory,IOException failure)throws IOException {
                if(failure!=null)throw failure;inside(directory);Files.delete(directory);return FileVisitResult.CONTINUE;
            }
        });
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("sidecar-note-files-").toRealPath();
        try{
            inventoryCombinations(root);inventoryValidation(root);residualDirectories(root);commitBytes(root);
            boundedReads(root);pendingLayouts(root);pendingAuthorization(root);pendingDeletionGates(root);pendingExactDeletion(root);pendingSymlinks(root);recoveryAdmission(root);markedFamilyDeletion(root);isolationClassification(root);
            System.out.println("PASS NoteFileSet: "+checks+" real host-file assertions; "+skipped+" skipped host capabilities; no Android AtomicFile/Keystore/device acceptance");
        }finally{cleanup(root);}
    }
}

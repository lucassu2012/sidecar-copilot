package local.sidecar.copilot;

import java.io.File;
import java.io.FileInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Checked operations for one AtomicFile family. Caller holds the meeting privacy lock. */
final class NoteFileSet {
    // Only a bounded, regular, safely named family may be isolated from other meetings.
    // All inventory/type/read failures remain ordinary I/O failures.
    static final class UnresolvedCopy extends IOException {
        UnresolvedCopy(){super("note copies require explicit disposition");}
    }
    final File base,pending,backup;
    NoteFileSet(File root,String id){
        Objects.requireNonNull(root,"meeting directory");
        if(id==null||!id.matches("[0-9a-f-]{36}"))throw new IllegalArgumentException("invalid meeting id");
        base=new File(root,id+".enc");pending=new File(root,id+".enc.new");backup=new File(root,id+".enc.bak");
    }
    static List<String> ids(File root,boolean includePending)throws IOException {
        if(root==null)throw new IOException("meeting directory unavailable");
        requireDirectory(root);
        File[] entries=root.listFiles();if(entries==null)throw new IOException("meeting directory inventory unavailable");
        TreeSet<String> ids=new TreeSet<>();
        for(File entry:entries){
            String name=entry.getName(),id;
            if(name.endsWith(".enc"))id=name.substring(0,name.length()-4);
            else if(name.endsWith(".enc.bak"))id=name.substring(0,name.length()-8);
            else if(includePending&&name.endsWith(".enc.new"))id=name.substring(0,name.length()-8);
            else continue;
            if(!id.matches("[0-9a-f-]{36}"))throw new IOException("invalid meeting file name");
            ids.add(id);
        }
        return new ArrayList<>(ids);
    }
    private static BasicFileAttributes attributes(File file)throws IOException {
        return Files.readAttributes(file.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
    }
    private static void requireDirectory(File root)throws IOException {
        if(!attributes(root).isDirectory())throw new IOException("meeting directory is not a regular directory");
    }
    private static boolean present(File file)throws IOException {
        try{attributes(file);return true;}catch(NoSuchFileException missing){return false;}
    }
    boolean empty()throws IOException {return !present(base)&&!present(pending)&&!present(backup);}
    /** No implicit AtomicFile disposal of an ambiguous pending or a distinct base/backup. */
    void requireRecoverableRead()throws IOException {
        requireDirectory(base.getParentFile());
        boolean hasPending=present(pending),hasBase=present(base),hasBackup=present(backup);
        if(hasPending)boundedAttributes(pending);
        if(hasBase)boundedAttributes(base);
        if(hasBackup)boundedAttributes(backup);
        if(hasPending)throw new UnresolvedCopy();
        if(hasBase&&hasBackup&&!Arrays.equals(readBounded(base),readBounded(backup)))
            throw new UnresolvedCopy();
    }
    private static BasicFileAttributes boundedAttributes(File file)throws IOException {
        BasicFileAttributes value=attributes(file);
        if(!value.isRegularFile()||value.size()<29||value.size()>2*1024*1024)throw new IOException("note ciphertext type or length is invalid");
        return value;
    }
    void requireReadResolved()throws IOException {
        if(!attributes(base).isRegularFile())throw new IOException("meeting base is not a regular file");
        if(present(pending)||present(backup))throw new IOException("meeting recovery companions unresolved");
    }
    void requireRemoved()throws IOException {
        if(present(base)||present(pending)||present(backup))throw new IOException("meeting file removal incomplete");
    }
    boolean pendingOnly()throws IOException {
        return !present(base)&&!present(backup)&&present(pending);
    }
    private void requirePendingOnly()throws IOException {
        if(!pendingOnly()||!attributes(pending).isRegularFile())throw new IOException("pending note layout is not an isolated regular file");
    }
    static byte[] readBounded(File file)throws IOException {
        BasicFileAttributes value=boundedAttributes(file);
        byte[] bytes=new byte[(int)value.size()];
        try(DataInputStream input=new DataInputStream(new FileInputStream(file))){
            input.readFully(bytes);if(input.read()!=-1)throw new IOException("note ciphertext grew while reading");
        }
        return bytes;
    }
    static byte[] readIfPresent(File file)throws IOException {
        return present(file)?readBounded(file):null;
    }
    byte[] readPendingOnly()throws IOException {
        requirePendingOnly();byte[] bytes=readBounded(pending);requirePendingOnly();return bytes;
    }
    interface DeletionGate {void check()throws IOException;}
    void removePendingOnly(byte[] authorized,DeletionGate gate)throws IOException {
        byte[] current=readPendingOnly();
        if(!Arrays.equals(current,authorized))throw new IOException("pending note ciphertext changed after authorization");
        Objects.requireNonNull(gate,"deletion gate").check();
        // Caller serializes managed writers. This is not an atomic defense against privileged file replacement.
        if(!pending.delete())throw new IOException("expired pending note file cleanup failed");
        requireRemoved();
    }
    /** One exact marked ciphertext only; other family members and all keys stay untouched. */
    void removeMarkedCopy(File member,byte[] authorized,DeletionGate gate)throws IOException {
        if(!base.equals(member)&&!pending.equals(member)&&!backup.equals(member))throw new IllegalArgumentException("foreign note family member");
        if(!Arrays.equals(readBounded(member),authorized))throw new IOException("marked note ciphertext changed after authorization");
        Objects.requireNonNull(gate,"deletion gate").check();
        if(!member.delete())throw new IOException("marked note copy cleanup failed");
        if(present(member))throw new IOException("marked note copy removal incomplete");
    }
    void verifyCommit(byte[] nonce,byte[] encrypted)throws IOException {
        requireReadResolved();Objects.requireNonNull(nonce,"nonce");Objects.requireNonNull(encrypted,"ciphertext");
        // Inspect the base directly: AtomicFile.openRead could recover a different old version.
        try(FileInputStream input=new FileInputStream(base)){
            byte[] buffer=new byte[8192];verifyPart(input,nonce,buffer);verifyPart(input,encrypted,buffer);
            if(input.read()!=-1)throw new IOException("committed meeting bytes have trailing data");
        }
    }
    private static void verifyPart(FileInputStream input,byte[] expected,byte[] buffer)throws IOException {
        for(int offset=0;offset<expected.length;){
            int count=input.read(buffer,0,Math.min(buffer.length,expected.length-offset));
            if(count<0)throw new IOException("committed meeting bytes are truncated");
            for(int i=0;i<count;i++)if(buffer[i]!=expected[offset+i])throw new IOException("committed meeting bytes differ");
            offset+=count;
        }
    }
}

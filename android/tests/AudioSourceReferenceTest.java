package local.sidecar.copilot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Pure value-contract regression; no storage, authentication, Android or sending authorization. */
public final class AudioSourceReferenceTest {
    private static final String FRAME="01234567-89ab-4cde-8fab-0123456789ab";
    private static final String SESSION="11234567-89ab-4cde-8fab-0123456789ab";
    private static final String STREAM="21234567-89ab-4cde-8fab-0123456789ab";
    private static final String OTHER="31234567-89ab-4cde-8fab-0123456789ab";
    private static final String HASH="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_HASH="1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final long DELAY=900_000_000_000L;
    private static int checks;

    private static final class Values {
        String frameId=FRAME,sessionId=SESSION,streamId=STREAM;
        long captureGeneration=3,bootCount=7,startSample=100,endSample=200;
        long hostReceiveEndNs=1234,eligibleMonoNs=DELAY+1234,expiresUtcMs=5000,ciphertextByteLength=216;
        String ciphertextSha256=HASH,aadSha256=OTHER_HASH,format="pcm16le-v1";
        AudioSourceReference create(){
            return new AudioSourceReference(frameId,sessionId,streamId,captureGeneration,bootCount,
                startSample,endSample,hostReceiveEndNs,eligibleMonoNs,expiresUtcMs,ciphertextByteLength,
                ciphertextSha256,aadSha256,format);
        }
    }
    private static void check(boolean condition,String message){
        if(!condition)throw new AssertionError(message);checks++;
    }
    private static void rejects(Class<? extends Throwable> expected,Runnable action,String message){
        try{action.run();}
        catch(Throwable failure){
            if(!expected.isInstance(failure))throw new AssertionError(message+" threw "+failure.getClass().getName(),failure);
            checks++;return;
        }
        throw new AssertionError(message+" was accepted");
    }
    private static void invalid(Consumer<Values> mutation,String message){
        Values values=new Values();mutation.accept(values);
        rejects(IllegalArgumentException.class,values::create,message);
    }
    private static void overflows(Consumer<Values> mutation,String message){
        Values values=new Values();mutation.accept(values);
        rejects(ArithmeticException.class,values::create,message);
    }

    private static void immutableShape()throws Exception{
        int classModifiers=AudioSourceReference.class.getModifiers();
        check(Modifier.isPublic(classModifiers)&&Modifier.isFinal(classModifiers),"reference type is public and final");
        Set<String> strings=new HashSet<>(Arrays.asList("frameId","sessionId","streamId","ciphertextSha256","aadSha256","format"));
        Set<String> longs=new HashSet<>(Arrays.asList("captureGeneration","bootCount","startSample","endSample",
            "hostReceiveEndNs","eligibleMonoNs","expiresUtcMs","ciphertextByteLength"));
        for(Field field:AudioSourceReference.class.getDeclaredFields()){
            int modifiers=field.getModifiers();String name=field.getName().toLowerCase(Locale.ROOT);
            check(Modifier.isFinal(modifiers),"all declared state is final: "+field.getName());
            check(field.getType()==String.class||field.getType()==long.class,"no mutable, plaintext or key-bearing field types");
            check(!name.contains("key")&&!name.contains("offset")&&!name.contains("plaintext")
                &&!name.contains("wire")&&!name.contains("sample_start_ns"),"no unauthorized evidence/storage fields");
            if(Modifier.isStatic(modifiers)){
                check(Modifier.isPrivate(modifiers),"constants do not expand the public contract");
            }else{
                check((modifiers&(Modifier.PUBLIC|Modifier.PROTECTED|Modifier.PRIVATE))==0,"reference fields are package-private");
                boolean expected=field.getType()==String.class?strings.remove(field.getName()):longs.remove(field.getName());
                check(expected,"only the specified fourteen instance fields exist");
            }
        }
        check(strings.isEmpty()&&longs.isEmpty(),"all specified evidence fields are present");
        Constructor<?>[] constructors=AudioSourceReference.class.getDeclaredConstructors();
        check(constructors.length==1,"one validated construction path");
        check((constructors[0].getModifiers()&(Modifier.PUBLIC|Modifier.PROTECTED|Modifier.PRIVATE))==0,"constructor is package-private");
        check(constructors[0].getParameterCount()==14,"constructor has the specified inputs");
    }

    private static void identityAndEquality(){
        AudioSourceReference a=new Values().create(),b=new Values().create(),c=new Values().create();
        check(a.equals(a)&&a.equals(b)&&b.equals(a)&&b.equals(c)&&a.equals(c),"value equality is reflexive, symmetric and transitive");
        check(!a.equals(null)&&!a.equals(HASH),"different types and null are unequal");
        check(a.hashCode()==b.hashCode()&&b.hashCode()==c.hashCode(),"equal references have equal hashes");
        Map<AudioSourceReference,String> map=new HashMap<>();map.put(a,"evidence");
        check("evidence".equals(map.get(b)),"an equal copy retrieves the same map entry");
        check(a.frameId.equals(FRAME)&&a.sessionId.equals(SESSION)&&a.streamId.equals(STREAM),"source identifiers preserved exactly");
        check(a.captureGeneration==3&&a.bootCount==7&&a.startSample==100&&a.endSample==200,"run and sample identity preserved exactly");
        check(a.hostReceiveEndNs==1234&&a.eligibleMonoNs==DELAY+1234&&a.expiresUtcMs==5000,"receipt, eligibility and UTC units remain separate");
        check(a.ciphertextByteLength==216&&a.ciphertextSha256.equals(HASH)&&a.aadSha256.equals(OTHER_HASH)&&a.format.equals("pcm16le-v1"),"integrity and format evidence preserved exactly");
        distinct(v->v.frameId=OTHER,"frame identity");
        distinct(v->v.sessionId=OTHER,"session identity");
        distinct(v->v.streamId=OTHER,"stream identity");
        distinct(v->v.captureGeneration++,"capture generation");
        distinct(v->v.bootCount++,"boot identity");
        distinct(v->{v.startSample++;v.endSample++;},"shifted range with equal ciphertext length");
        distinct(v->{v.startSample++;v.ciphertextByteLength-=2;},"changed range start and required length");
        distinct(v->{v.endSample++;v.ciphertextByteLength+=2;},"changed range end and required length");
        distinct(v->{v.hostReceiveEndNs++;v.eligibleMonoNs++;},"receipt and required eligibility");
        distinct(v->v.expiresUtcMs++,"expiry");
        distinct(v->v.ciphertextSha256=OTHER_HASH,"ciphertext hash");
        distinct(v->v.aadSha256=HASH,"AAD hash");
    }
    private static void distinct(Consumer<Values> mutation,String label){
        AudioSourceReference original=new Values().create();Values changed=new Values();mutation.accept(changed);
        AudioSourceReference other=changed.create();
        check(!original.equals(other)&&!other.equals(original),label+" must not alias");
        Set<AudioSourceReference> set=new HashSet<>();set.add(original);set.add(other);
        check(set.size()==2,label+" remains distinct in a set");
    }

    private static void constructorValidation(){
        String[] badUuids={null,"",FRAME.toUpperCase(Locale.ROOT)," "+FRAME,FRAME+" ","1-1-1-1-1",
            FRAME.replace("-",""),"g1234567-89ab-4cde-8fab-0123456789ab"};
        for(String value:badUuids){
            invalid(v->v.frameId=value,"invalid frame UUID");
            invalid(v->v.sessionId=value,"invalid session UUID");
            invalid(v->v.streamId=value,"invalid stream UUID");
        }
        String[] badHashes={null,"",HASH.toUpperCase(Locale.ROOT),HASH.substring(1),HASH+"0","g"+HASH.substring(1)," "+HASH};
        for(String value:badHashes){
            invalid(v->v.ciphertextSha256=value,"invalid ciphertext hash");
            invalid(v->v.aadSha256=value,"invalid AAD hash");
        }
        invalid(v->v.format=null,"missing format");
        invalid(v->v.format="PCM16LE-V1","case-changed format");
        invalid(v->v.format="pcm16le-v2","unsupported format");
        invalid(v->v.captureGeneration=0,"zero generation");
        invalid(v->v.captureGeneration=-1,"negative generation");
        invalid(v->v.bootCount=-1,"negative boot");
        invalid(v->v.bootCount=(long)Integer.MAX_VALUE+1,"boot outside Android int domain");
        invalid(v->v.startSample=-1,"negative range start");
        invalid(v->v.endSample=v.startSample,"empty sample range");
        invalid(v->v.endSample=v.startSample-1,"reversed sample range");
        invalid(v->v.hostReceiveEndNs=-1,"negative receipt time");
        invalid(v->v.eligibleMonoNs--,"eligibility cannot be earlier than 900 seconds");
        invalid(v->v.eligibleMonoNs++,"eligibility must preserve the exact 900-second boundary");
        invalid(v->v.expiresUtcMs=0,"zero expiry");
        invalid(v->v.expiresUtcMs=-1,"negative expiry");
        invalid(v->v.ciphertextByteLength--,"truncated ciphertext/tag length");
        invalid(v->v.ciphertextByteLength++,"excess ciphertext length");
        invalid(v->v.ciphertextByteLength=-1,"negative ciphertext length");
        Values lower=new Values();lower.bootCount=0;lower.captureGeneration=1;lower.startSample=0;lower.endSample=1;
        lower.ciphertextByteLength=18;lower.hostReceiveEndNs=0;lower.eligibleMonoNs=DELAY;lower.expiresUtcMs=1;
        check(lower.create().ciphertextByteLength==18,"minimum valid frame with 16-byte authentication tag");
        Values upper=new Values();upper.bootCount=Integer.MAX_VALUE;upper.captureGeneration=Long.MAX_VALUE;
        upper.startSample=Long.MAX_VALUE-1;upper.endSample=Long.MAX_VALUE;upper.ciphertextByteLength=18;
        upper.hostReceiveEndNs=Long.MAX_VALUE-DELAY;upper.eligibleMonoNs=Long.MAX_VALUE;upper.expiresUtcMs=Long.MAX_VALUE;
        check(upper.create().endSample==Long.MAX_VALUE,"valid upper boundaries remain accepted without narrowing or time-unit conversion");
    }

    private static void timingAndIntersection(){
        AudioSourceReference source=new Values().create();long eligible=source.eligibleMonoNs;
        source.requireMature(7,eligible,0);checks++;
        source.requireMature(7,eligible,4999);checks++;
        source.requireMature(7,Long.MAX_VALUE,4999);checks++;
        rejects(SecurityException.class,()->source.requireMature(7,eligible-1,0),"one nanosecond before eligibility");
        rejects(SecurityException.class,()->source.requireMature(7,-1,0),"negative monotonic time");
        rejects(SecurityException.class,()->source.requireMature(7,eligible,5000),"expiry is exclusive");
        rejects(SecurityException.class,()->source.requireMature(7,eligible,Long.MAX_VALUE),"expired reference");
        rejects(SecurityException.class,()->source.requireMature(7,eligible,-1),"negative UTC time");
        rejects(SecurityException.class,()->source.requireMature(8,Long.MAX_VALUE,0),"future uptime on another boot cannot mature old audio");
        rejects(SecurityException.class,()->source.requireMature(-1,eligible,0),"invalid current boot cannot match");
        check(source.intersects(SESSION,STREAM,7,100,200),"same interval intersects");
        check(source.intersects(SESSION,STREAM,7,100,101),"leftmost included sample intersects");
        check(source.intersects(SESSION,STREAM,7,199,200),"rightmost included sample intersects");
        check(source.intersects(SESSION,STREAM,7,0,Long.MAX_VALUE),"containing range intersects without endpoint arithmetic");
        check(!source.intersects(SESSION,STREAM,7,99,100),"left adjacent endpoint does not intersect");
        check(!source.intersects(SESSION,STREAM,7,200,201),"right adjacent endpoint does not intersect");
        check(!source.intersects(OTHER,STREAM,7,100,200),"other session never intersects");
        check(!source.intersects(SESSION,OTHER,7,100,200),"other stream never intersects");
        check(!source.intersects(SESSION,STREAM,8,100,200),"other boot never intersects");
        rejects(IllegalArgumentException.class,()->source.intersects(null,STREAM,7,100,200),"query needs a full session identity");
        rejects(IllegalArgumentException.class,()->source.intersects(SESSION,"1-1-1-1-1",7,100,200),"query rejects noncanonical stream");
        rejects(IllegalArgumentException.class,()->source.intersects(SESSION,STREAM,-1,100,200),"query rejects negative boot");
        rejects(IllegalArgumentException.class,()->source.intersects(SESSION,STREAM,(long)Integer.MAX_VALUE+1,100,200),"query rejects oversized boot");
        rejects(IllegalArgumentException.class,()->source.intersects(SESSION,STREAM,7,-1,100),"query rejects negative start");
        rejects(IllegalArgumentException.class,()->source.intersects(OTHER,STREAM,7,100,100),"mismatched identity cannot hide malformed empty range");
        rejects(IllegalArgumentException.class,()->source.intersects(SESSION,OTHER,7,200,100),"mismatched stream cannot hide reversed range");
    }

    private static void checkedArithmetic(){
        overflows(v->{v.hostReceiveEndNs=Long.MAX_VALUE-DELAY+1;v.eligibleMonoNs=Long.MAX_VALUE;},"900-second eligibility addition overflow");
        overflows(v->{v.startSample=0;v.endSample=Long.MAX_VALUE/2+1;v.ciphertextByteLength=Long.MAX_VALUE;},"PCM byte multiplication overflow");
        overflows(v->{v.startSample=0;v.endSample=(Long.MAX_VALUE-16)/2+1;v.ciphertextByteLength=Long.MAX_VALUE;},"authentication tag addition overflow");
        Values maximum=new Values();maximum.startSample=0;maximum.endSample=(Long.MAX_VALUE-16)/2;
        maximum.ciphertextByteLength=Long.MAX_VALUE-1;
        check(maximum.create().ciphertextByteLength==Long.MAX_VALUE-1,"largest representable even PCM-plus-tag length");
        check(AudioSourceReference.utcMillisToNanos(0)==0,"zero UTC milliseconds convert exactly");
        check(AudioSourceReference.utcMillisToNanos(1)==1_000_000L,"UTC milliseconds to nanoseconds unit scale");
        check(AudioSourceReference.utcMillisToNanos(Long.MAX_VALUE/1_000_000L)==9_223_372_036_854_000_000L,"maximum nonoverflowing UTC conversion");
        rejects(ArithmeticException.class,()->AudioSourceReference.utcMillisToNanos(Long.MAX_VALUE/1_000_000L+1),"UTC conversion overflow");
        rejects(ArithmeticException.class,()->AudioSourceReference.utcMillisToNanos(Long.MAX_VALUE),"extreme UTC conversion overflow");
        rejects(IllegalArgumentException.class,()->AudioSourceReference.utcMillisToNanos(-1),"negative UTC milliseconds");
        rejects(IllegalArgumentException.class,()->AudioSourceReference.utcMillisToNanos(Long.MIN_VALUE),"negative UTC extreme before conversion");
    }

    public static void main(String[] args)throws Exception{
        immutableShape();identityAndEquality();constructorValidation();timingAndIntersection();checkedArithmetic();
        System.out.println("PASS AudioSourceReferenceTest checks="+checks+"; immutable value contract only; no Android/storage/authentication/device acceptance");
    }
}

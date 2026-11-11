package local.sidecar.copilot;

/**
 * Immutable local PCM evidence identity. Construction validates its shape, not its
 * authenticity: the store must verify the complete ciphertext/AAD with AEAD and
 * recheck deletion, retention and read authorization before exposing this value.
 *
 * Sample positions are half-open offsets within one session/stream/Android boot.
 * hostReceiveEndNs is an elapsed-realtime host receipt, not a hardware capture
 * timestamp or a sample start. No start timestamp may be inferred from the
 * session's current start or from a guessed sample duration. This reference is
 * neither a wire manifest nor permission to transmit content.
 */
public final class AudioSourceReference {
    private static final long MATURITY_DELAY_NS=900_000_000_000L;
    private static final long NS_PER_UTC_MS=1_000_000L;
    private static final String PCM_FORMAT="pcm16le-v1";

    final String frameId,sessionId,streamId;
    final long captureGeneration,bootCount,startSample,endSample;
    final long hostReceiveEndNs,eligibleMonoNs,expiresUtcMs,ciphertextByteLength;
    final String ciphertextSha256,aadSha256,format;

    AudioSourceReference(String frameId,String sessionId,String streamId,
                         long captureGeneration,long bootCount,long startSample,long endSample,
                         long hostReceiveEndNs,long eligibleMonoNs,long expiresUtcMs,
                         long ciphertextByteLength,String ciphertextSha256,String aadSha256,String format){
        requireUuid(frameId);requireUuid(sessionId);requireUuid(streamId);
        requireBoot(bootCount);requireRange(startSample,endSample);
        if(captureGeneration<=0||hostReceiveEndNs<0||expiresUtcMs<=0)
            throw new IllegalArgumentException("invalid audio generation or time");
        if(eligibleMonoNs!=Math.addExact(hostReceiveEndNs,MATURITY_DELAY_NS))
            throw new IllegalArgumentException("audio maturity must preserve the 900-second boundary");
        // AES/GCM/NoPadding uses one 16-byte tag; PCM16LE contributes two bytes per sample.
        long pcmBytes=Math.multiplyExact(Math.subtractExact(endSample,startSample),2L);
        if(ciphertextByteLength!=Math.addExact(pcmBytes,16L))
            throw new IllegalArgumentException("ciphertext length does not match pcm16le-v1");
        requireSha256(ciphertextSha256);requireSha256(aadSha256);
        if(!PCM_FORMAT.equals(format))throw new IllegalArgumentException("unsupported audio format");
        this.frameId=frameId;this.sessionId=sessionId;this.streamId=streamId;
        this.captureGeneration=captureGeneration;this.bootCount=bootCount;
        this.startSample=startSample;this.endSample=endSample;
        this.hostReceiveEndNs=hostReceiveEndNs;this.eligibleMonoNs=eligibleMonoNs;
        this.expiresUtcMs=expiresUtcMs;this.ciphertextByteLength=ciphertextByteLength;
        this.ciphertextSha256=ciphertextSha256;this.aadSha256=aadSha256;this.format=format;
    }

    /**
     * Checks only time prerequisites. The caller must supply a validated trusted
     * UTC time and elapsed-realtime reading for currentBoot; this class cannot
     * establish clock trust. Success is not deletion, consent, unlock or sending
     * authorization and must not replace those checks at the point of use.
     */
    void requireMature(long currentBoot,long monotonicNowNs,long trustedUtcNowMs){
        if(currentBoot!=bootCount||monotonicNowNs<eligibleMonoNs
                ||trustedUtcNowMs<0||trustedUtcNowMs>=expiresUtcMs)
            throw new SecurityException("audio time prerequisites are not satisfied");
    }

    /** Samples from a different session, stream or Android boot never intersect. */
    boolean intersects(String sessionId,String streamId,long bootCount,long startSample,long endSample){
        requireUuid(sessionId);requireUuid(streamId);requireBoot(bootCount);requireRange(startSample,endSample);
        return this.sessionId.equals(sessionId)&&this.streamId.equals(streamId)&&this.bootCount==bootCount
            &&this.startSample<endSample&&this.endSample>startSample;
    }

    /** Unit conversion only; it creates no monotonic/UTC mapping or capture timestamp. */
    static long utcMillisToNanos(long utcMillis){
        if(utcMillis<0)throw new IllegalArgumentException("negative UTC milliseconds");
        return Math.multiplyExact(utcMillis,NS_PER_UTC_MS);
    }

    private static void requireUuid(String value){
        if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("canonical lowercase UUID required");
    }
    private static void requireSha256(String value){
        if(value==null||!value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("canonical lowercase SHA-256 required");
    }
    private static void requireBoot(long boot){
        if(boot<0||boot>Integer.MAX_VALUE)throw new IllegalArgumentException("invalid Android boot count");
    }
    private static void requireRange(long start,long end){
        if(start<0||end<=start)throw new IllegalArgumentException("invalid half-open sample range");
    }

    @Override public boolean equals(Object other){
        if(this==other)return true;
        if(!(other instanceof AudioSourceReference))return false;
        AudioSourceReference value=(AudioSourceReference)other;
        return frameId.equals(value.frameId)&&sessionId.equals(value.sessionId)&&streamId.equals(value.streamId)
            &&captureGeneration==value.captureGeneration&&bootCount==value.bootCount
            &&startSample==value.startSample&&endSample==value.endSample
            &&hostReceiveEndNs==value.hostReceiveEndNs&&eligibleMonoNs==value.eligibleMonoNs
            &&expiresUtcMs==value.expiresUtcMs&&ciphertextByteLength==value.ciphertextByteLength
            &&ciphertextSha256.equals(value.ciphertextSha256)&&aadSha256.equals(value.aadSha256)
            &&format.equals(value.format);
    }
    @Override public int hashCode(){
        int result=frameId.hashCode();result=31*result+sessionId.hashCode();result=31*result+streamId.hashCode();
        result=31*result+Long.hashCode(captureGeneration);result=31*result+Long.hashCode(bootCount);
        result=31*result+Long.hashCode(startSample);result=31*result+Long.hashCode(endSample);
        result=31*result+Long.hashCode(hostReceiveEndNs);result=31*result+Long.hashCode(eligibleMonoNs);
        result=31*result+Long.hashCode(expiresUtcMs);result=31*result+Long.hashCode(ciphertextByteLength);
        result=31*result+ciphertextSha256.hashCode();result=31*result+aadSha256.hashCode();
        return 31*result+format.hashCode();
    }
}

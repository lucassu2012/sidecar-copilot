package local.sidecar.copilot.uvcsim;

import java.util.Objects;

/** Synthetic driver boundary. Implementations in this slice must never open physical devices. */
public interface UvcDriver {
    interface Handle {}
    interface Callback {
        /** Transfers exclusive ownership of pixels, including on rejection. */
        void onFrame(Handle handle, Frame frame);
        void onFailure(Handle handle, String reason);
    }
    final class Format {
        public final int width, height, fpsNumerator, fpsDenominator;
        public final String encoding;
        public Format(int width,int height,String encoding,int fpsNumerator,int fpsDenominator) {
            if(width<=0 || height<=0 || fpsNumerator<=0 || fpsDenominator<=0)
                throw new IllegalArgumentException("invalid format");
            this.width=width; this.height=height; this.encoding=Objects.requireNonNull(encoding);
            this.fpsNumerator=fpsNumerator; this.fpsDenominator=fpsDenominator;
        }
        @Override public boolean equals(Object other) {
            if(!(other instanceof Format))return false;
            Format f=(Format)other;
            return width==f.width && height==f.height && fpsNumerator==f.fpsNumerator
                && fpsDenominator==f.fpsDenominator && encoding.equals(f.encoding);
        }
        @Override public int hashCode(){return Objects.hash(width,height,encoding,fpsNumerator,fpsDenominator);}
    }
    final class Frame {
        public final Format format;
        public final String bootId;
        public final long sequence, hostReceiveMonoNs;
        public final byte[] pixels;
        public Frame(Format format,String bootId,long sequence,long hostReceiveMonoNs,byte[] pixels) {
            this.format=Objects.requireNonNull(format); this.bootId=Objects.requireNonNull(bootId);
            this.sequence=sequence; this.hostReceiveMonoNs=hostReceiveMonoNs;
            this.pixels=Objects.requireNonNull(pixels);
        }
    }
    /** Returns exactly one newly owned handle; may block. On failure must not leak an unreturned handle. */
    Handle open(String attachmentId, Format requested)throws Exception;
    Format negotiatedFormat(Handle handle)throws Exception;
    /** Synchronous start completion; callbacks may occur on any thread or arrive late. */
    void start(Handle handle, Callback callback)throws Exception;
    void stop(Handle handle)throws Exception;
    /** Success guarantees release and no ongoing acquisition; callbacks already queued may still arrive. */
    void close(Handle handle)throws Exception;
}

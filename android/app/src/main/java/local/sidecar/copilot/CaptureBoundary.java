package local.sidecar.copilot;

/** Immutable host-side stop boundary; unknown crash coverage is stored separately, never fabricated. */
public final class CaptureBoundary {
    public final String eventId,kind,stream;
    public final long generation,boot,requestedNs,acceptedNs,acceptedEnd,observedEnd;
    public CaptureBoundary(String eventId,String kind,String stream,long generation,long boot,
                           long requestedNs,long acceptedNs,long acceptedEnd,long observedEnd) {
        if(eventId==null||kind==null||stream==null||eventId.isEmpty()||kind.isEmpty()||stream.isEmpty()
            ||generation<0||boot<0||requestedNs<0||acceptedNs<requestedNs||acceptedEnd<0||observedEnd<acceptedEnd)
            throw new IllegalArgumentException("invalid capture boundary");
        this.eventId=eventId;this.kind=kind;this.stream=stream;this.generation=generation;this.boot=boot;
        this.requestedNs=requestedNs;this.acceptedNs=acceptedNs;this.acceptedEnd=acceptedEnd;this.observedEnd=observedEnd;
    }
}

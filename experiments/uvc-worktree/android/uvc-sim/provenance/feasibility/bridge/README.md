# Pure JVM pixel-ownership bridge experiment

This experiment compiles project-owned Java against the unchanged synthetic `FramePipeline`. It does not compile or execute libyuv, libuvc, libusb, TurboJPEG, JNI or Android capture code. The small bridge is research code confined to this directory and is not connected to an application or driver.

Its deliberately narrow input is **640×360 opaque desktop RGBX bytes** with an explicit positive row stride. It copies RGB without scaling, omits row padding and sets alpha to 255; it does not interpret X as opacity. It transfers an independently owned tight RGBA array to the existing sink. Different input semantics are outside this contract; nonfixture dimensions and malformed row capacity are refused. This is a proposed fixture contract, not an added public interface or evidence that a real decoder meets it.

## Discriminating observations

| Synthetic experiment | What the recorded execution establishes | What it does not establish |
| --- | --- | --- |
| Padded RGBX source, varying X, missing final-row padding, distinct one-pixel color marker | RGB byte order, opaque alpha and the small marker survive copying; sentinel padding is excluded. The borrowed array is overwritten while the processing worker is deliberately held, and the owned result remains valid. The privacy rectangle becomes opaque black while the outside marker is retained. | Correctness of a native decoder, actual JNI buffer length/lifetime or optical text readability. The ownership guarantee comes from this Java copy, not a tested native acquisition. |
| A copied older frame submitted after a newer preview, followed by a callback after invalidation | Existing final publication ordering rejects the older frame, preserves the current preview hash and wipes rejected transferred buffers. Invalidation makes the preview unavailable; a late frame is wiped without publishing. | Full native callback ordering, source sequence generation, exposure timestamps or the coordinator/driver stop protocol. The experiment enters at the sink boundary. |
| Worker held immediately before publication, then lease invalidation/cancel/close | Preview is inaccessible while the worker is held; `close()` alone does not demonstrate worker exit. Explicit drain completes after the test releases the worker; the in-flight frame never publishes and its owned bytes are cleared. | Device release, DMA completion, GPU buffers or native threads. The result reinforces the need to define a combined acquisition/application stop barrier. |
| Bad source capacity/stride and a full 1920×1080 RGBA array | The bridge refuses bad layouts and nonfixture dimensions. The existing pipeline refuses and clears the Full HD frame and invalidates the affected lease; there is no silent resize. | Full HD support or a production memory limit. The rejected array is deliberately allocated only by this isolated experiment. |

The recorded run has **4 descriptive scenarios and 42 checks**, plus a negative control that deliberately fails the held worker and must exit nonzero. These names and counts are research results, not new PRD acceptance identifiers. Each scenario explicitly closes and drains its worker before checking that transferred arrays are zeroed and the fixture's owned-byte counter is zero. Hook timeouts and unexpected asynchronous failures are retained and checked after drain; the runner rejects unexpected JVM stderr. The negative control prevents an exceptional worker death from being accepted as successful drain. The older-frame check waits for release completion, not just the earlier drop counter update. The pipeline counter covers its own arrays only; the borrowed source, decoder, USB transport, JNI, Surface, conversion scratch and other application allocations are outside it.

The unchanged fixture accepts exactly 921,600 bytes (640×360×4), with a 7 MiB owned-byte limit. One 1080p RGBA frame is 8,294,400 bytes, larger than that fixture limit. Neither changing a pixel library nor silently reducing the image resolves this production contract gap. Main integration must separately define real negotiated formats, ownership, total memory and quality constraints.

## Reproduce

From the worktree, using an already installed JDK and Python standard library:

```powershell
python -B android/uvc-sim/provenance/feasibility/bridge/run_experiment.py
```

The default JDK location is the existing `E:/tools/jdk-17.0.19+10`; `--jdk <path>` selects another installed JDK. No download or installation occurs. The runner checks each existing Java source against Git baseline `c1afb3f61fa309bde0437fa2019c352ddc1792ba`, allowing only CRLF/LF checkout conversion, and records both working-byte and Git-byte SHA-256. It compiles for Java 8 into ignored `android/uvc-sim/build/feasibility/bridge/<unique-id>/`, then runs only `PixelBridgeExperiment`. Annotation processing and implicit source compilation are disabled; javac classpath/sourcepath are explicitly empty. Child processes omit `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`, `JDK_JAVAC_OPTIONS` and `CLASSPATH` to prevent ambient Java options or processor paths from changing the experiment.

Ordinary execution requires and compares [results.json](results.json). A different JDK version, checkout line endings or experimental code will cause the recorded result comparison to differ and needs explicit review; only `--write-report` captures new results. Build paths are normalized in the report. There is no timing benchmark or speed claim; short waits are synchronization/test deadlines, not latency measurements.

The existing runtime source, frame format, budget, mask configuration and state machine remain unchanged. Real acquisition, native dependency adoption and distribution remain closed.

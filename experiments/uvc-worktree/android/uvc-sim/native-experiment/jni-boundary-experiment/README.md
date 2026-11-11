# JNI/native boundary experiment

This isolated work continues commit `62eb3485a056c91720609f38ba436122bfdcb420`.
It prepares a reviewable host JNI boundary and exercises a small, original
upstream C translation unit. It is not a working Android UVC driver.

The delta stays entirely in this directory. The public contract, Java adapter,
USB owner, Android receiver, application and main worktree are unchanged. The
nine delivered public-contract snapshot files still matched the live main
delivery before this work. No driver binary, APK, device, emulator or USB I/O is
used. Generated Windows executables and DLLs remain in ignored `build/`.

## Two independent experiments

1. The host JNI fixture implements the methods of the inherited `BackendPort`
   so the actual `IsolatedCaptureSession`, `UsbConnectionOwner` and `StopBarrier`
   can be exercised together. That inherited port was explicitly a controlled
   Java experiment interface; this addition does not grant production native
   integration permission. The DLL is explicitly loaded by the test main, never
   by the Android application or its permission receiver. Native threads,
   JNI references, callback stacks and transfer buffers are real; USB operations,
   permission, numerical FD values and device traffic are controlled substitutes.
   There is no JNI image transport, decoder, masking or preview path in this work.
2. The C probe compiles the unchanged `libuvc` control-generator output against
   the actual pinned public `libuvc` and `libusb` headers. Clearly identified
   fixtures replace internal structure layouts, configuration and POSIX time
   declarations. An original `libusb_control_transfer` test function receives the
   requests. No `libusb` or `libuvc` runtime is linked. A separate compile-only
   object checks seven public API signatures and is deliberately not linked.

These experiments are separate binaries and separate claims. The JNI fixture
does not call the C probe or any upstream USB implementation. The probe cannot
validate real internal ABI, event pumping, cancellation or Android FD borrowing.

## Reproduce

Run from this directory on the inspected Windows x64 host:

```powershell
# Only necessary on a clean checkout without the ignored source-text cache:
python -B source-evidence/verify_sources.py --fetch
# Default verification is offline; the build runner never fetches or installs:
python -B run_checks.py
```

`inputs-lock.json` pins existing JDK 17.0.19+10, MSVC 14.50.35717 tools and nine
inherited inputs. A different toolchain requires a separate reviewed lock update.
`source-evidence/inputs.json` fixes official source URLs, commits, byte counts,
SHA-256 and Git blob identities. Source restoration accepts only those text
inputs; no runtime binary is downloaded. The source verifier checks literal
anchors as well as hashes. See [SOURCE_AND_GAPS.md](SOURCE_AND_GAPS.md) for the
license evidence, FD/cancel/timeout contract and remaining native work.

The runner makes a unique source snapshot before compiling, uses `javac -h` for
the JNI declarations, builds a host DLL, checks its imports and runs the Java
tests with `-Xcheck:jni`. Production and test Java classes compile separately;
test classes cannot shadow a production class. The native fixture and original
test C use warnings as errors. The unchanged upstream C retains its compiler
warnings in the receipt. All commands, logs, source/tool hashes and artifact
hashes remain in that run directory. A harness process deadline is a failure,
never a claim that native resources were drained. ASan, TSan and LeakSanitizer
are not part of this experiment.

`validation.json` is the retained result of the completed unified run; its
referenced ignored run directory contains the full command logs and artifacts.
The host contract's older test counts are inherited evidence only, not rerun or
added to this work's totals.

## Integration boundary

An Android implementation still needs an audited, reproducible NDK build and
real libusb/libuvc integration. The inspected SDK has no `ndk`, `ndk-bundle` or
`cmake` directory, and no NDK is used by this runner. MSVC/JDK host results do not
establish Android ABI, ART, native library loading or device compatibility.

Before any real driver gate can open, the implementation must provide all of:

- An Android authorized FD path with no discovery, identity bound to the live
  owner/borrow, and no raw FD close by the JNI/USB wrapper. A numerical FD match
  does not authorize use. The host fixture never opens or closes an OS FD.
- Proven cancellation and terminal callbacks for image, status and control
  transfers, while keeping callback and call stacks alive through their real
  return. `NOT_FOUND`, a Java callback lease closing, or a timeout notification
  does not satisfy this requirement.
- Bounded control requests with exact transfer-length checks, plus a policy for
  work that has not actually returned. The original control code's zero timeout
  and short-transfer behavior are recorded observations, not fixed by this work.
- A reviewed repair and build/test evidence for the previously identified
  libuvc status-transfer lifetime gap. The public close/stop declarations and a
  synthetic JNI worker cannot establish upstream status callback drain.
- A selected native source/build dependency closure, preserved license notices,
  Android ABI/packaging checks, and subsequent authorized device validation.

Resource counters in the host fixture cover its explicitly owned allocations
and references. They do not constitute a general leak detector or a proof of all
thread interleavings. The previous 4000-node exploration ceiling is not used as
a native correctness claim. No frozen PRD function, product state or acceptance
test identifier is added here.

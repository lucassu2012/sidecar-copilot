# Synthetic pixel contract feasibility

The smallest candidate supported by this experiment is an explicitly bounded copy
from RGBX bytes to tightly packed, owned RGBA bytes, setting alpha to 255. A separate
RGBA input contract preserves the supplied alpha. RGB channels are unchanged in
both cases. These are project-owned Python models, not an implemented camera bridge.

The run passed **48 numerical/contract checks on 10 hashed synthetic patterns**.
**10 harness integrity tests** also passed, including deliberately wrong channel,
alpha and YUY2-rounding implementations. Two distinct inputs collide after RGB565
or YUY2 conversion, so equality after those conversions cannot prove that original
pixels or small text changes were unchanged. This is a counterexample about bytes,
not a text-recognition or readability measurement.

Baseline: `c1afb3f61fa309bde0437fa2019c352ddc1792ba`. Only this research directory and
its ignored build cache were written. No production source, native build, upstream
code, device, dependency installation, PRD acceptance identifier, or git history
was changed by this work. The parent task separately examines the existing Java
pipeline; this experiment does not duplicate its stop/lease/mask lifecycle tests.

## Reproduce

From the repository root, on a fresh machine with Python standard library only:

```powershell
python -B android/uvc-sim/provenance/feasibility/synthetic/pixel_contracts.py --fetch
python -B android/uvc-sim/provenance/feasibility/synthetic/pixel_contracts.py
python -B -m unittest discover -s android/uvc-sim/provenance/feasibility/synthetic -p test_replay.py -v
```

`--fetch` downloads only missing, locked static files into
`android/uvc-sim/build/feasibility/synthetic`. Without it, replay is offline.
Ordinary replay compares the complete recomputed JSON byte-for-byte with
[`results.json`](results.json); it never updates the baseline. `--output <path>`
can additionally save a verified replay. `--record` is an explicit research
baseline-replacement operation, not needed for normal verification.

Existing evidence caches can instead be consumed read-only:

```powershell
python -B android/uvc-sim/provenance/feasibility/synthetic/pixel_contracts.py --existing-cache C:/Users/USER/Documents/Codex/2026-11-10/task-4/sidecar-uvc-licenses/android/uvc-sim/build/provenance/libyuv --existing-cache C:/Users/USER/Documents/Codex/2026-11-10/task-4/sidecar-uvc-native-research/android/uvc-sim/build/native-research/video-alternatives
```

Actual runs completed both the old-cache route and an empty-cache network
bootstrap under `build/feasibility/synthetic/fresh-files`, followed by offline
replay. Both produced the same report. Missing offline input, modified content,
and a corrupted preferred cache despite a valid fallback fail without replacing
evidence. The harness checks these failure paths using isolated temporary files.

An initial fresh Gitiles tar download did not match the old compressed-archive
hash and was correctly rejected. The current research lock therefore binds the
**exact individual source bytes**, retrieved via fixed-commit Gitiles `?format=TEXT`
and base64-decoded before SHA-256/length verification. The original locked archive
can still seed those identical files read-only. Each network fetch records the
observed raw transport SHA and decoded content SHA in an ignored receipt; no
archive hash was relaxed and no volatile JSON metadata is required.

## Fixed evidence and formulas

[`inputs.lock.json`](inputs.lock.json) binds five remote content inputs and four
local baseline files. The four official libyuv file hashes were obtained from the
previously locked official archive, and independently reconstructed through the
official static file URLs. The local inputs are FramePipeline.java and the prior
video-alternatives lock, evidence JSON and report. Reading those sources does not
execute or compile them. This research does not close the wider dependency or
distribution-license gate.

| Evidence | What is modeled |
| --- | --- |
| Official libyuv `3e38ce50589d9319badc0501f96d6c5b2b177472`, [formats.md:137-165](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/docs/formats.md#137) and [convert_argb.cc:2755-2756,2790-2800](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/source/convert_argb.cc#2755) | Register-order ABGR means memory bytes R,G,B,A on the little-endian target. ARGB means B,G,R,A. The scalar composition exchanges R/B and retains G/A. It does not use libyuv's differently named RGBA format. |
| [row_common.cc:305-324](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/source/row_common.cc#305) | RGB565 without dithering: R5/G6/B5 truncation, then explicitly little-endian output. Alpha is ignored. |
| [row_common.cc:33-49,531-661](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/source/row_common.cc#531) | Named scalar coefficient branches below. No CPU dispatch or compiled platform equivalence is claimed. |
| [convert_from_argb.cc:884-914,1026-1040](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/source/convert_from_argb.cc#884) and [row_common.cc:3577-3598](https://chromium.googlesource.com/libyuv/libyuv/+/3e38ce50589d9319badc0501f96d6c5b2b177472/source/row_common.cc#3577) | YUY2 shares horizontal chroma: Y0,U,Y1,V. UV conversion receives source stride zero, so it does not average adjacent image rows. Odd-width final packing writes a complete 4-byte group; tight whole-image strides can coalesce rows. This candidate explicitly rejects odd YUY2 width. |
| Official libjpeg-turbo `ecf021bc0d6f435daacff7c35ccaeef0145df1b9`, [turbojpeg.h:180-185,214-220](https://github.com/libjpeg-turbo/libjpeg-turbo/blob/ecf021bc0d6f435daacff7c35ccaeef0145df1b9/turbojpeg.h#L180) | RGBX has unspecified X on decompression; RGBA guarantees opaque alpha for that decoder output. This model does not run the decoder or assume any incoming camera buffer was produced with that setting. |
| [FramePipeline.java](../../../src/local/sidecar/copilot/uvcsim/FramePipeline.java):15-18,84-87,165-178 | Existing fixture has tight 640x360 RGBA, exactly 921,600 bytes, 7 MiB owned accounting, RGBA mask indexing and preview-byte packing. Synthetic small dimensions below are research inputs, not runtime dimension support. |

The default modeled 8-bit Y equation is
`floor((66R + 129G + 25B + 4224) / 256)`.
For a horizontal pair, the `sum_round` UV branch uses:

```text
U = floor((56(B0+B1) - 37(G0+G1) - 19(R0+R1) + 32896) / 256)
V = floor((56(R0+R1) - 47(G0+G1) -  9(B0+B1) + 32896) / 256)
```

The second modeled branch, `pavgb_truncate`, first averages each pair of RGB
components upward, then applies coefficients 112/-74/-38 for U and
112/-94/-18 for V with bias 32768. Both models use 8-bit Y. The historical special
non-clang MSVC/x86 `RGB7` Y branch is excluded: its white Y can be 237 rather than
235. `LIBYUV_BIT_EXACT`, compiler, architecture, CPU flags and selected row
implementations must be recorded before any future native comparison. The
`LIBYUV_UNLIMITED_DATA`/BT601 blue-coefficient switches concern YUV-to-RGB and do
not change the RGB-to-YUV equations modeled here.

All RGB values in the scalar conversion study are straight channel values.
Preserving RGBA alpha is not a claim about blending or premultiplication; these
conversions do not undo premultiplied RGB.

## Numerical observations and discriminating oracles

Named-color packed RGB565 expectations are hardcoded independently of the
conversion loop: black `0000`, white `ffff`, red `00f8`, green `e007`, blue `1f00`,
yellow `e0ff`, cyan `ff07`, magenta `1ff8` (hex bytes, low byte first). The odd-width
green/blue/red pattern ends in `00f8`, which catches an R/B swap in the final pixel.

| Synthetic condition | Measured model result |
| --- | --- |
| RGBX color bars with X values 0,31,62,93,124,155,186,217 | RGB unchanged; every output alpha becomes 255. Blind copying X fails the opaque oracle. |
| 3x2 RGBA, stride 14, padding `de ad`, no final-row padding | Exactly 26 readable source bytes become 24 tight destination bytes. Alpha 0,255,127,129,66,3 is preserved. A tight-source-stride assumption produces different bytes. |
| Borrowed source overwritten with `a5` after a successful copy, then expired | Owned output stays byte-identical. Reads/copies of the expired model are refused. Expiry injected during copying rolls back the budget reservation. |
| Width/height/stride/last-row/capacity/budget boundaries | Zero or negative dimensions, too-short stride/source/destination/budget, and signed-32-bit multiplication/addition overflow are refused before payload allocation/read. Exact final-row span succeeds. This is a modeled conservative bridge bound, not C/C++ integer-overflow testing. |
| RGB565 gray ramp 0..255 | 64 distinct gray codes. With discarded bits reconstructed as zero, max per-channel discarded amounts are R=7,G=3,B=7; summed amounts are 896,384,896. These are bit losses, not display/perceptual error. |
| A single channel of the middle pixel changes R=64 to R=65 | Tight RGBA differs; RGB565 results are identical. No resize, text detector or candidate-rate algorithm was involved. |
| Eight solid colors in `sum_round` | Y,U,V: black 16,128,128; white 235,128,128; red 82,90,240; green 144,54,34; blue 41,240,110; yellow 210,16,146; cyan 169,166,16; magenta 107,202,222. |
| Red/blue horizontal pair | YUY2 `[82,165,41,175]`; each pixel retains its own Y but shares U/V. Six distinct rows validate horizontal-only sampling. |
| Red/white pair | `sum_round` ends with V=184; `pavgb_truncate` ends with V=183. No cross-branch bit-exact assertion is made. |
| Black / RGB(0,0,2) pair | Correct shared U=128; independently quantizing U then averaging upward gives the detectably wrong value 129. |
| Red RGB(255,0,0) / green RGB(0,130,0), then reversed | Both have quantized Y=82 under this equation, and both orderings yield `52 5a 52 a0`. Their original RGBA bytes differ. This is a demonstrated loss of per-pixel chroma order, not a statement that physical luminance is identical. |
| Neutral Y=0,16,128,235,255; U=V=128 | Analytic full-range gray is 0,16,128,235,255; ideal limited-range interpretation gives 0,0,130,255,255. This is an explicitly separate range prediction, not output measured from libuvc, libyuv, JPEG or hardware. |

[`results.json`](results.json) stores every actual/expected pair plus each input's
SHA-256, size and short-pattern hex. Larger gray-ramp bytes are deterministically
constructed in the script and locked by hash. Negative controls and injected
wrong implementations make the checks distinguish the proposed contract from
plausible mistakes; passing does not prove all native paths share the model.

## Payload budget, not process memory or performance

Let P=W*H. A single RGBA payload is 4P; its borrowed source plus one owned copy is
8P while both live. Existing fixture `OWNED_BYTE_LIMIT=7,340,032` can account for
seven 640x360 payload reservations (6,451,200 bytes); the eighth would exceed it
by 32,768 bytes. This arithmetic does **not** imply the pipeline permits seven
queued frames. No full-size frames were allocated for these budget calculations.

| Dimensions | One RGBA payload | Borrowed + owned RGBA | Fits one payload in fixture 7 MiB? |
| --- | ---: | ---: | --- |
| 640x360 | 921,600 B | 1,843,200 B | Yes |
| 1920x1080 | 8,294,400 B | 16,588,800 B | No; short by 954,368 B |
| 3840x2160 | 33,177,600 B | 66,355,200 B | No |

For optional reverse conversion at 1920x1080, one RGBA input plus YUY2 output and
row scratch totals 12,453,120 payload bytes; scratch is `4W+2*roundUp64(W)` =
11,520 bytes. A full-frame ARGB temporary and the official tight-stride
coalesced scratch would instead total `10P+2*roundUp64(P)` = 24,883,200 bytes.
These expressions each count **one** RGBA input. If borrowed and owned RGBA
copies both remain alive, add another 4P. The model does not execute the official
allocator or measure alignment/allocation overhead.

The adapter's owned allocation would need to join the real end-to-end budget;
passing it to a pipeline that copies again cannot make that additional copy free.
All figures exclude Python objects, C/JVM runtime overhead, compressed frames,
decoder internals, USB buffers, frame pools, JNI, surface/HAL buffers and other
pipeline copies. `Owned.release()` clears this model's payload and returns its
reservation once; a Python bytearray can remain referenced, so this is not an
RSS, secure-erasure or physical-free measurement. No throughput, FPS, CPU, heat,
latency, or 1080p capture feasibility result follows from these calculations.

## Future comparison with official implementations

After the existing source/license admission conditions are met, an isolated native
test stage can build the exact
official source under recorded compiler/ABI/flags and select or record C and SIMD
dispatch explicitly, using the saved source-pattern bytes as external inputs. This
stage was not performed in the current research.
Compare actual output byte arrays against the appropriate coefficient-branch
oracle, not a blanket cross-platform expectation. Include even widths around
SIMD boundaries (2,14,16,18,30,32,34), different strides, unaligned addresses,
canaries beyond each allocation and row, and supported scalar tails. Odd YUY2
width remains rejected by this candidate until an explicit capacity/row contract
is chosen and checked. Probe RGB565 odd tails separately. Validate straight vs
premultiplied input and actual decoder RGBX/RGBA alpha semantics.

The real bridge must separately prove callback lifetime, ownership handoff,
allocation accounting, concurrent cancellation, stale callbacks and mask-before-
publication ordering. Borrowed lifetime in this Python experiment is deterministic
and single-threaded. Physical capture range/matrix, timestamp readability and
micro-digit changes require appropriate later evidence. Candidate sampling at
1-5 fps is not HDMI capture rate; this research neither implements sampling nor
changes the sealed PRD processing order.

Files: `pixel_contracts.py`, `inputs.lock.json`, `results.json`, `test_replay.py`,
and this report. Final result SHA-256:
`4ed2d78d6870a656f180d8e04c212007c47288515d0d91432de4e764c94478e1`.

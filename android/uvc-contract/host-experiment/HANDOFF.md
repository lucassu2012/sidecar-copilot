# Controlled host native ownership experiment

Completed on 2026-11-10 using existing Windows MSVC tools. This directory is the entire change boundary. It does not modify the parent Java contract, app, `uvc-sim`, task-4 worktrees, PRD, frozen inputs or APKs.

This is a self-authored C++17 lifecycle implementation with real threads, heap allocations and deterministic event barriers. It is **not** libusb/libuvc, JNI, an Android library or a real USB driver. Its Binding/OperationKey fields mirror the integration discussion independently; there is no generated schema, binary ABI or Java-to-native binding.

## Fixed result and evidence

Final successful run: [`build/run-20261110T175508.399869Z`](build/run-20261110T175508.399869Z/). Host runner exit **0**. Both compiler invocations exited **0** with `/W4 /WX /Od /Zi /MD /fsanitize=address`.

| Group | Observed result | Meaning |
| --- | --- | --- |
| ASan positive smoke | 1 process, exit 0 | Instrumented threaded allocation/use/free worked. |
| ASan deliberate UAF smoke | 1 process, exit 86 and `heap-use-after-free` | Detector actually caught the intended memory fault. |
| Correct lifecycle scenarios | 17 processes, all exit 0 | Each reported equal actual allocation/destruction counts and zero remaining tracked bytes. |
| Premature-release policies | 9 processes, all exit 86 and expected ASan UAF | Fault controls were detected; these are deliberately failing processes, not additional successful lifecycle cases. |
| Semantic wrong policies | 3 processes, exit 2 and the specified assertion | FD > 0, native-only full-stop and reused-FD identity policies were rejected. |

There were no command timeouts. The version-only `cl /Bv` command exited 2 because no source file was supplied; its version output is recorded separately from the two successful compilations.

The first attempt, [`build/run-20261110T175313.887546Z`](build/run-20261110T175313.887546Z/), remains intact: environment setup exited **1**, with **0** builds/tests started. Replacing inline `cmd /c` argument composition with a fixed local `.cmd` environment wrapper allowed the second attempt to proceed. The first attempt preserved source snapshots and the failure result but did not archive full setup stderr; that missing original diagnostic has not been reconstructed or claimed as recorded.

The intermediate successful run [`build/run-20261110T175330.697566Z`](build/run-20261110T175330.697566Z/) is also retained. Independent review then found that the experimental `ownApp` entrypoint could allocate after stop. The final source seals that entry and adds two assertions to the existing stopping-admission scenario. The same 31 checks were rebuilt and rerun against the final source; intermediate results are not transferred to the final version.

| Artifact | SHA-256 |
| --- | --- |
| Successful `result.json` | `30947151de5d5c3c85d47856519972ac0058950ac213e785ad83e152b4a67e06` |
| Successful `index.json` | `243b3f37b9cb8fe93a1b6ff9c93675f2fe06aba9f18070a34f59e45f3e25d267` |
| Successful `inputs.json` | `c6a5ea40e9fc91085b865804e3533f8c647701c51c1ecddf65f52c754f8e1ea0` |
| `ownership_checks.exe` | `e8d10fcec5cb126669ef5c06857e1a43c0ba00be83a195dcc6a2136e8985dc9e` |
| `asan_smoke.exe` | `d586a8898cac2773cd3632289ab5e16ce3cc52f7d8e71775840931d981c491b8` |

Final read-only verification matched all **86 indexed artifacts**, all four source inputs and their exact compiled snapshots. `commands.json` contains each command, working directory, start/end times, exit code and stdout/stderr hashes. `toolchain.json` records compiler/environment-script hashes, selected C++ backend/linker and ASan DLL/library hashes, plus relevant environment paths. This is selected toolchain binding, not a claim of a complete Windows SDK or loaded-module dependency closure. Raw environment dumps are not retained because they may contain unrelated credentials.

An independent read-only reviewer also confirmed the final 86/86 artifact bindings, 4/4 source bindings, 34 command receipts and exact positive/negative exit classifications. The final delta is limited to the app-buffer admission guard and its two existing-scenario assertions; the earlier review gap is closed within this host-only scope.

Compiler: `C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Tools\MSVC\14.44.35207\bin\Hostx64\x64\cl.exe`, observed version **19.44.35222.0**. No compiler, runtime or binary was downloaded or installed.

## What the experiment enforces

- Operation registration precedes a borrowed submit stack. A callback may finish before submit returns; terminal, callback exit, API stack exit and buffer destruction remain independent obligations.
- Explicit controlled BUSY leaves the preceding nonterminal submission alive. Explicit NOT_FOUND after terminal does not clear an active callback. Cancel return cannot retire a buffer still borrowed by its caller.
- Stop seals new open/control/submit admission. Only cancellation of an already registered operation may borrow during cleanup; closing seals that entry too. One close owner joins the real host pump thread; concurrent close callers observe incomplete/completed status without joining the same thread twice.
- Independent image/status/control obligations remain visible even if a wrong policy frees their buffer. Status re-submission is reserved under the stop lock: a pre-stop admitted cycle must finish, while a post-stop attempt is rejected.
- An outer control timeout or a late open result retains the borrowed native context until the real controlled worker returns. A late handle is disposed by its original owner and never publishes after stop.
- Foreign operation pointers and old binding generations are rejected by borrowing, callback, terminal and resubmission entrypoints. FD zero is valid; a negative FD is rejected before allocating resources or starting a thread. FD is only a simulated borrowed integer here: no OS descriptor is opened or closed.
- Application-owned pixel buffers are tracked separately from native drain. Stop and closing reject new app-buffer admission. Clearing native resources cannot emit a composite completion while those buffers remain. Successful buffer destructors zero and read back the actual bytes, free the allocation, and then return the budget; this is managed-buffer evidence, not forensic erasure.

The 17 positive scenarios cover callback-before-submit-return, terminal-before-cancel-return, NOT_FOUND, BUSY, outer control timeout, both status/stop orderings, absent callback at a bounded observation, late open, FD zero, app-buffer pending stop, stale binding, budget rejection, negative FD, foreign operation entrypoints, stopping admission and concurrent close.

Every event wait is bounded. The absent-callback scenario first observes failed stop completion with its buffer still alive, then explicitly delivers a controlled callback to clean up the test. It does not prove permanent missing completion will eventually recover. A harness process timeout or thread watchdog is a recorded test failure, never a successful drain receipt.

The 8 MiB budget applies to tracked experimental heap buffers. Runtime, thread stacks, metadata, allocator/ASan overhead and real transfer/JNI allocations are outside it. The largest observed tracked peak was 921,792 bytes. Budget refusal was exercised; an actual OS `new[]` failure was not injected. No LeakSanitizer or TSan result is claimed.

## Reproduce and continue

From this directory run:

```powershell
python -B run_checks.py
```

The runner creates a new ignored `build/run-*` directory, snapshots inputs, builds only self-authored sources, validates ASan smoke controls, then runs correct and wrong policies in separate child processes. Negative controls require the exact expected exit and diagnostic; arbitrary errors, timeouts and `NEGATIVE_POLICY_NOT_DETECTED` do not pass.

Static review found and corrected experimental implementation weaknesses before the successful run: single-close ownership/admission, independent obligation accounting, foreign-operation validation, BUSY timing, negative-FD constructor order and real-free-before-budget-return. Those findings concern this test implementation and are not reports of a production-driver defect.

Not executed: upstream libusb/libuvc compilation or patches, JNI, Android ABI/NDK, USB operations, ADB/emulators, actual device capture, real event-pump/control I/O, physical disconnection, real FD lifecycle, real-time stop guarantees, TSan, LeakSanitizer, sustained power/thermal tests or PRD G2/G3 acceptance. The existing libuvc status teardown and full dependency/license closure remain separate integration gates. No APK was built or changed by this experiment.

# JNI host boundary checks: design, not execution evidence

Baseline: `62eb3485a056c91720609f38ba436122bfdcb420`. This directory owns only new host tests. The parent `BackendPort`, `IsolatedCaptureSession`, `UsbConnectionOwner`, and `StopBarrier` remain unchanged. The root runner will build and run the original project-owned JNI experiment once its interface is fixed. No Android framework, emulator, USB hardware, OS file descriptor, or upstream native implementation is exercised here.

## Small, targeted test set

| Boundary | Controlled interleaving | Independent observations |
| --- | --- | --- |
| Native worker attachment | A real `std::thread` attaches, invokes Java, and exits through the native detach path; cleanup joins it. | Java observes a non-caller thread; native entry/exit events and attachment counters balance; worker/global-reference/buffer counts reach zero after context release. These observations do not establish arbitrary JVM or Android behavior. |
| Pending Java exception | A deliberately throwing Java callback raises one identifiable sentinel during an actual JNI `Call*Method`. | Native records the expected exception classification, clears the pending exception before continuing permitted JNI work, and reaches its cleanup path. An independent subsequent JNI call succeeds. An arbitrary exception or process failure is not a passing result. |
| Early terminal and native callback tail | Terminal enters before submit returns; separately, Java lease close has happened while the C++ callback tail is held by a native gate. | Submit and native callback obligations independently prevent transfer/context destruction. Releasing the gate permits progress; Java callback completion alone is insufficient evidence that C++ stopped borrowing the transfer. |
| Late terminal and identity | Stop is requested with a native completion still held; release it after admission closes. Reuse a numeric synthetic FD only in a separately bound owner if the interface permits that small scenario. | Terminal can settle only its registered attempt. It cannot publish a new frame or restore start admission. The old owner cannot close the replacement owner's connection. |
| Blocking native control | A real native control call remains blocked while the Java caller reports an outer timeout and requests stop. | The exact timeout remains visible; native stack/control counters remain nonzero, no transfer/context release is accepted, and FD owner close stays pending. Only actual native return permits drain. Timeout is metadata, not completion. |
| Actual composed stop | Use the actual parent adapter, owner, and barrier with a separately owned application frame and an independent synthetic connection ledger. | Native drain and the issued borrow's return precede one connection close. Application bytes may still keep the composed barrier pending; closing that owned frame is a separate event. Native must never close the synthetic FD. |
| Reference and allocation accounting | Inspect the same positive and failure paths above, without a large second scenario Cartesian product. | Independently reconcile allocation/release, callback entry/exit, worker attach/detach/join, and connection close events. Check per-context and final aggregate counts, not only a backend `drained` Boolean. No live-byte or live-global counter may be silently reset to claim completion. |

## Required deterministic seams

The concrete names await the native implementation owner's API. The minimum useful seams are callback delivery before submit return, a native gate after Java callback return but before the worker releases its own borrow, a blocking control gate, and a terminal-delivery permit after stop. Gate entry and release use explicit synchronization, never sleeps or timing races. Wait deadlines are only deadlock guards; no elapsed-time threshold constitutes a correctness oracle.

Snapshots must remain safe after context destruction: either immutable completed diagnostics or an independent process ledger. Tests must not dereference a stale native pointer merely to ask whether it was freed. Native event identities must retain the context and submission cycle; a numeric FD alone is not identity. A callback hook may observe or throw but must not be given a production bypass for terminal evidence.

The independent connection ledger will require zero issued borrows and genuine bound adapter native drain before one close. A separate trace oracle will require all native callback/control/submit obligations to finish before free, and worker join before context release. If negative controls are exposed, inject narrowly defined early-free/early-close events and demand specific violation codes; no arbitrary exception counts as a detected violation.

## JNI hazards to keep visible

- `JNIEnv*` belongs to the calling thread. A worker must use the environment obtained for that thread, and detach only if this experiment attached it.
- Java lease close, Java callback return, native callback return, and worker join are different boundaries. Cleanup must preserve the later obligations, including the C++ tail after `Call*Method` returns.
- Do not hold a native mutex while calling Java or while waiting for a callback that may reenter native code. Do not join the current callback thread.
- A pending Java exception restricts subsequent JNI operations. Capture the required diagnosis, clear at a deliberate boundary, release local/global references correctly, and do not leak the exception into unrelated native work. Failure reporting may itself throw.
- Do not hold a pinned array or critical-region pointer across a blocking gate, Java call, or join. Any test pixel buffer must have explicit allocation and copy ownership.
- JNI/native entry must contain C++ exceptions. Partial initialization, attachment failure, reference allocation failure, and unknown submission acceptance must not manufacture terminal cleanup evidence.
- Callback diagnostics, global references, native payload bytes, and Java owned-frame bytes have different lifetimes. Preserve the actual parent byte budget contract rather than declaring full drain from one global counter.

## Scope and result reporting

This is a bounded host JNI experiment, not a full scheduler exploration or proof under the C++ memory model. It does not establish Android ART attachment behavior, Binder permission authenticity, UVC/libusb transfer behavior, device compatibility, or native dependency license closure. Pure Java parent suites are not rerun. Design rows are not PRD acceptance numbers and are not passed tests until the root runner records their concrete executions.

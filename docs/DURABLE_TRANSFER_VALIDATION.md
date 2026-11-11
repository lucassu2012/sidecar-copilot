# Durable transfer and deletion validation — 2026-11-10

The implementation is described in [DURABLE_TRANSFER.md](DURABLE_TRANSFER.md). This is a software implementation checkpoint under the frozen v1.5 requirements, not a new PRD version or G2/G3 exit. No phone, microphone, USB capture card, provider account, publication or deployment was used.

## Final Python run

`python -m pytest -q`: **173 passed in 36.82 seconds; host exit 0**.

- Exact run: [result.json](../validation/durable-transfer-20261110T182602Z/result.json), 18:26:02–18:26:40 UTC.
- [Raw stdout](../validation/durable-transfer-20261110T182602Z/pytest.stdout.log) and [stderr](../validation/durable-transfer-20261110T182602Z/pytest.stderr.log).
- [30 selected inputs](../validation/durable-transfer-20261110T182602Z/inputs.json), source copies and [34-file archive index](../validation/durable-transfer-20261110T182602Z/index.json). Inputs cover every Python module/test, project dependency declarations, implementation documentation and the five changed Android source/test files. This is not a whole-repository or five-APK freeze.
- Input hashes matched before and after execution; a separate post-run check verified all 30 current input hashes and all 34 archived file hashes.
- Python 3.14.2; pytest 9.1.1, cryptography 48.0.0, FastAPI 0.136.1, uvicorn 0.46.0, httpx 0.28.1. Existing dependencies were reused; none were added.

| Evidence | SHA-256 |
| --- | --- |
| inputs.json | `47a003d884b6b71ac9387289a244639155266cb8431f4f0c37d39e00782ce6a9` |
| result.json | `eabe71e000a56d2f18bcc050497d37fa422d9118aea04d8038e0a29e118d9394` |
| index.json | `998535dd7c48a5c7d7d506cac8b12d1f5e569b3e06742b4231cf2e6f9e1c7db0` |

The 173 tests comprise the prior 46 reference/API tests, 31 receiver tests, 31 HTTP tests, 39 coordinator tests, 20 receiver-CLI tests and six independently delivered cancellation regressions. These are executable test cases, not 173 PRD acceptance items. Independent component runs and the earlier 165-test combined pass are subsets/history, not additional passes to sum.

The coordinator tests include actual sockets and independent SQLite databases; receiver identity isolation; exact parts and readback; a deliberately truncated HTTP commit response followed by source reopen/status reconciliation; part-ACK uncertainty; deletion at a held send boundary; late-commit rejection; local compaction followed by durable remote cleanup; failed cleanup receipts; range/deletion-generation changes; unlock/network/buffer gates; finite retries and persistent budget; strict receipt types; process ownership and cleanup/close concurrency. Deferred foreign-key faults separately force actual SQLite COMMIT failure for local deletion/outbox and pre-send byte reservation. An earlier trigger-ABORT case is named an insert failure, not a COMMIT fault.

The six cancellation tests were safely imported from `task-2/cancel-receipt-fix/cancel-receipt-tests.patch` after an application check. Only two new test files were applied; the independent production patch was not applied because the mainline already had the equivalent fix. They cover repeat/once/unblock semantics, identity and deleted-source rejection, actual COMMIT rollback, and failure to construct a receipt after a successful commit. They do not establish Android cancellation or provider behavior.

## Separate command-line service run

[CLI smoke receipts](../validation/transfer-cli-smoke-20261110T182656Z/result.json) record two actual child-process launches of `python -m sidecar.transfer_server --synthetic-only --root ... --port ...`, not a mocked uvicorn call. A bounded harness sent synthetic AES ciphertext through the real routes, verified committed object/metadata, terminated its owned test process, restarted on the same persistent receiver, verified the object again, deleted its range, and confirmed denial plus the deletion receipt after another reopen.

Both owned child processes were explicitly terminated after their checks and exited. Their individual exit codes are recorded as controlled termination, not successful graceful exits. The enclosing verification command exited 0. This is process-termination recovery, not simulated or physical power loss. The random bearer token was passed only through the child environment and was not recorded in the command or evidence. Synthetic receiver files and logs remain in the evidence directory; no background server remains from this run.

## Android source changes

The exact Android compilation/host evidence is in [range-delete handoff](../android/build/range-delete-final-a8a24f94a53a493e81e1a14190df799f/HANDOFF.md). The five source hashes in that handoff match the Python checkpoint's copies.

- 56 Java source inputs: 25 production and 31 test files compiled against Android API 35/JDK 17, exit 0, without drift.
- Independent deletion-order probe: eight scenarios / 253 checks passed with the real coordinator and queue but substituted host storage/clock.
- Queue checks: 19,583 assertions, including 200 stop races; assembler 13 and source lifecycle 30 checks passed. These are separate host suites and are not Android device assertions.
- Added Android range/buffer tests have only compiled. The planned 162 range assertions have **not** executed on Android SQLite/Keystore.

No new APK was built or installed in this slice. F134 and the other frozen candidates were not overwritten. Device validation of durable range intent, final append checks, synchronous pause retry, service input-stop wiring and the combined new candidate remains outstanding. Cached buffered-owner errors still mean incomplete cleanup, not automatic recovery.

## Earlier failures and review findings

- Before these changes, the existing 46 Python tests passed. After adding exclusive store ownership, the first regression run had 44 passes and two failures: two old restart fixtures opened another store without first closing the original. The fixtures now model an actual close/reopen; the independent double-owner test confirms simultaneous ownership is rejected.
- The first coordinator integration run passed 26 cases. Independent review then reproduced a one-nanosecond rounding rejection and acceptance of boolean values in numeric receipt fields, and identified permanent denial of an unaffected frame after a known deletion-generation change. Those boundaries were corrected and covered in the final run.
- A subsequent 36-case run had 35 passes and one failure: the extreme Retry-After test used `MAX_TIME`, which rounded beyond the HTTP parser's accepted bound as a float. The parser correctly rejected that response. The test now uses an accepted large delay to verify SQLite deadline saturation; invalid HTTP framing/values remain rejected.
- Independent core review identified public cleanup racing store close and JSON `1/1.0` satisfying `Literal[True]`. Shared worker serialization and a strict pre-validator fixed those cases; final tests include both.
- The Android handoff preserves three original missing-range negatives and two intermediate synchronous-retry negatives. The earlier HTTP component handoff preserves its Windows aborted-connection fixture cleanup failure. The external cancellation handoff preserves its three original failures.

Intermediate root failures above were observed in the execution transcript; their summaries are retained here rather than represented as newly reconstructed raw logs. Final runs do not erase or retroactively qualify earlier failures. Historical BLOCK_APPEND timing and old Android lock-screen precondition issues remain outside this checkpoint.

## Other parallel deliveries reviewed

F134 retention evidence was checked read-only at `task-3/anchor-frozen-f134e5d4-20261110T174417Z`. All 73 source inputs, five reference APKs, two independently signed test packages, seven host receipts, six-stage logs and 12 before/after installation snapshots matched their recorded hashes. The independently signed app has the same runtime ZIP entries as F134; only its signature entry differs. Logs support two expected process terminations with zero violations, then 26 / 7 / 65 / 40 checks and boot 16→17. Four normal stages do not print an instrumentation terminal code; none was invented. This supplements only F134's clock-anchor/reopen evidence, not current Android source or physical power-loss acceptance.

UVC adapter `ff46084` and USB-owner experiment `3e5abb1` were reviewed without integration. Public contract copies match the current mainline byte for byte. The adapter's 12 input hashes and USB owner's 14 current/18 inherited input hashes matched their evidence. Delivery receipts report 22 scenarios/189 assertions/two negative controls and 22 scenarios/283 assertions/two negative controls respectively; they were **not rerun here or added to mainline totals**.

The USB owner invalidation hook closes owner admission and reports failure; it does not itself stop a native session. Mainline must revoke publication, request the canonical stop and refresh native drain evidence. Real Android permission/broadcast binding, global request identity, platform FD ownership, application buffer evidence, JNI/native backend, complete license/build provenance and hardware verification remain absent. Both experiments should remain isolated, with only one copy of the public contract compiled when integration proceeds.

## Stage boundary

This checkpoint has a usable local receiver CLI, real host-side persistent transfer and delete reconciliation, plus the Android deletion-order source fix. It does **not** supply the Android uploader, a complete flashback button flow, OS-backed network/unlock/account policy, real OCR/ASR, provider charging authorization, trusted cross-boot time, hardware capture, or physical erasure. Next device work requires a new five-package candidate and bound runtime receipts. Existing F134 passes cannot be relabeled as that future candidate.

# Durable transfer implementation slice

This work implements the existing frozen v1.5 upload, deletion and cancellation requirements. It adds no PRD feature, product state or acceptance number. The sender and receiver now use real SQLite databases and real loopback HTTP. Capture content, identity, clocks, unlock and network permission inputs remain synthetic. Android has not been connected to this transfer service; its real capture and distribution gates remain closed.

Exact run results, prior failures and version boundaries are recorded in [the validation report](DURABLE_TRANSFER_VALIDATION.md).

## Implemented path

`UploadCoordinator` binds each explicitly confirmed frame to an immutable owner, tenant, device, binding generation, policy scope, processor and key scope. Merely constructing a destination does not import old objects or send a session's deletion metadata there. The first confirmed object backfills that session's ordered deletion history.

The sender persists the upload reference, fixed manifest, byte budget, finite attempt limit, part receipts, backoff, verified receipt and deletion outbox in the source database. A process lease is acquired before recovery, preventing two writers from sharing the source store. Reopening pauses upload authorization, and never starts capture. A separate explicit upload resume can release legal old frames even after the meeting ended.

Every outbound HTTP header/body slice is admitted under the same store lock used to commit a local deletion. That critical section reserves a durable byte charge and performs exactly one nonblocking `socket.send`. Connection, response and readiness waits occur outside the store lock. The gate checks owner, source integrity, independent range tombstones, expiry, boot, unlock, privacy/upload pause, last-sample plus 900 seconds, network generation, trusted/unmetered status and remaining budget. Local float-to-integer conversion never shortens the 900-second buffer. No whole segment is uploaded; only the selected frame's ciphertext bytes are sent.

Byte charges include outbound HTTP headers, body and retry traffic. They exclude responses, TCP/IP overhead and deletion-control requests, so they are not a device data-meter measurement or provider billing budget. Before a socket handoff the attempted bytes are charged; known partial writes refund the unused part, EAGAIN refunds all, and an uncertain write retains the conservative charge. Re-enqueueing cannot reset the budget or attempt count. Previously admitted bytes may already have left the process; cancellation never claims to recall them.

The receiver validates its server-owned credential binding, manifest, generation, TTL and deletion ranges on every operation. Parts have fixed 64 KiB boundaries, exact hashes and idempotent contents; this local implementation caps a frame at 8 MiB. These are implementation bounds, not newly promised product capacities. A successful upload requires both ciphertext readback/hash and the committed metadata receipt. It does not create a transcript or searchable text. Processing and correction remain blocked until a validated processing output exists; OCR/ASR/provider execution is still absent.

## Delete and recovery boundaries

The local transaction commits the tombstone, generation increment, frame-key revocation, derived-output hiding, job cancellation, upload cancellation and remote-delete outbox together. It never waits for the network. Reads independently check range tombstones, including point notes at the left boundary, even if an old frame row is artificially restored. Legacy source tombstones have no boot field and retain conservative all-boot scope; the wire record uses explicit `boot_id: null`, not a guessed boot.

The receiver first commits its deletion barrier and rejects reads/late parts/late commits. It then cleans stored parts in a separate transaction. A cleanup failure remains pending and does not roll the barrier back. The sender first queries deletion status after an unknown response; retry uses the same deletion identity. Outbox rows survive local frame compaction. The public cleanup worker and media worker share a reentrant worker lock, and source close waits for both before releasing ownership.

An already-admitted request can receive an old-generation rejection after a different local range is deleted. The sender may reconcile and retry that unaffected frame within its existing finite budget; unknown conflicts and idempotency conflicts remain denied. 401/403 do not retry automatically, 429 persists Retry-After, and transient/unknown results back off. Every media retry queries status before init/parts/commit. A restart or lost commit response cannot be interpreted as proof that nothing was sent.

All deletion receipts distinguish logical denial/managed cleanup from physical erasure. Neither SQLite FULL/WAL, source-file compaction, a simulated row rollback nor reopening a database proves power-loss durability, hostile snapshot rollback resistance or erasure of flash/WAL/backups. The receiver's injected clock is trusted by this local adapter; cross-boot clock trust and autonomous expired-part reclamation are not provided.

## Concrete interface

The authoritative immutable types and validation are in `sidecar/transfer_contract.py`. Fields use integer nanoseconds with explicit monotonic/UTC names, immutable frame IDs and ciphertext hashes. Capture generation and deletion generation remain distinct. `key_ref` is an opaque reference; private keys, wrapped DEKs and plaintext are not exported by this slice.

| Request | Purpose |
| --- | --- |
| `POST /uploads/init` | Bind immutable manifest and current deletion generation to a deterministic upload ID |
| `PUT /uploads/{id}/parts/{index}` | Send one exact ciphertext part with `X-Deletion-Generation` |
| `POST /uploads/{id}/commit` | Validate complete parts and commit object metadata |
| `GET /uploads/{id}/status?deletion_generation=N` | Reconcile after unknown delivery/commit |
| `GET /uploads/{id}/object?deletion_generation=N` | Read back opaque ciphertext for verification |
| `POST /deletions` | Persist a scoped deletion before cleanup |
| `GET /deletions/{id}` | Verify durable denial and managed cleanup separately |

Only literal `http://127.0.0.1:<port>` is accepted by this synthetic transport. It does not follow redirects, use DNS/proxies, or automatically retry. Bearer tokens are mapped by the server to immutable bindings and are not written to the queue. This is not the frozen PRD's production HTTPS, short-lived authorization, account rebinding or provider-key implementation.

The existing local API additionally exposes `POST /sessions/{sid}/uploads/resume` with exact JSON `{"consent":true}`. It leaves capture state and capture generation unchanged. Cancellation now reports the durable automatic-processing block rather than echoing a request that did not remove an existing block.

## Run locally

Use the repository's existing API and test dependencies; no dependency was added. With a random ASCII `SIDECAR_TRANSFER_TOKEN` of at least 32 characters in the environment:

```powershell
python -m sidecar.transfer_server --synthetic-only --root .\local-transfer-receiver --port 8767
python -m pytest tests/test_transfer_receiver.py tests/test_transfer_http.py tests/test_transfer_queue.py tests/test_transfer_server.py -q
```

The server persists only synthetic opaque transfer data in the chosen directory. The tests start and stop their own real loopback servers, use separate temporary sender/receiver databases and never operate a phone or microphone. For an application harness, create `LoopbackTransport(origin, token)`, construct `UploadCoordinator(store, transport, binding)`, explicitly `enqueue(frame_id, confirmed_import=True, byte_limit=..., max_attempts=...)`, then call `step(upload_id)`. Call `sync_deletions(session_id)` to retry deletion after local compaction. No unattended retry loop is installed.

## Android change in this slice

`AudioCaptureCoordinator.deleteSamples` now fixes the original stream/range identity, closes admission and signals the independent input-stop executor before committing the range tombstone and key revocation. Only a confirmed commit allows privacy discard and waiting for pause completion. A persistence/stop failure keeps the request incomplete and blocks replacement capture. Same-range retry reuses the original identity; it does not silently retarget a new run. Final append publication rechecks stream, generation, boot and deletion range.

Synchronous pause preserves its existing `CaptureBoundary` across a failed begin/finish transaction so that retry cannot report success solely because `active` became false. A failed buffered queue owner still reports its original error; automatic recovery of that owner is not claimed. The new range tests are compiled but have not run on Android SQLite/Keystore. See `android/build/range-delete-final-a8a24f94a53a493e81e1a14190df799f/HANDOFF.md` for source hashes, 56-source compile and host results. F134 APK/runtime evidence remains historical and does not validate these changed files.

## Remaining work

Android persistent uploader and trusted OS inputs, the complete five-minute flashback UI/press-time mapping, real provider authorization/HTTPS, key delivery and processing execution, provider budgets and real devices remain incomplete. The separate UVC adapter `ff46084` was read-only reviewed: its three contract snapshots match the current public contract and its 12-input evidence manifest matches its isolated files. Its 22 scenarios/189 assertions/2 controls were not rerun or merged here. It has no JNI/platform FD owner/real USB backend; native licensing, drain and hardware gates remain closed. Counts from that delivery or older F134 retention evidence cannot be added to this slice's runtime results.

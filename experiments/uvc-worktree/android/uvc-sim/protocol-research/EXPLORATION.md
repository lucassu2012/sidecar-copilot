# Bounded lifecycle interleaving exploration

The independent trace checker found no protocol violation in the visited positive
states and detected all four deliberately wrong model strategies. This is a
bounded model result, not a proof about real C/JNI execution. In particular, the
asynchronous-control graph reached its state cap before a closed node was visited.
Separate directed traces demonstrate normal ordered closure for both transfer
combinations; cutoff states are not counted as completed lifecycles.

Only `explore_interleavings.py` and this document belong to this exploration task.
The model is separately owned `protocol_model.py`; no production source was edited.
No native code, OS fd, physical device, network, emulator, threads or real clocks
are used by the experiment. Local baseline is
`c8874f6f39dddcd39a63289787fc3ba7a42b2cad`.

## Reproduce

From the repository root:

```powershell
python -B android/uvc-sim/protocol-research/explore_interleavings.py
```

The default command emits deterministic JSON to stdout and does not change tracked
evidence. To explicitly save a result in ignored build output:

```powershell
python -B android/uvc-sim/protocol-research/explore_interleavings.py --output android/uvc-sim/build/protocol-research/exploration.json
```

Parameters are `--max-depth`, `--max-states`, `--max-submissions`, and `--output`.
Defaults are **12 scheduling actions after setup, 4,000 visited states per run,
and at most two status submissions**. Defensive CLI limits are depth 20, states
20,000 and submissions 3. These are implementation bounds, not PRD requirements.
The final default run took **18.53 seconds wall time locally**, including Python
startup and a JSON-summary pipe. Peak memory was not measured; there is no native
performance inference. No further depth or state increase was needed for this
delivery.

Exit status is zero when the visited positive transitions pass the independent
oracle, all four negative policies produce counterexamples, and all directed
checks/witnesses complete. A positive counterexample or missing required negative
witness makes the command fail. A state/depth cutoff is reported explicitly; it
does not become a false protocol failure or a claim of complete safety.

Observed source hashes for the reported run:

```text
explore_interleavings.py  06eff4a6e4ef911e8666920f3fbc3d7b3b613c812e784835db40429828c840b0
protocol_model.py        3b94a901bbc01d994c72773e2b4f9f067fd1411842b3a7cd5da35b955b1461f8
```

The JSON records both hashes so a later model or explorer revision is visible.

## Scope and scheduler

Two separate main graphs start with one explicitly authorized synthetic session,
one status transfer and one control transfer. The control transfer is asynchronous
in one graph and a blocking control call in the other. The opaque descriptor token
is a string, not an operating-system resource.

Enabled actions are derived from the public model snapshot:

- User stop, combined `close`, and a later release attempt.
- One of CANCELLED, COMPLETED, ERROR or NO_DEVICE for a pending asynchronous
  completion, followed by separate callback-enter and callback-exit actions.
- Status callback exit with or without resubmission, including an attempted
  resubmission after closing. Only status resubmission is expanded in the graph.
- One ERROR, NOT_FOUND or OK cancellation-result notification per submitted cycle.
- One requester timeout per control cycle; a blocking control ends only through
  an explicit blocking-return action with one of the four outcomes.

Each public method call is one atomic scheduling action. For example `close`
combines stop with a release attempt, while separate stop/release actions also
exist. **The exploration cannot split a check from a submit inside a method,
interleave instructions within allocation/destruction, or establish C/C++ memory
ordering.** Initial open and initial submissions are synchronous setup, not an
open/start-race exploration. The model represents a sole-owner descriptor
contract; it does not prove any real native library implements that contract.

The main BFS uses the atomic, already-returned `cancel_result` notification.
It does **not** enumerate the explicit native cancel-call stack as a Cartesian
dimension. A separate directed scenario covers callback exit before cancel-call
return and verifies the remaining borrowed-resource obligation. Java-copy,
image-transfer, open-failure, repeated-cancel-call and multi-session Cartesian
spaces are also excluded from the main graphs. Other independently owned model
tests may cover these, but their coverage is not silently counted here.

## Algorithm and independent checks

The worklist is breadth-first and actions are sorted by canonical JSON. The first
representative of a state is therefore reached at minimum scheduling depth within
the generated graph. There is no random scheduling or partial-order reduction.

The deduplication key is the tuple of:

1. The model's public `state_key()` (which excludes its event log).
2. The independent oracle's complete resource ledger, including past submitted
   cycles, active callback/cancel-call tokens, identity bindings and live fd owners.
3. A sorted scheduler ledger of per-cycle timeout/cancel-result actions already
   consumed by the one-shot bounds.

Including the oracle ledger prevents two model states with different resource
histories from being merged accidentally. The depth is not part of the key: the
first BFS representative has at least as much remaining depth budget as later
paths to the same composite state. State-cap blocked successors are counted as
**edges**, not unique omitted states; their keys are not retained beyond the cap.
Every attempted transition's emitted events are checked, even if the method raises
an exception. An exception cannot hide emitted events or partial state mutation.

The oracle consumes events in trace order and independently reconstructs accepted
submission obligations. It does not invoke a model invariant or trust a model
`native_drain_confirmed` flag as proof. It does not inspect fault labels or accept
an `unsafe` annotation as an excuse. Its resource rules include:

- After publication revocation/closing, no submission may be accepted.
- Session identity is immutable across device identity, attachment generation,
  authorization revision, permission token and fd token. Every event must name
  its correct owner session.
- Completion queued, callback entered and callback exited are distinct states.
  A callback entry consumes the in-flight completion but retains the callback's
  stack obligation; transfer release still requires callback exit.
- A requester timeout or cancellation result never discharges the pending
  asynchronous callback or an on-stack blocking control call.
- A live native cancel-call token is another resource borrower. Transfer release
  requires its return even when the callback has already exited. Conversely cancel
  return alone cannot replace callback exit.
- Native release requires all accepted cycles retired, publication revoked and
  closing established. Pump stop follows native release with no outstanding
  cycle/callback; context release follows pump stop; descriptor close follows all
  of these and belongs to the sole live owner exactly once.
- Native drain confirmation is checked against the independently reconstructed
  obligations. Java-owned arrays may still exist, so native drain is not declared
  equivalent to full application drain.

Unknown trace events fail explicitly and require an oracle update. The snapshot
is used to generate schedules, not to certify release preconditions.

## Default-run counts and endpoint classification

| Positive graph | Visited nodes | Attempted/accepted edges | Duplicate edges | Native-closed nodes | Pending nodes | Open, no native obligation | State-cap blocked edges |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| status + asynchronous control | 4,000 | 27,644 / 27,644 | 17,112 | 0 | 3,991 | 9 | 6,533 |
| status + blocking control | 1,272 | 5,214 / 5,214 | 3,943 | 168 | 934 | 170 | 0 |

Both runs reported zero rejected actions, zero depth-frontier nodes and zero
stalled-pending nodes. The async graph has a **state-cap frontier** despite having
no depth frontier. The blocking graph has neither frontier under this exact action
enumerator and bounds; this is not exhaustion of real concurrent behavior.

`native_closed_nodes` means all modeled native resources, pump, context and owner
fd are closed and the oracle has no outstanding native cycle. Main-graph seeds
create no Java frames. `pending_nodes` includes a cycle still in flight, queued,
on callback/blocking/cancel stack, or otherwise not yet freed. An open-quiescent
node has no such transfer obligation but still has native ownership; it is not a
closed terminal. A pending node with no observed progress is a distinct metric,
not automatically proof of a real deadlock. No fairness, eventual terminal-event,
or bounded-time drain guarantee is assumed.

The JSON intentionally reports
`native_closed_reached_in_each_positive_run: false` for this default BFS. Two
separate directed traces close the status+async-control and status+blocking-control
combinations normally: stop, deliver/return terminal outcomes, refuse release
while a callback remains on stack, exit callbacks, then release in order. These
are positive witnesses, not a replacement claim of exhaustive BFS coverage.

## Counterexamples and directed identity/cancel coverage

The same oracle detects all four faulty model policies:

| Deliberate policy | First violating schedule after common setup | Independent rule violated |
| --- | --- | --- |
| `timeout_release` | control timeout (1 action) | Transfer freed before its terminal callback exits or blocking call returns. The requester deadline cannot discharge the outstanding resources. |
| `stop_pump` | close (1 action) | Pump/native resource retired while a submitted cycle or callback is still outstanding. |
| `ignore_status` | queue CANCELLED for control, enter control callback, exit it, close (4 actions) | Pending status transfer is freed despite never reaching terminal callback exit. |
| `late_resubmit` | queue ERROR for status, enter its callback (which requests stop), exit with resubmit requested (3 actions) | A new status submission is accepted after closing. |

The negative searches had respectively 2, 1, 796 and 270 visited nodes when their
first violating edge was found. JSON includes the complete setup/event trace,
scheduled actions and offending event for each counterexample. These are genuine
violations of the stated research protocol by intentionally wrong strategies;
they are **not** reports of newly observed upstream/native bugs. ERROR-triggered
closing in the late-resubmit witness is explicit, rather than described as a
separate user-stop action that did not occur.

The independent FD/identity sequence passes **17 checks**. It covers old-cycle
timer/completion/enter/cancel/exit operations after a new cycle has begun, close
while a control remains pending, release while a terminal callback is on stack,
closure followed by fd-token reuse under a new attachment/authorization, repeated
old-owner close, old-authority pixel delivery rejection/zeroing, preservation of
the new session, a stale attachment reopen rejection, and a new authorization
revision on the same attachment. These are directed examples; old authorization
permutations are not claimed to be exhaustively explored.

The explicit cancel-call sequence passes **three checks**: callback exits before
cancel returns and release remains pending; a deliberately inserted early
`transfer_free` event is rejected by the independent oracle; then NOT_FOUND cancel
return permits transfer retirement and ordered native closure. That extra witness
is labeled a **trace mutation**, not a fifth actual model policy. The mutated event
is tested in isolation against the current ledger; it is not represented as an
unmodified model execution.

The delivered result therefore separates visited safe edges, omitted frontier,
normal closed witnesses, still-pending states, actual negative model policies,
and the additional trace-mutation sensitivity check. It makes no deployment,
device-readiness or complete concurrency-verification claim.

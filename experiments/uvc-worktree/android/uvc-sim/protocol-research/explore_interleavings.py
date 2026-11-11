"""Bounded deterministic schedules plus an independent event-trace protocol oracle.

No threads/native/OS descriptors/devices/network. Each public model method is one
atomic scheduling action; this does not prove its eventual C/JNI implementation.
Default execution writes JSON to stdout only. --output is explicit opt-in.
"""
from collections import deque
from copy import deepcopy
import argparse
import hashlib
import json
from pathlib import Path

from protocol_model import ConnectionIdentity, ProtocolModel

HERE = Path(__file__).resolve().parent
OUTCOMES = ("CANCELLED", "COMPLETED", "ERROR", "NO_DEVICE")
CANCEL_RESULTS = ("ERROR", "NOT_FOUND", "OK")


def stable(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


class Violation(ValueError):
    def __init__(self, rule, event):
        super().__init__(rule)
        self.rule, self.event = rule, deepcopy(event)


class TraceOracle:
    """Reconstruct obligations from events, never call model invariant/helpers.

    Queued completion, callback entry, callback exit and blocking-call return are
    distinct. Cancel results and requester timeouts cannot discharge obligations.
    Fault labels are deliberately irrelevant to this checker.
    """
    def __init__(self):
        self.sessions = {}
        self.fd_owners = {}

    def key(self):
        return stable({"sessions": self.sessions, "fd_owners": self.fd_owners})

    def _need(self, condition, rule, event):
        if not condition:
            raise Violation(rule, event)

    def _outstanding(self, session):
        return [key for key, item in session["transfers"].items() if not item["freed"]]

    def is_closed(self):
        return bool(self.sessions) and all(s["fd_closed"] and s["context_released"]
            and s["native_released"] and not s["pump"] and not self._outstanding(s)
            for s in self.sessions.values())

    def has_pending(self):
        return any(self._outstanding(s) for s in self.sessions.values())

    def feed(self, events):
        for event in events:
            self.consume(event)

    def consume(self, e):
        event, sid = e["event"], e["session_id"]
        self._need(e["owner_session_id"] == sid, "event names a foreign FD owner", e)
        identity = e["identity"]
        self._need(e["generation"] == identity["attachment_generation"],
                   "event generation disagrees with authorization identity", e)
        if event == "open_requested":
            self._need(sid not in self.sessions, "session ID reused", e)
            self.sessions[sid] = {"identity": deepcopy(identity), "fd": e["fd_token"],
                "fd_opened": False, "fd_closed": True, "closing": False, "publishing": False,
                "context_created": False, "context_released": True,
                "native_created": False, "native_released": True, "pump": False,
                "transfers": {}}
            return
        self._need(sid in self.sessions, "event has no declared session", e)
        s = self.sessions[sid]
        self._need(identity == s["identity"] and e["fd_token"] == s["fd"],
                   "session mixed attachment/authorization/permission/FD identity", e)
        tkey = stable([e["transfer_id"], e["submission"]]) if "transfer_id" in e else None
        t = s["transfers"].get(tkey)

        if event == "fd_open":
            self._need(not s["fd_opened"] and s["fd"] not in self.fd_owners,
                       "FD token has duplicate live ownership", e)
            self._need(not self.fd_owners, "second live connection before prior owner closes", e)
            self.fd_owners[s["fd"]] = sid
            s["fd_opened"], s["fd_closed"] = True, False
        elif event == "context_create":
            self._need(not s["fd_closed"] and not s["context_created"], "invalid context creation", e)
            s["context_created"], s["context_released"] = True, False
        elif event == "pump_start":
            self._need(not s["context_released"] and not s["pump"], "invalid event-pump start", e)
            s["pump"] = True
        elif event == "native_open":
            self._need(s["pump"] and not s["context_released"] and not s["native_created"],
                       "native open lacks live pump/context", e)
            s["native_created"], s["native_released"] = True, False
        elif event == "publication_enabled":
            self._need(not s["closing"] and not s["native_released"] and not s["fd_closed"],
                       "publication enabled for revoked/released session", e)
            s["publishing"] = True
        elif event == "publication_revoked":
            s["publishing"], s["closing"] = False, True
        elif event == "stop_requested":
            self._need(not s["publishing"], "stop did not revoke publication", e)
            s["closing"] = True
        elif event == "submit":
            self._need(not s["closing"], "submission accepted after closing", e)
            self._need(s["pump"] and not s["native_released"] and not s["context_released"]
                       and self.fd_owners.get(s["fd"]) == sid, "submission lacks owned live native resources", e)
            self._need(t is None, "submission cycle reused", e)
            self._need(all(v["freed"] for v in s["transfers"].values()
                           if v["transfer_id"] == e["transfer_id"]), "transfer resubmitted before previous cycle retired", e)
            s["transfers"][tkey] = {"transfer_id": e["transfer_id"], "submission": e["submission"],
                "kind": e["kind"], "blocking": e["blocking_call"], "pending": True,
                "queued": None, "callback": None, "cancel_call": None,
                "returned": False, "exited": False, "freed": False}
        elif event == "completion_queued":
            self._need(t is not None and t["pending"] and not t["blocking"] and t["queued"] is None,
                       "completion does not match a live asynchronous cycle", e)
            t["queued"] = e["outcome"]
        elif event == "callback_enter":
            self._need(t is not None and t["pending"] and t["queued"] == e["outcome"]
                       and t["callback"] is None and not t["blocking"] and s["pump"]
                       and not s["native_released"], "callback entry lacks matching live cycle/pump", e)
            t["pending"], t["queued"], t["callback"] = False, None, e["token"]
        elif event == "callback_exit":
            self._need(t is not None and t["callback"] == e["token"] and not t["freed"],
                       "callback exit does not match its active cycle", e)
            t["callback"], t["exited"] = None, True
        elif event == "blocking_call_return":
            self._need(t is not None and t["blocking"] and t["pending"] and not t["freed"],
                       "blocking return does not match its on-stack cycle", e)
            t["pending"], t["returned"] = False, True
        elif event == "cancel_call_enter":
            self._need(t is not None and not t["freed"] and not t["blocking"] and t["cancel_call"] is None,
                       "cancel call lacks a live owned asynchronous transfer", e)
            t["cancel_call"] = e["token"]
        elif event == "cancel_call_return":
            self._need(t is not None and t["cancel_call"] == e["token"] and not t["freed"],
                       "cancel return does not match its active native call", e)
            t["cancel_call"] = None
        elif event == "transfer_free":
            self._need(t is not None and not t["freed"], "transfer freed without unique accepted cycle", e)
            self._need(t["cancel_call"] is None, "transfer freed while cancel call still borrows it", e)
            self._need(not t["pending"] and t["callback"] is None
                       and (t["returned"] if t["blocking"] else t["exited"]),
                       "transfer freed before terminal callback exit or blocking return", e)
            t["freed"] = True
        elif event in ("native_release", "pump_stop", "context_release", "fd_close"):
            self._need(not self._outstanding(s), "native resource retired with outstanding cycle/callback", e)
            self._need(s["closing"] and not s["publishing"], "resource retired before closing gate", e)
            if event == "native_release":
                self._need(s["native_created"] and not s["native_released"], "duplicate native release", e)
                s["native_released"] = True
            elif event == "pump_stop":
                self._need(s["native_released"] and s["pump"], "pump stopped before native teardown finished", e)
                s["pump"] = False
            elif event == "context_release":
                self._need(s["native_released"] and not s["pump"] and not s["context_released"],
                           "context release precedes native/pump drain", e)
                s["context_released"] = True
            else:
                self._need(s["native_released"] and s["context_released"] and not s["pump"]
                           and not s["fd_closed"] and self.fd_owners.get(s["fd"]) == sid,
                           "FD closed early, twice or by another session", e)
                del self.fd_owners[s["fd"]]
                s["fd_closed"] = True
        elif event == "control_timeout":
            self._need(t is not None and t["kind"] == "control" and not t["freed"],
                       "requester timeout applied to a retired/wrong cycle", e)
            # Deliberately retain pending/callback/blocking obligations.
        elif event == "native_drain_confirmed":
            self._need(not self._outstanding(s) and s["native_released"] and s["context_released"]
                       and s["fd_closed"] and not s["pump"], "false native drain confirmation", e)
            # java_owned_remaining may be nonzero: native drain is not Java drain.
        elif event == "delivery_accepted":
            self._need(s["publishing"] and not s["closing"] and not s["native_released"]
                       and self.fd_owners.get(s["fd"]) == sid, "old/revoked authorization published pixels", e)
        elif event in {"cancel_requested", "cancel_result", "blocking_cancel_unavailable", "async_error",
                       "release_pending", "stop_repeated", "resubmit_rejected", "submit_failed", "open_failed",
                       "frame_owned", "frame_copied", "copy_failed", "delivery_rejected", "frame_zeroed", "java_drain"}:
            # These events never discharge a native accepted-submission obligation.
            pass
        else:
            raise Violation("unrecognized event: update independent oracle explicitly", e)


def action(op, sid, **args):
    return {"op": op, "sid": sid, **args}


def perform(model, a):
    args = dict(a)
    op, sid = args.pop("op"), args.pop("sid")
    tid = args.pop("tid", None)
    if op in ("queue_completion", "cancel_result", "control_timeout", "callback_enter", "return_blocking_control"):
        return getattr(model, op)(sid, tid, **args)
    if op == "callback_exit":
        return model.callback_exit(sid, args.pop("token"), **args)
    return getattr(model, op)(sid, **args)


def choices(snapshot, used, max_submissions):
    result = []
    for sid, session in sorted(snapshot["sessions"].items()):
        if session["fd_owner_closed"]:
            continue
        if not session["closing"]:
            result += [action("request_stop", sid), action("close", sid)]
        else:
            result += [action("try_release", sid), action("close", sid)]
        for tid, t in sorted(session["transfers"].items()):
            if t["freed"]:
                continue
            cycle = t["submission"]
            token = stable([sid, tid, cycle])
            if t["kind"] == "control" and "timeout:" + token not in used:
                result.append(action("control_timeout", sid, tid=tid, submission=cycle))
            if t["blocking_call_active"]:
                for outcome in OUTCOMES:
                    result.append(action("return_blocking_control", sid, tid=tid, submission=cycle, outcome=outcome))
                continue
            if t["cancel_requested"] and "cancel:" + token not in used:
                for value in CANCEL_RESULTS:
                    result.append(action("cancel_result", sid, tid=tid, submission=cycle, result=value))
            if t["in_flight"] and t["completion_queued"] is None:
                for outcome in OUTCOMES:
                    result.append(action("queue_completion", sid, tid=tid, submission=cycle, outcome=outcome))
            if t["completion_queued"] is not None and t["callback_token"] is None:
                result.append(action("callback_enter", sid, tid=tid, submission=cycle))
        for token, cb in sorted(session["callbacks"].items()):
            result.append(action("callback_exit", sid, token=token, resubmit=False))
            t = session["transfers"][cb["transfer_id"]]
            if t["kind"] == "status" and t["submission"] < max_submissions:
                # Also offer after closing; correct model rejects the resubmission.
                result.append(action("callback_exit", sid, token=token, resubmit=True))
    return sorted(result, key=stable)


def scheduler_next(used, a):
    result = set(used)
    if a["op"] in ("control_timeout", "cancel_result"):
        prefix = "timeout:" if a["op"] == "control_timeout" else "cancel:"
        result.add(prefix + stable([a["sid"], a["tid"], a["submission"]]))
    return frozenset(result)


def seed(blocking, faults):
    model = ProtocolModel(faults=faults)
    sid = model.open(ConnectionIdentity("synthetic-camera", 1, 1, "synthetic-permission-1"), "opaque-fd-7")
    model.submit(sid, "status", "status")
    model.submit(sid, "control", "control", blocking_call=blocking)
    oracle = TraceOracle()
    oracle.feed(model.trace)
    return model, oracle


def state_key(model, oracle, used):
    return (model.state_key(), oracle.key(), tuple(sorted(used)))


def explore(blocking, faults, max_depth, max_states, max_submissions):
    initial, ledger = seed(blocking, faults)
    queue = deque([(initial, ledger, frozenset(), ())])
    seen = {state_key(initial, ledger, frozenset())}
    counts = {"visited_nodes": 1, "expanded_nodes": 0, "attempted_edges": 0, "accepted_edges": 0,
              "rejected_actions": 0, "duplicate_edges": 0, "native_closed_nodes": 0,
              "pending_nodes": 0, "open_quiescent_nodes": 0, "depth_frontier_nodes": 0,
              "depth_frontier_pending": 0, "state_cap_blocked_edges": 0, "stalled_pending_nodes": 0}
    witnessed = set()
    while queue:
        model, oracle, used, path = queue.popleft()
        if oracle.is_closed():
            counts["native_closed_nodes"] += 1
            continue
        pending = oracle.has_pending()
        counts["pending_nodes" if pending else "open_quiescent_nodes"] += 1
        if len(path) >= max_depth:
            counts["depth_frontier_nodes"] += 1
            counts["depth_frontier_pending"] += int(pending)
            continue
        counts["expanded_nodes"] += 1
        progress = False
        parent_key = state_key(model, oracle, used)
        before = len(model.trace)
        for a in choices(model.snapshot(), used, max_submissions):
            counts["attempted_edges"] += 1
            child, checked = model.clone(), deepcopy(oracle)
            rejected = False
            try:
                perform(child, a)
            except (ValueError, TypeError):
                counts["rejected_actions"] += 1
                rejected = True
            counts["accepted_edges"] += int(not rejected)
            # Inspect even an exceptional transition: an exception cannot hide
            # observable events or partially mutated protocol state.
            events = child.trace[before:]
            witnessed.update(e["event"] for e in events)
            try:
                checked.feed(events)
            except Violation as error:
                return {"scenario": "status+blocking_control" if blocking else "status+async_control",
                        "faults": list(faults), **counts, "witnessed_events": sorted(witnessed),
                        "counterexample": {"rule": error.rule, "offending_event": error.event,
                            "scheduled_actions": list(path) + [a], "event_trace": child.trace}}
            next_used = scheduler_next(used, a)
            key = state_key(child, checked, next_used)
            progress |= key != parent_key
            if key in seen:
                counts["duplicate_edges"] += 1
                continue
            if len(seen) >= max_states:
                counts["state_cap_blocked_edges"] += 1
                continue
            seen.add(key)
            counts["visited_nodes"] += 1
            queue.append((child, checked, next_used, path + (a,)))
        if pending and not progress:
            counts["stalled_pending_nodes"] += 1
    return {"scenario": "status+blocking_control" if blocking else "status+async_control",
            "faults": list(faults), **counts, "witnessed_events": sorted(witnessed), "counterexample": None}


def directed_identity_checks():
    """Separate bounded scenarios, not a two-session Cartesian BFS expansion."""
    model, ledger, cursor = ProtocolModel(), TraceOracle(), 0
    actions, checks = [], []

    def step(label, fn):
        nonlocal cursor
        result = fn()
        actions.append(label)
        ledger.feed(model.trace[cursor:])
        cursor = len(model.trace)
        return result

    def require(condition, name):
        if not condition:
            raise AssertionError(name)
        checks.append(name)

    identity1 = ConnectionIdentity("synthetic-camera", 1, 1, "permission-A")
    sid1 = step("open old authorization", lambda: model.open(identity1, "opaque-fd-7"))
    first = step("submit control cycle 1", lambda: model.submit(sid1, "control", "control"))
    step("queue completion cycle 1", lambda: model.queue_completion(sid1, "control", submission=first))
    cb = step("enter cycle 1", lambda: model.callback_enter(sid1, "control", submission=first))
    step("exit cycle 1", lambda: model.callback_exit(sid1, cb))
    second = step("submit control cycle 2", lambda: model.submit(sid1, "control", "control"))
    before = model.snapshot()
    stale_operations = [
        lambda: model.control_timeout(sid1, "control", submission=first),
        lambda: model.queue_completion(sid1, "control", submission=first),
        lambda: model.callback_enter(sid1, "control", submission=first),
        lambda: model.cancel_result(sid1, "control", submission=first),
        lambda: model.callback_exit(sid1, cb),
    ]
    for index, fn in enumerate(stale_operations):
        rejected = False
        try:
            fn()
        except ValueError:
            rejected = True
        require(rejected and model.snapshot() == before, "stale cycle operation {} rejected without mutation".format(index))
    require(not step("close while control pending", lambda: model.close(sid1)), "close stays pending before terminal callback")
    step("queue cancelled cycle 2", lambda: model.queue_completion(sid1, "control", "CANCELLED", submission=second))
    cb2 = step("enter cancelled cycle 2", lambda: model.callback_enter(sid1, "control", submission=second))
    require(not step("release while callback on stack", lambda: model.try_release(sid1)), "terminal callback entry is not callback exit")
    step("exit cancelled cycle 2", lambda: model.callback_exit(sid1, cb2))
    require(step("release old native owner", lambda: model.try_release(sid1)), "release succeeds after callback exit")

    identity2 = ConnectionIdentity("synthetic-camera", 2, 2, "permission-B")
    sid2 = step("reuse numeric-like token with new authorization", lambda: model.open(identity2, "opaque-fd-7"))
    new_before = model.snapshot()["sessions"][sid2]
    step("repeat old close after token reuse", lambda: model.close(sid1))
    pixels = bytearray([19, 37, 83, 255])
    require(not step("deliver pixels under old authorization", lambda: model.delivery(sid1, pixels)), "old authorization delivery rejected")
    require(list(pixels) == [0, 0, 0, 0], "rejected old owned pixels zeroed")
    require(model.snapshot()["sessions"][sid2] == new_before, "old owner actions leave new owner session unchanged")
    require(ledger.fd_owners["opaque-fd-7"] == sid2, "reused FD token belongs solely to new session")
    require(step("close new owner", lambda: model.close(sid2)), "new owner closes independently")
    before = model.snapshot()
    rejected = False
    try:
        model.open(identity1, "opaque-fd-8")
    except ValueError:
        rejected = True
    require(rejected and model.snapshot() == before, "old attachment generation cannot reopen")
    # Authorization-only change on the same attachment is a separate identity.
    identity3 = ConnectionIdentity("synthetic-camera", 2, 3, "permission-C")
    sid3 = step("new authorization on same attachment", lambda: model.open(identity3, "opaque-fd-7"))
    require(ledger.sessions[sid3]["identity"]["authorization_revision"] == 3,
            "same attachment retains explicit new authorization revision")
    require(step("close authorization revision 3", lambda: model.close(sid3)), "final native owner closed")
    require(ledger.is_closed(), "all directed native sessions closed")
    return {"checks_passed": len(checks), "checks": checks, "actions": actions, "event_trace": model.trace}


def directed_cancel_checks():
    model, ledger, cursor = ProtocolModel(), TraceOracle(), 0
    checks = []

    def step(fn):
        nonlocal cursor
        value = fn()
        ledger.feed(model.trace[cursor:])
        cursor = len(model.trace)
        return value

    sid = step(lambda: model.open(ConnectionIdentity("cancel-fixture", 1, 1, "permission-C"), "opaque-cancel-fd"))
    cycle = step(lambda: model.submit(sid, "status", "status"))
    step(lambda: model.request_stop(sid))
    call = step(lambda: model.cancel_call_enter(sid, "status", submission=cycle))
    step(lambda: model.queue_completion(sid, "status", "CANCELLED", submission=cycle))
    callback = step(lambda: model.callback_enter(sid, "status", submission=cycle))
    step(lambda: model.callback_exit(sid, callback))
    if step(lambda: model.try_release(sid)):
        raise AssertionError("cancel call stack must prevent release after callback exit")
    checks.append("callback exited but cancel call keeps release pending")
    fake_free = {**model.trace[-1], "event": "transfer_free", "transfer_id": "status", "submission": cycle,
                 "kind": "status", "blocking_call": False}
    try:
        deepcopy(ledger).consume(fake_free)
    except Violation as error:
        witness = {"kind": "deliberately injected event, not a model policy or observed native defect",
                   "rule": error.rule, "offending_event": fake_free}
    else:
        raise AssertionError("independent oracle did not detect premature free during cancel")
    checks.append("trace mutation free before cancel return is independently rejected")
    step(lambda: model.cancel_call_return(sid, call, "NOT_FOUND"))
    if not step(lambda: model.try_release(sid)) or not ledger.is_closed():
        raise AssertionError("cancel return plus callback exit should permit ordered native drain")
    checks.append("NOT_FOUND return plus prior callback exit permits drain")
    return {"checks_passed": len(checks), "checks": checks, "trace_mutation_counterexample": witness,
            "event_trace": model.trace}


def directed_closed_witnesses():
    witnesses = []
    for blocking in (False, True):
        model, oracle = seed(blocking, ())
        cursor = len(model.trace)
        actions = []

        def step(a):
            nonlocal cursor
            result = perform(model, a)
            actions.append(a)
            oracle.feed(model.trace[cursor:])
            cursor = len(model.trace)
            return result

        step(action("request_stop", 1))
        for tid in ("control", "status"):
            if tid == "control" and blocking:
                step(action("return_blocking_control", 1, tid=tid, submission=1, outcome="CANCELLED"))
            else:
                step(action("queue_completion", 1, tid=tid, submission=1, outcome="CANCELLED"))
                token = step(action("callback_enter", 1, tid=tid, submission=1))
                if step(action("try_release", 1)):
                    raise AssertionError("callback on stack was mistaken for complete drain")
                step(action("callback_exit", 1, token=token, resubmit=False))
        if not step(action("try_release", 1)) or not oracle.is_closed():
            raise AssertionError("two-transfer directed close failed")
        witnesses.append({"scenario": "status+blocking_control" if blocking else "status+async_control",
                          "native_closed": True, "scheduled_actions": actions, "event_trace": model.trace})
    return witnesses


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--max-depth", type=int, default=12)
    parser.add_argument("--max-states", type=int, default=4000)
    parser.add_argument("--max-submissions", type=int, default=2)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not (1 <= args.max_depth <= 20 and 1 <= args.max_states <= 20000 and 1 <= args.max_submissions <= 3):
        parser.error("bounds must satisfy depth 1..20, states 1..20000, submissions 1..3")
    positive = [explore(blocking, (), args.max_depth, args.max_states, args.max_submissions)
                for blocking in (False, True)]
    negative = [explore(False, (fault,), args.max_depth, args.max_states, args.max_submissions)
                for fault in ("timeout_release", "stop_pump", "ignore_status", "late_resubmit")]
    directed = directed_identity_checks()
    cancel_directed = directed_cancel_checks()
    closed_witnesses = directed_closed_witnesses()
    safe = all(r["counterexample"] is None for r in positive)
    sensitive = all(r["counterexample"] is not None for r in negative)
    complete_lifecycle_seen = all(r["native_closed_nodes"] > 0 for r in positive)
    result = {"schema": 1, "bounds": {"depth": args.max_depth, "states_per_run": args.max_states,
                "max_status_submissions": args.max_submissions, "initial_transfers_per_session": 2,
                "main_sessions": 1, "cancel_results_per_cycle": 1, "timeouts_per_control_cycle": 1},
              "positive_runs": positive, "negative_controls": negative, "directed_identity_checks": directed,
              "directed_cancel_checks": cancel_directed,
              "directed_closed_witnesses": closed_witnesses,
              "observed_positive_safety": safe, "all_negative_controls_detected": sensitive,
              "native_closed_reached_in_each_positive_run": complete_lifecycle_seen,
              "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "model_sha256": hashlib.sha256((HERE / "protocol_model.py").read_bytes()).hexdigest(),
              "limitations": ["Finite explicit method-call schedules, not all concurrency or C/JNI memory behavior.",
                  "A method is atomic: no internal check/submit, cancellation syscall, allocation or OS scheduling interleavings.",
                  "BFS includes oracle and one-shot scheduler ledger in deduplication; no partial-order reduction.",
                  "Pending/frontier states are not successes or deadlocks; no fairness or eventual-completion proof.",
                  "Native closed is distinct from Java owned-array drain; no Java copying Cartesian expansion.",
                  "BFS cancel_result is an atomic already-returned notification; explicit cancel-call stack interleaving is a separate directed check.",
                  "No native library, OS fd, physical device, clock, network or emulator executed."]}
    text = json.dumps(result, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8", newline="\n")
    print(text, end="")
    # A state cap can exclude a closed BFS node without demonstrating a defect.
    # Separate explicit closed witnesses are checked above; report frontier counts
    # and the BFS closed-reached flag honestly instead of equating cutoff to failure.
    return 0 if safe and sensitive else 1


if __name__ == "__main__":
    raise SystemExit(main())

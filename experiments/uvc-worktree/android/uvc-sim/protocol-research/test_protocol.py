"""Independent trace-ledger checks for the research-only protocol model.

The oracle reconstructs obligations from public events.  It never reads the
model's private counters or treats cancellation, a timeout, or worker death as
terminal completion.  No hardware, native library, network, or timing is used.
"""

from __future__ import annotations

from dataclasses import dataclass, field
import sys
import unittest

from protocol_model import ConnectionIdentity, ProtocolModel

NEGATIVE_DETECTIONS = []


@dataclass(frozen=True)
class Violation:
    code: str
    seq: int
    session_id: int
    detail: str


@dataclass
class Ledger:
    generation: object
    fd_token: str
    identity: dict
    closed: bool = True
    closing: bool = False
    context: bool = False
    native: bool = False
    pump: bool = False
    publishing: bool = False
    pending: dict = field(default_factory=dict)
    submissions: dict = field(default_factory=dict)
    blocking: set = field(default_factory=set)
    queued: set = field(default_factory=set)
    callbacks: dict = field(default_factory=dict)
    cancel_calls: dict = field(default_factory=dict)
    completed: set = field(default_factory=set)
    freed: set = field(default_factory=set)
    errors: list = field(default_factory=list)


class TraceOracle:
    """A resource ledger, deliberately separate from the model state machine."""

    PASSIVE_EVENTS = frozenset((
        "open_requested", "fd_open", "stop_repeated", "cancel_requested", "cancel_result",
        "blocking_cancel_unavailable", "control_timeout", "release_pending",
        "resubmit_rejected", "copy_failed", "delivery_rejected", "java_drain",
    ))

    def __init__(self, trace):
        self.sessions = {}
        self.frames = {}
        self.violations = []
        self._last_seq = -1
        for event in trace:
            self.observe(event)

    def fail(self, code, event, detail):
        self.violations.append(Violation(code, event.get("seq", -1),
                                         event.get("session_id", -1), detail))

    def outstanding(self, ledger):
        attempts = [f"{key[0]}#{key[1]}:{kind}" for key, kind in ledger.pending.items()]
        active = [f"callback={token}:{key[0]}#{key[1]}"
                  for token, key in ledger.callbacks.items()]
        unreleased = [f"unfreed={key[0]}#{key[1]}"
                      for key in ledger.submissions if key not in ledger.freed]
        calls = [f"cancel-call={token}:{key[0]}#{key[1]}"
                 for token, key in ledger.cancel_calls.items()]
        return ", ".join(attempts + active + calls + unreleased)

    def observe(self, event):
        kind = event["event"]
        seq = event["seq"]
        sid = event["session_id"]
        if seq <= self._last_seq:
            self.fail("LOG_ORDER", event, "event sequence did not increase")
        self._last_seq = seq
        if kind == "open_requested":
            if sid in self.sessions:
                self.fail("DUPLICATE_OWNER", event, "session acquired another fd owner")
            self.sessions[sid] = Ledger(event["generation"], event["fd_token"], event["identity"])
        if sid not in self.sessions:
            self.fail("UNKNOWN_SESSION", event, kind)
            return
        ledger = self.sessions[sid]
        if kind == "fd_open":
            if not ledger.closed:
                self.fail("DUPLICATE_OWNER", event, "session acquired another fd owner")
            for other in self.sessions.values():
                if other.fd_token == event["fd_token"] and not other.closed:
                    self.fail("FD_OWNER_OVERLAP", event, "numeric fd reused before old owner closed")
            ledger.closed = False
        if (event.get("generation") != ledger.generation or
                event.get("fd_token") != ledger.fd_token or
                event.get("identity") != ledger.identity or event.get("owner_session_id") != sid):
            self.fail("IDENTITY_CROSSTALK", event, "event does not belong to this fd generation")
        attempt = (event.get("transfer_id"), event.get("submission"))
        if kind == "context_create":
            ledger.context = True
        elif kind == "pump_start":
            ledger.pump = True
        elif kind == "native_open":
            ledger.native = True
        elif kind == "publication_enabled":
            if ledger.closing or ledger.closed:
                self.fail("PUBLICATION_AFTER_STOP", event, "publication enabled for revoked generation")
            ledger.publishing = True
        elif kind == "stop_requested":
            ledger.closing = True
        elif kind == "publication_revoked":
            ledger.publishing = False
        elif kind == "submit":
            if ledger.closing or ledger.closed:
                self.fail("SUBMIT_AFTER_STOP", event, repr(attempt))
            if not ledger.pump or not ledger.native:
                self.fail("SUBMIT_WITHOUT_BORROWER", event, repr(attempt))
            if attempt in ledger.pending or attempt in ledger.completed:
                self.fail("DUPLICATE_SUBMISSION", event, repr(attempt))
            ledger.pending[attempt] = event["kind"]
            ledger.submissions[attempt] = event["kind"]
            if event.get("blocking_call"):
                ledger.blocking.add(attempt)
        elif kind == "completion_queued":
            # Merely queued is intentionally not removed from pending.
            if attempt not in ledger.pending:
                self.fail("UNMATCHED_TERMINAL_QUEUE", event, repr(attempt))
            ledger.queued.add(attempt)
        elif kind == "callback_enter":
            token = event["token"]
            if attempt not in ledger.pending or attempt not in ledger.queued:
                self.fail("UNMATCHED_CALLBACK", event, repr(attempt))
            if attempt in ledger.blocking:
                self.fail("CALLBACK_FOR_BLOCKING_STACK", event, repr(attempt))
            if token in ledger.callbacks:
                self.fail("DUPLICATE_CALLBACK", event, token)
            ledger.pending.pop(attempt, None)
            ledger.queued.discard(attempt)
            ledger.callbacks[token] = attempt
        elif kind == "callback_exit":
            token = event["token"]
            if token not in ledger.callbacks:
                self.fail("UNMATCHED_CALLBACK_EXIT", event, token)
            completed = ledger.callbacks.pop(token, attempt)
            ledger.completed.add(completed)
        elif kind == "cancel_call_enter":
            token = event["token"]
            if (attempt not in ledger.submissions or attempt in ledger.freed or
                    not ledger.native or ledger.closed):
                self.fail("CANCEL_BORROW_AFTER_RELEASE", event, repr(attempt))
            if token in ledger.cancel_calls:
                self.fail("DUPLICATE_CANCEL_CALL", event, token)
            ledger.cancel_calls[token] = attempt
        elif kind == "cancel_call_return":
            token = event["token"]
            if ledger.cancel_calls.get(token) != attempt:
                self.fail("UNMATCHED_CANCEL_RETURN", event, token)
            ledger.cancel_calls.pop(token, None)
        elif kind == "blocking_call_return":
            if ledger.pending.get(attempt) != "control" or attempt not in ledger.blocking:
                self.fail("UNMATCHED_CONTROL_RETURN", event, repr(attempt))
            ledger.pending.pop(attempt, None)
            ledger.completed.add(attempt)
            if event.get("outcome") in ("ERROR", "NO_DEVICE"):
                ledger.errors.append(event)
        elif kind == "transfer_free":
            if attempt not in ledger.submissions:
                self.fail("FREE_UNSUBMITTED_TRANSFER", event, repr(attempt))
            if (attempt in ledger.pending or attempt in ledger.callbacks.values() or
                    attempt in ledger.cancel_calls.values()):
                self.fail("TRANSFER_FREE_LIVE", event, repr(attempt))
            if attempt in ledger.freed:
                self.fail("DOUBLE_TRANSFER_FREE", event, repr(attempt))
            ledger.freed.add(attempt)
        elif kind == "native_release":
            if ledger.pending or ledger.callbacks or ledger.cancel_calls:
                self.fail("NATIVE_RELEASE_LIVE", event, self.outstanding(ledger))
            if set(ledger.submissions) - ledger.freed:
                self.fail("NATIVE_RELEASE_UNFREED", event, self.outstanding(ledger))
            if ledger.publishing:
                self.fail("NATIVE_RELEASE_PUBLISHING", event, "publication still enabled")
            if not ledger.closing:
                self.fail("NATIVE_RELEASE_WITHOUT_STOP", event, "no stop request")
            if not ledger.native:
                self.fail("DOUBLE_NATIVE_RELEASE", event, "no acquired native handle")
            ledger.native = False
        elif kind == "pump_stop":
            if ledger.pending or ledger.callbacks or ledger.cancel_calls:
                self.fail("PUMP_STOP_LIVE", event, self.outstanding(ledger))
            ledger.pump = False
        elif kind == "context_release":
            if ledger.native or ledger.pump or ledger.pending or ledger.callbacks or ledger.cancel_calls:
                self.fail("CONTEXT_RELEASE_LIVE", event, self.outstanding(ledger))
            if not ledger.context:
                self.fail("DOUBLE_CONTEXT_RELEASE", event, "no acquired context")
            ledger.context = False
        elif kind == "fd_close":
            if (ledger.native or ledger.context or ledger.pump or ledger.pending or
                    ledger.callbacks or ledger.cancel_calls):
                self.fail("FD_CLOSE_BORROWED", event, self.outstanding(ledger))
            if ledger.closed:
                self.fail("DOUBLE_FD_CLOSE", event, "owner already closed")
            ledger.closed = True
        elif kind in ("async_error", "open_failed", "submit_failed"):
            ledger.errors.append(event)
        elif kind == "native_drain_confirmed":
            if not self.native_drained(sid):
                self.fail("FALSE_NATIVE_CONFIRMATION", event, self.outstanding(ledger))
        elif kind in ("frame_copied", "frame_owned"):
            frame_id = event["frame_id"]
            if frame_id in self.frames:
                self.fail("DUPLICATE_FRAME_OWNER", event, str(frame_id))
            if kind == "frame_copied":
                borrowed = ledger.callbacks.get(event.get("token"))
                if borrowed is None or ledger.submissions.get(borrowed) != "image" or not ledger.native:
                    self.fail("COPY_WITHOUT_BORROWED_FRAME", event, str(frame_id))
            self.frames[frame_id] = {"sid": sid, "zeroed": False, "released": False,
                                     "length": event["length"], "delivered": False}
        elif kind == "delivery_accepted":
            if ledger.closing or ledger.closed or not ledger.publishing:
                self.fail("DELIVERY_AFTER_STOP", event, str(event.get("frame_id")))
            frame_id = event["frame_id"]
            frame = self.frames.get(frame_id)
            if not frame or frame["sid"] != sid or frame["released"] or frame["delivered"]:
                self.fail("DELIVERY_OWNERSHIP", event, str(frame_id))
            else:
                frame["delivered"] = True
        elif kind == "frame_zeroed":
            frame_id = event["frame_id"]
            if frame_id not in self.frames:
                self.fail("UNOWNED_FRAME_ZERO", event, str(frame_id))
            else:
                if self.frames[frame_id]["sid"] != sid:
                    self.fail("FRAME_GENERATION_CROSSTALK", event, str(frame_id))
                if self.frames[frame_id]["released"]:
                    self.fail("DOUBLE_FRAME_RELEASE", event, str(frame_id))
                if event["length"] != self.frames[frame_id]["length"]:
                    self.fail("PARTIAL_FRAME_ZERO", event, str(frame_id))
                if not event.get("zeroed", False):
                    self.fail("FRAME_NOT_ZEROED", event, str(frame_id))
                self.frames[frame_id]["zeroed"] = bool(event.get("zeroed"))
                self.frames[frame_id]["released"] = True
        elif kind == "stop_confirmed":
            if not self.full_drain(sid):
                self.fail("FALSE_STOP_CONFIRMATION", event, self.outstanding(ledger))
        elif kind == "operation_succeeded":
            if not self.operation_succeeded(sid):
                self.fail("FALSE_OPERATION_SUCCESS", event, "resources pending or failure already observed")
        elif kind not in self.PASSIVE_EVENTS:
            self.fail("UNKNOWN_EVENT", event, kind)
        # cancel_requested/result, control_timeout and completion_queued never
        # discharge an obligation; rejection/error events are observations only.

    def native_drained(self, sid):
        ledger = self.sessions[sid]
        return (ledger.closed and not ledger.native and not ledger.context and
                not ledger.pump and not ledger.pending and not ledger.callbacks and not ledger.cancel_calls and
                not (set(ledger.submissions) - ledger.freed))

    def full_drain(self, sid):
        return (self.native_drained(sid) and
                all(frame["released"] and frame["zeroed"]
                    for frame in self.frames.values() if frame["sid"] == sid))

    def operation_succeeded(self, sid):
        return self.full_drain(sid) and not self.sessions[sid].errors


def identity(generation=1, revision=1):
    return ConnectionIdentity("device-A", generation, revision, f"grant-{generation}-{revision}")


class ProtocolHelpers:
    def opened(self, *, faults=(), generation=1, revision=1, fd="37"):
        model = ProtocolModel(faults=faults)
        sid = model.open(identity(generation, revision), fd)
        return model, sid

    def clean(self, model):
        oracle = TraceOracle(model.trace)
        self.assertEqual([], oracle.violations, oracle.violations)
        return oracle

    def finish_callback(self, model, sid, tid, submission, outcome="CANCELLED", **kwargs):
        model.queue_completion(sid, tid, outcome=outcome, submission=submission)
        token = model.callback_enter(sid, tid, submission=submission)
        return model.callback_exit(sid, token, **kwargs)

    def events(self, model, name, sid=None):
        return [e for e in model.trace if e["event"] == name and
                (sid is None or e["session_id"] == sid)]

    def rejected_without_mutation(self, model, action):
        before, trace = model.snapshot(), model.trace
        with self.assertRaises((ValueError, TypeError)):
            action()
        self.assertEqual(before, model.snapshot(), "rejected action mutated resource ownership")
        self.assertEqual(trace, model.trace, "rejected action emitted a side effect")
        self.clean(model)

    def borrowed_image(self, model, sid, tid="image"):
        cycle = model.submit(sid, tid, "image")
        model.queue_completion(sid, tid, submission=cycle)
        return model.callback_enter(sid, tid, submission=cycle)


class ProtocolChecks(ProtocolHelpers, unittest.TestCase):

    def test_cancel_stack_outlives_callback_and_defers_transfer_free(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "status", "status")
        model.request_stop(sid)
        cancel = model.cancel_call_enter(sid, "status", submission=cycle)
        self.finish_callback(model, sid, "status", cycle)
        self.assertEqual([], self.events(model, "transfer_free"))
        self.assertFalse(model.try_release(sid))
        oracle = self.clean(model)
        self.assertEqual({}, oracle.sessions[sid].pending)
        self.assertEqual({}, oracle.sessions[sid].callbacks)
        self.assertEqual({cancel: ("status", cycle)}, oracle.sessions[sid].cancel_calls)
        model.cancel_call_return(sid, cancel, "NOT_FOUND")
        self.assertEqual(1, len(self.events(model, "transfer_free")))
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_not_found_cancel_return_does_not_consume_queued_terminal(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "status", "status")
        model.request_stop(sid)
        cancel = model.cancel_call_enter(sid, "status", submission=cycle)
        model.queue_completion(sid, "status", submission=cycle)
        model.cancel_call_return(sid, cancel, "NOT_FOUND")
        self.assertFalse(model.try_release(sid))
        oracle = self.clean(model)
        self.assertEqual({}, oracle.sessions[sid].cancel_calls)
        self.assertEqual({("status", cycle): "status"}, oracle.sessions[sid].pending)
        token = model.callback_enter(sid, "status", submission=cycle)
        self.assertFalse(model.try_release(sid))
        model.callback_exit(sid, token)
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_missing_cancel_return_retains_resources_despite_terminal_exit(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "image", "image")
        model.request_stop(sid)
        cancel = model.cancel_call_enter(sid, "image", submission=cycle)
        self.finish_callback(model, sid, "image", cycle)
        for _ in range(3):
            self.assertFalse(model.try_release(sid))
            self.assertFalse(model.close(sid))
        self.rejected_without_mutation(model, lambda: model.cancel_result(
            sid, "image", "OK", submission=cycle))
        self.rejected_without_mutation(model, lambda: model.open(identity(2), "37"))
        oracle = self.clean(model)
        self.assertIn(cancel, oracle.sessions[sid].cancel_calls)
        self.assertTrue(oracle.sessions[sid].pump)
        self.assertFalse(oracle.native_drained(sid))
        self.assertEqual([], self.events(model, "fd_close"))

    def test_retired_cancel_call_cannot_borrow_reused_fd_owner(self):
        model, old = self.opened()
        cycle = model.submit(old, "status", "status")
        model.request_stop(old)
        cancel = model.cancel_call_enter(old, "status", submission=cycle)
        model.cancel_call_return(old, cancel, "ERROR")
        self.assertFalse(model.try_release(old))
        self.finish_callback(model, old, "status", cycle)
        self.assertTrue(model.try_release(old))
        new = model.open(identity(2), "37")
        before = model.snapshot()["sessions"][new]
        self.rejected_without_mutation(model, lambda: model.cancel_call_enter(old, "status", submission=cycle))
        self.rejected_without_mutation(model, lambda: model.cancel_call_return(old, cancel, "NOT_FOUND"))
        self.assertEqual(before, model.snapshot()["sessions"][new])
        self.assertTrue(model.close(new))
        self.assertTrue(self.clean(model).full_drain(new))

    def test_all_families_and_callback_exit_required_before_release(self):
        model, sid = self.opened()
        cycles = {kind: model.submit(sid, kind, kind) for kind in ("image", "status", "control")}
        model.request_stop(sid)
        self.assertFalse(model.try_release(sid))
        for kind in ("image", "status"):
            self.finish_callback(model, sid, kind, cycles[kind])
            self.assertFalse(model.try_release(sid))
        model.queue_completion(sid, "control", submission=cycles["control"])
        token = model.callback_enter(sid, "control", submission=cycles["control"])
        self.assertFalse(model.try_release(sid), "terminal receipt is not callback exit")
        model.callback_exit(sid, token)
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_not_found_with_terminal_queued_remains_live(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "status", "status")
        model.queue_completion(sid, "status", submission=cycle)
        model.request_stop(sid)
        model.cancel_result(sid, "status", "NOT_FOUND", submission=cycle)
        self.assertFalse(model.try_release(sid))
        self.assertEqual(1, len(self.clean(model).sessions[sid].pending))
        token = model.callback_enter(sid, "status", submission=cycle)
        self.assertFalse(model.try_release(sid))
        model.callback_exit(sid, token)
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_timeout_is_cancel_request_not_terminal(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "probe", "control")
        model.control_timeout(sid, "probe", submission=cycle)
        model.request_stop(sid)
        self.assertFalse(model.try_release(sid))
        self.assertTrue(self.events(model, "cancel_requested"))
        self.assertEqual(1, len(self.clean(model).sessions[sid].pending))
        self.finish_callback(model, sid, "probe", cycle)
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_permanently_missing_completion_retains_every_borrower(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "status", "status")
        model.request_stop(sid)
        for result in ("OK", "NOT_FOUND", "ERROR"):
            model.cancel_result(sid, "status", result, submission=cycle)
            self.assertFalse(model.try_release(sid))
        oracle = self.clean(model)
        self.assertFalse(oracle.native_drained(sid))
        self.assertFalse(oracle.full_drain(sid))
        self.assertTrue(oracle.sessions[sid].pump)
        for event in ("native_release", "context_release", "fd_close", "pump_stop"):
            self.assertEqual([], self.events(model, event))

    def test_closing_status_callback_cannot_resubmit(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "status", "status")
        model.queue_completion(sid, "status", submission=cycle)
        token = model.callback_enter(sid, "status", submission=cycle)
        model.request_stop(sid)
        self.assertIsNone(model.callback_exit(sid, token, resubmit=True))
        self.assertEqual(1, len(self.events(model, "submit")))
        self.assertTrue(self.events(model, "resubmit_rejected"))
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_failed_submit_creates_no_completion_obligation(self):
        model, sid = self.opened()
        self.assertIsNone(model.submit(sid, "failed", "image", fail=True))
        model.request_stop(sid)
        self.assertTrue(model.try_release(sid))
        self.assertEqual([], self.events(model, "submit"))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_repeated_stop_and_release_close_once(self):
        model, sid = self.opened()
        model.request_stop(sid)
        model.request_stop(sid)
        self.assertTrue(model.try_release(sid))
        self.assertTrue(model.close(sid))
        self.assertTrue(model.close(sid))
        model.request_stop(sid)
        self.assertTrue(model.try_release(sid))
        for event in ("native_release", "context_release", "fd_close", "pump_stop"):
            self.assertEqual(1, len(self.events(model, event)), event)
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_each_open_failure_cleans_only_acquired_resources(self):
        expected = {"open": (0, 0, 0), "context": (1, 0, 0), "native": (1, 1, 1)}
        for boundary, counts in expected.items():
            with self.subTest(boundary=boundary):
                model = ProtocolModel()
                sid = model.open(identity(), "37", fail_at=boundary)
                self.assertEqual(1, len(self.events(model, "open_failed")))
                self.assertEqual([], self.events(model, "native_release"))
                for event, count in zip(("fd_close", "context_release", "pump_stop"), counts):
                    self.assertEqual(count, len(self.events(model, event)), event)
                self.assertTrue(self.clean(model).native_drained(sid))
                self.assertTrue(model.close(sid))
                next_sid = model.open(identity(2), "37")
                self.assertNotEqual(sid, next_sid)
                self.assertTrue(model.close(next_sid))
                self.clean(model)

    def test_failed_submit_preserves_other_inflight_obligations(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "status", "status")
        self.assertIsNone(model.submit(sid, "image", "image", fail=True))
        self.assertFalse(model.try_release(sid))
        self.assertEqual({("status", cycle): "status"}, self.clean(model).sessions[sid].pending)
        self.finish_callback(model, sid, "status", cycle)
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).native_drained(sid))

    def test_stop_immediately_after_open_prevents_first_submit(self):
        # This is after synchronous open, not an in-flight open/start test.
        model, sid = self.opened()
        model.request_stop(sid)
        self.rejected_without_mutation(model, lambda: model.submit(sid, "image", "image"))
        self.assertTrue(model.close(sid))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_blocking_control_timeout_requires_actual_stack_return(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "probe", "control", blocking_call=True)
        model.control_timeout(sid, "probe", submission=cycle)
        self.assertFalse(model.try_release(sid))
        self.assertTrue(self.events(model, "blocking_cancel_unavailable"))
        self.assertEqual([], self.events(model, "cancel_requested"))
        self.rejected_without_mutation(model, lambda: model.cancel_result(
            sid, "probe", "OK", submission=cycle))
        self.rejected_without_mutation(model, lambda: model.queue_completion(
            sid, "probe", submission=cycle))
        self.assertEqual(1, len(self.clean(model).sessions[sid].pending))
        model.return_blocking_control(sid, "probe", submission=cycle)
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_blocking_control_error_preserves_failure_and_other_obligations(self):
        for outcome in ("ERROR", "NO_DEVICE"):
            with self.subTest(outcome=outcome):
                model, sid = self.opened()
                control = model.submit(sid, "probe", "control", blocking_call=True)
                status = model.submit(sid, "status", "status")
                model.return_blocking_control(sid, "probe", outcome, submission=control)
                self.assertFalse(model.try_release(sid))
                self.assertFalse(self.clean(model).sessions[sid].publishing)
                self.finish_callback(model, sid, "status", status)
                self.assertTrue(model.try_release(sid))
                oracle = self.clean(model)
                self.assertTrue(oracle.full_drain(sid))
                self.assertFalse(oracle.operation_succeeded(sid))

    def test_old_submission_timer_and_completions_do_not_clear_new_attempt(self):
        model, sid = self.opened()
        first = model.submit(sid, "probe", "control")
        second = self.finish_callback(model, sid, "probe", first, outcome="COMPLETED", resubmit=True)
        self.assertGreater(second, first)
        for action in (
            lambda: model.control_timeout(sid, "probe", submission=first),
            lambda: model.queue_completion(sid, "probe", submission=first),
            lambda: model.callback_enter(sid, "probe", submission=first),
            lambda: model.cancel_result(sid, "probe", "NOT_FOUND", submission=first),
        ):
            self.rejected_without_mutation(model, action)
        model.request_stop(sid)
        self.assertFalse(model.try_release(sid))
        self.assertEqual({("probe", second): "control"}, self.clean(model).sessions[sid].pending)
        self.finish_callback(model, sid, "probe", second)
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_status_resubmission_is_a_new_obligation(self):
        model, sid = self.opened()
        first = model.submit(sid, "status", "status")
        second = self.finish_callback(model, sid, "status", first, outcome="COMPLETED", resubmit=True)
        model.request_stop(sid)
        self.assertFalse(model.try_release(sid))
        self.assertEqual({("status", second): "status"}, self.clean(model).sessions[sid].pending)
        self.finish_callback(model, sid, "status", second)
        self.assertTrue(model.try_release(sid))
        self.assertEqual(2, len(self.events(model, "transfer_free")))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_copy_is_independent_of_borrowed_source_and_requires_callback(self):
        model, sid = self.opened()
        token = self.borrowed_image(model, sid)
        source = bytearray([31, 47, 255, 128, 9, 8, 7, 6])
        original = list(source)
        frame_id = model.copy_for_java(sid, token, source)
        model.callback_exit(sid, token)
        source[:] = b"\xa5" * len(source)
        self.assertEqual(original, model.snapshot()["java_frames"][frame_id]["pixels"])
        self.rejected_without_mutation(model, lambda: model.copy_for_java(sid, token, source))
        self.assertTrue(model.dispatch_java(frame_id))
        self.assertTrue(model.close(sid))
        self.assertTrue(self.clean(model).native_drained(sid))
        self.assertFalse(self.clean(model).full_drain(sid), "native release must not drain Java by assertion")
        self.assertEqual(1, model.drain_java(sid))
        self.assertEqual([0] * len(source), model.snapshot()["java_frames"][frame_id]["pixels"])
        self.assertEqual(bytearray(b"\xa5" * len(source)), source, "wipe must not touch borrowed source")
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_stop_during_borrowed_callback_rejects_and_zeros_late_copy(self):
        model, sid = self.opened()
        token = self.borrowed_image(model, sid)
        model.request_stop(sid)
        frame_id = model.copy_for_java(sid, token, bytes([1, 2, 3, 4, 5]))
        self.assertFalse(model.try_release(sid))
        self.assertFalse(model.dispatch_java(frame_id))
        row = model.snapshot()["java_frames"][frame_id]
        self.assertEqual([0] * 5, row["pixels"])
        self.assertEqual("released", row["state"])
        model.callback_exit(sid, token, resubmit=True)
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_copy_failure_creates_no_owned_frame_or_lost_callback(self):
        model, sid = self.opened()
        token = self.borrowed_image(model, sid)
        source = bytearray([0xAA, 0xBB, 0xCC])
        self.assertIsNone(model.copy_for_java(sid, token, source, fail=True))
        self.assertEqual({}, model.snapshot()["java_frames"])
        self.assertEqual(source, bytearray([0xAA, 0xBB, 0xCC]))
        model.request_stop(sid)
        self.assertFalse(model.try_release(sid))
        model.callback_exit(sid, token)
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_fd_reuse_keeps_stale_delivery_stop_and_cancel_with_old_owner(self):
        model, old = self.opened(generation=8, revision=3)
        token = self.borrowed_image(model, old)
        old_frame = model.copy_for_java(old, token, b"old-owned-pixels")
        status = model.submit(old, "status", "status")
        model.request_stop(old)
        model.callback_exit(old, token)
        self.finish_callback(model, old, "status", status)
        self.assertTrue(model.try_release(old))
        self.assertFalse(self.clean(model).full_drain(old))
        new = model.open(identity(9, 4), "37")
        new_pixels = bytearray(b"new-owned-pixels")
        self.assertTrue(model.delivery(new, new_pixels))
        self.rejected_without_mutation(model, lambda: model.callback_enter(old, "status", submission=status))
        self.rejected_without_mutation(model, lambda: model.callback_exit(old, token))
        before_new = model.snapshot()["sessions"][new]
        model.request_stop(old)
        model.cancel_result(old, "status", "NOT_FOUND", submission=status)
        self.assertTrue(model.close(old))
        self.assertFalse(model.dispatch_java(old_frame))
        self.assertEqual(before_new, model.snapshot()["sessions"][new])
        self.assertEqual(bytearray(b"new-owned-pixels"), new_pixels)
        self.assertEqual([0] * len(b"old-owned-pixels"),
                         model.snapshot()["java_frames"][old_frame]["pixels"])
        self.assertEqual(0, model.drain_java(old))
        self.assertEqual(1, len(self.events(model, "fd_close", old)))
        self.assertEqual([], self.events(model, "fd_close", new))
        self.assertTrue(self.clean(model).full_drain(old))
        self.assertTrue(model.close(new))
        self.assertEqual(1, model.drain_java(new))
        self.assertTrue(self.clean(model).full_drain(new))

    def test_stale_attachment_or_authorization_cannot_create_new_owner(self):
        model, old = self.opened(generation=5, revision=8)
        self.assertTrue(model.close(old))
        for stale in (identity(4, 9), identity(5, 7)):
            self.rejected_without_mutation(model, lambda stale=stale: model.open(stale, "37"))
        current = model.open(identity(5, 9), "37")
        self.assertTrue(model.delivery(current, bytearray(b"current")))
        before = model.snapshot()["sessions"][current]
        stale_pixels = bytearray(b"old-authorization")
        self.assertFalse(model.delivery(old, stale_pixels))
        self.assertEqual(bytearray(len(stale_pixels)), stale_pixels)
        self.assertEqual(before, model.snapshot()["sessions"][current])
        self.assertTrue(model.close(current))
        model.drain_java()
        self.assertTrue(self.clean(model).full_drain(current))

    def test_new_owner_blocked_while_old_status_completion_is_missing(self):
        model, old = self.opened()
        model.submit(old, "status", "status")
        model.request_stop(old)
        self.assertFalse(model.try_release(old))
        self.rejected_without_mutation(model, lambda: model.open(identity(2), "37"))
        self.assertEqual([], self.events(model, "fd_close"))
        self.assertEqual(1, len(self.events(model, "fd_open")))

    def test_owned_delivery_rejection_and_repeated_drain_zero_exactly_once(self):
        model, sid = self.opened()
        accepted = bytearray([4, 3, 2, 1, 0xFF, 0xEE])
        self.assertTrue(model.delivery(sid, accepted))
        self.rejected_without_mutation(model, lambda: model.delivery(sid, accepted))
        model.request_stop(sid)
        rejected = bytearray([1, 0x80, 0xFF])
        self.assertFalse(model.delivery(sid, rejected))
        self.assertEqual(bytearray(3), rejected)
        self.assertEqual(bytearray([4, 3, 2, 1, 0xFF, 0xEE]), accepted)
        self.assertEqual(1, model.drain_java(sid))
        self.assertEqual(bytearray(6), accepted)
        self.assertEqual(0, model.drain_java(sid))
        self.assertTrue(model.close(sid))
        self.assertEqual(2, len(self.events(model, "frame_zeroed")))
        self.assertTrue(self.clean(model).full_drain(sid))

    def test_async_error_visible_and_never_counts_other_obligations_as_complete(self):
        for outcome in ("ERROR", "NO_DEVICE"):
            with self.subTest(outcome=outcome):
                model, sid = self.opened()
                image = model.submit(sid, "image", "image")
                status = model.submit(sid, "status", "status")
                model.queue_completion(sid, "image", outcome=outcome, submission=image)
                token = model.callback_enter(sid, "image", submission=image)
                self.assertFalse(model.try_release(sid))
                model.callback_exit(sid, token)
                self.assertFalse(model.try_release(sid))
                self.assertEqual(1, len(self.events(model, "async_error")))
                self.assertEqual({("status", status): "status"}, self.clean(model).sessions[sid].pending)
                self.finish_callback(model, sid, "status", status)
                self.assertTrue(model.try_release(sid), "actual drain permits safe cleanup despite failure")
                oracle = self.clean(model)
                self.assertTrue(oracle.native_drained(sid))
                self.assertTrue(oracle.full_drain(sid), "safe reclamation is separate from operation outcome")
                self.assertFalse(oracle.operation_succeeded(sid), "failure must remain visible, not become success")
                self.assertEqual(1, len(oracle.sessions[sid].errors))

    def test_public_observations_are_copies_not_mutable_control_handles(self):
        model, sid = self.opened()
        pixels = bytearray([3, 4, 5])
        self.assertTrue(model.delivery(sid, pixels))
        before = model.snapshot()
        snapshot = model.snapshot()
        snapshot["sessions"][sid]["publishing"] = False
        snapshot["java_frames"][1]["pixels"][0] = 99
        trace = model.trace
        trace[0]["identity"]["authorization_revision"] = -1
        trace.clear()
        self.assertEqual(before, model.snapshot())
        self.assertEqual(bytearray([3, 4, 5]), pixels)
        self.assertTrue(model.close(sid))
        model.drain_java()
        self.assertTrue(self.clean(model).full_drain(sid))


class NegativeControls(ProtocolHelpers, unittest.TestCase):
    """The same oracle consumes correct and deliberately incorrect traces."""

    def assert_detected(self, model, code, detail=None):
        # Deliberately erase annotations; evidence must be the illegal ordering.
        trace = [{key: value for key, value in event.items() if key not in ("fault", "unsafe")}
                 for event in model.trace]
        oracle = TraceOracle(trace)
        matching = [v for v in oracle.violations if v.code == code]
        self.assertTrue(matching, f"missing {code}: {oracle.violations}")
        if detail is not None:
            self.assertTrue(any(detail in v.detail for v in matching), matching)
        self.assertTrue(all(v.seq > 0 and v.session_id > 0 for v in matching))
        NEGATIVE_DETECTIONS.append((self._testMethodName, matching[0]))
        return oracle

    def test_negative_timeout_release(self):
        for fault in ((), ("timeout_release",)):
            with self.subTest(fault=fault):
                model, sid = self.opened(faults=fault)
                cycle = model.submit(sid, "probe", "control", blocking_call=True)
                model.control_timeout(sid, "probe", submission=cycle)
                if fault:
                    self.assertTrue(self.events(model, "native_release"))
                    self.assert_detected(model, "NATIVE_RELEASE_LIVE", "probe#1:control")
                    self.assert_detected(model, "FD_CLOSE_BORROWED", "probe#1:control")
                else:
                    self.assertFalse(model.try_release(sid))
                    self.clean(model)


    def test_negative_stop_event_pump_before_completion(self):
        for fault in ((), ("stop_pump",)):
            with self.subTest(fault=fault):
                model, sid = self.opened(faults=fault)
                model.submit(sid, "status", "status")
                model.request_stop(sid)
                if fault:
                    self.assertTrue(self.events(model, "pump_stop"))
                    self.assert_detected(model, "PUMP_STOP_LIVE", "status#1:status")
                else:
                    self.clean(model)

    def test_negative_image_only_drain_ignores_status(self):
        for fault in ((), ("ignore_status",)):
            with self.subTest(fault=fault):
                model, sid = self.opened(faults=fault)
                image = model.submit(sid, "image", "image")
                model.submit(sid, "status", "status")
                model.request_stop(sid)
                self.finish_callback(model, sid, "image", image)
                result = model.try_release(sid)
                if fault:
                    self.assertTrue(result)
                    self.assert_detected(model, "NATIVE_RELEASE_LIVE", "status#1:status")
                    self.assert_detected(model, "FALSE_NATIVE_CONFIRMATION", "status#1:status")
                else:
                    self.assertFalse(result)
                    self.clean(model)

    def test_negative_callback_resubmits_after_stop(self):
        for fault in ((), ("late_resubmit",)):
            with self.subTest(fault=fault):
                model, sid = self.opened(faults=fault)
                cycle = model.submit(sid, "status", "status")
                model.queue_completion(sid, "status", submission=cycle)
                token = model.callback_enter(sid, "status", submission=cycle)
                model.request_stop(sid)
                model.callback_exit(sid, token, resubmit=True)
                if fault:
                    self.assertEqual(2, len(self.events(model, "submit")))
                    self.assert_detected(model, "SUBMIT_AFTER_STOP", "status")
                else:
                    self.assertEqual(1, len(self.events(model, "submit")))
                    self.clean(model)

class OracleMutationChecks(ProtocolHelpers, unittest.TestCase):
    """These are trace sensitivity checks, not extra model fault strategies."""

    def appended(self, model, event, **details):
        trace = model.trace
        record = dict(trace[-1])
        record.update(seq=record["seq"] + 1, event=event)
        record.update(details)
        trace.append(record)
        return TraceOracle(trace)

    def has(self, oracle, code, detail=None):
        violations = [v for v in oracle.violations if v.code == code]
        self.assertTrue(violations, f"missing {code}: {oracle.violations}")
        if detail is not None:
            self.assertTrue(any(detail in v.detail for v in violations), violations)

    def test_oracle_rejects_release_inside_entered_callback(self):
        model, sid = self.opened()
        token = self.borrowed_image(model, sid)
        model.request_stop(sid)
        self.clean(model)
        self.has(self.appended(model, "native_release"), "NATIVE_RELEASE_LIVE", token)
        self.has(self.appended(model, "fd_close"), "FD_CLOSE_BORROWED", token)

    def test_oracle_rejects_free_and_release_while_cancel_stack_active(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "status", "status")
        model.request_stop(sid)
        cancel = model.cancel_call_enter(sid, "status", submission=cycle)
        self.finish_callback(model, sid, "status", cycle)
        self.clean(model)
        self.has(self.appended(model, "transfer_free", transfer_id="status", submission=cycle),
                 "TRANSFER_FREE_LIVE")
        self.has(self.appended(model, "native_release"), "NATIVE_RELEASE_LIVE", cancel)
        self.has(self.appended(model, "pump_stop"), "PUMP_STOP_LIVE", cancel)
        self.has(self.appended(model, "context_release"), "CONTEXT_RELEASE_LIVE", cancel)
        self.has(self.appended(model, "fd_close"), "FD_CLOSE_BORROWED", cancel)

    def test_oracle_requires_transfer_free_after_terminal_and_callback_exit(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "status", "status")
        model.request_stop(sid)
        self.finish_callback(model, sid, "status", cycle)
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))
        trace = [event for event in model.trace if event["event"] != "transfer_free"]
        oracle = TraceOracle(trace)
        self.has(oracle, "NATIVE_RELEASE_UNFREED", "unfreed=status#1")
        self.has(oracle, "FALSE_NATIVE_CONFIRMATION")
        self.assertFalse(oracle.native_drained(sid))

    def test_oracle_rejects_false_full_drain_with_owned_java_queue(self):
        model, sid = self.opened()
        token = self.borrowed_image(model, sid)
        frame = model.copy_for_java(sid, token, b"pending Java owned bytes")
        model.callback_exit(sid, token)
        self.assertTrue(model.close(sid))
        self.assertEqual("pending", model.snapshot()["java_frames"][frame]["state"])
        self.assertTrue(self.clean(model).native_drained(sid))
        self.has(self.appended(model, "stop_confirmed"), "FALSE_STOP_CONFIRMATION")
        model.drain_java()
        oracle = self.appended(model, "stop_confirmed")
        self.assertEqual([], oracle.violations)

    def test_oracle_keeps_error_outcome_after_safe_cleanup(self):
        model, sid = self.opened()
        cycle = model.submit(sid, "status", "status")
        self.finish_callback(model, sid, "status", cycle, outcome="ERROR")
        self.assertTrue(model.try_release(sid))
        self.assertTrue(self.clean(model).full_drain(sid))
        self.has(self.appended(model, "operation_succeeded"), "FALSE_OPERATION_SUCCESS")

    def test_oracle_detects_stale_authorization_and_wrong_owner_event(self):
        model, sid = self.opened(generation=3, revision=4)
        self.assertTrue(model.close(sid))
        new = model.open(identity(4, 5), "37")
        self.clean(model)
        self.has(self.appended(model, "fd_close", owner_session_id=sid), "IDENTITY_CROSSTALK")
        self.has(self.appended(model, "fd_close", identity={
            "device_id": "device-A", "attachment_generation": 4,
            "authorization_revision": 4, "permission_token": "old-grant"}), "IDENTITY_CROSSTALK")
        self.assertNotEqual(sid, new)

    def test_oracle_fails_closed_on_unknown_event_name(self):
        model, sid = self.opened()
        self.has(self.appended(model, "unrecognized_terminal_success"), "UNKNOWN_EVENT")



if __name__ == "__main__":
    suite = unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__])
    result = unittest.TextTestRunner(stream=sys.stdout, verbosity=2).run(suite)
    for scenario, violation in NEGATIVE_DETECTIONS:
        print("NEGATIVE_DETECTED scenario={} code={} seq={} session={} detail={} labels_removed=true".format(
            scenario, violation.code, violation.seq, violation.session_id, violation.detail))
    negative_count = unittest.defaultTestLoader.loadTestsFromTestCase(NegativeControls).countTestCases()
    mutation_count = unittest.defaultTestLoader.loadTestsFromTestCase(OracleMutationChecks).countTestCases()
    print("PROTOCOL_TESTS={} MODEL_NEGATIVE_CONTROLS={} ORACLE_MUTATION_CHECKS={} RESULT={}".format(
        result.testsRun, negative_count, mutation_count, "PASS" if result.wasSuccessful() else "FAIL"))
    raise SystemExit(0 if result.wasSuccessful() else 1)

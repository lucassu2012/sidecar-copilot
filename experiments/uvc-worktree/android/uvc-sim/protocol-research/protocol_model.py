"""Original deterministic lifecycle protocol fixture, not a libusb/libuvc fix.

No threads, clocks, file descriptors, networking, native code or device operations.
Every completion and callback boundary is advanced explicitly by the caller.
Fault switches deliberately violate the protocol for independent negative controls.
"""
from copy import deepcopy
from dataclasses import asdict, dataclass, field
import json


@dataclass(frozen=True)
class ConnectionIdentity:
    device_id: str
    attachment_generation: int
    authorization_revision: int
    permission_token: str

    def __post_init__(self):
        for value in (self.device_id, self.permission_token):
            if not isinstance(value, str) or not value:
                raise ValueError("identity strings must be nonempty")
        for value in (self.attachment_generation, self.authorization_revision):
            if type(value) is not int or value < 0:
                raise ValueError("identity revisions must be nonnegative integers")


@dataclass
class _Transfer:
    kind: str
    submission: int
    blocking_call: bool = False
    in_flight: bool = True
    completion_queued: object = None
    callback_token: object = None
    cancel_requested: bool = False
    cancel_intent: bool = False
    cancel_results: list = field(default_factory=list)
    cancel_call_token: object = None
    cancel_call_count: int = 0
    callback_finished: bool = False
    blocking_call_active: bool = False
    freed: bool = False


@dataclass
class _Session:
    identity: ConnectionIdentity
    fd_token: str
    fd_owner_closed: bool = True
    context_created: bool = False
    context_released: bool = True
    native_open: bool = False
    native_released: bool = True
    pump_running: bool = False
    closing: bool = False
    publishing: bool = False
    failed: object = None
    transfers: dict = field(default_factory=dict)
    callbacks: dict = field(default_factory=dict)
    cancel_calls: dict = field(default_factory=dict)


class ProtocolModel:
    """Single active connection, explicit event scheduler, immutable authorization binding.

    Registered asynchronous obligations survive cancel and timer events. A callback
    consumes its queued completion, but its stack remains an obligation until exit.
    Synchronous control calls use a separate explicit return event, not a callback.
    """

    FAULTS = frozenset(("timeout_release", "stop_pump", "ignore_status", "late_resubmit"))
    KINDS = frozenset(("status", "image", "control"))
    OUTCOMES = frozenset(("COMPLETED", "CANCELLED", "NO_DEVICE", "ERROR"))

    def __init__(self, faults=()):
        self.faults = frozenset(faults)
        if not self.faults <= self.FAULTS:
            raise ValueError("unknown negative-control fault")
        self._sessions = {}
        self._active_sid = None
        self._next_sid = 1
        self._next_frame = 1
        self._frames = {}
        self._trace = []

    @property
    def trace(self):
        return deepcopy(self._trace)

    def _emit(self, event, sid, **details):
        session = self._session(sid)
        record = {"seq": len(self._trace) + 1, "event": event, "session_id": sid,
                  "generation": session.identity.attachment_generation,
                  "identity": asdict(session.identity), "fd_token": session.fd_token,
                  "owner_session_id": sid}
        record.update(details)
        self._trace.append(deepcopy(record))

    def _session(self, sid):
        if type(sid) is not int or sid not in self._sessions:
            raise ValueError("unknown session")
        return self._sessions[sid]

    def _transfer(self, sid, tid, submission):
        session = self._session(sid)
        transfer = session.transfers.get(tid)
        if type(submission) is not int or transfer is None or transfer.submission != submission:
            raise ValueError("unknown or stale submission")
        return session, transfer

    def _transfer_event(self, event, sid, tid, transfer, **details):
        self._emit(event, sid, transfer_id=tid, submission=transfer.submission,
                   kind=transfer.kind, blocking_call=transfer.blocking_call, **details)

    def open(self, identity, fd_token, fail_at=None):
        if not isinstance(identity, ConnectionIdentity):
            raise TypeError("identity must be ConnectionIdentity")
        if not isinstance(fd_token, str) or not fd_token:
            raise ValueError("fd_token must be a nonempty opaque string")
        if fail_at not in (None, "open", "context", "native"):
            raise ValueError("unknown open failure boundary")
        if self._active_sid is not None:
            raise ValueError("previous active session has not released its native ownership")
        for session in self._sessions.values():
            if session.fd_token == fd_token and not session.fd_owner_closed:
                raise ValueError("opaque fd already has a live owner")
            if session.identity.device_id == identity.device_id:
                prior = session.identity
                if identity.attachment_generation < prior.attachment_generation:
                    raise ValueError("stale attachment generation")
                if (identity.attachment_generation == prior.attachment_generation
                        and identity.authorization_revision < prior.authorization_revision):
                    raise ValueError("stale authorization revision")
        sid = self._next_sid
        self._next_sid += 1
        session = _Session(identity, fd_token)
        self._sessions[sid] = session
        self._active_sid = sid
        self._emit("open_requested", sid)
        if fail_at == "open":
            self._open_failed(sid, fail_at)
            return sid
        session.fd_owner_closed = False
        self._emit("fd_open", sid, ownership="sole-owner; native borrows opaque token")
        if fail_at == "context":
            self._open_failed(sid, fail_at)
            return sid
        session.context_created = True
        session.context_released = False
        self._emit("context_create", sid)
        session.pump_running = True
        self._emit("pump_start", sid)
        if fail_at == "native":
            self._open_failed(sid, fail_at)
            return sid
        session.native_open = True
        session.native_released = False
        self._emit("native_open", sid)
        session.publishing = True
        self._emit("publication_enabled", sid)
        return sid

    def _open_failed(self, sid, boundary):
        session = self._session(sid)
        session.failed = "open:" + boundary
        self._emit("open_failed", sid, boundary=boundary)
        self.request_stop(sid, "open failure")
        self.try_release(sid)

    def submit(self, sid, transfer_id, kind, fail=False, blocking_call=False):
        return self._submit(sid, transfer_id, kind, fail, blocking_call, False)

    def _submit(self, sid, tid, kind, fail, blocking_call, allow_closing):
        session = self._session(sid)
        if not isinstance(tid, str) or not tid or kind not in self.KINDS:
            raise ValueError("invalid transfer id/kind")
        if blocking_call and kind != "control":
            raise ValueError("only control models a blocking call")
        if session.native_released or not session.native_open or not session.pump_running:
            raise ValueError("cannot submit without live native context and event pump")
        if session.closing and not allow_closing:
            raise ValueError("cannot submit after stop")
        previous = session.transfers.get(tid)
        if previous and not previous.freed:
            raise ValueError("transfer already has a live submission")
        cycle = 1 if previous is None else previous.submission + 1
        if fail:
            self._emit("submit_failed", sid, transfer_id=tid, submission=cycle, kind=kind,
                       blocking_call=blocking_call, accepted=False)
            session.failed = "submit:" + tid
            self.request_stop(sid, "submit failure")
            self.try_release(sid)
            return None
        transfer = _Transfer(kind, cycle, bool(blocking_call), blocking_call_active=bool(blocking_call))
        session.transfers[tid] = transfer
        self._transfer_event("submit", sid, tid, transfer, closing=session.closing)
        return cycle

    def request_stop(self, sid, reason="user"):
        session = self._session(sid)
        if session.closing:
            self._emit("stop_repeated", sid, reason=reason)
            return
        was_enabled = session.publishing
        session.publishing = False
        session.closing = True
        self._emit("publication_revoked", sid, was_enabled=was_enabled)
        self._emit("stop_requested", sid, reason=reason)
        for tid, transfer in session.transfers.items():
            if transfer.freed:
                continue
            transfer.cancel_intent = True
            if transfer.blocking_call_active:
                self._transfer_event("blocking_cancel_unavailable", sid, tid, transfer)
            elif transfer.in_flight:
                transfer.cancel_requested = True
                self._transfer_event("cancel_requested", sid, tid, transfer)
        if "stop_pump" in self.faults and session.pump_running:
            session.pump_running = False
            self._emit("pump_stop", sid, fault="stop_pump")

    def cancel_result(self, sid, transfer_id, result="OK", *, submission):
        """Identity-only notice of an already returned atomic cancel operation.

        To model a native cancellation call that is still on the stack, callers
        must use cancel_call_enter/return instead. This notice borrows no resource.
        """
        _, transfer = self._transfer(sid, transfer_id, submission)
        if result not in ("OK", "NOT_FOUND", "ERROR"):
            raise ValueError("invalid cancel result")
        if not transfer.cancel_requested or transfer.blocking_call:
            raise ValueError("no asynchronous cancellation was requested")
        if transfer.cancel_call_token is not None:
            raise ValueError("explicit cancel call must return through cancel_call_return")
        transfer.cancel_results.append(result)
        self._transfer_event("cancel_result", sid, transfer_id, transfer, result=result,
                             resource_borrow=False, meaning="already returned atomic operation notice")

    def cancel_call_enter(self, sid, transfer_id, *, submission):
        session, transfer = self._transfer(sid, transfer_id, submission)
        if (transfer.freed or session.native_released or transfer.blocking_call
                or not transfer.cancel_requested or transfer.cancel_call_token is not None):
            raise ValueError("cancel call cannot borrow this submission")
        transfer.cancel_call_count += 1
        token = "cancel:{}/{}/{}/{}".format(sid, transfer_id, submission, transfer.cancel_call_count)
        transfer.cancel_call_token = token
        session.cancel_calls[token] = {"transfer_id": transfer_id, "submission": submission}
        self._transfer_event("cancel_call_enter", sid, transfer_id, transfer, token=token)
        return token

    def cancel_call_return(self, sid, token, result="OK"):
        session = self._session(sid)
        call = session.cancel_calls.get(token)
        if call is None or result not in ("OK", "NOT_FOUND", "ERROR"):
            raise ValueError("unknown cancel call or invalid return result")
        tid = call["transfer_id"]
        _, transfer = self._transfer(sid, tid, call["submission"])
        if transfer.cancel_call_token != token or transfer.freed:
            raise ValueError("cancel call does not own this submission")
        self._transfer_event("cancel_call_return", sid, tid, transfer, token=token, result=result)
        del session.cancel_calls[token]
        transfer.cancel_call_token = None
        self.cancel_result(sid, tid, result, submission=transfer.submission)
        self._retire_transfer(sid, tid, transfer)

    def _retire_transfer(self, sid, tid, transfer):
        if (not transfer.freed and transfer.callback_finished and not transfer.in_flight
                and not transfer.blocking_call_active and transfer.callback_token is None
                and transfer.cancel_call_token is None):
            transfer.freed = True
            self._transfer_event("transfer_free", sid, tid, transfer)

    def queue_completion(self, sid, transfer_id, outcome="COMPLETED", *, submission):
        session, transfer = self._transfer(sid, transfer_id, submission)
        if outcome not in self.OUTCOMES:
            raise ValueError("invalid completion outcome")
        if session.native_released or transfer.freed or not transfer.in_flight:
            raise ValueError("no pending submission for completion")
        if transfer.blocking_call:
            raise ValueError("blocking call ends only through return_blocking_control")
        if transfer.completion_queued is not None:
            raise ValueError("completion already queued")
        transfer.completion_queued = outcome
        self._transfer_event("completion_queued", sid, transfer_id, transfer, outcome=outcome)

    def callback_enter(self, sid, transfer_id, *, submission):
        session, transfer = self._transfer(sid, transfer_id, submission)
        if not session.pump_running or session.native_released:
            raise ValueError("event pump is not available")
        if transfer.freed or transfer.blocking_call or transfer.completion_queued is None:
            raise ValueError("no queued asynchronous completion")
        if transfer.callback_token is not None:
            raise ValueError("callback already entered")
        token = "session:{}/transfer:{}/submission:{}".format(sid, transfer_id, submission)
        outcome = transfer.completion_queued
        transfer.completion_queued = None
        transfer.in_flight = False
        transfer.callback_token = token
        session.callbacks[token] = {"transfer_id": transfer_id, "submission": submission, "outcome": outcome}
        self._transfer_event("callback_enter", sid, transfer_id, transfer, token=token, outcome=outcome)
        if outcome in ("NO_DEVICE", "ERROR"):
            session.failed = "async:" + outcome
            self._transfer_event("async_error", sid, transfer_id, transfer, outcome=outcome)
            self.request_stop(sid, "asynchronous " + outcome)
        return token

    def callback_exit(self, sid, token, resubmit=False):
        session = self._session(sid)
        callback = session.callbacks.get(token)
        if callback is None:
            raise ValueError("unknown or exited callback token")
        tid = callback["transfer_id"]
        _, transfer = self._transfer(sid, tid, callback["submission"])
        if transfer.callback_token != token or transfer.freed:
            raise ValueError("callback does not own this submission")
        self._transfer_event("callback_exit", sid, tid, transfer, token=token, outcome=callback["outcome"])
        del session.callbacks[token]
        transfer.callback_token = None
        transfer.callback_finished = True
        self._retire_transfer(sid, tid, transfer)
        if not resubmit:
            return None
        if session.closing and "late_resubmit" not in self.faults:
            self._transfer_event("resubmit_rejected", sid, tid, transfer, reason="closing")
            return None
        if not transfer.freed:
            self._transfer_event("resubmit_rejected", sid, tid, transfer, reason="cancel call still active")
            return None
        return self._submit(sid, tid, transfer.kind, False, False, "late_resubmit" in self.faults)

    def control_timeout(self, sid, transfer_id, *, submission):
        _, transfer = self._transfer(sid, transfer_id, submission)
        if transfer.kind != "control" or transfer.freed:
            raise ValueError("no active control obligation for timeout")
        self._transfer_event("control_timeout", sid, transfer_id, transfer,
                             meaning="requester deadline only; not completion")
        self.request_stop(sid, "control timeout")
        if "timeout_release" in self.faults:
            self._release(sid, forced=True, fault="timeout_release")

    def return_blocking_control(self, sid, transfer_id, outcome="COMPLETED", *, submission):
        session, transfer = self._transfer(sid, transfer_id, submission)
        if outcome not in self.OUTCOMES:
            raise ValueError("invalid blocking return outcome")
        if (transfer.kind != "control" or not transfer.blocking_call_active
                or transfer.freed or session.native_released):
            raise ValueError("no live synchronous control stack")
        transfer.blocking_call_active = False
        transfer.in_flight = False
        self._transfer_event("blocking_call_return", sid, transfer_id, transfer, outcome=outcome)
        transfer.callback_finished = True
        self._retire_transfer(sid, transfer_id, transfer)
        if outcome in ("ERROR", "NO_DEVICE"):
            session.failed = "blocking:" + outcome
            self.request_stop(sid, "blocking control " + outcome)

    def try_release(self, sid):
        session = self._session(sid)
        if not session.closing:
            raise ValueError("stop must revoke publication before release")
        blockers = [tid for tid, transfer in session.transfers.items()
                    if not transfer.freed and not ("ignore_status" in self.faults and transfer.kind == "status")]
        if blockers:
            self._emit("release_pending", sid, transfer_ids=sorted(blockers))
            return False
        self._release(sid, fault="ignore_status" if "ignore_status" in self.faults else None)
        return True

    def _release(self, sid, forced=False, fault=None):
        session = self._session(sid)
        for tid, transfer in session.transfers.items():
            if transfer.freed:
                continue
            if not forced and not (fault == "ignore_status" and transfer.kind == "status"):
                raise ValueError("attempt to release live native obligation")
            self._transfer_event("transfer_free", sid, tid, transfer, unsafe=True, fault=fault)
            if transfer.callback_token in session.callbacks:
                del session.callbacks[transfer.callback_token]
            if transfer.cancel_call_token in session.cancel_calls:
                del session.cancel_calls[transfer.cancel_call_token]
            transfer.callback_token = None
            transfer.cancel_call_token = None
            transfer.in_flight = False
            transfer.completion_queued = None
            transfer.blocking_call_active = False
            transfer.freed = True
        if not session.native_released:
            session.native_released = True
            session.native_open = False
            self._emit("native_release", sid, fault=fault)
        if session.pump_running:
            session.pump_running = False
            self._emit("pump_stop", sid, fault=fault)
        if not session.context_released:
            session.context_released = True
            self._emit("context_release", sid, fault=fault)
        if not session.fd_owner_closed:
            session.fd_owner_closed = True
            self._emit("fd_close", sid, fault=fault)
        if self._active_sid == sid:
            self._active_sid = None
        self._emit("native_drain_confirmed", sid, fault=fault,
                   java_owned_remaining=sum(1 for f in self._frames.values() if f["session_id"] == sid and f["state"] != "released"))

    def close(self, sid):
        self.request_stop(sid, "close")
        return self.try_release(sid)

    def _new_frame(self, sid, pixels, state):
        frame_id = self._next_frame
        self._next_frame += 1
        self._frames[frame_id] = {"session_id": sid, "state": state, "pixels": pixels, "zeroed": False}
        return frame_id

    def copy_for_java(self, sid, cbtoken, pixels, fail=False):
        session = self._session(sid)
        callback = session.callbacks.get(cbtoken)
        if callback is None:
            raise ValueError("copy requires an active borrowed callback")
        transfer = session.transfers[callback["transfer_id"]]
        if transfer.kind != "image" or transfer.freed or callback["outcome"] != "COMPLETED":
            raise ValueError("copy requires a successfully completed image callback")
        if not isinstance(pixels, (bytes, bytearray)):
            raise TypeError("pixels must be bytes or bytearray")
        if fail:
            self._emit("copy_failed", sid, token=cbtoken, length=len(pixels), ownership_created=False)
            return None
        owned = bytearray(pixels)
        frame_id = self._new_frame(sid, owned, "pending")
        self._emit("frame_copied", sid, token=cbtoken, frame_id=frame_id, length=len(owned), owner="java")
        return frame_id

    def _zero_frame(self, frame_id, reason):
        frame = self._frames[frame_id]
        if frame["state"] == "released":
            return
        pixels = frame["pixels"]
        pixels[:] = b"\0" * len(pixels)
        frame["zeroed"] = True
        frame["state"] = "released"
        self._emit("frame_zeroed", frame["session_id"], frame_id=frame_id, length=len(pixels),
                   zeroed=all(value == 0 for value in pixels), reason=reason)

    def dispatch_java(self, delivery_id):
        frame = self._frames.get(delivery_id)
        if frame is None or frame["state"] != "pending":
            raise ValueError("delivery must identify pending owned pixels")
        sid = frame["session_id"]
        session = self._session(sid)
        accepted = (sid == self._active_sid and session.publishing and not session.closing
                    and not session.native_released and not session.fd_owner_closed)
        if accepted:
            frame["state"] = "accepted"
            self._emit("delivery_accepted", sid, frame_id=delivery_id, length=len(frame["pixels"]))
            return True
        self._emit("delivery_rejected", sid, frame_id=delivery_id, length=len(frame["pixels"]), reason="inactive or revoked identity")
        self._zero_frame(delivery_id, "rejected stale/revoked delivery")
        return False

    def delivery(self, sid, pixels):
        """Convenience owned-array fixture entry; not a native borrowed-buffer API."""
        self._session(sid)
        if not isinstance(pixels, bytearray):
            raise TypeError("owned delivery requires bytearray")
        if any(frame["pixels"] is pixels and frame["state"] != "released" for frame in self._frames.values()):
            raise ValueError("array already owned by a queued delivery")
        frame_id = self._new_frame(sid, pixels, "pending")
        self._emit("frame_owned", sid, frame_id=frame_id, length=len(pixels), owner="java")
        return self.dispatch_java(frame_id)

    def drain_java(self, sid=None):
        if sid is not None:
            self._session(sid)
        count = 0
        for frame_id, frame in self._frames.items():
            if frame["state"] != "released" and (sid is None or frame["session_id"] == sid):
                self._zero_frame(frame_id, "java queue drain")
                self._emit("java_drain", frame["session_id"], frame_id=frame_id)
                count += 1
        return count

    def snapshot(self):
        sessions = {}
        for sid, session in self._sessions.items():
            row = asdict(session)
            row["owner_session_id"] = sid
            row["native_obligations"] = sorted(tid for tid, t in session.transfers.items() if not t.freed)
            row["native_drain_confirmed"] = (session.closing and session.native_released
                                               and session.context_released and session.fd_owner_closed
                                               and not row["native_obligations"] and not session.callbacks
                                               and not session.cancel_calls)
            sessions[sid] = row
        frames = {frame_id: {**frame, "pixels": list(frame["pixels"])} for frame_id, frame in self._frames.items()}
        return deepcopy({"active_session_id": self._active_sid, "sessions": sessions,
                         "java_frames": frames, "faults": sorted(self.faults),
                         "next_session_id": self._next_sid, "next_frame_id": self._next_frame})

    def clone(self):
        return deepcopy(self)

    def state_key(self):
        return json.dumps(self.snapshot(), sort_keys=True, separators=(",", ":"))

"""Existing T10/T11/T12/T15/T16/T25/T33/T39: real sockets, synthetic capture.

These exercise two SQLite stores and the actual sender/receiver. Fault wrappers
are named explicitly; none imply phone, power-loss, or production cloud acceptance.
"""
import json
import socket
import sqlite3
import threading
import time
from contextlib import contextmanager

import pytest
import uvicorn
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from sidecar.api import create_app
from sidecar.core import Sidecar, SimulationClock, Blocked
from sidecar.transfer_contract import Binding, TransferError, UncertainTransfer, MAX_TIME, BUFFER_NS
from sidecar.transfer_http import LoopbackTransport, create_receiver_app
from sidecar.transfer_queue import UploadCoordinator, ns
from sidecar.transfer_receiver import Receiver

TOKEN = 'synthetic-transfer-queue-token-' + 'a' * 32
BINDING = Binding('alice', 'local', 'synthetic-device', 1, 'development', 'opaque-receiver', 'local-key')


@contextmanager
def running(app):
    sock = socket.socket()
    sock.bind(('127.0.0.1', 0))
    origin = f'http://127.0.0.1:{sock.getsockname()[1]}'
    server = uvicorn.Server(uvicorn.Config(app, log_level='critical', access_log=False, ws='none', timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, kwargs={'sockets': [sock]}, daemon=True)
    thread.start()
    try:
        deadline = time.monotonic() + 10
        while not server.started and thread.is_alive() and time.monotonic() < deadline:
            time.sleep(.01)
        assert server.started
        yield origin
    finally:
        server.should_exit = True
        thread.join(5)
        sock.close()
        assert not thread.is_alive()


class Backend:
    def __init__(self, receiver):
        self.receiver, self.calls, self.failure, self.before_status = receiver, [], None, None

    def __getattr__(self, name):
        def call(*args):
            self.calls.append(name)
            if self.failure:
                raise self.failure
            if name == 'status' and self.before_status:
                hook, self.before_status = self.before_status, None
                hook()
            return getattr(self.receiver, name)(*args)
        return call


class DropCommitAck:
    """Actually truncates the HTTP response after the durable receiver commit."""
    def __init__(self, app):
        self.app, self.enabled, self.dropped = app, False, False

    async def __call__(self, scope, receive, send):
        cut = self.enabled and not self.dropped and scope.get('path', '').endswith('/commit')
        async def send_or_cut(message):
            if cut and message['type'] == 'http.response.start':
                message = dict(message, headers=[(b'content-length', b'100000')])
            if cut and message['type'] == 'http.response.body':
                self.dropped = True
                raise ConnectionResetError('synthetic lost commit response')
            await send(message)
        await self.app(scope, receive, send_or_cut)


class TransportProxy:
    def __init__(self, delegate):
        self.delegate, self.origin = delegate, delegate.origin

    def __getattr__(self, name):
        return getattr(self.delegate, name)


@pytest.fixture(scope='module')
def key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture
def h(tmp_path, key):
    class Harness:
        def reopen(self):
            self.a.close()
            self.a = Sidecar(tmp_path / 'sender', self.clock, key)
            self.a.unlocked = True
            self.a.network(trusted=True, unmetered=True)
            self.a.resume_uploads('alice', self.sid, consent=True)
            self.q = UploadCoordinator(self.a, self.t, BINDING)

        def enqueue(self, **kw):
            return self.q.enqueue(self.fid, confirmed_import=True, byte_limit=kw.get('budget', 2_000_000), max_attempts=kw.get('attempts', 5))

    x = Harness()
    x.clock = SimulationClock()
    x.a = Sidecar(tmp_path / 'sender', x.clock, key)
    x.a.unlocked = True
    x.sid = x.a.create_session('alice', 'in_person')
    x.gen = x.a.start('alice', x.sid, consent=True)
    x.clock.advance(5)
    x.fid = x.a.capture('alice', x.sid, x.gen, stream='mic', kind='audio', start=0, end=5, synthetic_text='synthetic data ' * 10000)
    x.a.network(trusted=True, unmetered=True)
    x.receiver = Receiver(tmp_path / 'receiver.sqlite', now_utc_ns=lambda: ns(x.clock.utc))
    x.backend = Backend(x.receiver)
    x.cut = DropCommitAck(create_receiver_app(x.backend, {TOKEN: BINDING}))
    try:
        with running(x.cut) as origin:
            x.t = LoopbackTransport(origin, TOKEN)
            x.q = UploadCoordinator(x.a, x.t, BINDING)
            yield x
    finally:
        x.a.close()
        x.receiver.close()


def test_real_ciphertext_parts_readback_and_processing_wait(h):
    oid = h.enqueue()
    h.clock.advance(900)
    result = h.q.step(oid)
    assert result['state'] == 'committed' and result['remote_verified'] == 1
    assert 0 < result['known_sent_bytes'] == result['charged_bytes'] <= result['byte_limit']
    assert h.backend.calls.count('put_part') == 3
    assert h.t.read_object(oid, 0) == h.a._ciphertext(h.a.frame('alice', h.fid))
    assert h.a.search('alice', 'synthetic') == [] and result['processing_ready'] is False
    assert h.enqueue() == oid
    with pytest.raises(Blocked, match='real transfer'):
        h.a.upload('alice', h.fid, h.a.network_generation)
    h.a.end('alice', h.sid)
    job = h.a.request_job('alice', h.sid)
    with pytest.raises(Blocked, match='processing output'):
        h.a.finish_job('alice', job)
    with pytest.raises(Blocked, match='processing output'):
        h.a.correct('alice', h.fid, 0, 'must not become a transcript')


@pytest.mark.parametrize('condition', ['immature', 'locked', 'paused', 'global_pause', 'network', 'metered', 'unknown_network', 'boot', 'clock'])
def test_all_preflight_gates_send_zero_http(h, condition):
    oid = h.enqueue()
    h.clock.advance(899 if condition == 'immature' else 900)
    if condition == 'locked': h.a.unlocked = False
    if condition == 'paused': h.a.pause('alice', h.sid)
    if condition == 'global_pause': h.a.set_privacy_pause(True)
    if condition == 'network': h.a.network(trusted=False, unmetered=True)
    if condition == 'metered': h.a.network(trusted=True, unmetered=False)
    if condition == 'unknown_network': h.a.network(trusted=None, unmetered=True)
    if condition == 'boot': h.clock.boot = 'new-boot'
    if condition == 'clock': h.clock.trusted = False
    with pytest.raises(Blocked): h.q.step(oid)
    assert h.backend.calls == []
    assert h.q.status(oid)['charged_bytes'] == 0


def test_lost_commit_response_restart_reconciles_before_retry(h):
    oid = h.enqueue()
    h.clock.advance(900)
    h.cut.enabled = True
    with pytest.raises(UncertainTransfer): h.q.step(oid)
    assert h.cut.dropped and h.backend.calls.count('commit') == 1
    assert not h.q.status(oid)['remote_verified']
    before = h.q.status(oid)['charged_bytes']
    h.reopen()
    with pytest.raises(Blocked, match='backoff'): h.q.step(oid)
    h.clock.advance(3)
    assert h.q.step(oid)['remote_verified'] == 1
    assert h.backend.calls.count('init') == h.backend.calls.count('commit') == 1
    assert h.backend.calls.count('put_part') == 3
    assert h.q.status(oid)['charged_bytes'] > before


def test_persistent_part_ack_resumes_missing_parts_only(h):
    class InterruptPart(TransportProxy):
        failed = False
        def put_part(self, oid, index, data, generation, **kw):
            result = self.delegate.put_part(oid, index, data, generation, **kw)
            if not self.failed:
                self.failed = True
                raise UncertainTransfer('synthetic lost part response')
            return result
    h.q = UploadCoordinator(h.a, InterruptPart(h.t), BINDING)
    oid = h.enqueue()
    h.clock.advance(900)
    with pytest.raises(UncertainTransfer): h.q.step(oid)
    h.reopen()
    h.clock.advance(3)
    assert h.q.step(oid)['remote_verified']
    assert h.backend.calls.count('put_part') == 3


def test_delete_wins_at_socket_handoff_and_cleanup_survives_compaction(h):
    entered, proceed = threading.Event(), threading.Event()
    class HeldPart(TransportProxy):
        def put_part(self, *args, **kw):
            entered.set()
            assert proceed.wait(5)
            return self.delegate.put_part(*args, **kw)
    h.q = UploadCoordinator(h.a, HeldPart(h.t), BINDING)
    oid = h.enqueue(); h.clock.advance(900)
    failures = []
    def run():
        try: h.q.step(oid)
        except BaseException as exc: failures.append(exc)
    worker = threading.Thread(target=run)
    worker.start()
    try:
        assert entered.wait(5)
        receipt = h.a.delete_range('alice', h.sid, 1, 2)
        assert receipt['remote_cleanup_pending']
        assert h.q.status(oid)['possible_egress']  # Earlier metadata was sent.
    finally:
        proceed.set(); worker.join(5)
    assert not worker.is_alive() and len(failures) == 1 and isinstance(failures[0], Blocked)
    assert h.backend.calls.count('put_part') == 0
    h.a.compact()
    assert h.a.db.execute('SELECT 1 FROM frames WHERE id=?', (h.fid,)).fetchone() is None
    h.reopen()
    assert h.q.sync_deletions(h.sid) == 1
    assert not h.q.status(oid)['remote_deletion_pending']
    with pytest.raises(TransferError): h.t.commit(oid, 1)
    assert h.q.status(oid)['state'] == 'cancelled'


def test_delete_outbox_insert_failure_rolls_back_barrier_and_outbox(h):
    oid = h.enqueue()
    h.a.db.execute("CREATE TRIGGER fail_outbox BEFORE INSERT ON upload_deletion_outbox BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
    with pytest.raises(sqlite3.IntegrityError): h.a.delete_range('alice', h.sid, 1, 2)
    assert h.a.db.execute('SELECT COUNT(*) FROM tombstones').fetchone()[0] == 0
    assert h.a.session('alice', h.sid)['deletion_generation'] == 0
    assert h.a.frame('alice', h.fid)['wrapped_key'] is not None
    assert h.q.status(oid)['state'] == 'queued'


def test_delete_actual_sqlite_commit_failure_never_acknowledges_barrier(h):
    oid = h.enqueue()
    h.a.db.executescript('''
        CREATE TABLE transfer_parent(id INTEGER PRIMARY KEY);
        CREATE TABLE transfer_child(id INTEGER REFERENCES transfer_parent(id) DEFERRABLE INITIALLY DEFERRED);
        CREATE TRIGGER transfer_commit_fail AFTER INSERT ON upload_deletion_outbox
        BEGIN INSERT INTO transfer_child VALUES (1); END;
    ''')
    statements = []
    h.a.db.set_trace_callback(statements.append)
    try:
        with pytest.raises(sqlite3.IntegrityError): h.a.delete_range('alice', h.sid, 1, 2)
    finally:
        h.a.db.set_trace_callback(None)
    assert [s for s in statements if s in ('COMMIT', 'ROLLBACK')] == ['COMMIT', 'ROLLBACK']
    assert h.a.db.execute('SELECT COUNT(*) FROM tombstones').fetchone()[0] == 0
    assert h.a.db.execute('SELECT COUNT(*) FROM upload_deletion_outbox').fetchone()[0] == 0
    assert h.a.session('alice', h.sid)['deletion_generation'] == 0
    assert h.a.frame('alice', h.fid)['wrapped_key'] is not None
    assert h.q.status(oid)['state'] == 'queued'


def test_budget_actual_commit_failure_prevents_socket_handoff(h):
    oid = h.enqueue(); h.clock.advance(900)
    h.a.db.executescript('''
        CREATE TABLE charge_parent(id INTEGER PRIMARY KEY);
        CREATE TABLE charge_child(id INTEGER REFERENCES charge_parent(id) DEFERRABLE INITIALLY DEFERRED);
        CREATE TRIGGER charge_commit_fail AFTER UPDATE OF charged_bytes ON upload_intents
        WHEN NEW.charged_bytes > OLD.charged_bytes
        BEGIN INSERT INTO charge_child VALUES (1); END;
    ''')
    statements = []
    h.a.db.set_trace_callback(statements.append)
    try:
        with pytest.raises(sqlite3.IntegrityError): h.q.step(oid)
    finally:
        h.a.db.set_trace_callback(None)
    assert [s for s in statements if s in ('COMMIT', 'ROLLBACK')][-2:] == ['COMMIT', 'ROLLBACK']
    assert h.backend.calls == []
    assert h.q.status(oid)['charged_bytes'] == 0


def test_remote_cleanup_retry_keeps_barrier(h):
    class LostDelete(TransportProxy):
        def delete_range(self, *args, **kw):
            self.delegate.delete_range(*args, **kw)
            raise UncertainTransfer('synthetic lost deletion receipt')
    oid = h.enqueue(); h.clock.advance(900); h.q.step(oid)
    h.a.delete_range('alice', h.sid, 1, 2)
    h.q = UploadCoordinator(h.a, LostDelete(h.t), BINDING)
    with pytest.raises(UncertainTransfer): h.q.sync_deletions(h.sid)
    assert h.q.status(oid)['remote_deletion_pending']
    with pytest.raises(TransferError): h.t.read_object(oid, 1)
    h.reopen()
    assert h.q.sync_deletions(h.sid) == 1
    assert h.backend.calls.count('delete_range') == 1


def test_readback_corruption_never_marks_uploaded(h):
    class BadReadback(TransportProxy):
        def read_object(self, *args, **kw):
            return self.delegate.read_object(*args, **kw) + b'wrong'
    h.q = UploadCoordinator(h.a, BadReadback(h.t), BINDING)
    oid = h.enqueue(); h.clock.advance(900)
    with pytest.raises(UncertainTransfer): h.q.step(oid)
    assert not h.q.status(oid)['remote_verified']
    assert not h.a.frame('alice', h.fid)['uploaded']


def test_budget_and_finite_attempts_cannot_reset_by_enqueue(h):
    oid = h.enqueue(budget=1, attempts=2); h.clock.advance(900)
    for _ in range(2):
        with pytest.raises(Blocked, match='budget'): h.q.step(oid)
        assert h.enqueue(budget=9_000_000) == oid
    with pytest.raises(Blocked, match='finite retry'): h.q.step(oid)
    assert h.q.status(oid)['byte_limit'] == 1
    assert h.q.status(oid)['charged_bytes'] == 0 and h.backend.calls == []


@pytest.mark.parametrize('status', [401, 403, 409, 410, 422])
def test_terminal_receiver_rejection_is_not_automatically_retried(h, status):
    oid = h.enqueue(); h.clock.advance(900)
    h.backend.failure = TransferError('test_rejection', status)
    with pytest.raises(TransferError): h.q.step(oid)
    calls = len(h.backend.calls)
    h.backend.failure = None
    with pytest.raises(Blocked): h.q.step(oid)
    assert len(h.backend.calls) == calls and h.q.status(oid)['state'] == 'denied'


def test_429_retry_after_and_5xx_have_finite_backoff(h):
    oid = h.enqueue(attempts=2); h.clock.advance(900)
    error = TransferError('rate_limited', 429); error.retry_after = 15
    h.backend.failure = error
    with pytest.raises(TransferError): h.q.step(oid)
    assert h.q.status(oid)['next_attempt_ns'] >= ns(h.clock.utc + 15)
    with pytest.raises(Blocked, match='backoff'): h.q.step(oid)
    h.clock.advance(15)
    h.backend.failure = TransferError('unavailable', 503)
    with pytest.raises(TransferError): h.q.step(oid)
    h.clock.advance(60)
    with pytest.raises(Blocked, match='finite retry'): h.q.step(oid)


def test_process_ownership_and_durable_barrier_recheck(h, key):
    with pytest.raises(RuntimeError, match='owned'): Sidecar(h.a.root, h.clock, key)
    old = h.a.frame('alice', h.fid)['wrapped_key']
    h.a.delete_range('alice', h.sid, 0, 1)
    # Artificial row rollback: independent barrier still rejects managed reads.
    with h.a.db:
        h.a.db.execute('UPDATE frames SET deleted=0,wrapped_key=? WHERE id=?', (old, h.fid))
    with pytest.raises(Blocked, match='durable deletion'): h.a.read('alice', h.fid)


def test_point_note_half_open_barrier(h):
    note = h.a.add_note('alice', h.sid, 'synthetic point')
    wrapped = h.a.frame('alice', note)['wrapped_key']
    h.a.delete_range('alice', h.sid, 0, 5)
    assert h.a.read('alice', note)['text'] == 'synthetic point'
    h.a.delete_range('alice', h.sid, 5, 6)
    with h.a.db:
        h.a.db.execute('UPDATE frames SET deleted=0,wrapped_key=? WHERE id=?', (wrapped, note))
    with pytest.raises(Blocked, match='durable deletion'): h.a.read('alice', note)


def test_ended_upload_resume_and_cancel_receipt_are_actual_policy(h):
    h.a.pause('alice', h.sid); h.a.end('alice', h.sid)
    generation = h.a.session('alice', h.sid)['generation']
    client = TestClient(create_app(h.a, {'test-token':'alice'}))
    headers = {'Authorization':'Bearer test-token'}
    for invalid_consent in (False, 1, 1.0, 'true', None):
        assert client.post(f'/sessions/{h.sid}/uploads/resume', headers=headers, json={'consent':invalid_consent}).status_code == 422
    response = client.post(f'/sessions/{h.sid}/uploads/resume', headers=headers, json={'consent':True})
    assert response.json() == {'upload_paused':False, 'capture_state':'ended'}
    assert h.a.session('alice', h.sid)['generation'] == generation
    client.post(f'/sessions/{h.sid}/cancel', headers=headers, json={'block_automatic':True})
    assert client.post(f'/sessions/{h.sid}/cancel', headers=headers, json={'block_automatic':False}).json()['automatic_blocked'] is True
    with pytest.raises(Blocked): h.a.request_job('alice', h.sid, automatic=True)


def test_fractional_time_conversion_never_shortens_buffer(h):
    f = dict(h.a.frame('alice', h.fid))
    f.update(start=0.1, end=0.1+0.2, eligible=0.1+0.2+900)
    manifest = h.q._manifest(f)
    assert manifest.eligible_at_ns >= manifest.sample_end_ns + BUFFER_NS


@pytest.mark.parametrize('field', ['capture_generation', 'media_version', 'deletion_generation'])
def test_boolean_metadata_receipts_are_not_integer_proof(h, field):
    oid = h.enqueue(); h.clock.advance(900)
    manifest = h.q._manifest(h.a.frame('alice', h.fid))
    value = {'upload_id':oid, 'state':'committed', 'manifest':manifest.as_dict(),
             'manifest_hash':manifest.digest(BINDING), 'deletion_generation':1,
             'part_bytes':65536, 'received_parts':list(range(manifest.part_count)), 'verified':True}
    if field == 'deletion_generation': value[field] = True
    else: value['manifest'][field] = True
    with pytest.raises(UncertainTransfer): h.q._receipt(value, oid, manifest, 1)


def test_old_generation_reply_does_not_permanently_deny_neighbor(h):
    oid = h.enqueue(); h.clock.advance(900)
    def delete_neighbor_after_request_arrived():
        # The old-generation HTTP request was already admitted and received.
        h.a.delete_range('alice', h.sid, 20, 21)
        # Receiver represents a separately arriving durable delete, without
        # invoking the same sender's serialized worker recursively across threads.
        row = h.a.db.execute('SELECT payload FROM upload_deletion_outbox').fetchone()
        from sidecar.transfer_contract import Deletion
        h.receiver.delete_range(BINDING, Deletion.from_dict(json.loads(row[0])))
    h.backend.before_status = delete_neighbor_after_request_arrived
    # First status has no object yet; receiver lookup can return 404 before gen
    # validation, so initialize a staging object explicitly before the race.
    h.t.init(h.q._manifest(h.a.frame('alice', h.fid)), 0)
    with pytest.raises(TransferError, match='deletion_conflict'): h.q.step(oid)
    assert h.q.status(oid)['state'] != 'denied'
    h.clock.advance(3)
    assert h.q.step(oid)['remote_verified']


@pytest.mark.parametrize('control', ['lock', 'network'])
def test_each_socket_handoff_rechecks_control_after_previous_parts(h, control):
    class ControlAfterPart(TransportProxy):
        def put_part(self, *args, **kw):
            result = self.delegate.put_part(*args, **kw)
            if control == 'lock': h.a.unlocked = False
            else: h.a.network(trusted=True, unmetered=True)  # New route generation.
            return result
    h.q = UploadCoordinator(h.a, ControlAfterPart(h.t), BINDING)
    oid = h.enqueue(); h.clock.advance(900)
    with pytest.raises(Blocked): h.q.step(oid)
    assert h.backend.calls.count('put_part') == 1
    assert h.backend.calls.count('commit') == 0
    assert not h.q.status(oid)['remote_verified']


def test_no_cleanup_metadata_to_unconfirmed_session_destination(h):
    h.a.delete_range('alice', h.sid, 1, 2)
    assert h.q.sync_deletions(h.sid) == 0
    assert h.backend.calls == []


def test_first_confirmed_legal_import_backfills_prior_deletion(h):
    h.a.delete_range('alice', h.sid, 1, 2)
    h.clock.advance(5)
    h.fid = h.a.capture('alice', h.sid, h.gen, stream='mic', kind='audio', start=5, end=10, synthetic_text='legal later frame')
    oid = h.enqueue(); h.clock.advance(900)
    assert h.q.status(oid)['remote_deletion_pending']
    assert h.q.step(oid)['remote_verified']
    assert h.backend.calls.index('delete_range') < h.backend.calls.index('init')
    assert h.t.status(oid, 1)['deletion_generation'] == 1


def test_close_waits_for_standalone_cleanup_before_releasing_store(h, key):
    entered, proceed, closed = threading.Event(), threading.Event(), threading.Event()
    class HeldCleanup(TransportProxy):
        def deletion_status(self, *args, **kw):
            entered.set(); assert proceed.wait(5)
            return self.delegate.deletion_status(*args, **kw)
    h.enqueue(); h.a.delete_range('alice', h.sid, 1, 2)
    h.q = UploadCoordinator(h.a, HeldCleanup(h.t), BINDING)
    errors = []
    def cleanup():
        try: h.q.sync_deletions(h.sid)
        except BaseException as exc: errors.append(exc)
    worker = threading.Thread(target=cleanup)
    worker.start()
    closer = threading.Thread(target=lambda: (h.a.close(), closed.set()))
    try:
        assert entered.wait(5)
        closer.start()
        assert not closed.wait(.1)
        with pytest.raises(RuntimeError, match='owned'): Sidecar(h.a.root, h.clock, key)
    finally:
        proceed.set(); worker.join(5)
        if closer.ident is not None: closer.join(5)
    assert not errors and closed.is_set() and not worker.is_alive() and not closer.is_alive()


def test_extreme_retry_after_is_persisted_without_integer_overflow(h):
    oid = h.enqueue(); h.clock.advance(900)
    # A valid HTTP numeric value that exceeds the SQLite nanosecond horizon.
    error = TransferError('rate_limited', 429); error.retry_after = 1_000_000_000_000
    h.backend.failure = error
    with pytest.raises(TransferError, match='rate_limited'): h.q.step(oid)
    assert h.q.status(oid)['next_attempt_ns'] == MAX_TIME

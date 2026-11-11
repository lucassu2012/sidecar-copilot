"""Additional fault value from task-2/synthetic-receiver-fixtures.

Real Sidecar/Receiver and two SQLite files; the transport is a synchronous
guarded in-memory model, not HTTP, Android, a device clock or power-loss evidence.
The accepted-send hook deliberately runs outside the sender lock, before the
receiver sees bytes: local deletion cannot recall already accepted material.
"""
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from sidecar.core import Blocked, Sidecar, SimulationClock
from sidecar.transfer_contract import Binding, BUFFER_NS, TransferError, UncertainTransfer, canonical
from sidecar.transfer_queue import UploadCoordinator, ns
from sidecar.transfer_receiver import Receiver


BINDING = Binding('alice', 'local', 'synthetic-device', 1, 'development', 'opaque-receiver', 'local-key')


def committed_rows(path, query, params=()):
    """Independent connection reads committed rows, including a live WAL."""
    reader = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)
    try:
        reader.row_factory = sqlite3.Row
        return [dict(row) for row in reader.execute(query, params)]
    finally:
        reader.close()


class GuardedMemoryTransport:
    origin = 'synthetic://transfer-fault-fixtures'
    METHODS = frozenset(('status', 'init', 'put_part', 'commit', 'read_object', 'deletion_status', 'delete_range'))

    def __init__(self, sender, receiver):
        self.sender, self.receiver = sender, receiver
        self.events = []
        self.after_accepted = None
        self.drop_result = set()

    def __getattr__(self, method):
        if method not in self.METHODS:
            raise AttributeError(method)

        def call(*args, guard):
            assert not self.sender.lock._is_owned()
            self.events.append(('entry', method))
            payload = args[2] if method == 'put_part' else canonical([
                method, [arg.as_dict() if hasattr(arg, 'as_dict') else arg for arg in args]])

            def accept():
                # CPython lock introspection is a fixture assertion only.
                assert self.sender.lock._is_owned()
                self.events.append(('accepted', method))
                return len(payload)

            assert guard(accept, len(payload)) == len(payload)
            assert not self.sender.lock._is_owned()
            if self.after_accepted:
                self.after_accepted(method)
            assert not self.sender.lock._is_owned()
            self.events.append(('receiver', method))
            result = getattr(self.receiver, method)(BINDING, *args)
            if method in self.drop_result:
                self.drop_result.remove(method)
                self.events.append(('discarded_python_result', method))
                raise UncertainTransfer('fixture discarded successful receiver result')
            return result

        return call

    def operations(self):
        return [method for event, method in self.events if event == 'receiver']


class Harness:
    def __init__(self, root, key, *, end=5, context=None):
        self.root = Path(root)
        self.clock = SimulationClock(**context['clock']) if context else SimulationClock()
        self.a = Sidecar(self.root / 'sender', self.clock, key)
        self.receiver = None
        try:
            # Explicit synthetic controls, including after reopen. These do not
            # resume an existing intent: the restart case must do that itself.
            self.a.unlocked = True
            self.a.network(trusted=True, unmetered=True)
            if context:
                self.sid, self.fid = context['sid'], context['fid']
            else:
                self.sid = self.a.create_session('alice', 'in_person')
                generation = self.a.start('alice', self.sid, consent=True)
                self.clock.advance(end)
                self.fid = self.a.capture('alice', self.sid, generation, stream='mic', kind='audio',
                                          start=0, end=end, synthetic_text='synthetic fault fixture')
            self.receiver = Receiver(self.root / 'receiver.sqlite', now_utc_ns=lambda: ns(self.clock.utc))
            self.transport = GuardedMemoryTransport(self.a, self.receiver)
            self.queue = UploadCoordinator(self.a, self.transport, BINDING)
        except BaseException:
            self.close()
            raise

    def close(self):
        try:
            self.a.close()
        finally:
            if self.receiver is not None:
                self.receiver.close()

    def enqueue(self, **kwargs):
        return self.queue.enqueue(self.fid, confirmed_import=True,
                                  byte_limit=kwargs.get('byte_limit', 2_000_000),
                                  max_attempts=kwargs.get('max_attempts', 3))

    def intent(self):
        return committed_rows(self.root / 'sender/state.sqlite', 'SELECT * FROM upload_intents')[0]


@pytest.fixture(scope='module')
def synthetic_key():
    return rsa.generate_private_key(public_exponent=65537, key_size=2048)


@pytest.fixture
def harness_factory(tmp_path, synthetic_key):
    opened = []

    def make(**kwargs):
        instance = Harness(tmp_path / str(len(opened)), synthetic_key, **kwargs)
        opened.append(instance)
        return instance

    yield make
    for instance in opened:
        instance.close()


def test_fractional_eligibility_rejects_one_ns_early_in_full_pipeline(harness_factory):
    h = harness_factory(end=0.1 + 0.2)
    oid = h.enqueue()
    manifest = h.queue._manifest(h.a.frame('alice', h.fid))
    eligible = manifest.eligible_at_ns
    assert eligible == manifest.sample_end_ns + BUFFER_NS
    assert eligible > ns((0.1 + 0.2) + 900, upper=True)
    h.clock.mono = (eligible - 1) / 1_000_000_000
    assert ns(h.clock.mono) == eligible - 1
    before = h.intent()
    with pytest.raises(Blocked, match='buffer immature'):
        h.queue.step(oid)
    assert h.transport.events == [] and h.intent() == before
    h.clock.mono = eligible / 1_000_000_000
    assert ns(h.clock.mono) == eligible
    assert h.queue.step(oid)['remote_verified'] == 1
    assert h.transport.operations()[-2:] == ['read_object', 'status']


def test_local_delete_after_send_acceptance_rejects_late_part_receipt(harness_factory):
    h = harness_factory()
    oid = h.enqueue()
    h.clock.advance(900)
    deleted = []

    def delete_after_acceptance(method):
        if method == 'put_part' and not deleted:
            deleted.append(h.a.delete_range('alice', h.sid, 1, 2))
            h.transport.events.append(('local_delete_committed', method))

    h.transport.after_accepted = delete_after_acceptance
    with pytest.raises(Blocked, match='cancelled'):
        h.queue.step(oid)
    events = h.transport.events
    assert events.index(('accepted', 'put_part')) < events.index(('local_delete_committed', 'put_part'))
    assert events.index(('local_delete_committed', 'put_part')) < events.index(('receiver', 'put_part'))
    assert h.queue.status(oid)['state'] == 'cancelled'
    assert h.queue.status(oid)['possible_egress'] and not h.queue.status(oid)['remote_verified']
    assert committed_rows(h.root / 'receiver.sqlite', 'SELECT COUNT(*) AS n FROM transfer_parts') == [{'n': 1}]
    assert committed_rows(h.root / 'sender/state.sqlite', 'SELECT * FROM upload_part_receipts') == []
    assert committed_rows(h.root / 'sender/state.sqlite',
                          'SELECT wrapped_key IS NULL AS revoked FROM frames WHERE id=?', (h.fid,)) == [{'revoked': 1}]
    h.transport.after_accepted = None
    assert h.queue.sync_deletions(h.sid) == 1
    assert not h.queue.status(oid)['remote_deletion_pending']
    assert committed_rows(h.root / 'receiver.sqlite', 'SELECT COUNT(*) AS n FROM transfer_parts') == [{'n': 0}]
    with pytest.raises(TransferError) as late:
        h.receiver.commit(BINDING, oid, 1)
    assert (late.value.status, late.value.code) == (409, 'deletion_conflict')


def test_nonliteral_reconfirmation_cannot_reuse_existing_intent(harness_factory):
    h = harness_factory()
    h.enqueue()
    before = h.intent()
    for confirmation in (False, 1, 1.0, 'true', None):
        with pytest.raises(Blocked, match='explicit object and destination confirmation'):
            h.queue.enqueue(h.fid, confirmed_import=confirmation, byte_limit=9_000_000, max_attempts=20)
        assert h.intent() == before
        assert h.transport.events == []


def _restart_stage(stage, root):
    """Normal close/new process control, never a forced-termination model."""
    root = Path(root)
    if stage == 'prepare':
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        context = None
    elif stage == 'verify':
        context = json.loads((root / 'context.json').read_text(encoding='utf-8'))
        key = serialization.load_pem_private_key((root / 'synthetic-key.pem').read_bytes(), None)
    else:
        raise ValueError('unknown restart stage')
    h = Harness(root, key, context=context)
    try:
        if stage == 'prepare':
            oid = h.enqueue()
            h.clock.advance(900)
            h.transport.drop_result.add('commit')
            with pytest.raises(UncertainTransfer):
                h.queue.step(oid)
            before = h.intent()
            assert before['attempts'] == 1 and before['charged_bytes'] > 0
            assert before['known_sent_bytes'] > 0 and not before['remote_verified']
            assert h.receiver.status(BINDING, oid, 0)['state'] == 'committed'
            # Unlike the existing zero-byte exhausted-budget control, this
            # checks the entire charged ledger after an uncertain commit.
            assert h.enqueue(byte_limit=9_000_000, max_attempts=20) == oid
            assert h.intent() == before
            context = {'pid': os.getpid(), 'clock': vars(h.clock), 'sid': h.sid,
                       'fid': h.fid, 'oid': oid, 'intent': before}
            (root / 'context.json').write_text(json.dumps(context), encoding='utf-8')
            (root / 'synthetic-key.pem').write_bytes(key.private_bytes(
                serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
            result = {'pid': os.getpid(), 'remote_committed': True, 'duplicate_preserved_full_ledger': True}
        else:
            assert os.getpid() != context['pid']
            oid = context['oid']
            assert h.intent() == context['intent']
            assert h.a.session('alice', h.sid)['upload_paused'] == 1
            with pytest.raises(Blocked, match='explicit upload resume'):
                h.queue.step(oid)
            assert h.transport.events == [] and h.intent() == context['intent']
            resumed = h.a.resume_uploads('alice', h.sid, consent=True)
            assert resumed == {'upload_paused': False, 'capture_state': 'paused'}
            with pytest.raises(Blocked, match='backoff'):
                h.queue.step(oid)
            assert h.transport.events == [] and h.intent() == context['intent']
            h.clock.advance(3)
            assert h.queue.step(oid)['remote_verified'] == 1
            assert h.transport.operations() == ['status', 'read_object', 'status']
            after = h.intent()
            assert after['upload_id'] == oid and after['attempts'] == 2
            assert after['byte_limit'] == 2_000_000 and after['max_attempts'] == 3
            assert committed_rows(root / 'receiver.sqlite', 'SELECT COUNT(*) AS n FROM transfer_objects') == [{'n': 1}]
            result = {'pid': os.getpid(), 'prior_pid': context['pid'], 'explicit_resume_required': True,
                      'backoff_preserved': True, 'operations': h.transport.operations()}
        return result
    finally:
        h.close()


def test_normal_new_process_requires_resume_and_preserves_unknown_commit_ledger(tmp_path):
    root = tmp_path / 'normal-reopen'
    root.mkdir()
    results = []
    for stage in ('prepare', 'verify'):
        command = [sys.executable, '-B', str(Path(__file__).resolve()), '--restart-stage', stage, str(root)]
        child_env = dict(os.environ)
        child_env.pop('PYTHONOPTIMIZE', None)
        child_env['PYTHONDONTWRITEBYTECODE'] = '1'
        child_env['PYTHONPATH'] = str(Path(__file__).resolve().parents[1])
        try:
            completed = subprocess.run(command, capture_output=True, timeout=20, env=child_env,
                                       cwd=Path(__file__).resolve().parents[1])
        except subprocess.TimeoutExpired as error:
            (root / (stage + '.stdout.log')).write_bytes(error.stdout or b'')
            (root / (stage + '.stderr.log')).write_bytes(error.stderr or b'')
            (root / (stage + '.receipt.json')).write_text(json.dumps({'command': command, 'timed_out': True}), encoding='utf-8')
            raise
        (root / (stage + '.stdout.log')).write_bytes(completed.stdout)
        (root / (stage + '.stderr.log')).write_bytes(completed.stderr)
        (root / (stage + '.receipt.json')).write_text(
            json.dumps({'command': command, 'exit_code': completed.returncode}), encoding='utf-8')
        assert completed.returncode == 0, completed.stderr.decode('utf-8', errors='replace')
        results.append(json.loads(completed.stdout))
    assert results[0]['pid'] != results[1]['pid']
    assert results[1]['prior_pid'] == results[0]['pid']


if __name__ == '__main__':
    if sys.flags.optimize or len(sys.argv) != 4 or sys.argv[1] != '--restart-stage':
        raise SystemExit('explicit unoptimized fixture restart stage required')
    print(json.dumps(_restart_stage(sys.argv[2], sys.argv[3])))

"""Existing T33 cancellation contract: a failed commit cannot acknowledge success."""
import pytest
import sqlite3
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from sidecar.api import create_app
from sidecar.core import Blocked, Sidecar, SimulationClock


def test_cancel_commit_failure_rolls_back_then_retry_preserves_policy(tmp_path):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    store = Sidecar(tmp_path, SimulationClock(), key)
    try:
        store.unlocked = True
        sid = store.create_session('alice', 'manual_note')
        fid = store.add_note('alice', sid, 'synthetic cancellation commit fixture')
        store.end('alice', sid)
        store.clock.advance(900)
        store.upload('alice', fid, store.network(trusted=True, unmetered=True))
        old_job = store.request_job('alice', sid)
        # A deferred foreign-key violation fails at SQLite COMMIT, after the
        # cancellation statements ran. This is neither a disk nor network fault.
        store.db.executescript('''
            CREATE TABLE cancel_probe_parent(id INTEGER PRIMARY KEY);
            CREATE TABLE cancel_probe_child(parent_id INTEGER REFERENCES cancel_probe_parent(id)
                DEFERRABLE INITIALLY DEFERRED);
            CREATE TRIGGER cancel_probe_commit_failure AFTER INSERT ON processing_blocks
            BEGIN INSERT INTO cancel_probe_child VALUES (1); END;
        ''')
        statements = []
        store.db.set_trace_callback(statements.append)
        with TestClient(create_app(store, {'synthetic-alice': 'alice'}),
                        headers={'Authorization': 'Bearer synthetic-alice'},
                        raise_server_exceptions=False) as client:
            failed = client.post(f'/sessions/{sid}/cancel', json={'block_automatic': True})
            store.db.set_trace_callback(None)
            assert failed.status_code == 500
            assert 'cancelled' not in failed.text
            transaction_events = [sql.strip().upper() for sql in statements
                                  if sql.strip().upper() in ('COMMIT', 'ROLLBACK')]
            assert transaction_events == ['COMMIT', 'ROLLBACK']
            assert store.db.execute('SELECT COUNT(*) FROM processing_blocks').fetchone()[0] == 0
            assert store.db.execute('SELECT COUNT(*) FROM processing_versions').fetchone()[0] == 0
            assert store.db.execute('SELECT state FROM jobs WHERE id=?', (old_job,)).fetchone()[0] == 'waiting'
            assert store.db.execute('SELECT COUNT(*) FROM cancel_probe_child').fetchone()[0] == 0
            store.finish_job('alice', old_job)

            store.db.executescript('''
                DROP TRIGGER cancel_probe_commit_failure;
                DROP TABLE cancel_probe_child;
                DROP TABLE cancel_probe_parent;
            ''')
            for request_option in (True, True, False, False):
                receipt = client.post(f'/sessions/{sid}/cancel', json={'block_automatic': request_option})
                assert receipt.status_code == 200
                assert receipt.json() == {'cancelled': True, 'automatic_blocked': True}
                assert store.db.execute('SELECT COUNT(*) FROM processing_blocks WHERE session=?', (sid,)).fetchone()[0] == 1
                with pytest.raises(Blocked):
                    store.finish_job('alice', old_job)
                assert client.post(f'/sessions/{sid}/jobs', json={'automatic': True}).status_code == 403
    finally:
        store.db.set_trace_callback(None)
        store.close()


def test_cancel_receipt_read_failure_keeps_committed_barrier_and_safe_retry(tmp_path):
    store = Sidecar(tmp_path, SimulationClock(),
                    rsa.generate_private_key(public_exponent=65537, key_size=2048))
    try:
        store.unlocked = True
        sid = store.create_session('alice', 'manual_note')
        store.end('alice', sid)
        old_job = store.request_job('alice', sid)
        denied_reads, statements = [], []

        def deny_policy_read(action, table, column, database, trigger):
            if action == sqlite3.SQLITE_READ and table == 'processing_blocks':
                denied_reads.append((table, column))
                return sqlite3.SQLITE_DENY
            return sqlite3.SQLITE_OK

        with TestClient(create_app(store, {'synthetic-alice': 'alice'}),
                        headers={'Authorization': 'Bearer synthetic-alice'},
                        raise_server_exceptions=False) as client:
            store.db.set_authorizer(deny_policy_read)
            store.db.set_trace_callback(statements.append)
            failed = client.post(f'/sessions/{sid}/cancel', json={'block_automatic': True})
            store.db.set_authorizer(None)
            store.db.set_trace_callback(None)
            assert failed.status_code == 500
            assert 'cancelled' not in failed.text
            assert denied_reads
            transaction_events = [sql.strip().upper() for sql in statements
                                  if sql.strip().upper() in ('COMMIT', 'ROLLBACK')]
            assert transaction_events == ['COMMIT']
            # Only receipt construction failed: the cancellation is durable in
            # this local model. Do not claim its transaction was rolled back.
            assert store.db.execute('SELECT COUNT(*) FROM processing_blocks WHERE session=?', (sid,)).fetchone()[0] == 1
            assert store.db.execute('SELECT version FROM processing_versions WHERE session=?', (sid,)).fetchone()[0] == 1
            assert store.db.execute('SELECT state FROM jobs WHERE id=?', (old_job,)).fetchone()[0] == 'cancelled'
            with pytest.raises(Blocked):
                store.finish_job('alice', old_job)
            retried = client.post(f'/sessions/{sid}/cancel', json={'block_automatic': False})
            assert retried.status_code == 200
            assert retried.json() == {'cancelled': True, 'automatic_blocked': True}
            assert client.post(f'/sessions/{sid}/jobs', json={'automatic': True}).status_code == 403
    finally:
        store.db.set_authorizer(None)
        store.db.set_trace_callback(None)
        store.close()

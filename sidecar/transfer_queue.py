"""Persistent, single-process upload coordinator for the real loopback transport.

The captured content and authorization/clock providers are still synthetic.
Network waits never hold Sidecar.lock; only a single nonblocking socket write is
admitted under the same lock as a durable local deletion transaction.
"""
from __future__ import annotations

import hashlib
import json
import threading
from decimal import Decimal, ROUND_CEILING, ROUND_FLOOR

from .core import Blocked, Conflict
from .transfer_contract import (Binding, Deletion, Manifest, PART_BYTES, BUFFER_NS, MAX_TIME, TransferError,
                                UncertainTransfer, canonical, integer, upload_id)

SCHEMA = """
CREATE TABLE IF NOT EXISTS upload_destinations(
 binding_key TEXT PRIMARY KEY, owner_id TEXT NOT NULL, binding_json TEXT NOT NULL, origin TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS upload_intents(
 upload_id TEXT PRIMARY KEY, frame_id TEXT NOT NULL, session_id TEXT NOT NULL,
 owner_id TEXT NOT NULL, binding_key TEXT NOT NULL, manifest TEXT NOT NULL,
 state TEXT NOT NULL DEFAULT 'queued', attempts INTEGER NOT NULL DEFAULT 0,
 max_attempts INTEGER NOT NULL, byte_limit INTEGER NOT NULL, charged_bytes INTEGER NOT NULL DEFAULT 0,
 known_sent_bytes INTEGER NOT NULL DEFAULT 0, next_attempt_ns INTEGER NOT NULL DEFAULT 0,
 last_error TEXT, receipt TEXT, remote_verified INTEGER NOT NULL DEFAULT 0);
CREATE UNIQUE INDEX IF NOT EXISTS upload_frame_binding ON upload_intents(frame_id,binding_key);
CREATE TABLE IF NOT EXISTS upload_part_receipts(
 upload_id TEXT NOT NULL, part_index INTEGER NOT NULL, PRIMARY KEY(upload_id,part_index));
CREATE TABLE IF NOT EXISTS upload_deletion_outbox(
 binding_key TEXT NOT NULL, deletion_id TEXT NOT NULL, session_id TEXT NOT NULL,
 generation INTEGER NOT NULL, payload TEXT NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0,
 receipt TEXT, PRIMARY KEY(binding_key,deletion_id));
INSERT OR IGNORE INTO settings VALUES('upload_schema_version',1);
"""


def ns(seconds, *, upper=False):
    value = Decimal(str(seconds)) * 1_000_000_000
    if not value.is_finite():
        raise Blocked('clock or range unavailable')
    result = int(value.to_integral_value(rounding=ROUND_CEILING if upper else ROUND_FLOOR))
    return integer(result)


def initialize(db):
    db.executescript(SCHEMA)
    if db.execute("SELECT value FROM settings WHERE key='upload_schema_version'").fetchone()[0] != 1:
        raise Blocked('unsupported upload schema')


def _deletion(row):
    return Deletion(row['id'], row['session'], None, ns(row['start']), ns(row['end'], upper=True), row['generation'])


def _outbox(db, binding_key, deletion):
    db.execute('''INSERT OR IGNORE INTO upload_deletion_outbox
        (binding_key,deletion_id,session_id,generation,payload) VALUES(?,?,?,?,?)''',
        (binding_key, deletion.deletion_id, deletion.session_id, deletion.generation,
         canonical(deletion.as_dict()).decode()))


def stage_deletion(db, owner, deletion_id, sid, start, end, generation):
    """Called INSIDE core.delete_range's transaction, never opens/commits a transaction."""
    deletion = Deletion(deletion_id, sid, None, ns(start), ns(end, upper=True), generation)
    # Only destinations explicitly confirmed for an object in this session.
    # A future first import backfills the whole ordered barrier history.
    for target in db.execute('SELECT DISTINCT binding_key FROM upload_intents WHERE owner_id=? AND session_id=?', (owner, sid)).fetchall():
        _outbox(db, target['binding_key'], deletion)
    for row in db.execute('SELECT upload_id,manifest FROM upload_intents WHERE session_id=? AND owner_id=?', (sid, owner)).fetchall():
        if deletion.intersects(Manifest.from_dict(json.loads(row['manifest']))):
            db.execute("UPDATE upload_intents SET state='cancelled',remote_verified=0,last_error='deletion_barrier' WHERE upload_id=?", (row['upload_id'],))


class UploadCoordinator:
    def __init__(self, store, transport, binding: Binding):
        if not isinstance(binding, Binding):
            raise ValueError('immutable binding required')
        self.store, self.transport, self.binding = store, transport, binding
        self.origin = transport.origin
        # Serializes workers, NOT capture/control/deletion or network callbacks.
        with store.lock:
            if not hasattr(store, '_upload_worker_lock'):
                store._upload_worker_lock = threading.RLock()
            self.worker_lock = store._upload_worker_lock
            old = store.db.execute('SELECT * FROM upload_destinations WHERE binding_key=?', (binding.key,)).fetchone()
            identity = canonical(binding.as_dict()).decode()
            if old and (old['binding_json'] != identity or old['origin'] != self.origin):
                raise Conflict('upload destination changed; explicit new binding required')
            with store.db:
                store.db.execute('INSERT OR IGNORE INTO upload_destinations VALUES(?,?,?,?)',
                                 (binding.key, binding.owner_id, identity, self.origin))

    @staticmethod
    def _manifest(f):
        end_ns = ns(f['end'], upper=True)
        return Manifest(f['id'], f['session'], f['stream'], f['kind'], f['boot'], 'monotonic_ns',
                        ns(f['start']), end_ns, max(ns(f['eligible'], upper=True), end_ns + BUFFER_NS),
                        ns(f['expires']), f['generation'], 1, f['length'], f['digest'], 'frame:' + f['id'])

    def enqueue(self, frame_id, *, confirmed_import, byte_limit, max_attempts=5):
        """Explicit per-object import into a fixed destination; login never moves old data."""
        if confirmed_import is not True:
            raise Blocked('explicit object and destination confirmation required')
        integer(byte_limit, 1); integer(max_attempts, 1, 20)
        s = self.store
        with s.lock:
            if not s.unlocked:
                raise Blocked('waiting for unlock')
            f = s.frame(self.binding.owner_id, frame_id)
            manifest = self._manifest(f)
            # Check the entire immutable local ciphertext before creating an intent.
            s._ciphertext(f)
            oid = upload_id(self.binding, frame_id)
            old = s.db.execute('SELECT * FROM upload_intents WHERE upload_id=?', (oid,)).fetchone()
            if old:
                if old['manifest'] != canonical(manifest.as_dict()).decode():
                    raise Conflict('immutable upload reference changed')
                return oid  # Does not reset budget, attempts, receipt or deletion state.
            if s.db.execute('SELECT 1 FROM upload_intents WHERE frame_id=? AND binding_key<>?', (frame_id, self.binding.key)).fetchone():
                raise Blocked('object already bound to another destination')
            barriers = s.db.execute('SELECT * FROM tombstones WHERE session=? ORDER BY generation', (f['session'],)).fetchall()
            current_generation = s.session(self.binding.owner_id, f['session'])['deletion_generation']
            if [x['generation'] for x in barriers] != list(range(1, current_generation + 1)):
                raise Blocked('deletion history incomplete')
            with s.db:
                s.db.execute('''INSERT INTO upload_intents
                    (upload_id,frame_id,session_id,owner_id,binding_key,manifest,max_attempts,byte_limit)
                    VALUES(?,?,?,?,?,?,?,?)''', (oid, frame_id, f['session'], self.binding.owner_id,
                    self.binding.key, canonical(manifest.as_dict()).decode(), max_attempts, byte_limit))
                for row in barriers:
                    _outbox(s.db, self.binding.key, _deletion(row))
            return oid

    def _intent(self, oid):
        row = self.store.db.execute('SELECT * FROM upload_intents WHERE upload_id=? AND binding_key=?', (oid, self.binding.key)).fetchone()
        if row is None:
            raise Blocked('upload unavailable')
        return row

    def _network(self, generation):
        s = self.store
        if generation != s.network_generation or s.trusted_network is not True or s.unmetered is not True:
            raise Blocked('network authorization invalid')

    def _gate(self, oid, network_generation, deletion_generation):
        s = self.store
        row = self._intent(oid)
        if row['state'] in ('cancelled', 'denied'):
            raise Blocked('upload cancelled or requires authorization')
        f = s.frame(self.binding.owner_id, row['frame_id'])
        session = s.session(self.binding.owner_id, row['session_id'])
        manifest = Manifest.from_dict(json.loads(row['manifest']))
        if self._manifest(f) != manifest:
            raise Conflict('immutable source changed')
        if not s.unlocked or s.privacy_paused() or session['upload_paused']:
            raise Blocked('waiting for unlock or explicit upload resume')
        if not s.clock.trusted or s.clock.boot != manifest.boot_id or ns(s.clock.mono) < manifest.eligible_at_ns:
            raise Blocked('buffer immature or clock uncertain')
        if session['deletion_generation'] != deletion_generation:
            raise Blocked('deletion admission changed')
        self._network(network_generation)
        if s.db.execute('''SELECT 1 FROM upload_deletion_outbox
                WHERE binding_key=? AND session_id=? AND confirmed=0''', (self.binding.key, row['session_id'])).fetchone():
            raise Blocked('remote deletion synchronization pending')
        return row, f, manifest

    def _guard(self, oid, network_generation, deletion_generation):
        def handoff(send, attempted_bytes):
            integer(attempted_bytes, 1, PART_BYTES)
            s = self.store
            with s.lock:
                row, _, _ = self._gate(oid, network_generation, deletion_generation)
                if attempted_bytes > row['byte_limit'] - row['charged_bytes']:
                    raise Blocked('upload byte budget exhausted')
                # Crash before/after send leaves a conservative possible-egress charge.
                with s.db:
                    s.db.execute("UPDATE upload_intents SET state='in_flight',charged_bytes=charged_bytes+? WHERE upload_id=?", (attempted_bytes, oid))
                try:
                    sent = send()  # EXACTLY one nonblocking socket.send; no wait/select/sendall.
                except BlockingIOError:
                    with s.db:
                        s.db.execute('UPDATE upload_intents SET charged_bytes=charged_bytes-? WHERE upload_id=?', (attempted_bytes, oid))
                    raise
                integer(sent, 0, attempted_bytes)
                with s.db:
                    s.db.execute('''UPDATE upload_intents SET charged_bytes=charged_bytes-?,
                        known_sent_bytes=known_sent_bytes+? WHERE upload_id=?''', (attempted_bytes - sent, sent, oid))
                return sent
        return handoff

    def sync_deletions(self, session_id):
        """No content is read. This path can remove remote copies after local frame compaction."""
        with self.worker_lock:
            return self._sync_deletions(session_id)

    def _sync_deletions(self, session_id):
        s = self.store
        with s.lock:
            # A revoked local session still owns its existing cleanup requests.
            owner = s.db.execute('SELECT owner FROM sessions WHERE id=?', (session_id,)).fetchone()
            if owner is None or owner['owner'] != self.binding.owner_id:
                raise Blocked('cleanup identity unavailable')
            generation = s.network_generation
            self._network(generation)
            rows = s.db.execute('''SELECT * FROM upload_deletion_outbox
                WHERE binding_key=? AND session_id=? AND confirmed=0 ORDER BY generation''',
                (self.binding.key, session_id)).fetchall()
        def guard(send, count):
            with s.lock:
                self._network(generation)
                return send()  # deletion metadata only, no media or wrapped keys
        for row in rows:
            deletion = Deletion.from_dict(json.loads(row['payload']))
            try:
                receipt = self.transport.deletion_status(deletion.deletion_id, guard=guard)
            except TransferError as error:
                if error.status != 404:
                    raise
                receipt = None
            if receipt is None or receipt.get('cleanup') != 'complete':
                receipt = self.transport.delete_range(deletion, guard=guard)
            if (receipt.get('deletion_id') != deletion.deletion_id
                    or type(receipt.get('generation')) is not int or receipt['generation'] != deletion.generation
                    or receipt.get('blocked') is not True or receipt.get('cleanup') != 'complete'):
                raise UncertainTransfer('deletion result not verified')
            with s.lock, s.db:
                s.db.execute('''UPDATE upload_deletion_outbox SET confirmed=1,receipt=?
                    WHERE binding_key=? AND deletion_id=? AND payload=?''',
                    (canonical(receipt).decode(), self.binding.key, deletion.deletion_id, row['payload']))
        return len(rows)

    def _receipt(self, value, oid, manifest, generation):
        if (not isinstance(value, dict) or value.get('upload_id') != oid
                or canonical(value.get('manifest')) != canonical(manifest.as_dict()) or value.get('manifest_hash') != manifest.digest(self.binding)
                or type(value.get('deletion_generation')) is not int or value['deletion_generation'] != generation
                or type(value.get('part_bytes')) is not int or value['part_bytes'] != PART_BYTES
                or value.get('state') not in ('staging', 'committed')):
            raise UncertainTransfer('remote metadata not verified')
        parts = value.get('received_parts')
        if not isinstance(parts, list) or any(type(i) is not int or not 0 <= i < manifest.part_count for i in parts) or parts != sorted(set(parts)):
            raise UncertainTransfer('remote parts not verified')
        if value['state'] == 'committed' and (value.get('verified') is not True or parts != list(range(manifest.part_count))):
            raise UncertainTransfer('remote commit not verified')
        return value

    def _remember_parts(self, oid, receipt, network_generation, generation):
        with self.store.lock:
            self._gate(oid, network_generation, generation)
            with self.store.db:
                for index in receipt['received_parts']:
                    self.store.db.execute('INSERT OR IGNORE INTO upload_part_receipts VALUES(?,?)', (oid, index))

    def step(self, oid):
        """One finite attempt. Every restart/unknown result queries status before init or retry."""
        s = self.store
        with self.worker_lock:
            with s.lock:
                row = self._intent(oid)
                sid = row['session_id']
            self.sync_deletions(sid)
            with s.lock:
                generation = s.session(self.binding.owner_id, sid)['deletion_generation']
                network_generation = s.network_generation
                row, f, manifest = self._gate(oid, network_generation, generation)
                if row['remote_verified']:
                    return self.status(oid)
                if row['attempts'] >= row['max_attempts']:
                    raise Blocked('finite retry limit reached')
                if ns(s.clock.utc) < row['next_attempt_ns']:
                    raise Blocked('waiting for retry backoff')
                with s.db:
                    s.db.execute('UPDATE upload_intents SET attempts=attempts+1,last_error=NULL WHERE upload_id=?', (oid,))
            guard = self._guard(oid, network_generation, generation)
            try:
                try:
                    remote = self.transport.status(oid, generation, guard=guard)
                except TransferError as error:
                    if error.status != 404:
                        raise
                    remote = self.transport.init(manifest, generation, guard=guard)
                remote = self._receipt(remote, oid, manifest, generation)
                self._remember_parts(oid, remote, network_generation, generation)
                if remote['state'] != 'committed':
                    for index in range(manifest.part_count):
                        if index in remote['received_parts']:
                            continue
                        with s.lock:
                            _, f, _ = self._gate(oid, network_generation, generation)
                            # Ciphertext only; no private key, plaintext, whole-segment upload or key export.
                            whole = s._ciphertext(f)
                            part = whole[index * PART_BYTES:(index + 1) * PART_BYTES]
                            del whole
                        remote = self._receipt(self.transport.put_part(oid, index, part, generation, guard=guard), oid, manifest, generation)
                        self._remember_parts(oid, remote, network_generation, generation)
                    remote = self._receipt(self.transport.commit(oid, generation, guard=guard), oid, manifest, generation)
                # Object bytes AND metadata must both be readable and match, not merely commit HTTP 200.
                data = self.transport.read_object(oid, generation, guard=guard)
                if len(data) != manifest.byte_length or hashlib.sha256(data).hexdigest() != manifest.sha256:
                    raise UncertainTransfer('remote object not verified')
                del data
                final = self._receipt(self.transport.status(oid, generation, guard=guard), oid, manifest, generation)
                if final['state'] != 'committed':
                    raise UncertainTransfer('remote commit not verified')
                with s.lock:
                    self._gate(oid, network_generation, generation)
                    with s.db:
                        s.db.execute("UPDATE upload_intents SET state='committed',remote_verified=1,receipt=?,last_error=NULL,next_attempt_ns=0 WHERE upload_id=?", (canonical(final).decode(), oid))
                        s.db.execute('UPDATE frames SET uploaded=1 WHERE id=?', (manifest.frame_id,))
                return self.status(oid)
            except (TransferError, UncertainTransfer, Blocked, Conflict, OSError) as error:
                with s.lock:
                    row = self._intent(oid)
                    if row['state'] != 'cancelled':
                        # An already-admitted request may return the old generation
                        # after a different local range is deleted. Keep unaffected
                        # evidence resumable; arbitrary remote conflicts stay denied.
                        current = s.db.execute('SELECT deletion_generation FROM sessions WHERE id=?', (sid,)).fetchone()[0]
                        changed_generation = (isinstance(error, TransferError) and error.code == 'deletion_conflict'
                                              and error.status == 409 and current > generation)
                        terminal = isinstance(error, TransferError) and error.status in (401, 403, 409, 410, 422) and not changed_generation
                        retryable = changed_generation or isinstance(error, (UncertainTransfer, OSError)) or (isinstance(error, TransferError) and (error.status == 429 or error.status >= 500))
                        delay = min(60, 2 ** min(row['attempts'], 6)) if retryable else 0
                        if isinstance(error, TransferError) and error.status == 429:
                            delay = max(delay, getattr(error, 'retry_after', None) or 0)
                        deadline_ns = min(MAX_TIME, int(((Decimal(str(s.clock.utc)) + Decimal(str(delay))) * 1_000_000_000).to_integral_value(rounding=ROUND_CEILING)))
                        code = error.code if isinstance(error, TransferError) else type(error).__name__
                        with s.db:
                            s.db.execute('''UPDATE upload_intents SET state=?,last_error=?,next_attempt_ns=?
                                WHERE upload_id=?''', ('denied' if terminal else row['state'], code, deadline_ns, oid))
                raise

    def status(self, oid):
        with self.store.lock:
            row = self._intent(oid)
            pending = self.store.db.execute('''SELECT COUNT(*) FROM upload_deletion_outbox
                WHERE binding_key=? AND session_id=? AND confirmed=0''', (self.binding.key, row['session_id'])).fetchone()[0]
            return {k: row[k] for k in ('upload_id', 'frame_id', 'session_id', 'state', 'attempts',
                'charged_bytes', 'known_sent_bytes', 'byte_limit', 'next_attempt_ns', 'last_error', 'remote_verified')} | {
                'possible_egress': row['charged_bytes'] > 0, 'remote_deletion_pending': pending > 0,
                'processing_ready': False}  # Verified opaque media is not OCR/ASR or a searchable transcript.

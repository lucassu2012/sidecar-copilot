"""Durable local ciphertext receiver for synthetic integration tests.

Binding is an already-authorized namespace supplied by the caller, not an
authentication mechanism. The receiver cannot attest a device's monotonic clock
or consent; the sender enforces actual eligibility before egress. SQLite logical
deletion and FULL/WAL durability do not establish physical erasure or power-loss
certification. No decryption, key material, networking or production cloud lives
in this module.
"""
from __future__ import annotations

from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import sqlite3
import threading
import time

from .transfer_contract import (
    Binding, Deletion, Manifest, PART_BYTES, TransferError, canonical,
    identifier, integer, upload_id as make_upload_id, validate_upload_id,
)


SCHEMA = """
CREATE TABLE IF NOT EXISTS transfer_bindings (
    binding_key TEXT PRIMARY KEY,
    binding_json BLOB NOT NULL
);
CREATE TABLE IF NOT EXISTS transfer_sessions (
    binding_key TEXT NOT NULL REFERENCES transfer_bindings(binding_key),
    session_id TEXT NOT NULL,
    deletion_generation INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(binding_key, session_id)
);
CREATE TABLE IF NOT EXISTS transfer_objects (
    upload_id TEXT PRIMARY KEY,
    binding_key TEXT NOT NULL,
    session_id TEXT NOT NULL,
    manifest_json BLOB NOT NULL,
    manifest_hash TEXT NOT NULL,
    committed INTEGER NOT NULL DEFAULT 0 CHECK(committed IN (0, 1)),
    deleted INTEGER NOT NULL DEFAULT 0 CHECK(deleted IN (0, 1)),
    FOREIGN KEY(binding_key, session_id) REFERENCES transfer_sessions(binding_key, session_id)
);
CREATE INDEX IF NOT EXISTS transfer_objects_session ON transfer_objects(binding_key, session_id);
CREATE TABLE IF NOT EXISTS transfer_parts (
    upload_id TEXT NOT NULL REFERENCES transfer_objects(upload_id),
    part_index INTEGER NOT NULL,
    ciphertext BLOB NOT NULL,
    sha256 TEXT NOT NULL,
    PRIMARY KEY(upload_id, part_index)
);
CREATE TABLE IF NOT EXISTS transfer_deletions (
    binding_key TEXT NOT NULL,
    deletion_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    generation INTEGER NOT NULL,
    deletion_json BLOB NOT NULL,
    cleanup_complete INTEGER NOT NULL DEFAULT 0 CHECK(cleanup_complete IN (0, 1)),
    PRIMARY KEY(binding_key, deletion_id),
    UNIQUE(binding_key, session_id, generation),
    FOREIGN KEY(binding_key, session_id) REFERENCES transfer_sessions(binding_key, session_id)
);
CREATE TABLE IF NOT EXISTS transfer_expiry_barriers (
    binding_key TEXT NOT NULL REFERENCES transfer_bindings(binding_key),
    upload_id TEXT NOT NULL,
    observed_expires_at_utc_ns INTEGER NOT NULL,
    PRIMARY KEY(binding_key, upload_id)
);
"""


class _ObservedExpiry(TransferError):
    """A rejection whose new persistent barrier must commit, not roll back."""


class Receiver:
    """One independent SQLite file; methods also coordinate across connections.

    BEGIN IMMEDIATE pairs each admission check with its mutation or verified
    read. The Python lock protects only this connection and local transactions.
    An acknowledged deletion barrier commits before a separate cleanup
    transaction, so cleanup failure cannot resurrect eligibility.
    """

    def __init__(self, db_path, *, now_utc_ns=None):
        path = Path(db_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        self._now_utc_ns = now_utc_ns if now_utc_ns is not None else time.time_ns
        self._closed = False
        self._db = sqlite3.connect(path, isolation_level=None, check_same_thread=False, timeout=5)
        self._db.row_factory = sqlite3.Row
        try:
            self._db.execute("PRAGMA foreign_keys=ON")
            mode = self._db.execute("PRAGMA journal_mode=WAL").fetchone()[0]
            self._db.execute("PRAGMA synchronous=FULL")
            self._db.execute("PRAGMA secure_delete=ON")
            if str(mode).lower() != "wal" or self._db.execute("PRAGMA synchronous").fetchone()[0] != 2:
                raise TransferError("durability_unavailable", 503)
            self._db.executescript(SCHEMA)
            self._recover_cleanup()
        except BaseException:
            self._db.close()
            self._closed = True
            raise

    def close(self):
        with self._lock:
            if not self._closed:
                self._db.close()
                self._closed = True

    @contextmanager
    def _transaction(self):
        with self._lock:
            if self._closed:
                raise TransferError("receiver_closed", 503)
            try:
                self._db.execute("BEGIN IMMEDIATE")
                yield
                self._db.execute("COMMIT")
            except BaseException as exc:
                if isinstance(exc, _ObservedExpiry) and self._db.in_transaction:
                    try:
                        self._db.execute("COMMIT")
                    except sqlite3.Error as commit_error:
                        if self._db.in_transaction:
                            self._db.execute("ROLLBACK")
                        raise TransferError("storage_unavailable", 503) from commit_error
                    raise
                if self._db.in_transaction:
                    self._db.execute("ROLLBACK")
                if isinstance(exc, sqlite3.Error):
                    raise TransferError("storage_unavailable", 503) from exc
                raise

    @staticmethod
    def _require_record(value, cls):
        if not isinstance(value, cls):
            raise TransferError("invalid_fields", 422)
        # Revalidate even direct Python callers; wire adapters use from_dict too.
        return cls.from_dict(value.as_dict())

    def _binding(self, binding, *, create=False):
        row = self._db.execute("SELECT binding_json FROM transfer_bindings WHERE binding_key=?", (binding.key,)).fetchone()
        encoded = canonical(binding.as_dict())
        if row is None:
            if create:
                self._db.execute("INSERT INTO transfer_bindings VALUES (?,?)", (binding.key, encoded))
            return
        if bytes(row["binding_json"]) != encoded:
            raise TransferError("integrity_error", 500)

    def _session(self, binding, session_id, *, create=False):
        self._binding(binding, create=create)
        row = self._db.execute(
            "SELECT deletion_generation FROM transfer_sessions WHERE binding_key=? AND session_id=?",
            (binding.key, session_id),
        ).fetchone()
        if row is None:
            if create:
                self._db.execute("INSERT INTO transfer_sessions VALUES (?,?,0)", (binding.key, session_id))
            return 0
        return row["deletion_generation"]

    @staticmethod
    def _stored_deletion(row):
        try:
            deletion = Deletion.from_dict(json.loads(row["deletion_json"]))
            if (canonical(deletion.as_dict()) != bytes(row["deletion_json"])
                    or row["deletion_id"] != deletion.deletion_id
                    or row["session_id"] != deletion.session_id
                    or row["generation"] != deletion.generation):
                raise ValueError("deletion metadata disagrees")
            return deletion
        except (ValueError, TypeError, KeyError, TransferError) as exc:
            raise TransferError("integrity_error", 500) from exc

    def _admit(self, binding, manifest, deletion_generation, *, create=False):
        integer(deletion_generation)
        current = self._session(binding, manifest.session_id, create=create)
        if deletion_generation != current:
            raise TransferError("deletion_conflict")
        for row in self._db.execute(
            "SELECT * FROM transfer_deletions WHERE binding_key=? AND session_id=? ORDER BY generation",
            (binding.key, manifest.session_id),
        ):
            if self._stored_deletion(row).intersects(manifest):
                raise TransferError("deletion_conflict")
        object_id = make_upload_id(binding, manifest.frame_id)
        if self._db.execute("SELECT 1 FROM transfer_expiry_barriers WHERE binding_key=? AND upload_id=?", (binding.key, object_id)).fetchone():
            raise TransferError("expired", 410)
        now = self._now_utc_ns()
        if type(now) is not int or now < 0:
            raise TransferError("clock_unavailable", 503)
        if now >= manifest.expires_at_utc_ns:
            self._db.execute("INSERT INTO transfer_expiry_barriers VALUES (?,?,?)", (binding.key, object_id, manifest.expires_at_utc_ns))
            raise _ObservedExpiry("expired", 410)
        return current

    @staticmethod
    def _stored_manifest(row, binding):
        try:
            manifest = Manifest.from_dict(json.loads(row["manifest_json"]))
            if (canonical(manifest.as_dict()) != bytes(row["manifest_json"])
                    or manifest.digest(binding) != row["manifest_hash"]
                    or make_upload_id(binding, manifest.frame_id) != row["upload_id"]
                    or row["binding_key"] != binding.key
                    or row["session_id"] != manifest.session_id):
                raise ValueError("object metadata disagrees")
            return manifest
        except (ValueError, TypeError, KeyError, TransferError) as exc:
            raise TransferError("integrity_error", 500) from exc

    def _object(self, binding, object_id, deletion_generation):
        validate_upload_id(object_id)
        row = self._db.execute("SELECT * FROM transfer_objects WHERE upload_id=? AND binding_key=?", (object_id, binding.key)).fetchone()
        if row is None:
            raise TransferError("not_found", 404)
        manifest = self._stored_manifest(row, binding)
        generation = self._admit(binding, manifest, deletion_generation)
        if row["deleted"]:
            raise TransferError("deletion_conflict")
        return row, manifest, generation

    def _parts(self, row, manifest, *, complete=False):
        indices, chunks = [], []
        for part in self._db.execute("SELECT * FROM transfer_parts WHERE upload_id=? ORDER BY part_index", (row["upload_id"],)):
            index = part["part_index"]
            try:
                expected = manifest.part_length(index)
                data = bytes(part["ciphertext"])
            except (TransferError, ValueError, TypeError) as exc:
                raise TransferError("integrity_error", 500) from exc
            if len(data) != expected or hashlib.sha256(data).hexdigest() != part["sha256"]:
                raise TransferError("integrity_error", 500)
            indices.append(index)
            chunks.append(data)
        full = indices == list(range(manifest.part_count))
        if complete and not full:
            raise TransferError("integrity_error" if row["committed"] else "incomplete_upload", 500 if row["committed"] else 409)
        data = b"".join(chunks) if full else None
        if complete and (len(data) != manifest.byte_length or hashlib.sha256(data).hexdigest() != manifest.sha256):
            raise TransferError("integrity_error" if row["committed"] else "hash_mismatch", 500 if row["committed"] else 422)
        return indices, data

    def _receipt(self, row, manifest, generation, binding):
        indices, _ = self._parts(row, manifest, complete=bool(row["committed"]))
        # Hashing/materializing up to the experiment limit can cross a deadline.
        self._admit(binding, manifest, generation)
        return {"upload_id": row["upload_id"], "state": "committed" if row["committed"] else "staging",
                "manifest": manifest.as_dict(), "manifest_hash": row["manifest_hash"],
                "deletion_generation": generation, "received_parts": indices,
                "part_bytes": PART_BYTES, "verified": bool(row["committed"])}

    def init(self, binding, manifest, deletion_generation):
        binding = self._require_record(binding, Binding)
        manifest = self._require_record(manifest, Manifest)
        object_id = make_upload_id(binding, manifest.frame_id)
        with self._transaction():
            row = self._db.execute("SELECT * FROM transfer_objects WHERE upload_id=?", (object_id,)).fetchone()
            if row is not None:
                previous = self._stored_manifest(row, binding)
                # Apply barriers to the original immutable object before
                # considering a conflicting retry payload. Never persist an
                # expiry decision based on altered metadata for an existing ID.
                self._admit(binding, previous, deletion_generation)
                if previous != manifest:
                    raise TransferError("idempotency_conflict")
            else:
                self._admit(binding, manifest, deletion_generation, create=True)
            if row is None:
                self._db.execute(
                    "INSERT INTO transfer_objects(upload_id,binding_key,session_id,manifest_json,manifest_hash) VALUES (?,?,?,?,?)",
                    (object_id, binding.key, manifest.session_id, canonical(manifest.as_dict()), manifest.digest(binding)),
                )
            row, saved, generation = self._object(binding, object_id, deletion_generation)
            return self._receipt(row, saved, generation, binding)

    def put_part(self, binding, object_id, index, ciphertext, deletion_generation):
        binding = self._require_record(binding, Binding)
        if type(ciphertext) is not bytes:
            raise TransferError("invalid_part", 422)
        with self._transaction():
            row, manifest, generation = self._object(binding, object_id, deletion_generation)
            if len(ciphertext) != manifest.part_length(index):
                raise TransferError("invalid_part_length", 422)
            previous = self._db.execute("SELECT ciphertext,sha256 FROM transfer_parts WHERE upload_id=? AND part_index=?", (object_id, index)).fetchone()
            digest = hashlib.sha256(ciphertext).hexdigest()
            if previous is not None:
                if bytes(previous["ciphertext"]) != ciphertext or previous["sha256"] != digest:
                    raise TransferError("idempotency_conflict")
            else:
                if row["committed"]:
                    raise TransferError("integrity_error", 500)
                self._db.execute("INSERT INTO transfer_parts VALUES (?,?,?,?)", (object_id, index, ciphertext, digest))
            return self._receipt(row, manifest, generation, binding)

    def commit(self, binding, object_id, deletion_generation):
        binding = self._require_record(binding, Binding)
        with self._transaction():
            row, manifest, _ = self._object(binding, object_id, deletion_generation)
            self._parts(row, manifest, complete=True)
            self._db.execute("UPDATE transfer_objects SET committed=1 WHERE upload_id=?", (object_id,))
        # A distinct read after COMMIT verifies persisted metadata and all parts.
        # A deletion/expiry that wins between these transactions denies success.
        return self.status(binding, object_id, deletion_generation)

    def status(self, binding, object_id, deletion_generation):
        binding = self._require_record(binding, Binding)
        with self._transaction():
            row, manifest, generation = self._object(binding, object_id, deletion_generation)
            return self._receipt(row, manifest, generation, binding)

    def read_object(self, binding, object_id, deletion_generation):
        binding = self._require_record(binding, Binding)
        with self._transaction():
            row, manifest, _ = self._object(binding, object_id, deletion_generation)
            if not row["committed"]:
                raise TransferError("incomplete_upload")
            _, ciphertext = self._parts(row, manifest, complete=True)
            self._admit(binding, manifest, deletion_generation)
            return ciphertext

    @staticmethod
    def _deletion_receipt(row):
        return {"deletion_id": row["deletion_id"], "generation": row["generation"], "blocked": True,
                "cleanup": "complete" if row["cleanup_complete"] else "pending",
                "physical_erasure": "not_guaranteed"}

    def deletion_status(self, binding, deletion_id):
        binding = self._require_record(binding, Binding)
        identifier(deletion_id)
        with self._transaction():
            self._binding(binding)
            row = self._db.execute("SELECT * FROM transfer_deletions WHERE binding_key=? AND deletion_id=?", (binding.key, deletion_id)).fetchone()
            if row is None:
                raise TransferError("unknown_deletion", 404)
            self._stored_deletion(row)
            return self._deletion_receipt(row)

    def delete_range(self, binding, deletion):
        binding = self._require_record(binding, Binding)
        deletion = self._require_record(deletion, Deletion)
        with self._transaction():
            current = self._session(binding, deletion.session_id, create=True)
            previous = self._db.execute("SELECT * FROM transfer_deletions WHERE binding_key=? AND deletion_id=?", (binding.key, deletion.deletion_id)).fetchone()
            if previous is not None:
                if self._stored_deletion(previous) != deletion:
                    raise TransferError("idempotency_conflict")
            else:
                if deletion.generation != current + 1:
                    raise TransferError("deletion_conflict")
                self._db.execute("INSERT INTO transfer_deletions(binding_key,deletion_id,session_id,generation,deletion_json) VALUES (?,?,?,?,?)",
                                 (binding.key, deletion.deletion_id, deletion.session_id, deletion.generation, canonical(deletion.as_dict())))
                self._db.execute("UPDATE transfer_sessions SET deletion_generation=? WHERE binding_key=? AND session_id=?",
                                 (deletion.generation, binding.key, deletion.session_id))
        # The committed range is immediately consulted by every object request.
        self._cleanup_deletion(binding, deletion.deletion_id)
        return self.deletion_status(binding, deletion.deletion_id)

    def _cleanup_deletion(self, binding, deletion_id):
        try:
            with self._transaction():
                row = self._db.execute("SELECT * FROM transfer_deletions WHERE binding_key=? AND deletion_id=?", (binding.key, deletion_id)).fetchone()
                if row is None:
                    raise TransferError("unknown_deletion", 404)
                deletion = self._stored_deletion(row)
                if row["cleanup_complete"]:
                    return
                objects = self._db.execute("SELECT * FROM transfer_objects WHERE binding_key=? AND session_id=?", (binding.key, deletion.session_id)).fetchall()
                for obj in objects:
                    if deletion.intersects(self._stored_manifest(obj, binding)):
                        self._db.execute("DELETE FROM transfer_parts WHERE upload_id=?", (obj["upload_id"],))
                        self._db.execute("UPDATE transfer_objects SET deleted=1 WHERE upload_id=?", (obj["upload_id"],))
                self._db.execute("UPDATE transfer_deletions SET cleanup_complete=1 WHERE binding_key=? AND deletion_id=?", (binding.key, deletion_id))
        except TransferError as exc:
            raise TransferError("cleanup_pending", 503) from exc

    def _recover_cleanup(self):
        with self._transaction():
            rows = self._db.execute(
                "SELECT d.deletion_id,b.binding_json FROM transfer_deletions d JOIN transfer_bindings b "
                "ON b.binding_key=d.binding_key WHERE d.cleanup_complete=0 ORDER BY d.binding_key,d.session_id,d.generation"
            ).fetchall()
        for row in rows:
            try:
                binding = Binding.from_dict(json.loads(row["binding_json"]))
                self._cleanup_deletion(binding, row["deletion_id"])
            except (TransferError, ValueError, TypeError):
                # Durable barriers remain enforced. A failed cleanup stays
                # queryable and must not prevent unrelated namespaces opening.
                continue

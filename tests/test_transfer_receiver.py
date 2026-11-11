"""Real temporary SQLite files; synthetic AES-GCM ciphertext only.

These are local durability/fault models, not server authentication, trustworthy
clock, physical erasure, actual power-loss or Android/device acceptance tests.
"""
from dataclasses import replace
import hashlib
import json
import os
import sqlite3
import threading

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import pytest

from sidecar.transfer_contract import Binding, Deletion, Manifest, PART_BYTES, BUFFER_NS, TransferError, canonical, upload_id
from sidecar.transfer_receiver import Receiver


def ciphertext(length=PART_BYTES + 37):
    assert length >= 16
    return AESGCM(AESGCM.generate_key(bit_length=256)).encrypt(os.urandom(12), b"s" * (length - 16), b"synthetic receiver test")


def binding():
    return Binding("owner", "tenant", "device", 1, "policy", "processor", "key-scope")


def manifest(body, *, frame="frame", session="session", boot="boot", start=10, end=20, expires=1000, source="audio"):
    return Manifest(frame, session, "track", source, boot, "monotonic_ns", start, end,
                    end + BUFFER_NS, expires, 1, 1, len(body), hashlib.sha256(body).hexdigest(), "wrapped-key-reference")


@pytest.fixture
def environment(tmp_path):
    clock = [100]
    path = tmp_path / "receiver.sqlite"
    opened = []

    def connect():
        receiver = Receiver(path, now_utc_ns=lambda: clock[0])
        opened.append(receiver)
        return receiver

    yield path, clock, connect
    for receiver in opened:
        receiver.close()


def expect_error(code, call, status=None):
    with pytest.raises(TransferError) as error:
        call()
    assert error.value.code == code
    if status is not None:
        assert error.value.status == status


def stage(receiver, identity, metadata, body, generation=0):
    receipt = receiver.init(identity, metadata, generation)
    for index in range(metadata.part_count):
        receiver.put_part(identity, receipt["upload_id"], index, body[index * PART_BYTES:(index + 1) * PART_BYTES], generation)
    return receipt["upload_id"]


def stored(receiver, identity, metadata, body, generation=0):
    object_id = stage(receiver, identity, metadata, body, generation)
    receiver.commit(identity, object_id, generation)
    return object_id


def query(path, sql, args=()):
    with sqlite3.connect(path) as db:
        return db.execute(sql, args).fetchall()


def test_durable_parts_out_of_order_commit_and_lost_response(environment):
    path, _, connect = environment
    receiver = connect()
    assert receiver._db.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
    assert receiver._db.execute("PRAGMA synchronous").fetchone()[0] == 2
    who, body = binding(), ciphertext(2 * PART_BYTES + 37)
    meta = manifest(body)
    initial = receiver.init(who, meta, 0)
    oid = initial["upload_id"]
    assert oid == upload_id(who, meta.frame_id)
    assert initial == {"upload_id": oid, "state": "staging", "manifest": meta.as_dict(),
                       "manifest_hash": meta.digest(who), "deletion_generation": 0,
                       "received_parts": [], "part_bytes": PART_BYTES, "verified": False}
    for index in (2, 0):
        receiver.put_part(who, oid, index, body[index * PART_BYTES:(index + 1) * PART_BYTES], 0)
    receiver.close()
    receiver = connect()
    assert receiver.status(who, oid, 0)["received_parts"] == [0, 2]
    expect_error("incomplete_upload", lambda: receiver.commit(who, oid, 0), 409)
    expect_error("incomplete_upload", lambda: receiver.read_object(who, oid, 0), 409)
    receiver.put_part(who, oid, 1, body[PART_BYTES:2 * PART_BYTES], 0)
    receiver.commit(who, oid, 0)  # Simulate a lost response: no caller receipt is retained.
    receiver.close()
    receiver = connect()
    status = receiver.status(who, oid, 0)
    assert status["state"] == "committed" and status["verified"] is True
    assert status["received_parts"] == [0, 1, 2]
    assert receiver.commit(who, oid, 0) == status
    assert receiver.init(who, meta, 0) == status
    assert receiver.put_part(who, oid, 1, body[PART_BYTES:2 * PART_BYTES], 0) == status
    assert receiver.read_object(who, oid, 0) == body
    assert query(path, "SELECT COUNT(*) FROM transfer_objects") == [(1,)]
    assert query(path, "SELECT COUNT(*) FROM transfer_parts") == [(3,)]


@pytest.mark.parametrize("field,value", [("session_id", "other-session"), ("stream_id", "other-stream"),
    ("boot_id", "other-boot"), ("key_ref", "other-key"), ("capture_generation", 2),
    ("expires_at_utc_ns", 2000), ("sha256", "0" * 64)])
def test_immutable_manifest_conflicts(environment, field, value):
    _, _, connect = environment
    receiver = connect(); who = binding(); body = ciphertext(); meta = manifest(body)
    receiver.init(who, meta, 0)
    expect_error("idempotency_conflict", lambda: receiver.init(who, replace(meta, **{field: value}), 0), 409)


def test_part_retries_conflict_limits_and_hash_failure(environment):
    _, _, connect = environment
    receiver = connect(); who = binding(); body = ciphertext(); meta = manifest(body)
    oid = receiver.init(who, meta, 0)["upload_id"]
    receiver.put_part(who, oid, 0, body[:PART_BYTES], 0)
    assert receiver.put_part(who, oid, 0, body[:PART_BYTES], 0)["received_parts"] == [0]
    changed = bytes([body[0] ^ 1]) + body[1:PART_BYTES]
    expect_error("idempotency_conflict", lambda: receiver.put_part(who, oid, 0, changed, 0), 409)
    for index in (-1, True, 2):
        expect_error("invalid_integer", lambda: receiver.put_part(who, oid, index, b"x", 0), 422)
    expect_error("invalid_part_length", lambda: receiver.put_part(who, oid, 0, body[:PART_BYTES] + b"x", 0), 422)
    expect_error("invalid_part_length", lambda: receiver.put_part(who, oid, 1, b"x", 0), 422)
    expect_error("invalid_part", lambda: receiver.put_part(who, oid, 1, "plaintext", 0), 422)
    receiver.put_part(who, oid, 1, b"x" * 37, 0)
    expect_error("hash_mismatch", lambda: receiver.commit(who, oid, 0), 422)
    assert receiver.status(who, oid, 0)["state"] == "staging"


@pytest.mark.parametrize("field,value", [("owner_id", "owner2"), ("tenant_id", "tenant2"),
    ("device_id", "device2"), ("binding_generation", 2), ("policy_scope_id", "policy2"),
    ("processor_id", "processor2"), ("key_scope", "scope2")])
def test_all_binding_fields_isolate_same_frame_and_deletions(environment, field, value):
    _, _, connect = environment
    receiver = connect(); who = binding(); other = replace(who, **{field: value})
    body = ciphertext(); meta = manifest(body)
    oid = stored(receiver, who, meta, body)
    expect_error("not_found", lambda: receiver.read_object(other, oid, 0), 404)
    expect_error("not_found", lambda: receiver.put_part(other, oid, 0, body[:PART_BYTES], 0), 404)
    second_id = stored(receiver, other, meta, body)
    assert second_id != oid
    deletion = Deletion("remove", meta.session_id, meta.boot_id, 10, 20, 1)
    receiver.delete_range(who, deletion)
    expect_error("deletion_conflict", lambda: receiver.read_object(who, oid, 1), 409)
    expect_error("unknown_deletion", lambda: receiver.deletion_status(other, deletion.deletion_id), 404)
    assert receiver.read_object(other, second_id, 0) == body


def test_generation_sequence_and_deletion_idempotency(environment):
    path, _, connect = environment
    receiver = connect(); who = binding(); body = ciphertext(); meta = manifest(body)
    oid = stored(receiver, who, meta, body)
    for generation in (1, 3):
        expect_error("deletion_conflict", lambda: receiver.status(who, oid, generation), 409)
    gap = Deletion("gap", "session", "boot", 80, 90, 2)
    expect_error("deletion_conflict", lambda: receiver.delete_range(who, gap), 409)
    first = Deletion("first", "session", "boot", 50, 60, 1)
    receipt = receiver.delete_range(who, first)
    assert receipt == {"deletion_id": "first", "generation": 1, "blocked": True,
                       "cleanup": "complete", "physical_erasure": "not_guaranteed"}
    assert receiver.delete_range(who, first) == receipt
    expect_error("idempotency_conflict", lambda: receiver.delete_range(who, replace(first, end_ns=61)), 409)
    expect_error("deletion_conflict", lambda: receiver.delete_range(who, replace(first, deletion_id="unknown-old")), 409)
    second = Deletion("second", "session", "boot", 80, 90, 2)
    receiver.delete_range(who, second)
    assert receiver.delete_range(who, first) == receipt
    assert query(path, "SELECT deletion_generation FROM transfer_sessions") == [(2,)]
    assert query(path, "SELECT COUNT(*) FROM transfer_deletions") == [(2,)]
    expect_error("deletion_conflict", lambda: receiver.read_object(who, oid, 1), 409)
    assert receiver.read_object(who, oid, 2) == body


def test_range_boundaries_points_boots_and_adjacent_objects(environment):
    _, _, connect = environment
    receiver = connect(); who = binding(); body = ciphertext(80)
    fixtures = {
        "left": manifest(body, frame="left", start=0, end=10),
        "middle": manifest(body, frame="middle", start=10, end=20),
        "right": manifest(body, frame="right", start=20, end=30),
        "point-start": manifest(body, frame="point-start", start=10, end=10, source="manual_note"),
        "point-end": manifest(body, frame="point-end", start=20, end=20, source="manual_note"),
        "other-boot": manifest(body, frame="other-boot", boot="boot2"),
        "other-session": manifest(body, frame="other-session", session="session2"),
    }
    ids = {name: stored(receiver, who, meta, body) for name, meta in fixtures.items()}
    receiver.delete_range(who, Deletion("range", "session", "boot", 10, 20, 1))
    for name in ("middle", "point-start"):
        expect_error("deletion_conflict", lambda: receiver.read_object(who, ids[name], 1), 409)
    for name in ("left", "right", "point-end", "other-boot"):
        assert receiver.read_object(who, ids[name], 1) == body
    assert receiver.read_object(who, ids["other-session"], 0) == body
    expect_error("deletion_conflict", lambda: receiver.init(who, manifest(body, frame="new-overlap"), 1), 409)
    receiver.delete_range(who, Deletion("legacy-all-boot", "session", None, 10, 20, 2))
    expect_error("deletion_conflict", lambda: receiver.read_object(who, ids["other-boot"], 2), 409)
    assert receiver.read_object(who, ids["right"], 2) == body


def test_cleanup_failure_keeps_durable_barrier_and_recovers_on_reopen(environment):
    path, _, connect = environment
    receiver = connect(); who = binding(); body = ciphertext(); meta = manifest(body)
    oid = stored(receiver, who, meta, body)
    neighbor = stored(receiver, who, manifest(body, frame="neighbor", start=20, end=30), body)
    query(path, "CREATE TRIGGER fail_cleanup BEFORE DELETE ON transfer_parts BEGIN SELECT RAISE(ABORT,'injected cleanup failure'); END")
    deletion = Deletion("delete", "session", "boot", 10, 20, 1)
    expect_error("cleanup_pending", lambda: receiver.delete_range(who, deletion), 503)
    assert receiver.deletion_status(who, "delete")["cleanup"] == "pending"
    assert query(path, "SELECT COUNT(*) FROM transfer_parts WHERE upload_id=?", (oid,)) == [(2,)]
    receiver.close(); receiver = connect()
    assert receiver.deletion_status(who, "delete")["cleanup"] == "pending"
    for generation in (0, 1):
        expect_error("deletion_conflict", lambda: receiver.commit(who, oid, generation), 409)
        expect_error("deletion_conflict", lambda: receiver.read_object(who, oid, generation), 409)
    assert receiver.read_object(who, neighbor, 1) == body
    query(path, "DROP TRIGGER fail_cleanup")
    receiver.close(); receiver = connect()
    assert receiver.deletion_status(who, "delete")["cleanup"] == "complete"
    assert query(path, "SELECT COUNT(*) FROM transfer_parts WHERE upload_id=?", (oid,)) == [(0,)]
    assert receiver.delete_range(who, deletion)["cleanup"] == "complete"
    expect_error("deletion_conflict", lambda: receiver.init(who, meta, 1), 409)
    expect_error("deletion_conflict", lambda: receiver.init(who, replace(meta, key_ref="late-conflict"), 0), 409)
    assert receiver.read_object(who, neighbor, 1) == body


def test_barrier_commit_failure_is_not_reported_as_blocked(environment):
    path, _, connect = environment
    receiver = connect(); who = binding(); body = ciphertext(80); meta = manifest(body)
    oid = stored(receiver, who, meta, body)
    query(path, "CREATE TRIGGER fail_barrier BEFORE INSERT ON transfer_deletions BEGIN SELECT RAISE(ABORT,'injected barrier failure'); END")
    deletion = Deletion("delete", "session", "boot", 10, 20, 1)
    expect_error("storage_unavailable", lambda: receiver.delete_range(who, deletion), 503)
    expect_error("unknown_deletion", lambda: receiver.deletion_status(who, "delete"), 404)
    assert query(path, "SELECT deletion_generation FROM transfer_sessions") == [(0,)]
    assert receiver.read_object(who, oid, 0) == body


def test_expiry_is_durable_after_rollback_clock_and_keeps_cleanup_limit_explicit(environment):
    path, clock, connect = environment
    receiver = connect(); who = binding(); body = ciphertext(80); meta = manifest(body)
    oid = stored(receiver, who, meta, body)
    neighbor = stored(receiver, who, manifest(body, frame="not-expired", expires=2000), body)
    clock[0] = 1000
    expect_error("expired", lambda: receiver.read_object(who, oid, 0), 410)
    assert query(path, "SELECT observed_expires_at_utc_ns FROM transfer_expiry_barriers") == [(1000,)]
    assert query(path, "SELECT COUNT(*) FROM transfer_parts WHERE upload_id=?", (oid,)) == [(1,)]
    receiver.close(); clock[0] = 100; receiver = connect()
    for operation in (
        lambda: receiver.init(who, meta, 0), lambda: receiver.status(who, oid, 0),
        lambda: receiver.put_part(who, oid, 0, body, 0), lambda: receiver.commit(who, oid, 0),
        lambda: receiver.read_object(who, oid, 0),
    ):
        expect_error("expired", operation, 410)
    assert receiver.read_object(who, neighbor, 0) == body


def test_expired_init_cannot_reuse_frame_id_or_report_unpersisted_expiry(environment):
    path, clock, connect = environment
    receiver = connect(); who = binding(); body = ciphertext(80); meta = manifest(body)
    clock[0] = 1000
    query(path, "CREATE TRIGGER fail_expiry BEFORE INSERT ON transfer_expiry_barriers BEGIN SELECT RAISE(ABORT,'injected expiry failure'); END")
    expect_error("storage_unavailable", lambda: receiver.init(who, meta, 0), 503)
    assert query(path, "SELECT COUNT(*) FROM transfer_expiry_barriers") == [(0,)]
    query(path, "DROP TRIGGER fail_expiry")
    expect_error("expired", lambda: receiver.init(who, meta, 0), 410)
    receiver.close(); clock[0] = 100; receiver = connect()
    expect_error("expired", lambda: receiver.init(who, replace(meta, expires_at_utc_ns=2000), 0), 410)
    assert query(path, "SELECT COUNT(*) FROM transfer_objects") == [(0,)]


@pytest.mark.parametrize("operation", ["status", "read_object", "commit"])
def test_deadline_crossed_during_materialization_denies_success(environment, monkeypatch, operation):
    path, clock, connect = environment
    receiver = connect(); who = binding(); body = ciphertext(80); meta = manifest(body)
    oid = stored(receiver, who, meta, body)
    original_parts = receiver._parts

    def deadline_after_materialization(*args, **kwargs):
        result = original_parts(*args, **kwargs)
        clock[0] = meta.expires_at_utc_ns
        return result

    monkeypatch.setattr(receiver, "_parts", deadline_after_materialization)
    expect_error("expired", lambda: getattr(receiver, operation)(who, oid, 0), 410)
    assert query(path, "SELECT COUNT(*) FROM transfer_expiry_barriers") == [(1,)]


@pytest.mark.parametrize("mutation", ["part", "part-with-hash", "metadata", "missing-part"])
def test_committed_receipts_reread_bytes_and_metadata(environment, mutation):
    path, _, connect = environment
    receiver = connect(); who = binding(); body = ciphertext(80); meta = manifest(body)
    oid = stored(receiver, who, meta, body)
    if mutation == "part":
        query(path, "UPDATE transfer_parts SET ciphertext=? WHERE upload_id=?", (b"x" * len(body), oid))
    elif mutation == "part-with-hash":
        changed = b"x" * len(body)
        query(path, "UPDATE transfer_parts SET ciphertext=?,sha256=? WHERE upload_id=?", (changed, hashlib.sha256(changed).hexdigest(), oid))
    elif mutation == "metadata":
        changed = replace(meta, key_ref="tampered-reference")
        query(path, "UPDATE transfer_objects SET manifest_json=? WHERE upload_id=?", (canonical(changed.as_dict()), oid))
    else:
        query(path, "DELETE FROM transfer_parts WHERE upload_id=?", (oid,))
    expect_error("integrity_error", lambda: receiver.status(who, oid, 0), 500)
    expect_error("integrity_error", lambda: receiver.commit(who, oid, 0), 500)
    expect_error("integrity_error", lambda: receiver.read_object(who, oid, 0), 500)


def test_commit_ack_rechecks_persisted_state_and_late_delete(environment, monkeypatch):
    path, _, connect = environment
    receiver, other = connect(), connect(); who = binding(); body = ciphertext(80); meta = manifest(body)
    oid = stage(receiver, who, meta, body)
    original_status = receiver.status
    observed = []

    def delete_before_receipt(*args):
        observed.extend(query(path, "SELECT committed FROM transfer_objects WHERE upload_id=?", (oid,)))
        other.delete_range(who, Deletion("late-delete", "session", "boot", 10, 20, 1))
        return original_status(*args)

    monkeypatch.setattr(receiver, "status", delete_before_receipt)
    expect_error("deletion_conflict", lambda: receiver.commit(who, oid, 0), 409)
    assert observed == [(1,)]
    expect_error("deletion_conflict", lambda: other.read_object(who, oid, 1), 409)


def test_two_connections_cannot_write_or_read_after_persisted_barrier(environment, monkeypatch):
    _, _, connect = environment
    receiver, other = connect(), connect(); who = binding(); body = ciphertext(); meta = manifest(body)
    oid = stage(receiver, who, meta, body)
    barrier_committed, allow_cleanup = threading.Event(), threading.Event()
    original_cleanup = receiver._cleanup_deletion
    failure = []

    def paused_cleanup(*args):
        barrier_committed.set()
        assert allow_cleanup.wait(3), "test cleanup release timed out"
        original_cleanup(*args)

    monkeypatch.setattr(receiver, "_cleanup_deletion", paused_cleanup)

    def delete():
        try:
            receiver.delete_range(who, Deletion("concurrent-delete", "session", "boot", 10, 20, 1))
        except BaseException as error:
            failure.append(error)

    worker = threading.Thread(target=delete)
    worker.start()
    try:
        assert barrier_committed.wait(3)
        assert other.deletion_status(who, "concurrent-delete")["cleanup"] == "pending"
        for generation in (0, 1):
            for action in (lambda: other.init(who, meta, generation),
                           lambda: other.put_part(who, oid, 0, body[:PART_BYTES], generation),
                           lambda: other.commit(who, oid, generation),
                           lambda: other.status(who, oid, generation),
                           lambda: other.read_object(who, oid, generation)):
                expect_error("deletion_conflict", action, 409)
    finally:
        allow_cleanup.set()
        worker.join(4)
    assert not worker.is_alive() and failure == []
    assert other.deletion_status(who, "concurrent-delete")["cleanup"] == "complete"

"""Cancel receipts describe persisted policy, without weakening existing barriers."""
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from sidecar.api import create_app
from sidecar.core import Blocked, Sidecar, SimulationClock


CREDENTIALS = {"alice-cancel-test": "alice", "bob-cancel-test": "bob"}


@pytest.fixture
def store(tmp_path):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    reference = Sidecar(tmp_path, SimulationClock(), key)
    reference.unlocked = True
    try:
        yield reference
    finally:
        reference.close()


def client(store):
    return TestClient(
        create_app(store, CREDENTIALS),
        headers={"Authorization": "Bearer alice-cancel-test"},
    )


def ended_input(store, owner="alice", text="SYNTHETIC cancel input"):
    sid = store.create_session(owner, "desktop")
    generation = store.start(owner, sid, consent=True)
    start = store.clock.mono
    store.clock.advance(5)
    fid = store.capture(
        owner, sid, generation, stream="audio", kind="audio", start=start,
        end=store.clock.mono, synthetic_text=text,
    )
    store.end(owner, sid)
    store.clock.advance(900)
    network = store.network(trusted=True, unmetered=True)
    store.upload(owner, fid, network)
    return sid, fid


def request_job(api, sid, **body):
    response = api.post(f"/sessions/{sid}/jobs", json=body)
    assert response.status_code == 200, response.text
    return response.json()["job_id"]


def assert_blocked(response):
    assert response.status_code == 403, response.text
    assert response.json()["error"] == "blocked"


def block_count(store, sid):
    return store.db.execute(
        "SELECT count(*) FROM processing_blocks WHERE session=?", (sid,)
    ).fetchone()[0]


def database_snapshot(store):
    """Rejected requests must not mutate any persisted reference-model state."""
    return tuple(store.db.iterdump())


def test_cancel_receipt_reports_actual_policy_across_repeats_and_reopen(store):
    sid, _ = ended_input(store)
    old_jobs = []
    with client(store) as api:
        old_jobs.append(request_job(api, sid))
        # False cancels a job but must never clear a previously established block.
        for requested, actual in (
            (False, False), (False, False), (True, True),
            (True, True), (False, True), (False, True),
        ):
            response = api.post(
                f"/sessions/{sid}/cancel", json={"block_automatic": requested}
            )
            assert response.status_code == 200, response.text
            assert response.json() == {"cancelled": True, "automatic_blocked": actual}
            assert block_count(store, sid) == int(actual)
            for old in old_jobs:
                assert_blocked(api.post(f"/jobs/{old}/finish"))
            if actual:
                assert_blocked(api.post(f"/sessions/{sid}/jobs", json={"automatic": True}))
            else:
                # This job must also be invalidated by the next cancellation.
                old_jobs.append(request_job(api, sid, automatic=True))

    # Ordinary close/reopen with the same explicit synthetic key, not a crash test.
    root, clock, key = store.root, store.clock, store.private_key
    store.close()
    reopened = Sidecar(root, clock, key)
    try:
        reopened.unlocked = True
        with client(reopened) as api:
            response = api.post(f"/sessions/{sid}/cancel", json={"block_automatic": False})
            assert response.status_code == 200, response.text
            assert response.json() == {"cancelled": True, "automatic_blocked": True}
            assert block_count(reopened, sid) == 1
            for old in old_jobs:
                assert_blocked(api.post(f"/jobs/{old}/finish"))
            assert_blocked(api.post(f"/sessions/{sid}/jobs", json={"automatic": True}))
    finally:
        reopened.close()


def test_once_preserves_block_and_allow_automatic_is_the_explicit_unblock(store):
    sid, _ = ended_input(store)
    with client(store) as api:
        old = request_job(api, sid)
        first = api.post(f"/sessions/{sid}/cancel", json={"block_automatic": True})
        assert first.status_code == 200
        assert first.json() == {"cancelled": True, "automatic_blocked": True}

        once = request_job(api, sid, override="once")
        assert api.post(f"/jobs/{once}/finish").status_code == 200
        assert block_count(store, sid) == 1
        assert_blocked(api.post(f"/sessions/{sid}/jobs", json={"automatic": True}))

        again = api.post(f"/sessions/{sid}/cancel", json={"block_automatic": False})
        assert again.status_code == 200
        assert again.json() == {"cancelled": True, "automatic_blocked": True}
        for prior in (old, once):
            assert_blocked(api.post(f"/jobs/{prior}/finish"))

        enabled = request_job(api, sid, override="allow_automatic")
        assert api.post(f"/jobs/{enabled}/finish").status_code == 200
        assert block_count(store, sid) == 0
        receipt = api.post(f"/sessions/{sid}/cancel", json={"block_automatic": False})
        assert receipt.status_code == 200
        assert receipt.json() == {"cancelled": True, "automatic_blocked": False}
        for prior in (old, once, enabled):
            assert_blocked(api.post(f"/jobs/{prior}/finish"))
        automatic = request_job(api, sid, automatic=True)
        assert api.post(f"/jobs/{automatic}/finish").status_code == 200


def test_foreign_owner_and_revoked_identity_cannot_return_cancel_success(store):
    sid, _ = ended_input(store)
    bob_sid, _ = ended_input(store, owner="bob", text="SYNTHETIC Bob input")
    with client(store) as api:
        old = request_job(api, sid)
        before = database_snapshot(store)
        bob_headers = {"Authorization": "Bearer bob-cancel-test"}
        for route, body in (
            (f"/sessions/{sid}/cancel", {"block_automatic": True}),
            (f"/sessions/{sid}/cancel", {"block_automatic": False}),
            (f"/sessions/{sid}/jobs", {"override": "allow_automatic"}),
        ):
            response = api.post(route, json=body, headers=bob_headers)
            assert_blocked(response)
            assert "cancelled" not in response.json()
            assert database_snapshot(store) == before

        # A denied foreign cancel has not invalidated Alice's legitimate job.
        assert api.post(f"/jobs/{old}/finish").status_code == 200
        own = api.post(
            f"/sessions/{bob_sid}/cancel", json={"block_automatic": True}, headers=bob_headers
        )
        assert own.status_code == 200 and own.json()["automatic_blocked"] is True
        assert block_count(store, sid) == 0 and block_count(store, bob_sid) == 1

        store.revoke("alice", sid)
        revoked = database_snapshot(store)
        for route, body in (
            (f"/sessions/{sid}/cancel", {"block_automatic": False}),
            (f"/sessions/{sid}/cancel", {"block_automatic": True}),
            (f"/sessions/{sid}/jobs", {"override": "once"}),
            (f"/sessions/{sid}/jobs", {"override": "allow_automatic"}),
        ):
            response = api.post(route, json=body)
            assert_blocked(response)
            assert "cancelled" not in response.json()
            assert database_snapshot(store) == revoked
        assert_blocked(api.post(f"/jobs/{old}/finish"))
        assert database_snapshot(store) == revoked


def test_cancel_and_overrides_cannot_resurrect_deleted_inputs_or_artifacts(store):
    sid, fid = ended_input(store, text="SYNTHETIC erased target")
    frame = store.frame("alice", fid)
    neighbor_sid, neighbor_fid = ended_input(store, text="SYNTHETIC retained neighbor")
    with client(store) as api:
        completed = request_job(api, sid)
        assert api.post(f"/jobs/{completed}/finish").status_code == 200
        waiting = request_job(api, sid, automatic=True)
        neighbor_job = request_job(api, neighbor_sid)
        assert api.get(f"/artifacts/{completed}").status_code == 200
        deletion = api.post(
            f"/sessions/{sid}/deletions", json={"start": frame["start"], "end": frame["end"]}
        )
        assert deletion.status_code == 200 and deletion.json()["deleted_frames"] == [fid]

        for requested in (True, False):
            response = api.post(f"/sessions/{sid}/cancel", json={"block_automatic": requested})
            assert response.status_code == 200
            assert response.json() == {"cancelled": True, "automatic_blocked": True}
        for override in ("once", "allow_automatic"):
            empty = request_job(api, sid, override=override)
            assert_blocked(api.post(f"/jobs/{empty}/finish"))
            for old in (completed, waiting):
                assert_blocked(api.post(f"/jobs/{old}/finish"))
            assert_blocked(api.get(f"/artifacts/{completed}"))
            with pytest.raises(Blocked):
                store.read("alice", fid)
            with pytest.raises(Blocked):
                store.upload("alice", fid, store.network_generation)

        revoked = store.db.execute("SELECT deleted,wrapped_key FROM frames WHERE id=?", (fid,)).fetchone()
        assert revoked["deleted"] == 1 and revoked["wrapped_key"] is None
        assert api.get("/search", params={"q": "erased target"}).json()["results"] == []
        assert api.post(f"/jobs/{neighbor_job}/finish").status_code == 200
        assert store.read("alice", neighbor_fid)["text"] == "SYNTHETIC retained neighbor"
        assert api.get(f"/artifacts/{neighbor_job}").status_code == 200

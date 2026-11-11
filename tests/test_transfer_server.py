"""CLI admission and real ASGI routes, without launching a persistent server."""
import hashlib
import os
import sqlite3
import time

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi.testclient import TestClient
import pytest

from sidecar import transfer_server
from sidecar.transfer_contract import BUFFER_NS, Manifest, upload_id
from sidecar.transfer_receiver import Receiver


TOKEN = "synthetic-cli-test-" + "a" * 48


@pytest.mark.parametrize("arguments", [
    [], ["--synthetic-only"], ["--root", "explicit"],
    ["--synthetic-only", "--root", ""],
    ["--synthetic-only", "--root", "explicit", "--host", "0.0.0.0"],
    *[["--synthetic-only", "--root", "explicit", "--port", p] for p in ("0", "80", "1023", "65536", "text")],
])
def test_cli_rejects_missing_ack_directory_or_invalid_address_port(arguments, monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail("invalid arguments must not initialize storage or a server")
    monkeypatch.setattr(transfer_server, "create_server", forbidden)
    with pytest.raises(SystemExit) as error:
        transfer_server.main(arguments)
    assert error.value.code == 2


@pytest.mark.parametrize("token", ["", "a" * 31, "a" * 513, "bad\r\n" + "x" * 40, "\u79d8" * 40])
def test_token_uses_http_validation_without_disclosure_or_storage(tmp_path, monkeypatch, capsys, token):
    root = tmp_path / "not-created"
    monkeypatch.setenv(transfer_server.TOKEN_ENV, token)
    with pytest.raises(SystemExit) as error:
        transfer_server.main(["--synthetic-only", "--root", str(root)])
    assert error.value.code == 2 and not root.exists()
    output = capsys.readouterr()
    if token:
        assert token not in output.out + output.err
    assert "token" in output.err.lower()


def test_real_factory_routes_persist_ciphertext_and_fixed_binding(tmp_path, capsys):
    root = tmp_path / "persistent"
    receiver, application = transfer_server.create_server(root, TOKEN)
    assert isinstance(receiver, Receiver)
    assert transfer_server.SYNTHETIC_BINDING.as_dict() == {
        "owner_id": "alice", "tenant_id": "local", "device_id": "synthetic-device", "binding_generation": 1,
        "policy_scope_id": "development", "processor_id": "opaque-receiver", "key_scope": "local-key",
    }
    body = AESGCM(AESGCM.generate_key(bit_length=256)).encrypt(os.urandom(12), b"synthetic CLI material", b"test")
    metadata = Manifest("frame", "session", "track", "audio", "boot", "monotonic_ns", 0, 5,
                        BUFFER_NS + 5, time.time_ns() + 60_000_000_000, 1, 1,
                        len(body), hashlib.sha256(body).hexdigest(), "synthetic-key-ref")
    authorization = {"Authorization": "Bearer " + TOKEN}
    try:
        with TestClient(application) as client:
            assert client.get("/openapi.json").status_code == 401
            response = client.post("/uploads/init", headers=authorization,
                                   json={"manifest": metadata.as_dict(), "deletion_generation": 0})
            assert response.status_code == 200
            object_id = response.json()["upload_id"]
            assert object_id == upload_id(transfer_server.SYNTHETIC_BINDING, metadata.frame_id)
            assert client.put(f"/uploads/{object_id}/parts/0", content=body,
                              headers=authorization | {"Content-Type": "application/octet-stream", "X-Deletion-Generation": "0"}).status_code == 200
            committed = client.post(f"/uploads/{object_id}/commit", headers=authorization, json={"deletion_generation": 0})
            assert committed.status_code == 200 and committed.json()["verified"] is True
    finally:
        receiver.close()
    assert (root / "receiver.sqlite").is_file()
    receiver, application = transfer_server.create_server(root, TOKEN)
    try:
        with TestClient(application) as client:
            read = client.get(f"/uploads/{object_id}/object?deletion_generation=0", headers=authorization)
            assert read.status_code == 200 and read.content == body
            assert client.get(f"/uploads/{object_id}/object?deletion_generation=0").status_code == 401
        with sqlite3.connect(root / "receiver.sqlite") as db:
            assert db.execute("SELECT COUNT(*) FROM transfer_objects").fetchone()[0] == 1
            assert TOKEN not in "\n".join(db.iterdump())
    finally:
        receiver.close()
    output = capsys.readouterr()
    assert TOKEN not in output.out + output.err


@pytest.mark.parametrize("selected_port", [None, "1024", "65535"])
def test_main_uses_literal_loopback_and_closes_real_receiver(tmp_path, monkeypatch, selected_port):
    created = []
    real_factory = transfer_server.create_server

    def factory(*args):
        result = real_factory(*args)
        created.append(result[0])
        return result

    calls = []

    def run(application, **kwargs):
        calls.append(kwargs)
        # Exercise the actual app instead of replacing its router/receiver.
        with TestClient(application) as client:
            assert client.get("/openapi.json", headers={"Authorization": "Bearer " + TOKEN}).status_code == 200

    monkeypatch.setenv(transfer_server.TOKEN_ENV, TOKEN)
    monkeypatch.setattr(transfer_server, "create_server", factory)
    monkeypatch.setattr(transfer_server.uvicorn, "run", run)
    arguments = ["--synthetic-only", "--root", str(tmp_path)]
    if selected_port:
        arguments += ["--port", selected_port]
    assert transfer_server.main(arguments) == 0
    assert calls == [{"host": "127.0.0.1", "port": int(selected_port) if selected_port else 8767,
                      "access_log": False, "ws": "none", "proxy_headers": False, "workers": 1}]
    assert len(created) == 1 and created[0]._closed


def test_receiver_closed_when_launcher_fails_and_file_root_rejected(tmp_path, monkeypatch):
    created = []
    real_factory = transfer_server.create_server

    def factory(*args):
        result = real_factory(*args)
        created.append(result[0])
        return result

    def fail(*args, **kwargs):
        raise RuntimeError("synthetic launcher failure")

    monkeypatch.setenv(transfer_server.TOKEN_ENV, TOKEN)
    monkeypatch.setattr(transfer_server, "create_server", factory)
    monkeypatch.setattr(transfer_server.uvicorn, "run", fail)
    with pytest.raises(RuntimeError, match="synthetic launcher failure"):
        transfer_server.main(["--synthetic-only", "--root", str(tmp_path / "receiver")])
    assert len(created) == 1 and created[0]._closed
    file_root = tmp_path / "file"
    file_root.write_text("existing user file", encoding="utf-8")
    with pytest.raises(ValueError, match="directory"):
        real_factory(file_root, TOKEN)
    assert file_root.read_text(encoding="utf-8") == "existing user file"

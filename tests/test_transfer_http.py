"""Existing T10/T11/T12/T16/T25/T39 boundaries: real loopback, synthetic bytes.

Receiver integration uses an independent SQLite database. Deliberately malformed
HTTP peers and receiver fault wrappers test transport boundaries only.
"""
import hashlib
import socket
import threading
import time
from contextlib import contextmanager

import httpx
import pytest
import uvicorn

from sidecar.core import Blocked
from sidecar.transfer_contract import Binding, Deletion, Manifest, BUFFER_NS, PART_BYTES, MAX_OBJECT_BYTES, TransferError
from sidecar.transfer_http import (
    LoopbackTransport, UncertainTransfer, create_receiver_app, METADATA_BYTES,
    _DeadlineSocket,
)
from sidecar.transfer_receiver import Receiver


TOKEN = 'synthetic-only-transport-token-' + 'a' * 32
OTHER_TOKEN = 'synthetic-only-transport-token-' + 'b' * 32
BINDING = Binding('owner', 'tenant', 'device', 1, 'policy', 'processor', 'key-scope')
OTHER = Binding('other-owner', 'tenant', 'device', 1, 'policy', 'processor', 'key-scope')


def manifest(data, frame='frame'):
    return Manifest(frame, 'session', 'stream', 'audio', 'boot', 'monotonic_ns',
                    0, 5_000_000_000, 5_000_000_000 + BUFFER_NS,
                    9_000_000_000_000, 1, 1, len(data), hashlib.sha256(data).hexdigest(), 'key-ref')


@contextmanager
def running(app):
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind(('127.0.0.1', 0))
    origin = f'http://127.0.0.1:{sock.getsockname()[1]}'
    server = uvicorn.Server(uvicorn.Config(app, log_level='critical', access_log=False, ws='none', timeout_graceful_shutdown=2))
    thread = threading.Thread(target=server.run, kwargs={'sockets': [sock]}, daemon=True)
    thread.start()
    try:
        deadline = time.monotonic() + 10
        while not server.started and thread.is_alive() and time.monotonic() < deadline:
            time.sleep(.01)
        assert server.started, 'loopback fixture failed to start'
        yield origin
    finally:
        server.should_exit = True
        thread.join(5)
        sock.close()
        assert not thread.is_alive(), 'loopback fixture failed to stop'


class Spy:
    def __init__(self, receiver):
        self.receiver, self.calls, self.failure = receiver, [], None

    def __getattr__(self, name):
        def call(*args):
            self.calls.append((name, args))
            if self.failure:
                raise self.failure
            return getattr(self.receiver, name)(*args)
        return call


def test_T10_T16_real_http_parts_restart_and_deletion(tmp_path):
    path = tmp_path / 'receiver.sqlite'
    data = b'synthetic opaque ciphertext\x00' * 6000
    source = manifest(data)
    receiver = Receiver(path, now_utc_ns=lambda: 1_000_000_000)
    try:
        with running(create_receiver_app(receiver, {TOKEN: BINDING, OTHER_TOKEN: OTHER})) as origin:
            transport = LoopbackTransport(origin, TOKEN)
            initialized = transport.init(source, 0)
            uid = initialized['upload_id']
            assert initialized['state'] != 'committed'
            with pytest.raises(TransferError) as missing:
                transport.read_object(uid, 0)
            assert missing.value.status == 409
            for index in reversed(range(source.part_count)):
                piece = data[index * PART_BYTES:(index + 1) * PART_BYTES]
                transport.put_part(uid, index, piece, 0)
                transport.put_part(uid, index, piece, 0)
            committed = transport.commit(uid, 0)
            assert committed['verified'] is True and committed['state'] == 'committed'
            assert transport.status(uid, 0)['manifest'] == source.as_dict()
            assert transport.read_object(uid, 0) == data
            other = LoopbackTransport(origin, OTHER_TOKEN)
            with pytest.raises(TransferError) as foreign:
                other.read_object(uid, 0)
            assert foreign.value.status == 404
            assert other.init(source, 0)['upload_id'] != uid
    finally:
        receiver.close()
    reopened = Receiver(path, now_utc_ns=lambda: 1_000_000_000)
    try:
        with running(create_receiver_app(reopened, {TOKEN: BINDING})) as origin:
            transport = LoopbackTransport(origin, TOKEN)
            assert transport.init(source, 0)['upload_id'] == uid
            assert transport.commit(uid, 0)['state'] == 'committed'
            assert transport.read_object(uid, 0) == data
            deletion = Deletion('delete-1', 'session', 'boot', 1, 2, 1)
            receipt = transport.delete_range(deletion)
            assert transport.deletion_status(deletion.deletion_id) == receipt
            assert transport.delete_range(deletion) == receipt
            for generation in (0, 1):
                with pytest.raises(TransferError) as denied:
                    transport.commit(uid, generation)
                assert denied.value.code == 'deletion_conflict'
                with pytest.raises(TransferError):
                    transport.read_object(uid, generation)
    finally:
        reopened.close()


def test_T12_auth_strict_fields_stream_limit_and_no_leak(tmp_path):
    receiver = Receiver(tmp_path / 'receiver.sqlite', now_utc_ns=lambda: 1)
    spy = Spy(receiver)
    source = manifest(b'synthetic')
    try:
        with running(create_receiver_app(spy, {TOKEN: BINDING})) as origin:
            with httpx.Client(base_url=origin, trust_env=False, timeout=3) as client:
                unauthorized = client.get('/openapi.json')
                assert unauthorized.status_code == 401 and unauthorized.headers['cache-control'] == 'no-store'
                client.headers['Authorization'] = 'Bearer ' + TOKEN
                assert client.get('/openapi.json').status_code == 200
                assert client.get('/docs').status_code == 404
                assert client.get('/redoc').status_code == 404
                assert client.options('/uploads/init', headers={'Origin': 'http://evil.invalid'}).headers.get('access-control-allow-origin') is None
                good = {'manifest': source.as_dict(), 'deletion_generation': 0}
                for bad in (
                    dict(good, owner_id='other-owner'),
                    dict(good, deletion_generation=True),
                    dict(good, manifest=dict(source.as_dict(), byte_length='9')),
                ):
                    response = client.post('/uploads/init', json=bad)
                    assert response.status_code == 422 and response.headers['cache-control'] == 'no-store'
                    assert 'other-owner' not in response.text and TOKEN not in response.text
                for raw in (b'{"deletion_generation":0,"deletion_generation":1}', b'{"secret":"DO_NOT_ECHO",', b'{"value":NaN}'):
                    response = client.post('/uploads/init', content=raw, headers={'Content-Type': 'application/json'})
                    assert response.status_code == 422 and 'DO_NOT_ECHO' not in response.text
                assert spy.calls == []
                initialized = client.post('/uploads/init', json=good).json()
                uid = initialized['upload_id']
                spy.calls.clear()
                oversized = client.put(f'/uploads/{uid}/parts/0', content=iter([b'x' * PART_BYTES, b'y']),
                                       headers={'Content-Type': 'application/octet-stream', 'X-Deletion-Generation': '0'})
                assert oversized.status_code == 413 and not spy.calls
                assert client.post('/uploads/init', content=b'x' * (METADATA_BYTES + 1), headers={'Content-Type': 'application/json'}).status_code == 413
                for query in ('deletion_generation=true', 'deletion_generation=0&deletion_generation=1', 'deletion_generation=0&owner=x'):
                    assert client.get(f'/uploads/{uid}/status?{query}').status_code == 422
                assert not spy.calls
                spy.failure = RuntimeError('DO_NOT_ECHO ' + TOKEN)
                failed = client.get(f'/uploads/{uid}/status?deletion_generation=0')
                assert failed.status_code == 500 and failed.json() == {'code': 'internal_error'}
                assert failed.headers['cache-control'] == 'no-store' and TOKEN not in failed.text
    finally:
        receiver.close()


@pytest.mark.parametrize('origin', [
    'http://localhost:8766', 'http://127.1:8766', 'http://2130706433:8766',
    'http://[::1]:8766', 'https://127.0.0.1:8766', 'http://user@127.0.0.1:8766',
    'http://127.0.0.1:8766/', 'http://127.0.0.1:8766?x=1', 'http://127.0.0.1:8766#x',
    'http://127.0.0.1:80', 'http://127.0.0.1:65536', 'http://127.0.0.1:08766',
])
def test_T25_literal_origin_only(origin):
    with pytest.raises(ValueError):
        LoopbackTransport(origin, TOKEN)


def test_T12_T25_credentials_origin_and_no_dns_or_proxy(tmp_path, monkeypatch):
    receiver = Receiver(tmp_path / 'receiver.sqlite', now_utc_ns=lambda: 1)
    try:
        for token in ('short', 'x' * 31, 'x' * 40 + '\r\nInjected: yes'):
            with pytest.raises(ValueError):
                create_receiver_app(receiver, {token: BINDING})
            with pytest.raises(ValueError):
                LoopbackTransport('http://127.0.0.1:8766', token)
        credentials = {TOKEN: BINDING}
        app = create_receiver_app(receiver, credentials)
        credentials[TOKEN] = OTHER
        with running(app) as origin:
            transport = LoopbackTransport(origin, TOKEN)
            assert transport.origin == origin
            with pytest.raises(AttributeError):
                transport.origin = 'http://127.0.0.1:9999'
            monkeypatch.setenv('HTTP_PROXY', 'http://127.0.0.1:1')
            monkeypatch.setenv('ALL_PROXY', 'http://127.0.0.1:1')
            monkeypatch.setattr(socket, 'getaddrinfo', lambda *a, **k: (_ for _ in ()).throw(AssertionError('DNS forbidden')))
            received = transport.init(manifest(b'synthetic'), 0)
            assert receiver.status(BINDING, received['upload_id'], 0)['upload_id'] == received['upload_id']
            with pytest.raises(TransferError):
                receiver.status(OTHER, received['upload_id'], 0)
    finally:
        receiver.close()


@contextmanager
def raw_peer(response, *, drip=False):
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(('127.0.0.1', 0)); listener.listen(1); listener.settimeout(3)
    received, failures = [], []

    def serve():
        try:
            connection, _ = listener.accept()
            with connection:
                connection.settimeout(3)
                request = b''
                while b'\r\n\r\n' not in request and len(request) < 32000:
                    chunk = connection.recv(4096)
                    if not chunk:
                        break
                    request += chunk
                received.append(request)
                if drip:
                    for byte in response:
                        connection.send(bytes([byte])); time.sleep(.02)
                else:
                    connection.sendall(response)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass  # The bounded client is expected to close malformed/dripping peers.
        except Exception as error:
            failures.append(error)

    thread = threading.Thread(target=serve, daemon=True); thread.start()
    try:
        yield f'http://127.0.0.1:{listener.getsockname()[1]}', received
    finally:
        thread.join(4); listener.close()
        assert not thread.is_alive() and not failures


@pytest.mark.parametrize('response', [
    b'HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\n{}',
    b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 9\r\n\r\n{}',
    b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 999999\r\n\r\n',
    b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n[]',
    b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 13\r\n\r\n{"a":1,"a":2}',
    b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{"a":1e999}',
    b'HTTP/1.1 503 Error\r\nContent-Type: text/plain\r\nContent-Length: 6\r\n\r\nSECRET',
    b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nTransfer-Encoding: chunked\r\n\r\n10\r\n{}',
])
def test_T10_T39_bad_receipt_is_uncertain(response):
    with raw_peer(response) as (origin, received):
        with pytest.raises(UncertainTransfer) as unknown:
            LoopbackTransport(origin, TOKEN, timeout=1).status('a' * 64, 0)
        assert 'SECRET' not in str(unknown.value) and TOKEN not in str(unknown.value)
        assert len(received) == 1


def test_T10_T25_no_redirect_or_retry_and_retry_after():
    for status, code, extra in ((307, 'redirect_forbidden', b'Location: http://127.0.0.1:1/\r\n'),
                                (429, 'rate_limited', b'Retry-After: 2.5\r\n')):
        body = ('{"code":"' + code + '"}').encode()
        response = f'HTTP/1.1 {status} Rejected\r\nContent-Type: application/json\r\nContent-Length: {len(body)}\r\n'.encode() + extra + b'\r\n' + body
        with raw_peer(response) as (origin, received):
            with pytest.raises(TransferError) as error:
                LoopbackTransport(origin, TOKEN).status('a' * 64, 0)
            assert error.value.status == status and error.value.code == code
            if status == 429:
                assert error.value.retry_after == 2.5
            assert len(received) == 1


def test_T10_total_deadline_includes_dripping_response_headers():
    with raw_peer(b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}', drip=True) as (origin, received):
        start = time.monotonic()
        with pytest.raises(UncertainTransfer):
            LoopbackTransport(origin, TOKEN, timeout=.08).status('a' * 64, 0)
        assert time.monotonic() - start < 1 and len(received) == 1


@pytest.mark.parametrize('declared', [0, MAX_OBJECT_BYTES + 1])
def test_T10_object_response_length_is_bounded(declared):
    response = f'HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: {declared}\r\n\r\n'.encode()
    with raw_peer(response) as (origin, received):
        with pytest.raises(UncertainTransfer):
            LoopbackTransport(origin, TOKEN).read_object('a' * 64, 0)
        assert len(received) == 1


def test_T16_T25_guard_single_nonblocking_send_and_wait_outside_lock(tmp_path, monkeypatch):
    receiver = Receiver(tmp_path / 'receiver.sqlite', now_utc_ns=lambda: 1)
    lock = threading.Lock(); attempts = []; delivered = []; waits = []
    real_send, real_select = socket.socket.send, __import__('select').select
    first = True

    def send(connection, data, *args):
        nonlocal first
        assert lock.locked() and connection.gettimeout() == 0
        assert len(data) <= PART_BYTES
        if first:
            first = False
            raise BlockingIOError()
        count = real_send(connection, data[:17], *args)
        delivered.append(count)
        return count

    def wait(*args):
        assert not lock.locked()
        waits.append(True)
        return real_select(*args)

    def guard(action, attempted):
        with lock:
            attempts.append(attempted)
            return action()

    try:
        with running(create_receiver_app(receiver, {TOKEN: BINDING})) as origin:
            monkeypatch.setattr(_DeadlineSocket, 'send', send)
            monkeypatch.setattr('sidecar.transfer_http._wait_writable', wait)
            result = LoopbackTransport(origin, TOKEN).init(manifest(b'synthetic'), 0, guard=guard)
            assert result['upload_id'] and waits and delivered
            assert len(attempts) == len(delivered) + 1
            assert sum(delivered) > len(b'synthetic') and max(attempts) <= PART_BYTES
    finally:
        receiver.close()


@pytest.mark.parametrize('failure', [Blocked('synthetic stop'), TransferError('deletion_conflict')])
def test_T16_guard_refusal_propagates_and_receiver_not_called(tmp_path, failure):
    receiver = Receiver(tmp_path / 'receiver.sqlite', now_utc_ns=lambda: 1)
    spy = Spy(receiver)
    def guard(action, attempted):
        raise failure
    try:
        with running(create_receiver_app(spy, {TOKEN: BINDING})) as origin:
            with pytest.raises(type(failure)) as caught:
                LoopbackTransport(origin, TOKEN).init(manifest(b'synthetic'), 0, guard=guard)
            assert caught.value is failure and not spy.calls
    finally:
        receiver.close()


def test_T16_late_guard_refusal_keeps_prior_delivery_known(tmp_path):
    receiver = Receiver(tmp_path / 'receiver.sqlite', now_utc_ns=lambda: 1)
    spy = Spy(receiver)
    failure = TransferError('deletion_conflict')
    delivered = []

    def guard(action, attempted):
        if delivered:
            raise failure
        sent = action()
        delivered.append(sent)
        return sent

    try:
        with running(create_receiver_app(spy, {TOKEN: BINDING})) as origin:
            with pytest.raises(TransferError) as caught:
                LoopbackTransport(origin, TOKEN).init(manifest(b'synthetic'), 0, guard=guard)
            assert caught.value is failure
            assert delivered and sum(delivered) > 0 and not spy.calls
    finally:
        receiver.close()

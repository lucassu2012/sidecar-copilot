"""Bounded, synthetic-only loopback transport. No retry or remote deployment.

The optional guard owns the sender's durable admission/accounting. It surrounds
one nonblocking socket.send, never a connection, a readiness wait, or a response.
Without a guard this adapter is suitable only for receiver/transport fixtures.
"""
from __future__ import annotations

import hmac
import http.client
import json
import math
import re
from select import select as _wait_writable
import socket
import time
from urllib.parse import quote

from fastapi import Depends, FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from starlette.concurrency import run_in_threadpool

from .transfer_contract import (
    Binding, Deletion, Manifest, MAX_OBJECT_BYTES, MAX_TIME, PART_BYTES,
    TransferError, UncertainTransfer, canonical, identifier, integer, validate_upload_id,
)

METADATA_BYTES = 256 * 1024
_TOKEN = re.compile(r'[A-Za-z0-9._~+/-]{32,512}={0,2}')
_CODE = re.compile(r'[a-z][a-z0-9_]{0,63}')


def _token(value):
    if not isinstance(value, str) or not _TOKEN.fullmatch(value):
        raise ValueError('a random ASCII bearer token of at least 32 characters is required')
    return value


def _decimal(value):
    if not isinstance(value, str) or not re.fullmatch(r'0|[1-9][0-9]{0,18}', value):
        raise TransferError('invalid_integer', 422)
    return integer(int(value))


def _json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('duplicate key')
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValueError('nonfinite number')

    def finite_float(value):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError('nonfinite number')
        return number

    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs,
                      parse_constant=invalid_constant, parse_float=finite_float)


def _fields(value, expected):
    if type(value) is not dict or set(value) != set(expected):
        raise TransferError('invalid_fields', 422)
    return value


def _retry_after(value):
    if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= MAX_TIME:
        return None
    return value


async def _body(request, limit, content_type):
    if len(request.headers.getlist('content-type')) != 1 or request.headers.get('content-type', '').split(';', 1)[0].strip().lower() != content_type:
        raise TransferError('unsupported_content_type', 415)
    lengths = request.headers.getlist('content-length')
    if len(lengths) > 1:
        raise TransferError('invalid_request', 422)
    if lengths and _decimal(lengths[0]) > limit:
        raise TransferError('request_too_large', 413)
    chunks, size = [], 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > limit:
            raise TransferError('request_too_large', 413)
        chunks.append(chunk)
    if lengths and size != int(lengths[0]):
        raise TransferError('invalid_request', 422)
    return b''.join(chunks)


async def _document(request, fields):
    raw = await _body(request, METADATA_BYTES, 'application/json')
    try:
        return _fields(_json(raw), fields)
    except (ValueError, UnicodeError, RecursionError):
        raise TransferError('invalid_json', 422) from None


def _query_generation(request):
    if set(request.query_params) != {'deletion_generation'} or len(request.query_params.getlist('deletion_generation')) != 1:
        raise TransferError('invalid_query', 422)
    return _decimal(request.query_params['deletion_generation'])


def _no_query(request):
    if request.query_params:
        raise TransferError('invalid_query', 422)


def create_receiver_app(receiver, credentials: dict[str, Binding]):
    """Bind tokens to server-owned identities; requests never select a Binding."""
    if not isinstance(credentials, dict) or not credentials:
        raise ValueError('explicit credential bindings required')
    mapping = {}
    for token, binding in credentials.items():
        if type(binding) is not Binding:
            raise ValueError('explicit credential bindings required')
        mapping[_token(token)] = Binding.from_dict(binding.as_dict())
    app = FastAPI(title='Sidecar synthetic loopback receiver', version='0.1.0',
                  docs_url=None, redoc_url=None, openapi_url=None)
    bearer = HTTPBearer(auto_error=False)

    def identity(request: Request, auth: HTTPAuthorizationCredentials | None = Depends(bearer)):
        if len(request.headers.getlist('authorization')) == 1 and auth and auth.scheme.lower() == 'bearer':
            supplied = auth.credentials.encode('utf-8')
            for token, binding in mapping.items():
                if hmac.compare_digest(supplied, token.encode('ascii')):
                    return binding
        raise TransferError('authentication_required', 401)

    @app.middleware('http')
    async def response_boundary(request, call_next):
        try:
            response = await call_next(request)
        except Exception:
            # Do not serialize/log receiver exception text, body, token or paths.
            response = JSONResponse({'code': 'internal_error'}, status_code=500)
        response.headers['Cache-Control'] = 'no-store'
        response.headers['X-Content-Type-Options'] = 'nosniff'
        return response

    @app.exception_handler(TransferError)
    async def transfer_error(request, error):
        code = error.code if isinstance(error.code, str) and _CODE.fullmatch(error.code) else 'transfer_error'
        status = error.status if type(error.status) is int and 400 <= error.status <= 599 else 500
        headers = {'WWW-Authenticate': 'Bearer'} if status == 401 else {}
        delay = _retry_after(getattr(error, 'retry_after', None))
        if status == 429 and delay is not None:
            headers['Retry-After'] = str(delay)
        return JSONResponse({'code': code}, status_code=status, headers=headers)

    @app.exception_handler(RequestValidationError)
    async def invalid_request(request, error):
        return JSONResponse({'code': 'invalid_request'}, status_code=422)

    @app.get('/openapi.json')
    def schema(binding=Depends(identity)):
        return app.openapi()

    @app.post('/uploads/init')
    async def initialize(request: Request, binding=Depends(identity)):
        _no_query(request)
        body = await _document(request, ('manifest', 'deletion_generation'))
        manifest = Manifest.from_dict(body['manifest'])
        generation = integer(body['deletion_generation'])
        return await run_in_threadpool(receiver.init, binding, manifest, generation)

    @app.put('/uploads/{upload_id}/parts/{index}')
    async def part(upload_id: str, index: str, request: Request, binding=Depends(identity)):
        _no_query(request)
        validate_upload_id(upload_id)
        number = _decimal(index)
        generations = request.headers.getlist('x-deletion-generation')
        if len(generations) != 1:
            raise TransferError('invalid_generation', 422)
        generation = _decimal(generations[0])
        ciphertext = await _body(request, PART_BYTES, 'application/octet-stream')
        return await run_in_threadpool(receiver.put_part, binding, upload_id, number, ciphertext, generation)

    @app.post('/uploads/{upload_id}/commit')
    async def commit(upload_id: str, request: Request, binding=Depends(identity)):
        _no_query(request)
        validate_upload_id(upload_id)
        body = await _document(request, ('deletion_generation',))
        return await run_in_threadpool(receiver.commit, binding, upload_id, integer(body['deletion_generation']))

    @app.get('/uploads/{upload_id}/status')
    async def status(upload_id: str, request: Request, binding=Depends(identity)):
        validate_upload_id(upload_id)
        return await run_in_threadpool(receiver.status, binding, upload_id, _query_generation(request))

    @app.get('/uploads/{upload_id}/object')
    async def read_object(upload_id: str, request: Request, binding=Depends(identity)):
        validate_upload_id(upload_id)
        ciphertext = await run_in_threadpool(receiver.read_object, binding, upload_id, _query_generation(request))
        if type(ciphertext) is not bytes or len(ciphertext) > MAX_OBJECT_BYTES:
            raise TransferError('invalid_response', 500)
        return Response(ciphertext, media_type='application/octet-stream')

    @app.post('/deletions')
    async def delete(request: Request, binding=Depends(identity)):
        _no_query(request)
        body = await _document(request, ('deletion',))
        return await run_in_threadpool(receiver.delete_range, binding, Deletion.from_dict(body['deletion']))

    @app.get('/deletions/{deletion_id}')
    async def deletion_status(deletion_id: str, request: Request, binding=Depends(identity)):
        _no_query(request)
        return await run_in_threadpool(receiver.deletion_status, binding, identifier(deletion_id))

    return app


def _remaining(deadline):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError('transfer deadline elapsed')
    return remaining


class _DeadlineSocket(socket.socket):
    """Bound slow response headers/body too, not just each individual recv."""
    def __init__(self, deadline):
        super().__init__(socket.AF_INET, socket.SOCK_STREAM)
        self.deadline = deadline

    def recv_into(self, buffer, nbytes=0, flags=0):
        self.settimeout(_remaining(self.deadline))
        return super().recv_into(buffer, nbytes, flags)

    def recv(self, size, flags=0):
        self.settimeout(_remaining(self.deadline))
        return super().recv(size, flags)


class _GuardedConnection(http.client.HTTPConnection):
    def __init__(self, port, timeout, guard):
        super().__init__('127.0.0.1', port, timeout=timeout)
        self.deadline = time.monotonic() + timeout
        self.guard = guard

    def connect(self):
        # Numeric AF_INET connection: no getaddrinfo, environment proxy or tunnel.
        connection = _DeadlineSocket(self.deadline)
        try:
            connection.settimeout(_remaining(self.deadline))
            connection.connect(('127.0.0.1', self.port))
        except BaseException:
            connection.close()
            raise
        self.sock = connection

    def send(self, data):
        if self.sock is None:
            self.connect()
        if not isinstance(data, (bytes, bytearray, memoryview)):
            raise UncertainTransfer('unsupported request stream')
        view = memoryview(data)
        position = 0
        try:
            while position < len(view):
                _remaining(self.deadline)
                chunk = view[position:position + PART_BYTES]
                self.sock.setblocking(False)
                try:
                    # The action performs exactly one nonblocking syscall. The guard
                    # may recheck deletion and durably account actual returned bytes.
                    action = lambda: self.sock.send(chunk)
                    sent = action() if self.guard is None else self.guard(action, len(chunk))
                except BlockingIOError:
                    # No application lock is held while waiting for socket readiness.
                    _, writable, _ = _wait_writable([], [self.sock], [], _remaining(self.deadline))
                    if not writable:
                        raise TimeoutError('transfer send deadline elapsed')
                    continue
                if type(sent) is not int or not 0 < sent <= len(chunk):
                    raise UncertainTransfer('invalid guarded send result')
                position += sent
        finally:
            if self.sock is not None:
                self.sock.settimeout(max(0.001, self.deadline - time.monotonic()))


class LoopbackTransport:
    def __init__(self, base_url, token, timeout=5.0):
        match = re.fullmatch(r'http://127\.0\.0\.1:([1-9][0-9]{3,4})', base_url) if isinstance(base_url, str) else None
        if match is None or not 1024 <= int(match[1]) <= 65535:
            raise ValueError('an explicit literal loopback HTTP origin is required')
        if type(timeout) not in (int, float) or not math.isfinite(timeout) or not 0 < timeout <= 60:
            raise ValueError('timeout must be finite and within 0..60 seconds')
        self._origin, self._port, self._token, self.timeout = base_url, int(match[1]), _token(token), timeout

    @property
    def origin(self):
        return self._origin

    @property
    def port(self):
        return self._port

    def _request(self, method, path, *, document=None, payload=None, generation=None, binary=False, guard=None):
        headers = {'Authorization': 'Bearer ' + self._token, 'Accept': 'application/octet-stream' if binary else 'application/json'}
        if document is not None:
            payload = canonical(document)
            if len(payload) > METADATA_BYTES:
                raise TransferError('request_too_large', 413)
            headers['Content-Type'] = 'application/json'
        elif payload is not None:
            headers['Content-Type'] = 'application/octet-stream'
        if generation is not None:
            headers['X-Deletion-Generation'] = str(generation)
        connection = _GuardedConnection(self.port, self.timeout, guard)
        response = None
        try:
            connection.request(method, path, body=payload, headers=headers)
            response = connection.getresponse()
            if not 100 <= response.status <= 599:
                raise UncertainTransfer('invalid response status')
            success = 200 <= response.status < 300
            limit = MAX_OBJECT_BYTES if success and binary else METADATA_BYTES
            lengths = response.headers.get_all('Content-Length', [])
            if len(lengths) > 1 or (lengths and not re.fullmatch(r'0|[1-9][0-9]{0,18}', lengths[0])):
                raise UncertainTransfer('invalid response length')
            expected = int(lengths[0]) if lengths else None
            if expected is not None and expected > limit:
                raise UncertainTransfer('response exceeds limit')
            if lengths and response.headers.get('Transfer-Encoding'):
                raise UncertainTransfer('ambiguous response framing')
            if response.headers.get('Transfer-Encoding', '').lower() not in ('', 'chunked') or response.headers.get('Content-Encoding', '').lower() not in ('', 'identity'):
                raise UncertainTransfer('unsupported response encoding')
            raw = response.read(limit + 1)
            if len(raw) > limit or (expected is not None and len(raw) != expected):
                raise UncertainTransfer('incomplete or oversized response')
            content_type = response.getheader('Content-Type', '').split(';', 1)[0].strip().lower()
            if success and binary:
                if content_type != 'application/octet-stream' or not raw:
                    raise UncertainTransfer('invalid object response')
                return raw
            if content_type != 'application/json':
                raise UncertainTransfer('invalid metadata response')
            try:
                result = _json(raw)
            except (ValueError, UnicodeError, RecursionError):
                raise UncertainTransfer('invalid metadata response') from None
            if type(result) is not dict:
                raise UncertainTransfer('invalid metadata response')
            if success:
                return result
            code = result.get('code')
            if not isinstance(code, str) or not _CODE.fullmatch(code):
                raise UncertainTransfer('invalid error response')
            error = TransferError(code, response.status)
            if response.status == 429:
                delay = response.getheader('Retry-After')
                if delay is not None:
                    if not re.fullmatch(r'[0-9]+(?:\.[0-9]+)?', delay):
                        raise UncertainTransfer('invalid retry response')
                    retry = _retry_after(float(delay))
                    if retry is None:
                        raise UncertainTransfer('invalid retry response')
                    error.retry_after = retry
            raise error
        except (OSError, http.client.HTTPException):
            raise UncertainTransfer('transfer outcome requires reconciliation') from None
        finally:
            try:
                if response is not None:
                    response.close()
            finally:
                connection.close()

    def init(self, manifest, generation, *, guard=None):
        if type(manifest) is not Manifest:
            raise TransferError('invalid_fields', 422)
        checked = Manifest.from_dict(manifest.as_dict())
        return self._request('POST', '/uploads/init', document={'manifest': checked.as_dict(), 'deletion_generation': integer(generation)}, guard=guard)

    def put_part(self, upload_id, index, ciphertext, generation, *, guard=None):
        validate_upload_id(upload_id); integer(index); integer(generation)
        if type(ciphertext) is not bytes or not 1 <= len(ciphertext) <= PART_BYTES:
            raise TransferError('invalid_part', 422)
        return self._request('PUT', f'/uploads/{upload_id}/parts/{index}', payload=ciphertext, generation=generation, guard=guard)

    def commit(self, upload_id, generation, *, guard=None):
        validate_upload_id(upload_id)
        return self._request('POST', f'/uploads/{upload_id}/commit', document={'deletion_generation': integer(generation)}, guard=guard)

    def status(self, upload_id, generation, *, guard=None):
        validate_upload_id(upload_id)
        return self._request('GET', f'/uploads/{upload_id}/status?deletion_generation={integer(generation)}', guard=guard)

    def read_object(self, upload_id, generation, *, guard=None):
        validate_upload_id(upload_id)
        return self._request('GET', f'/uploads/{upload_id}/object?deletion_generation={integer(generation)}', binary=True, guard=guard)

    def delete_range(self, deletion, *, guard=None):
        if type(deletion) is not Deletion:
            raise TransferError('invalid_fields', 422)
        checked = Deletion.from_dict(deletion.as_dict())
        return self._request('POST', '/deletions', document={'deletion': checked.as_dict()}, guard=guard)

    def deletion_status(self, deletion_id, *, guard=None):
        path = quote(identifier(deletion_id), safe='')
        return self._request('GET', '/deletions/' + path, guard=guard)

"""Frozen-PRD upload slice: immutable references and separate deletion admission.

This transport contract is used only by the synthetic local implementation.
It does not attest Android clocks, device identity, consent or a cloud provider.
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import asdict, dataclass, fields

PART_BYTES = 64 * 1024
MAX_OBJECT_BYTES = 8 * 1024 * 1024  # Local experiment limit, not a product capability.
BUFFER_NS = 900_000_000_000
MAX_TIME = (1 << 63) - 1


class TransferError(RuntimeError):
    def __init__(self, code: str, status: int = 409):
        super().__init__(code)
        self.code, self.status = code, status


class UncertainTransfer(RuntimeError):
    """The request's delivery/commit outcome must be reconciled, not guessed."""


def canonical(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def identifier(value: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_.:@-]{1,160}', value):
        raise TransferError('invalid_identity', 422)
    return value


def integer(value: int, minimum=0, maximum=MAX_TIME) -> int:
    if type(value) is not int or not minimum <= value <= maximum:
        raise TransferError('invalid_integer', 422)
    return value


class Record:
    def as_dict(self):
        return asdict(self)

    @classmethod
    def from_dict(cls, value):
        if not isinstance(value, dict) or set(value) != {f.name for f in fields(cls)}:
            raise TransferError('invalid_fields', 422)
        return cls(**value)


@dataclass(frozen=True)
class Binding(Record):
    owner_id: str
    tenant_id: str
    device_id: str
    binding_generation: int
    policy_scope_id: str
    processor_id: str
    key_scope: str

    def __post_init__(self):
        for f in fields(self):
            if f.name == 'binding_generation':
                integer(self.binding_generation, 1)
            else:
                identifier(getattr(self, f.name))

    @property
    def key(self):
        return hashlib.sha256(canonical(self.as_dict())).hexdigest()


@dataclass(frozen=True)
class Manifest(Record):
    frame_id: str
    session_id: str
    stream_id: str
    source_type: str
    boot_id: str
    clock_basis: str
    sample_start_ns: int
    sample_end_ns: int
    eligible_at_ns: int
    expires_at_utc_ns: int
    capture_generation: int
    media_version: int
    byte_length: int
    sha256: str
    key_ref: str

    def __post_init__(self):
        for name in ('frame_id', 'session_id', 'stream_id', 'boot_id', 'key_ref'):
            identifier(getattr(self, name))
        if self.source_type not in ('audio', 'desktop_frame', 'manual_note') or self.clock_basis != 'monotonic_ns':
            raise TransferError('unsupported_source_or_clock', 422)
        for name in ('sample_start_ns', 'sample_end_ns', 'eligible_at_ns', 'expires_at_utc_ns', 'capture_generation'):
            integer(getattr(self, name))
        integer(self.media_version, 1, 1)
        integer(self.byte_length, 1, MAX_OBJECT_BYTES)
        if not isinstance(self.sha256, str) or not re.fullmatch('[0-9a-f]{64}', self.sha256):
            raise TransferError('invalid_hash', 422)
        if self.sample_start_ns > self.sample_end_ns or (self.sample_start_ns == self.sample_end_ns and self.source_type != 'manual_note'):
            raise TransferError('invalid_source_range', 422)
        if self.eligible_at_ns < self.sample_end_ns + BUFFER_NS:
            raise TransferError('insufficient_buffer', 422)

    @property
    def part_count(self):
        return (self.byte_length + PART_BYTES - 1) // PART_BYTES

    def part_length(self, index):
        integer(index, 0, self.part_count - 1)
        return min(PART_BYTES, self.byte_length - index * PART_BYTES)

    def digest(self, binding: Binding):
        return hashlib.sha256(canonical({'binding': binding.as_dict(), 'manifest': self.as_dict()})).hexdigest()


@dataclass(frozen=True)
class Deletion(Record):
    deletion_id: str
    session_id: str
    boot_id: str | None
    start_ns: int
    end_ns: int
    generation: int

    def __post_init__(self):
        identifier(self.deletion_id); identifier(self.session_id)
        # None explicitly preserves a legacy all-boot barrier, never guesses a boot.
        if self.boot_id is not None:
            identifier(self.boot_id)
        integer(self.start_ns); integer(self.end_ns); integer(self.generation, 1)
        if self.start_ns >= self.end_ns:
            raise TransferError('invalid_deletion_range', 422)

    def intersects(self, manifest: Manifest):
        if self.session_id != manifest.session_id or (self.boot_id is not None and self.boot_id != manifest.boot_id):
            return False
        if manifest.sample_start_ns == manifest.sample_end_ns:
            return self.start_ns <= manifest.sample_start_ns < self.end_ns
        return self.start_ns < manifest.sample_end_ns and self.end_ns > manifest.sample_start_ns


def upload_id(binding: Binding, frame_id: str):
    return hashlib.sha256(canonical([binding.key, identifier(frame_id)])).hexdigest()


def validate_upload_id(value):
    if not isinstance(value, str) or not re.fullmatch('[0-9a-f]{64}', value):
        raise TransferError('invalid_upload_id', 422)
    return value

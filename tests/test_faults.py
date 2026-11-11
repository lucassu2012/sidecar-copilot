"""Failure probes for existing T10/T39/T40; no additional PRD IDs."""
import os
from unittest.mock import patch

import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from sidecar.core import Sidecar, SimulationClock, Blocked


def create(tmp_path):
    a=Sidecar(tmp_path,SimulationClock(),rsa.generate_private_key(public_exponent=65537,key_size=2048))
    a.unlocked=True
    sid=a.create_session('synthetic','in_person')
    g=a.start('synthetic',sid,consent=True)
    return a,sid,g


def capture(a,sid,g):
    start=a.clock.mono
    a.clock.advance(5)
    return a.capture('synthetic',sid,g,stream='mic',kind='audio',start=start,end=a.clock.mono,synthetic_text='synthetic fixture')


def test_T39_fsync_failure_does_not_commit_and_recovery_removes_tail(tmp_path):
    a,sid,g=create(tmp_path)
    try:
        fid=capture(a,sid,g)
        with patch('sidecar.core.os.fsync',side_effect=OSError('synthetic disk full')):
            with pytest.raises(OSError): capture(a,sid,g)
        assert a.db.execute('SELECT count(*) FROM frames').fetchone()[0]==1
        assert a.recover()['orphan_bytes']>0
        assert a.read('synthetic',fid)['synthetic']
    finally: a.close()


def test_T40_compaction_io_failure_preserves_only_valid_copy(tmp_path):
    a,sid,g=create(tmp_path)
    try:
        first=capture(a,sid,g)
        second=capture(a,sid,g)
        a.delete_range('synthetic',sid,5,10)
        with patch('sidecar.core.os.fsync',side_effect=OSError('synthetic disk full')):
            with pytest.raises(OSError): a.compact()
        a.recover()
        assert a.read('synthetic',first)['synthetic']
        with pytest.raises(Blocked): a.read('synthetic',second)
    finally: a.close()


def test_T12_authenticated_metadata_rejects_index_substitution(tmp_path):
    a,sid,g=create(tmp_path)
    try:
        fid=capture(a,sid,g)
        with a.db: a.db.execute('UPDATE frames SET eligible=0 WHERE id=?',(fid,))
        with pytest.raises(Blocked): a.read('synthetic',fid)
    finally: a.close()


def test_T10_1000_lost_response_retries_one_visible_object(tmp_path):
    a,sid,g=create(tmp_path)
    try:
        fid=capture(a,sid,g)
        a.clock.advance(900)
        n=a.network(trusted=True,unmetered=True)
        for attempt in range(1000):
            result=a.upload('synthetic',fid,n)
            assert result['duplicate']==(attempt>0)
        assert len(a.search('synthetic','fixture'))==1
    finally: a.close()

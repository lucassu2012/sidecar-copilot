"""Existing PRD T IDs are mappings, not claims of full scenario acceptance."""
import threading

import pytest
from cryptography.hazmat.primitives.asymmetric import rsa

from sidecar.core import Sidecar, SimulationClock, Blocked, Conflict


@pytest.fixture(scope='session')
def key():
    return rsa.generate_private_key(public_exponent=65537,key_size=2048)


@pytest.fixture
def app(tmp_path,key):
    a=Sidecar(tmp_path,SimulationClock(),key)
    a.unlocked=True
    yield a
    a.close()


def recording(a,mode='in_person'):
    sid=a.create_session('alice',mode)
    gen=a.start('alice',sid,consent=True)
    return sid,gen


def frame(a,sid,gen,text='synthetic amount 100',kind='audio',stream='mic'):
    start=a.clock.mono
    a.clock.advance(5)
    return a.capture('alice',sid,gen,stream=stream,kind=kind,start=start,end=a.clock.mono,synthetic_text=text)


def mature(a,*ids):
    a.clock.advance(900)
    n=a.network(trusted=True,unmetered=True)
    for fid in ids:
        a.upload('alice',fid,n)
    return n


def test_T01_T36_no_consent_no_capture(app):
    sid=app.create_session('alice','in_person')
    with pytest.raises(Blocked): app.start('alice',sid,consent=False)
    with pytest.raises(Blocked): frame(app,sid,0)
    assert not list(app.root.glob('*.seg'))


def test_T08_pause_barrier_and_stale_callback(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    app.pause('alice',sid)
    with pytest.raises(Blocked): frame(app,sid,g)
    g2=app.start('alice',sid,consent=True)
    with pytest.raises(Blocked): frame(app,sid,g)
    assert g2>g and app.read('alice',fid)['synthetic']


def test_T15_last_sample_buffer_and_no_wall_clock_shortcut(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    n=app.network(trusted=True,unmetered=True)
    app.clock.utc+=100
    app.clock.advance(899)
    with pytest.raises(Blocked): app.upload('alice',fid,n)
    app.clock.advance(1)
    assert not app.upload('alice',fid,n)['duplicate']
    assert app.upload('alice',fid,n)['duplicate']
    assert len(app.search('alice','amount'))==1


@pytest.mark.parametrize('trusted,unmetered',[(False,False),(True,False),(False,True),(None,True),(True,None)])
def test_T25_network_fail_closed(app,trusted,unmetered):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    app.clock.advance(900)
    n=app.network(trusted=trusted,unmetered=unmetered)
    with pytest.raises(Blocked): app.upload('alice',fid,n)


def test_T25_network_generation(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    app.clock.advance(900)
    old=app.network(trusted=True,unmetered=True)
    app.network(trusted=True,unmetered=True)
    with pytest.raises(Blocked): app.upload('alice',fid,old)


def test_T23_T44_lock_write_allowed_but_read_and_upload_blocked(app):
    sid,g=recording(app)
    app.unlocked=False
    fid=frame(app,sid,g)
    with pytest.raises(Blocked): app.read('alice',fid)
    app.clock.advance(900)
    n=app.network(trusted=True,unmetered=True)
    with pytest.raises(Blocked): app.upload('alice',fid,n)
    app.unlocked=True
    assert app.read('alice',fid)['synthetic']


def test_T39_recovery_orphan_tail_and_no_auto_resume(tmp_path,key):
    a=Sidecar(tmp_path,SimulationClock(),key)
    a.unlocked=True
    sid,g=recording(a)
    fid=frame(a,sid,g)
    path=next(tmp_path.glob('*.seg'))
    original=path.stat().st_size
    a.close()
    with open(path,'ab') as out: out.write(b'uncommitted')
    b=Sidecar(tmp_path,SimulationClock(mono=10),key)
    try:
        assert b.recovery['orphan_bytes']==11
        assert path.stat().st_size==original
        assert b.session('alice',sid)['state']=='paused'
        assert not b.unlocked
        b.unlocked=True
        assert b.read('alice',fid)['synthetic']
    finally: b.close()


def test_T39_ciphertext_tamper_fails_closed(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    path=next(app.root.glob('*.seg'))
    with open(path,'r+b') as out: out.write(b'BAD')
    with pytest.raises(Blocked): app.read('alice',fid)


def test_T39_mapping_bounds_fail_closed(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    with app.db: app.db.execute('UPDATE frames SET offset=-1 WHERE id=?',(fid,))
    with pytest.raises(Blocked): app.read('alice',fid)


def test_T16_T40_delete_middle_compact_remaining_and_reopen(app,key):
    sid,g=recording(app)
    a,b,c=[frame(app,sid,g,text) for text in ('first','private','third')]
    result=app.delete_range('alice',sid,6,9)
    assert result['actual_ranges']==[[5,10]]
    with pytest.raises(Blocked): app.read('alice',b)
    app.compact()
    assert app.read('alice',a)['text']=='first'
    assert app.read('alice',c)['text']=='third'
    mature(app,a,c)
    assert app.search('alice','private')==[]
    app.close()  # A restart releases the previous store owner first.
    other=Sidecar(app.root,app.clock,key)
    try:
        other.unlocked=True
        with pytest.raises(Blocked): other.read('alice',b)
        assert other.read('alice',c)['text']=='third'
    finally: other.close()


def test_T16_exact_boundary_preserves_preceding_frame(app):
    sid,g=recording(app)
    a=frame(app,sid,g)
    b=frame(app,sid,g)
    assert app.delete_range('alice',sid,5,10)['deleted_frames']==[b]
    assert app.read('alice',a)['synthetic']


def test_T12_T30_identity_and_revocation(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    mature(app,fid)
    with pytest.raises(Blocked): app.read('bob',fid)
    assert app.search('bob','amount')==[]
    app.revoke('alice',sid)
    with pytest.raises(Blocked): app.read('alice',fid)
    assert app.search('alice','amount')==[]


def test_T41_prepare_is_idempotent_and_does_not_record(app):
    sid,g=recording(app,'desktop')
    target=app.prepare_in_person('alice',sid)
    assert app.prepare_in_person('alice',sid)==target
    assert app.session('alice',sid)['state']=='paused'
    assert app.session('alice',target)['state']=='ready'
    with pytest.raises(Blocked): frame(app,target,0)
    app.set_privacy_pause(True)
    with pytest.raises(Blocked): app.start('alice',target,consent=True)


def test_T37_single_active_mode(app):
    sid,g=recording(app,'desktop')
    other=app.create_session('alice','in_person')
    with pytest.raises(Blocked): app.start('alice',other,consent=True)
    assert app.session('alice',sid)['state']=='recording'


def test_T17_wait_for_tail_and_idempotent_job(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    app.end('alice',sid)
    jid=app.request_job('alice',sid)
    assert app.request_job('alice',sid)==jid
    with pytest.raises(Blocked): app.finish_job('alice',jid)
    mature(app,fid)
    assert app.finish_job('alice',jid)==jid
    assert app.finish_job('alice',jid)==jid
    assert app.artifact('alice',jid)['body']=='synthetic amount 100'


def test_T42_post_meeting_note_does_not_extend_audio(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    end=app.end('alice',sid)
    jid=app.request_job('alice',sid)
    app.clock.advance(10)
    note=app.add_note('alice',sid,'whiteboard note','meeting_background')
    assert app.session('alice',sid)['end']==end
    assert app.session('alice',sid)['state']=='ended'
    assert app.frame('alice',note)['eligible']==915
    with pytest.raises(Conflict): app.finish_job('alice',jid)
    mature(app,fid,note)
    new=app.request_job('alice',sid)
    app.finish_job('alice',new)
    assert 'whiteboard note' in app.artifact('alice',new)['body']


def test_T34_correction_conflict_and_old_job_barrier(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    app.end('alice',sid)
    mature(app,fid)
    jid=app.request_job('alice',sid)
    expiry=app.frame('alice',fid)['expires']
    assert app.correct('alice',fid,0,'synthetic amount 1000')==1
    with pytest.raises(Conflict): app.correct('alice',fid,0,'stale')
    with pytest.raises(Conflict): app.finish_job('alice',jid)
    assert app.search('alice','1000')[0]['revision']==1
    assert app.frame('alice',fid)['expires']==expiry


def test_T21_T44_deleted_artifact_is_hidden(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    app.end('alice',sid)
    mature(app,fid)
    jid=app.request_job('alice',sid)
    app.finish_job('alice',jid)
    app.delete_range('alice',sid,1,2)
    with pytest.raises(Blocked): app.artifact('alice',jid)
    with pytest.raises(Blocked): app.finish_job('alice',jid)
    assert app.search('alice','amount')==[]


def test_T33_cancel_persists_across_restart(app,key):
    sid,g=recording(app)
    frame(app,sid,g)
    app.end('alice',sid)
    jid=app.request_job('alice',sid)
    app.cancel('alice',sid)
    app.close()
    other=Sidecar(app.root,app.clock,key)
    try:
        other.unlocked=True
        with pytest.raises(Blocked): other.request_job('alice',sid,automatic=True)
        with pytest.raises(Blocked): other.finish_job('alice',jid)
    finally: other.close()


def test_T35_expired_never_revives_on_wall_clock_rollback(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    app.clock.advance(604801)
    with pytest.raises(Blocked): app.read('alice',fid)
    app.clock.utc-=604801
    with pytest.raises(Blocked): app.read('alice',fid)


def test_T09_T15_reboot_does_not_release_buffer(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    app.clock.advance(1000)
    app.clock.boot='new-boot'
    n=app.network(trusted=True,unmetered=True)
    with pytest.raises(Blocked): app.upload('alice',fid,n)


def test_T16_concurrent_delete_and_upload_never_resurrects(app):
    sid,g=recording(app)
    fid=frame(app,sid,g)
    app.clock.advance(900)
    n=app.network(trusted=True,unmetered=True)
    barrier=threading.Barrier(2)
    failures=[]
    def send():
        barrier.wait()
        try: app.upload('alice',fid,n)
        except Blocked: pass
        except Exception as exc: failures.append(exc)
    t=threading.Thread(target=send)
    t.start()
    barrier.wait()
    app.delete_range('alice',sid,0,5)
    t.join()
    assert not failures
    assert not app.search('alice','amount')
    with pytest.raises(Blocked): app.upload('alice',fid,n)

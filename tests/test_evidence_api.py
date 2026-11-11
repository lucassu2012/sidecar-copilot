from datetime import datetime,timezone
import json
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient
from sidecar.core import Sidecar,SimulationClock,Blocked
from sidecar.api import create_app
from sidecar.evidence import EvidenceService,day_bounds,union_duration


@pytest.fixture
def app(tmp_path):
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    clock=SimulationClock(utc=datetime(2026,11,10,9,tzinfo=timezone.utc).timestamp())
    a=Sidecar(tmp_path,clock,key);a.unlocked=True
    yield a
    a.close()


def start(a,mode='desktop',owner='alice'):
    sid=a.create_session(owner,mode);g=a.start(owner,sid,consent=True)
    return sid,g


def put(a,sid,g,text,kind='audio',owner='alice'):
    begin=a.clock.mono;a.clock.advance(5)
    return a.capture(owner,sid,g,stream=kind,kind=kind,start=begin,end=a.clock.mono,synthetic_text=text)


def upload(a,*fids,owner='alice'):
    a.clock.advance(900);n=a.network(trusted=True,unmetered=True)
    for fid in fids:a.upload(owner,fid,n)


def test_T19_joint_terms_in_separate_sources_and_overlap_dedup(app):
    sid,g=start(app)
    image=put(app,sid,g,'A公司 合同',kind='desktop_frame')
    audio=put(app,sid,g,'张工 第三条 风险')
    upload(app,image,audio)
    hits=EvidenceService(app).episodes('alice',['A公司','张工'])
    assert len(hits)==1
    assert {r['source_id'] for r in hits[0]['evidence']}=={image,audio}
    assert '关联待核实' in hits[0]['association']


def test_T19_long_unknown_gap_does_not_join(app):
    sid,g=start(app)
    one=put(app,sid,g,'company',kind='desktop_frame')
    app.clock.advance(180)
    two=put(app,sid,g,'person')
    upload(app,one,two)
    assert EvidenceService(app).episodes('alice',['company','person'])==[]


def test_T20_no_joint_evidence_across_owners_or_sessions(app):
    sid,g=start(app);one=put(app,sid,g,'company');app.end('alice',sid);upload(app,one)
    sid2,g2=start(app,owner='bob');two=put(app,sid2,g2,'person',owner='bob');app.end('bob',sid2);upload(app,two,owner='bob')
    assert EvidenceService(app).episodes('alice',['company','person'])==[]
    assert EvidenceService(app).episodes('alice',['person'])==[]


def test_T42_background_association_and_delete_quarantine(app):
    sid,g=start(app,'in_person');audio=put(app,sid,g,'discussion')
    app.end('alice',sid)
    background=app.add_note('alice',sid,'whiteboard background','meeting_background')
    pending=app.add_note('alice',sid,'unassigned material')
    upload(app,audio,background,pending)
    hits=EvidenceService(app).episodes('alice',['discussion'])
    assert [x['source_id'] for x in hits[0]['meeting_background']]==[background]
    assert EvidenceService(app).episodes('alice',['whiteboard'])[0]['evidence'][0]['source_id']==background
    app.delete_range('alice',sid,1,2)
    assert EvidenceService(app).episodes('alice',['whiteboard'])==[]
    assert app.search('alice','whiteboard')==[]
    with pytest.raises(Blocked):app.read('alice',background)


def test_T34_evidence_uses_current_correction(app):
    sid,g=start(app);fid=put(app,sid,g,'amount 100');upload(app,fid)
    app.correct('alice',fid,0,'amount 900')
    assert EvidenceService(app).episodes('alice',['100'])==[]
    assert EvidenceService(app).episodes('alice',['900'])[0]['evidence'][0]['revision']==1


def test_T43_three_report_sections_unknown_quality_and_no_fake_rate(app):
    sid,g=start(app,'in_person');fid=put(app,sid,g,'synthetic discussion');app.end('alice',sid);upload(app,fid)
    manual=app.create_session('alice','manual_note');note=app.add_note('alice',manual,'manual only');upload(app,note)
    report=EvidenceService(app).report('alice','2026-11-10','UTC')
    assert set(['coverage_intervals','meeting_cards','desktop_workflows'])<=report.keys()
    assert report['coverage_denominator'] is None
    assert {m['label'] for m in report['meeting_cards']}=={'线下会议','纯手工笔记'}
    assert all(not c['audio_coverage'] for c in report['coverage_intervals'] if c['source_type']=='manual_note')
    assert all(not m['decisions'] and not m['todos'] for m in report['meeting_cards'])
    assert '远场单麦' in json.dumps(report,ensure_ascii=False)


def test_T43_deleted_gap_persists_after_compaction_without_text(app):
    sid,g=start(app);fid=put(app,sid,g,'secret');app.end('alice',sid);upload(app,fid)
    before=EvidenceService(app).report('alice','2026-11-10','UTC')
    app.delete_range('alice',sid,0,5);app.compact()
    after=EvidenceService(app).report('alice','2026-11-10','UTC')
    assert after['input_version']!=before['input_version']
    assert after['coverage_intervals'][0]['status']=='deleted_or_expired'
    assert 'secret' not in json.dumps(after)


def test_T09_dst_day_and_overlap_denominator():
    a,b=day_bounds('2026-03-08','America/New_York');assert b-a==23*3600
    a,b=day_bounds('2026-11-01','America/New_York');assert b-a==25*3600
    assert union_duration([(0,10),(5,20),(25,30)])==25


def test_T33_single_cancel_allows_new_night_job_but_not_old_result(app):
    sid,g=start(app);fid=put(app,sid,g,'one');app.end('alice',sid);upload(app,fid)
    old=app.request_job('alice',sid)
    app.cancel('alice',sid,block_automatic=False)
    new=app.request_job('alice',sid,automatic=True)
    assert new!=old
    with pytest.raises(Blocked):app.finish_job('alice',old)
    app.finish_job('alice',new)


def test_T33_once_override_does_not_enable_future_automatic_jobs(app):
    sid,g=start(app);fid=put(app,sid,g,'one');app.end('alice',sid);upload(app,fid)
    app.cancel('alice',sid)
    once=app.request_job('alice',sid,override='once');app.finish_job('alice',once)
    with pytest.raises(Blocked):app.request_job('alice',sid,automatic=True)
    enabled=app.request_job('alice',sid,override='allow_automatic');app.finish_job('alice',enabled)
    app.finish_job('alice',app.request_job('alice',sid,automatic=True))


def test_T33_cancel_overrides_inflight_once_grant(app):
    sid,g=start(app);fid=put(app,sid,g,'one');app.end('alice',sid);upload(app,fid)
    app.cancel('alice',sid)
    once=app.request_job('alice',sid,override='once')
    app.cancel('alice',sid)
    with pytest.raises(Blocked):app.finish_job('alice',once)


def client(app,token='alice-test'):
    return TestClient(create_app(app,{'alice-test':'alice','bob-test':'bob'}),headers={'Authorization':'Bearer '+token})


def test_T12_api_auth_and_owner_not_client_supplied(app):
    sid,g=start(app);fid=put(app,sid,g,'private');upload(app,fid)
    assert client(app,'invalid').get('/sessions').status_code==401
    bob=client(app,'bob-test')
    assert bob.get('/sessions').json()==[]
    assert bob.post(f'/sessions/{sid}/deletions',json={'start':0,'end':5}).status_code==403
    alice=client(app)
    assert alice.post(f'/sources/{fid}/corrections',json={'base_revision':0,'text':'x','owner':'bob'}).status_code==422
    result=alice.get('/search',params={'q':'private'})
    assert result.status_code==200 and result.headers['cache-control']=='no-store'
    assert 'private' in result.text


def test_T11_api_delete_blocks_late_result_and_report(app):
    sid,g=start(app);fid=put(app,sid,g,'private');app.end('alice',sid);upload(app,fid)
    c=client(app)
    job=c.post(f'/sessions/{sid}/jobs',json={}).json()['job_id']
    assert c.post(f'/sessions/{sid}/deletions',json={'start':0,'end':5}).status_code==200
    assert c.post(f'/jobs/{job}/finish').status_code==403
    assert 'private' not in c.get('/reports/2026-11-10').text
    assert c.get('/search',params={'q':'private'}).json()['results']==[]


def test_T34_api_revision_conflict_and_contract(app):
    sid,g=start(app);fid=put(app,sid,g,'100');upload(app,fid)
    c=client(app)
    assert c.post(f'/sources/{fid}/corrections',json={'base_revision':0,'text':'200'}).status_code==200
    assert c.post(f'/sources/{fid}/corrections',json={'base_revision':0,'text':'300'}).status_code==409
    schema=c.get('/openapi.json').json()
    assert schema['components']['securitySchemes']['HTTPBearer']['scheme']=='bearer'
    assert c.get('/reports/2026-11-10',params={'timezone':'unknown/place'}).status_code==422
    assert c.post(f'/sessions/{sid}/deletions',json={'start':10,'end':5}).status_code==422


def test_T12_api_locked_reads_fail_closed(app):
    c=client(app);app.unlocked=False
    assert c.get('/sessions').status_code==403
    assert c.get('/search',params={'q':'x'}).status_code==403
    assert c.get('/reports/2026-11-10').status_code==403

"""Single-process synthetic reference; public methods serialize control barriers.

Not Android security: RSA private keys are supplied by the test harness, and the
synthetic search index is plaintext. No real content is supported here.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import sqlite3
import threading
import uuid
from dataclasses import dataclass
from functools import wraps

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from .process_lock import RootLease


class Blocked(RuntimeError):
    pass


class Conflict(RuntimeError):
    pass


@dataclass
class SimulationClock:
    mono: float = 0
    utc: float = 1_800_000_000
    boot: str = "synthetic-boot-1"
    trusted: bool = True

    def advance(self, seconds):
        if seconds < 0:
            raise ValueError("monotonic time cannot go backwards")
        self.mono += seconds
        self.utc += seconds


def serialized(method):
    @wraps(method)
    def call(self, *args, **kwargs):
        with self.lock:
            return method(self, *args, **kwargs)
    return call


def encode(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


SCHEMA = """
PRAGMA foreign_keys=ON;
PRAGMA journal_mode=WAL;
PRAGMA synchronous=FULL;
PRAGMA secure_delete=ON;
CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value INTEGER NOT NULL);
INSERT OR IGNORE INTO settings VALUES ('privacy_pause',0);
CREATE TABLE IF NOT EXISTS sessions (
 id TEXT PRIMARY KEY, owner TEXT NOT NULL, mode TEXT NOT NULL,
 state TEXT NOT NULL, generation INTEGER NOT NULL DEFAULT 0,
 deletion_generation INTEGER NOT NULL DEFAULT 0, revoked INTEGER NOT NULL DEFAULT 0,
 upload_paused INTEGER NOT NULL DEFAULT 0, start REAL, end REAL,
 attachment_revision INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS segments (
 id TEXT PRIMARY KEY, session TEXT NOT NULL, stream TEXT NOT NULL,
 start REAL NOT NULL, committed_length INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS frames (
 id TEXT PRIMARY KEY, session TEXT NOT NULL REFERENCES sessions(id), stream TEXT NOT NULL,
 kind TEXT NOT NULL, start REAL NOT NULL, end REAL NOT NULL, boot TEXT NOT NULL,
 eligible REAL NOT NULL, expires REAL NOT NULL, generation INTEGER NOT NULL,
 segment TEXT NOT NULL REFERENCES segments(id), offset INTEGER NOT NULL, length INTEGER NOT NULL,
 digest TEXT NOT NULL, aad BLOB NOT NULL, nonce BLOB NOT NULL, wrapped_key BLOB,
 deleted INTEGER NOT NULL DEFAULT 0, expired INTEGER NOT NULL DEFAULT 0,
 uploaded INTEGER NOT NULL DEFAULT 0, revision INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS tombstones (
 id TEXT PRIMARY KEY, session TEXT NOT NULL, start REAL NOT NULL, end REAL NOT NULL,
 generation INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS texts (
 frame TEXT PRIMARY KEY REFERENCES frames(id), body TEXT NOT NULL, revision INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS artifacts (
 id TEXT PRIMARY KEY, session TEXT NOT NULL, body TEXT NOT NULL, refs TEXT NOT NULL,
 revision INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0, edited INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS jobs (
 id TEXT PRIMARY KEY, session TEXT NOT NULL, manifest TEXT NOT NULL, attachment_revision INTEGER NOT NULL,
 state TEXT NOT NULL, automatic INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS processing_blocks (session TEXT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS attachments (
 frame TEXT PRIMARY KEY, session TEXT NOT NULL, scope TEXT NOT NULL, ranges TEXT NOT NULL,
 created REAL NOT NULL, added REAL NOT NULL);
CREATE TABLE IF NOT EXISTS frame_times (
 frame TEXT PRIMARY KEY REFERENCES frames(id) ON DELETE CASCADE,
 captured_start_utc REAL NOT NULL, captured_end_utc REAL NOT NULL,
 source_health TEXT NOT NULL DEFAULT 'unknown');
CREATE TABLE IF NOT EXISTS processing_versions (session TEXT PRIMARY KEY, version INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS job_policies (job TEXT PRIMARY KEY, version INTEGER NOT NULL, allow_once INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS source_quarantine (frame TEXT PRIMARY KEY, reason TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS deleted_coverage (
 frame TEXT PRIMARY KEY, session TEXT NOT NULL, kind TEXT NOT NULL, stream TEXT NOT NULL,
 start_utc REAL NOT NULL, end_utc REAL NOT NULL);
"""


class Sidecar:
    """No sockets: upload() is an in-process simulated receipt/index operation."""

    def __init__(self, root, clock, private_key, buffer_seconds=900, retention_seconds=604800):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.clock, self.private_key = clock, private_key
        self.public_key = private_key.public_key()
        self.buffer, self.retention = buffer_seconds, retention_seconds
        self.lock = threading.RLock()
        self.unlocked = False
        self.network_generation = 0
        self.trusted_network = self.unmetered = False
        self.lease = RootLease(self.root / 'store.lock')
        self._upload_worker_lock = threading.RLock()
        try:
            self.db = sqlite3.connect(self.root / "state.sqlite", check_same_thread=False)
            self.db.row_factory = sqlite3.Row
            self.db.executescript(SCHEMA)
            from .transfer_queue import initialize
            initialize(self.db)
            # Reopening never resumes capture or prior egress authorizations.
            with self.db:
                self.db.execute("UPDATE sessions SET state='paused',upload_paused=1,generation=generation+1 WHERE state='recording'")
                self.db.execute("UPDATE sessions SET upload_paused=1")
            self.recovery = self.recover()
        except BaseException:
            if hasattr(self, 'db'):
                self.db.close()
            self.lease.close()
            raise

    def close(self):
        # Worker lock precedes the store lock everywhere; a network wait never
        # owns the store lock. Close cannot invalidate an active worker's DB.
        with self._upload_worker_lock, self.lock:
            try:
                self.db.close()
            finally:
                self.lease.close()

    def session(self, owner, sid):
        s = self.db.execute("SELECT * FROM sessions WHERE id=? AND owner=?", (sid, owner)).fetchone()
        if s is None or s['revoked']:
            raise Blocked("identity unavailable")
        return s

    def frame(self, owner, fid):
        f = self.db.execute("SELECT * FROM frames WHERE id=?", (fid,)).fetchone()
        if f is None:
            raise Blocked("source unavailable")
        self.session(owner, f['session'])
        if f['deleted'] or f['expired']:
            raise Blocked("source deleted or expired")
        if self.db.execute('''SELECT 1 FROM tombstones WHERE session=? AND ((start<?
                AND end>?) OR (?=? AND start<=? AND end>?))''',
                (f['session'], f['end'], f['start'], f['start'], f['end'], f['start'], f['start'])).fetchone():
            raise Blocked('source covered by durable deletion barrier')
        if self.db.execute("SELECT 1 FROM source_quarantine WHERE frame=?",(fid,)).fetchone():
            raise Blocked("source association quarantined pending deletion review")
        if not self.clock.trusted:
            raise Blocked("clock uncertain")
        if self.clock.utc >= f['expires']:
            with self.db:
                self.db.execute("UPDATE frames SET expired=1 WHERE id=?", (fid,))
            raise Blocked("source expired")
        return f

    def privacy_paused(self):
        return bool(self.db.execute("SELECT value FROM settings WHERE key='privacy_pause'").fetchone()[0])

    @serialized
    def create_session(self, owner, mode):
        if mode not in ('desktop', 'in_person', 'manual_note') or not owner:
            raise ValueError("invalid identity or capture mode")
        sid = uuid.uuid4().hex
        with self.db:
            self.db.execute("INSERT INTO sessions(id,owner,mode,state) VALUES (?,?,?,'ready')", (sid, owner, mode))
        return sid

    @serialized
    def start(self, owner, sid, *, consent):
        s = self.session(owner, sid)
        if not consent or self.privacy_paused() or not self.unlocked:
            raise Blocked("explicit consent, unlock and active permission required")
        if s['state'] == 'ended':
            raise Blocked("meeting has ended")
        active = self.db.execute("SELECT id FROM sessions WHERE state='recording'").fetchone()
        if active and active['id'] != sid:
            raise Blocked("one active capture session per device")
        if s['state'] == 'recording':
            return s['generation']
        with self.db:
            self.db.execute("UPDATE sessions SET state='recording',generation=generation+1,upload_paused=0,start=? WHERE id=?", (self.clock.mono, sid))
        return self.session(owner, sid)['generation']

    @serialized
    def pause(self, owner, sid):
        s = self.session(owner, sid)
        with self.db:
            self.db.execute("UPDATE sessions SET state=?,generation=generation+1,upload_paused=1 WHERE id=?", ('ended' if s['state']=='ended' else 'paused', sid))
        return {"effective_mono": self.clock.mono, "boot_id": self.clock.boot}

    @serialized
    def resume_uploads(self, owner, sid, *, consent):
        self.session(owner, sid)
        if consent is not True or not self.unlocked or self.privacy_paused():
            raise Blocked('explicit upload resume and unlock required')
        with self.db:
            self.db.execute('UPDATE sessions SET upload_paused=0 WHERE id=?', (sid,))
        return {'upload_paused': False, 'capture_state': self.session(owner, sid)['state']}

    @serialized
    def set_privacy_pause(self, enabled):
        with self.db:
            self.db.execute("UPDATE settings SET value=? WHERE key='privacy_pause'", (int(enabled),))
            if enabled:
                self.db.execute("UPDATE sessions SET state=CASE WHEN state='recording' THEN 'paused' ELSE state END,upload_paused=1,generation=generation+1")

    @serialized
    def prepare_in_person(self, owner, desktop):
        if self.session(owner, desktop)['mode'] != 'desktop':
            raise ValueError("not a desktop session")
        self.pause(owner, desktop)
        # Stable intent identity across duplicate invocations and process restarts.
        sid = 'in_person_' + desktop
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO sessions(id,owner,mode,state) VALUES (?,?,'in_person','ready')", (sid, owner))
        return sid

    @serialized
    def end(self, owner, sid):
        s = self.session(owner, sid)
        if s['state'] != 'ended':
            with self.db:
                self.db.execute("UPDATE sessions SET state='ended',end=?,generation=generation+1 WHERE id=?", (self.clock.mono, sid))
        return self.session(owner, sid)['end']

    def _oaep(self):
        return padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None)

    def _write(self, s, stream, kind, start, end, body):
        fid = uuid.uuid4().hex
        meta = dict(id=fid, owner=s['owner'], session=s['id'], stream=stream, kind=kind,
                    start=start, end=end, boot=self.clock.boot, generation=s['generation'], schema_version=1,
                    eligible=end+self.buffer, expires=self.clock.utc-(self.clock.mono-start)+self.retention)
        aad = encode(meta)
        key, nonce = AESGCM.generate_key(bit_length=256), os.urandom(12)
        ciphertext = AESGCM(key).encrypt(nonce, body, aad)
        wrapped = self.public_key.encrypt(key, self._oaep())
        del key
        seg = self.db.execute("SELECT * FROM segments WHERE session=? AND stream=? ORDER BY rowid DESC LIMIT 1", (s['id'], stream)).fetchone()
        if not seg or start - seg['start'] >= 900 or seg['committed_length'] + len(ciphertext) > 8*1024*1024:
            segment = uuid.uuid4().hex
            with self.db:
                self.db.execute("INSERT INTO segments(id,session,stream,start) VALUES (?,?,?,?)", (segment,s['id'],stream,start))
            seg = self.db.execute("SELECT * FROM segments WHERE id=?", (segment,)).fetchone()
        path = self.root / (seg['id'] + '.seg')
        offset = seg['committed_length']
        with open(path, 'a+b') as out:
            out.truncate(offset)  # Remove a prior uncommitted tail before append.
            out.seek(offset)
            out.write(ciphertext)
            out.flush()
            os.fsync(out.fileno())
        # File first; a crash before index commit leaves only an orphan tail.
        with self.db:
            self.db.execute("INSERT INTO frames(id,session,stream,kind,start,end,boot,eligible,expires,generation,segment,offset,length,digest,aad,nonce,wrapped_key) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (fid,s['id'],stream,kind,start,end,self.clock.boot,end+self.buffer,
                 self.clock.utc-(self.clock.mono-start)+self.retention,s['generation'],seg['id'],offset,
                 len(ciphertext),hashlib.sha256(ciphertext).hexdigest(),aad,nonce,wrapped))
            self.db.execute("UPDATE segments SET committed_length=? WHERE id=?", (offset+len(ciphertext),seg['id']))
            self.db.execute("INSERT INTO frame_times(frame,captured_start_utc,captured_end_utc) VALUES (?,?,?)",
                (fid,self.clock.utc-(self.clock.mono-start),self.clock.utc-(self.clock.mono-end)))
        return fid

    @serialized
    def capture(self, owner, sid, generation, *, stream, kind, start, end, synthetic_text):
        s = self.session(owner, sid)
        if self.privacy_paused() or s['state'] != 'recording' or generation != s['generation']:
            raise Blocked("capture barrier")
        if not self.clock.trusted or not (s['start'] <= start < end <= self.clock.mono) or end-start > 10:
            raise Blocked("invalid sample boundary")
        if kind not in ('audio','desktop_frame') or (s['mode'] != 'desktop' and kind != 'audio') or s['mode']=='manual_note':
            raise Blocked("source not authorized for mode")
        if self.db.execute("SELECT 1 FROM tombstones WHERE session=? AND start<? AND end>?",(sid,end,start)).fetchone():
            raise Blocked("deleted interval")
        try:
            return self._write(s,stream,kind,start,end,encode({'synthetic':True,'text':synthetic_text}))
        except (OSError, sqlite3.Error):
            self.pause(owner, sid)
            raise

    @serialized
    def add_note(self, owner, sid, text, scope='unassigned', ranges=()):
        s = self.session(owner, sid)
        if not self.unlocked or self.privacy_paused():
            raise Blocked("unlock and permission required")
        if scope not in ('unassigned','meeting_background','range'):
            raise ValueError("invalid attachment scope")
        if scope == 'range' and (not ranges or any(a>=b for a,b in ranges)):
            raise ValueError("explicit evidence range required")
        now = self.clock.mono
        fid = self._write(s,'notes','manual_note',now,now,encode({'synthetic':True,'text':text}))
        with self.db:
            self.db.execute("INSERT INTO attachments VALUES (?,?,?,?,?,?)", (fid,sid,scope,json.dumps(ranges),now,now))
            self.db.execute("UPDATE sessions SET attachment_revision=attachment_revision+1 WHERE id=?", (sid,))
        return fid

    def _ciphertext(self, f):
        owner = self.db.execute("SELECT owner FROM sessions WHERE id=?", (f['session'],)).fetchone()[0]
        expected = {k:f[k] for k in ('id','session','stream','kind','start','end','boot','generation','eligible','expires')}
        expected.update(owner=owner, schema_version=1)
        # Numeric JSON spelling may differ (0 vs 0.0); compare values, not bytes.
        if json.loads(f['aad']) != expected:
            raise Blocked("authenticated metadata mismatch")
        seg = self.db.execute("SELECT * FROM segments WHERE id=?", (f['segment'],)).fetchone()
        if f['offset'] < 0 or f['length'] <= 0 or f['offset']+f['length'] > seg['committed_length']:
            raise Blocked("invalid frame mapping")
        with open(self.root / (f['segment']+'.seg'), 'rb') as source:
            source.seek(f['offset'])
            blob = source.read(f['length'])
        if hashlib.sha256(blob).hexdigest() != f['digest']:
            raise Blocked("corrupt frame")
        return blob

    @serialized
    def read(self, owner, fid):
        if not self.unlocked:
            raise Blocked("waiting for unlock")
        f = self.frame(owner, fid)
        key = self.private_key.decrypt(f['wrapped_key'], self._oaep())
        try:
            return json.loads(AESGCM(key).decrypt(f['nonce'],self._ciphertext(f),f['aad']))
        finally:
            del key

    @serialized
    def network(self, *, trusted, unmetered):
        self.network_generation += 1
        self.trusted_network, self.unmetered = trusted, unmetered
        return self.network_generation

    @serialized
    def upload(self, owner, fid, network_generation):
        if self.db.execute('SELECT 1 FROM upload_intents WHERE frame_id=?', (fid,)).fetchone():
            raise Blocked('real transfer requires its verified receipt; synthetic upload unavailable')
        f = self.frame(owner, fid)
        s = self.session(owner, f['session'])
        if not self.unlocked or self.privacy_paused() or s['upload_paused']:
            raise Blocked("waiting for unlock or resume")
        if network_generation != self.network_generation or not self.trusted_network or not self.unmetered:
            raise Blocked("network authorization invalid")
        if f['boot'] != self.clock.boot or self.clock.mono < f['eligible']:
            raise Blocked("buffer not mature or boot clock uncertain")
        if f['uploaded']:
            return {'id':fid, 'status':'committed', 'duplicate':True}
        payload = self.read(owner,fid)
        # Synthetic sink only. Does not perform real OCR/ASR or any network call.
        with self.db:
            self.db.execute("INSERT INTO texts VALUES (?,?,?)", (fid,payload['text'],f['revision']))
            self.db.execute("UPDATE frames SET uploaded=1 WHERE id=?", (fid,))
        return {'id':fid, 'status':'committed', 'duplicate':False}

    @serialized
    def search(self, owner, query):
        if not self.unlocked:
            raise Blocked("waiting for unlock")
        results=[]
        for row in self.db.execute("SELECT texts.*,frames.session,frames.kind,frames.start,frames.end FROM texts JOIN frames ON texts.frame=frames.id JOIN sessions ON frames.session=sessions.id WHERE sessions.owner=?", (owner,)).fetchall():
            try:
                self.frame(owner,row['frame'])
            except Blocked:
                continue
            if query.casefold() in row['body'].casefold():
                results.append(dict(row))
        return results

    @serialized
    def delete_range(self, owner, sid, start, end):
        from .transfer_queue import ns, stage_deletion
        s = self.session(owner,sid)
        if ns(start) >= ns(end, upper=True) or start >= end:
            raise ValueError("empty interval")
        rows = self.db.execute("SELECT * FROM frames WHERE session=? AND deleted=0 AND start<? AND (end>? OR (start=end AND start>=?))", (sid,end,start,start)).fetchall()
        ids={r['id'] for r in rows}
        deletion_id = uuid.uuid4().hex
        generation = s['deletion_generation'] + 1
        with self.db:
            self.db.execute("INSERT INTO tombstones VALUES (?,?,?,?,?)", (deletion_id,sid,start,end,generation))
            self.db.execute("UPDATE sessions SET deletion_generation=deletion_generation+1 WHERE id=?",(sid,))
            for fid in ids:
                self.db.execute('''INSERT OR IGNORE INTO deleted_coverage
                    SELECT f.id,f.session,f.kind,f.stream,ft.captured_start_utc,ft.captured_end_utc
                    FROM frames f JOIN frame_times ft ON ft.frame=f.id WHERE f.id=?''',(fid,))
                self.db.execute("UPDATE frames SET deleted=1,wrapped_key=NULL WHERE id=?",(fid,))
                self.db.execute("DELETE FROM texts WHERE frame=?",(fid,))
            for attachment in self.db.execute("SELECT * FROM attachments WHERE session=?",(sid,)).fetchall():
                if ids and (attachment['scope']=='meeting_background' or (attachment['scope']=='range' and any(a<end and b>start for a,b in json.loads(attachment['ranges'])))):
                    self.db.execute("INSERT OR IGNORE INTO source_quarantine VALUES (?,?)",(attachment['frame'],'deletion_dependency_review'))
            for a in self.db.execute("SELECT * FROM artifacts WHERE session=?",(sid,)).fetchall():
                if ids.intersection(json.loads(a['refs'])):
                    # Conservative whole-artifact isolation; no old body export path.
                    self.db.execute("UPDATE artifacts SET hidden=1 WHERE id=?",(a['id'],))
            self.db.execute("UPDATE jobs SET state='cancelled' WHERE session=?",(sid,))
            stage_deletion(self.db, owner, deletion_id, sid, start, end, generation)
        return {'requested':[start,end], 'deleted_frames':sorted(ids),
                'deletion_id':deletion_id, 'deletion_generation':generation,
                'remote_cleanup_pending':bool(self.db.execute('SELECT 1 FROM upload_deletion_outbox WHERE deletion_id=? AND confirmed=0',(deletion_id,)).fetchone()),
                'actual_ranges':[[r['start'],r['end']] for r in rows],
                'active_storage':'pending_compaction', 'physical_erasure':'not_guaranteed'}

    @serialized
    def compact(self):
        for old in self.db.execute("SELECT * FROM segments").fetchall():
            rows=self.db.execute("SELECT * FROM frames WHERE segment=? AND deleted=0",(old['id'],)).fetchall()
            new=uuid.uuid4().hex
            path=self.root/(new+'.seg')
            positions=[]
            with open(path,'xb') as out:
                for f in rows:
                    blob=self._ciphertext(f)
                    positions.append((out.tell(),f['id']))
                    out.write(blob)
                out.flush()
                os.fsync(out.fileno())
            # Verify the complete replacement before changing any live mapping.
            with open(path,'rb') as check:
                for (offset,_),f in zip(positions,rows):
                    check.seek(offset)
                    if hashlib.sha256(check.read(f['length'])).hexdigest()!=f['digest']:
                        raise Blocked("compaction verification failed")
            with self.db:
                self.db.execute("INSERT INTO segments VALUES (?,?,?,?,?)", (new,old['session'],old['stream'],old['start'],path.stat().st_size))
                for offset,fid in positions:
                    self.db.execute("UPDATE frames SET segment=?,offset=? WHERE id=?",(new,offset,fid))
                self.db.execute("DELETE FROM frames WHERE segment=? AND deleted=1",(old['id'],))
                # Tombstones stay durable after payload/index reclamation.
                self.db.execute("DELETE FROM segments WHERE id=?",(old['id'],))
            (self.root/(old['id']+'.seg')).unlink(missing_ok=True)

    @serialized
    def recover(self):
        result={'orphan_bytes':0,'corrupt_frames':[]}
        known=set()
        for seg in self.db.execute("SELECT * FROM segments").fetchall():
            path=self.root/(seg['id']+'.seg')
            known.add(path.name)
            if path.exists() and path.stat().st_size>seg['committed_length']:
                result['orphan_bytes']+=path.stat().st_size-seg['committed_length']
                with open(path,'r+b') as out:
                    out.truncate(seg['committed_length'])
                    out.flush()
                    os.fsync(out.fileno())
        for path in self.root.glob('*.seg'):
            if path.name not in known:
                path.unlink()  # Orphan compaction output, never the indexed copy.
        for f in self.db.execute("SELECT * FROM frames WHERE deleted=0").fetchall():
            try:
                self._ciphertext(f)
            except (Blocked,OSError):
                result['corrupt_frames'].append(f['id'])
        return result

    @serialized
    def correct(self, owner, fid, base_revision, text):
        f=self.frame(owner,fid)
        if not self.unlocked or not f['uploaded']:
            raise Blocked("source not available for correction")
        if not self.db.execute('SELECT 1 FROM texts WHERE frame=?', (fid,)).fetchone():
            raise Blocked('waiting for validated processing output')
        if f['revision']!=base_revision:
            raise Conflict("revision conflict")
        with self.db:
            self.db.execute("UPDATE frames SET revision=revision+1 WHERE id=?",(fid,))
            self.db.execute("UPDATE texts SET body=?,revision=revision+1 WHERE frame=?",(text,fid))
            for a in self.db.execute("SELECT * FROM artifacts WHERE session=?",(f['session'],)).fetchall():
                if fid in json.loads(a['refs']):
                    self.db.execute("UPDATE artifacts SET hidden=1 WHERE id=?",(a['id'],))
        return base_revision+1

    @serialized
    def request_job(self, owner, sid, *, automatic=False, override=None):
        s=self.session(owner,sid)
        if not self.clock.trusted or s['state']!='ended':
            raise Blocked("this G0 adapter only processes ended sessions on a trusted clock")
        if override not in (None,'once','allow_automatic') or (automatic and override):
            raise ValueError("invalid explicit processing override")
        blocked=bool(self.db.execute("SELECT 1 FROM processing_blocks WHERE session=?",(sid,)).fetchone())
        if blocked and override is None:
            raise Blocked("scope blocked; explicit policy resolution required")
        if override=='allow_automatic':
            with self.db:
                self.db.execute("DELETE FROM processing_blocks WHERE session=?",(sid,))
                self.db.execute("INSERT INTO processing_versions VALUES (?,1) ON CONFLICT(session) DO UPDATE SET version=version+1",(sid,))
        version_row=self.db.execute("SELECT version FROM processing_versions WHERE session=?",(sid,)).fetchone()
        version=version_row[0] if version_row else 0
        manifest=[]
        for f in self.db.execute("SELECT * FROM frames WHERE session=? ORDER BY id",(sid,)).fetchall():
            try:
                self.frame(owner,f['id'])
            except Blocked:
                continue
            manifest.append([f['id'],f['revision']])
        jid=hashlib.sha256(encode([sid,manifest,s['attachment_revision'],automatic,version,override])).hexdigest()
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO jobs VALUES (?,?,?,?,'waiting',?)",(jid,sid,json.dumps(manifest),s['attachment_revision'],int(automatic)))
            self.db.execute("INSERT OR IGNORE INTO job_policies VALUES (?,?,?)",(jid,version,int(override=='once')))
        return jid

    @serialized
    def finish_job(self, owner, jid):
        j=self.db.execute("SELECT * FROM jobs WHERE id=?",(jid,)).fetchone()
        if j is None:
            raise Blocked("unknown job")
        s=self.session(owner,j['session'])
        policy=self.db.execute("SELECT * FROM job_policies WHERE job=?",(jid,)).fetchone()
        version=self.db.execute("SELECT version FROM processing_versions WHERE session=?",(s['id'],)).fetchone()
        blocked=self.db.execute("SELECT 1 FROM processing_blocks WHERE session=?",(s['id'],)).fetchone()
        if (not self.unlocked or j['state']=='cancelled' or policy is None
                or policy['version']!=(version[0] if version else 0) or (blocked and not policy['allow_once'])):
            raise Blocked("processing barrier")
        if s['attachment_revision']!=j['attachment_revision']:
            raise Conflict("attachment snapshot superseded")
        if not json.loads(j['manifest']):
            raise Blocked("no usable evidence")
        bodies=[]
        refs=[]
        for fid,revision in json.loads(j['manifest']):
            f=self.frame(owner,fid)
            if f['revision']!=revision:
                raise Conflict("correction snapshot superseded")
            if not f['uploaded']:
                raise Blocked("waiting for complete inputs")
            intent=self.db.execute('SELECT remote_verified FROM upload_intents WHERE frame_id=?',(fid,)).fetchone()
            if intent is not None and not intent['remote_verified']:
                raise Blocked('waiting for verified remote receipt')
            text=self.db.execute('SELECT body FROM texts WHERE frame=?',(fid,)).fetchone()
            if text is None:
                raise Blocked('waiting for validated processing output')
            refs.append(fid)
            bodies.append(text[0])
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO artifacts(id,session,body,refs) VALUES (?,?,?,?)",(jid,s['id'],'\n'.join(bodies),json.dumps(refs)))
            self.db.execute("UPDATE jobs SET state='done' WHERE id=?",(jid,))
        return jid

    @serialized
    def artifact(self, owner, aid):
        a=self.db.execute("SELECT * FROM artifacts WHERE id=?",(aid,)).fetchone()
        if a is None or a['hidden'] or not self.unlocked:
            raise Blocked("artifact unavailable")
        self.session(owner,a['session'])
        for fid in json.loads(a['refs']):
            self.frame(owner,fid)
        return dict(a)

    @serialized
    def cancel(self, owner, sid, *, block_automatic=True):
        self.session(owner,sid)
        with self.db:
            self.db.execute("UPDATE jobs SET state='cancelled' WHERE session=?",(sid,))
            self.db.execute("INSERT INTO processing_versions VALUES (?,1) ON CONFLICT(session) DO UPDATE SET version=version+1",(sid,))
            if block_automatic:
                self.db.execute("INSERT OR IGNORE INTO processing_blocks VALUES (?)",(sid,))
        return {'cancelled':True, 'automatic_blocked':bool(self.db.execute(
            'SELECT 1 FROM processing_blocks WHERE session=?',(sid,)).fetchone())}

    @serialized
    def revoke(self, owner, sid):
        self.pause(owner,sid)
        with self.db:
            self.db.execute("UPDATE sessions SET revoked=1 WHERE id=?",(sid,))

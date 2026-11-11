"""Evidence-first read models. Rebuilt from current authorized revisions on each call.

No LLM, inferred decisions, voice identity or confidence percentages are generated.
"""
from collections import defaultdict
from datetime import date, datetime, time, timedelta
import hashlib
import json
import math
from zoneinfo import ZoneInfo

from .core import Blocked, encode


def day_bounds(day, timezone):
    day=date.fromisoformat(day)
    zone=ZoneInfo(timezone)
    return (datetime.combine(day,time.min,zone).timestamp(),
            datetime.combine(day+timedelta(days=1),time.min,zone).timestamp())


def union_duration(intervals):
    total=0.0
    end=None
    for start,stop in sorted(intervals):
        if end is None or start>end:
            total+=stop-start
        elif stop>end:
            total+=stop-end
        end=max(end if end is not None else stop,stop)
    return total


class EvidenceService:
    def __init__(self,store):
        self.store=store

    def _rows(self,owner):
        s=self.store
        if not s.unlocked or not s.clock.trusted:
            raise Blocked('unlock and trusted time required')
        rows=[]
        for r in s.db.execute('''SELECT f.*,t.body,t.revision AS text_revision,
                sessions.mode,ft.captured_start_utc,ft.captured_end_utc,ft.source_health,
                a.scope,a.ranges,a.created AS attachment_created,a.added AS attachment_added
                FROM frames f JOIN sessions ON sessions.id=f.session
                LEFT JOIN texts t ON t.frame=f.id LEFT JOIN frame_times ft ON ft.frame=f.id
                LEFT JOIN attachments a ON a.frame=f.id WHERE sessions.owner=?''',(owner,)).fetchall():
            try:
                s.frame(owner,r['id'])
            except Blocked:
                continue
            if r['body'] is not None and r['text_revision']==r['revision']:
                rows.append(dict(r))
        return rows

    @staticmethod
    def _card(row):
        quality=[]
        if row['kind']=='audio' and row['mode']=='in_person':
            quality.extend(['远场单麦转写（合成适配器）','发言人身份未知'])
        if row['source_health']!='healthy':
            quality.append('声源健康未知' if row['source_health'] in (None,'unknown') else row['source_health'])
        return {'source_id':row['id'],'session_id':row['session'],'source_type':row['kind'],
                'start':row['start'],'end':row['end'],'boot_id':row['boot'],
                'revision':row['revision'],'text':row['body'],'quality':quality,
                'scope_kind':row['scope'],'synthetic':True}

    def episodes(self,owner,terms,window_seconds=300,stride_seconds=150):
        if not terms or len(terms)>12 or any(not t.strip() or len(t)>100 for t in terms):
            raise ValueError('provide 1..12 explicit query terms')
        if not (180<=window_seconds<=600 and 0<stride_seconds<=window_seconds):
            raise ValueError('invalid candidate episode window')
        terms=[t.casefold() for t in terms]
        with self.store.lock:
            rows=self._rows(owner)
            groups=defaultdict(list)
            for r in rows:
                groups[(r['session'],r['boot'])].append(r)
            hits={}
            for (sid,boot),members in groups.items():
                for r in members:
                    if r['scope'] in ('meeting_background','range') and all(t in r['body'].casefold() for t in terms):
                        hits[(r['id'],)]={'episode_id':r['id'],'session_id':sid,'boot_id':boot,
                            'start':r['start'],'end':r['end'],'evidence':[self._card(r)],'context':[],
                            'meeting_background':[],'association':'人工资料命中；创建时间不是发言时间'}
                temporal=[r for r in members if r['scope'] is None or r['scope']=='unassigned']
                windows=defaultdict(list)
                for r in temporal:
                    first=math.floor((r['start']-window_seconds)/stride_seconds)+1
                    last=math.floor(r['start']/stride_seconds)
                    for index in range(first,last+1):
                        windows[index].append(r)
                for index, candidates in windows.items():
                    # Split on unobserved long gaps instead of manufacturing continuous context.
                    runs=[]
                    for r in sorted(candidates,key=lambda x:x['start']):
                        if not runs or r['start']-max(x['end'] for x in runs[-1])>120:
                            runs.append([])
                        runs[-1].append(r)
                    for run in runs:
                        if not all(any(t in r['body'].casefold() for r in run) for t in terms):
                            continue
                        direct=[r for r in run if any(t in r['body'].casefold() for t in terms)]
                        ids=tuple(sorted(r['id'] for r in direct))
                        if ids in hits:
                            continue
                        start=min(r['start'] for r in run);end=max(r['end'] for r in run)
                        backgrounds=[]
                        for r in members:
                            if r['scope']=='meeting_background' or (r['scope']=='range' and any(a<end and b>start for a,b in json.loads(r['ranges']))):
                                backgrounds.append(self._card(r))
                        hits[ids]={'episode_id':hashlib.sha256(encode([sid,boot,ids])).hexdigest(),
                            'session_id':sid,'boot_id':boot,'start':start,'end':end,
                            'evidence':[self._card(r) for r in direct],
                            'context':[self._card(r) for r in run if r not in direct],
                            'meeting_background':backgrounds,
                            'association':'时间相邻，关联待核实；会议背景不是直接结论证据'}
            return list(hits.values())

    def report(self,owner,day,timezone,planned_intervals=None):
        lower,upper=day_bounds(day,timezone)
        with self.store.lock:
            rows=self._rows(owner)
            by_id={r['id']:r for r in rows}
            coverage=[];sessions={};snapshots=[]
            seen=set()
            for f in self.store.db.execute('''SELECT f.*,s.mode,ft.captured_start_utc,ft.captured_end_utc,
                    ft.source_health FROM frames f JOIN sessions s ON s.id=f.session
                    LEFT JOIN frame_times ft ON ft.frame=f.id WHERE s.owner=?''',(owner,)).fetchall():
                try:
                    session=self.store.session(owner,f['session'])
                except Blocked:
                    continue
                if f['captured_start_utc'] is None:
                    continue  # Legacy missing anchors are not fabricated into a reporting day.
                a,b=f['captured_start_utc'],f['captured_end_utc']
                if not (a<upper and (b>lower or a==b and a>=lower)):
                    continue
                deleted=f['deleted'] or f['expired'] or self.store.clock.utc>=f['expires']
                quarantined=self.store.db.execute('SELECT 1 FROM source_quarantine WHERE frame=?',(f['id'],)).fetchone()
                status='deleted_or_expired' if deleted else ('quarantined' if quarantined else 'available' if f['id'] in by_id else 'not_uploaded' if not f['uploaded'] else 'unparsed')
                coverage.append({'source_id':f['id'],'source_type':f['kind'],'session_id':f['session'],
                    'stream':f['stream'],'start_utc':max(a,lower),'end_utc':min(b,upper),
                    'status':status,'source_health':f['source_health'] or 'unknown',
                    'audio_coverage':f['kind']=='audio' and not deleted and f['source_health']=='healthy'})
                seen.add(f['id'])
                sessions[f['session']]=session
                snapshots.append([f['id'],f['revision'],f['deleted'],f['expired'],session['attachment_revision'],session['deletion_generation']])
            for gap in self.store.db.execute('''SELECT d.* FROM deleted_coverage d JOIN sessions s ON s.id=d.session WHERE s.owner=?''',(owner,)).fetchall():
                if gap['frame'] in seen or not (gap['start_utc']<upper and gap['end_utc']>=lower):continue
                try:self.store.session(owner,gap['session'])
                except Blocked:continue
                coverage.append({'source_id':gap['frame'],'source_type':gap['kind'],'session_id':gap['session'],
                    'stream':gap['stream'],'start_utc':max(lower,gap['start_utc']),'end_utc':min(upper,gap['end_utc']),
                    'status':'deleted_or_expired','source_health':'unknown','audio_coverage':False})
                snapshots.append([gap['frame'],'deleted'])
            cards=[];desktop=[]
            for sid,s in sessions.items():
                evidence=[self._card(by_id[c['source_id']]) for c in coverage if c['session_id']==sid and c['source_id'] in by_id]
                item={'session_id':sid,'capture_mode':s['mode'],'evidence':evidence,
                    'attachment_revision':s['attachment_revision'],'decisions':[],'todos':[],
                    'status':'evidence_only','quality':'合成资料摘录，未调用摘要模型'}
                if s['mode']=='desktop':
                    desktop.append(item)
                else:
                    item['label']='线下会议' if s['mode']=='in_person' else '纯手工笔记'
                    cards.append(item)
            denominator=None
            if planned_intervals is not None:
                clipped=[]
                for a,b in planned_intervals:
                    if not (math.isfinite(a) and math.isfinite(b) and a<b):
                        raise ValueError('invalid planned interval')
                    if max(a,lower)<min(b,upper):clipped.append((max(a,lower),min(b,upper)))
                denominator={'basis':'user_selected_intervals','seconds':union_duration(clipped)}
            return {'report_date':day,'reporting_timezone':timezone,'day_seconds':upper-lower,
                'coverage_intervals':coverage,'coverage_denominator':denominator,
                'meeting_cards':cards,'desktop_workflows':desktop,
                'input_version':hashlib.sha256(encode(sorted(snapshots))).hexdigest(),
                'status':'evidence_only','synthetic':True,
                'limitations':['无记录时段不推断工作缺口','未配置计划区间时不计算覆盖率','未调用OCR/ASR/摘要模型']}

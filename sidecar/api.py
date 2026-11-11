"""Loopback development API for synthetic fixtures; not a cloud deployment."""
import hmac
from typing import Literal
from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.responses import JSONResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel, ConfigDict, Field, field_validator
from .core import Blocked, Conflict
from .evidence import EvidenceService


class StrictBody(BaseModel):
    model_config=ConfigDict(extra='forbid',allow_inf_nan=False)


class DeleteRange(StrictBody):
    start:float=Field(ge=0)
    end:float=Field(gt=0)


class Correction(StrictBody):
    base_revision:int=Field(ge=0)
    text:str=Field(min_length=1,max_length=10000)


class JobRequest(StrictBody):
    automatic:bool=False
    override:Literal['once','allow_automatic']|None=None


class CancelRequest(StrictBody):
    block_automatic:bool=True


class UploadResume(StrictBody):
    consent:Literal[True]

    @field_validator('consent', mode='before')
    @classmethod
    def explicit_true(cls, value):
        if value is not True:
            raise ValueError('explicit true required')
        return value


def create_app(store, credentials):
    if not credentials or any(not token or not owner for token,owner in credentials.items()):
        raise ValueError('explicit development credential mapping required')
    app=FastAPI(title='Sidecar Copilot synthetic local API',version='0.2.0',
        description='Frozen v1.5 implementation draft. Synthetic data only; no remote processing.',
        docs_url=None,redoc_url=None,openapi_url=None)
    auth=HTTPBearer(auto_error=False)
    evidence=EvidenceService(store)

    def owner(token:HTTPAuthorizationCredentials|None=Depends(auth)):
        if token and token.scheme.lower()=='bearer':
            for expected,identity in credentials.items():
                if hmac.compare_digest(token.credentials.encode(),expected.encode()):return identity
        raise HTTPException(status_code=401,detail='authentication required',headers={'WWW-Authenticate':'Bearer'})

    @app.exception_handler(Blocked)
    async def blocked(request,error):
        return JSONResponse(status_code=403,content={'error':'blocked','detail':str(error)})

    @app.exception_handler(Conflict)
    async def conflict(request,error):
        return JSONResponse(status_code=409,content={'error':'conflict','detail':str(error)})

    @app.exception_handler(ValueError)
    async def invalid(request,error):
        return JSONResponse(status_code=422,content={'error':'invalid_request'})

    @app.middleware('http')
    async def no_cache(request,call_next):
        response=await call_next(request)
        response.headers['Cache-Control']='no-store'
        response.headers['X-Content-Type-Options']='nosniff'
        return response

    @app.get('/capabilities')
    def capabilities(identity=Depends(owner)):
        return {'phase':'G0_partial','synthetic_only':True,'real_capture':False,'external_processing':False,
            'hardware_accepted':False,'prd':'1.5','conservative_deletion':'whole_frame'}

    @app.get('/openapi.json')
    def schema(identity=Depends(owner)):return app.openapi()

    @app.get('/sessions')
    def sessions(identity=Depends(owner)):
        with store.lock:
            if not store.unlocked:raise Blocked('waiting for unlock')
            return [dict(r) for r in store.db.execute('SELECT id,mode,state FROM sessions WHERE owner=? AND revoked=0',(identity,)).fetchall()]

    @app.get('/search')
    def search(q:str=Query(min_length=1,max_length=1000),identity=Depends(owner)):
        return {'results':evidence.episodes(identity,q.split()),'answer':'请核对引用来源；本开发版不生成事实结论'}

    @app.get('/reports/{day}')
    def report(day:str,timezone:str='UTC',identity=Depends(owner)):
        try:return evidence.report(identity,day,timezone)
        except KeyError:raise HTTPException(422,'unknown timezone')

    @app.post('/sessions/{sid}/deletions')
    def delete(sid:str,body:DeleteRange,identity=Depends(owner)):
        return store.delete_range(identity,sid,body.start,body.end)

    @app.post('/sources/{fid}/corrections')
    def correct(fid:str,body:Correction,identity=Depends(owner)):
        return {'revision':store.correct(identity,fid,body.base_revision,body.text)}

    @app.post('/sessions/{sid}/jobs')
    def request_job(sid:str,body:JobRequest,identity=Depends(owner)):
        return {'job_id':store.request_job(identity,sid,automatic=body.automatic,override=body.override)}

    @app.post('/jobs/{jid}/finish')
    def finish_job(jid:str,identity=Depends(owner)):
        return {'artifact_id':store.finish_job(identity,jid)}

    @app.get('/artifacts/{aid}')
    def artifact(aid:str,identity=Depends(owner)):return store.artifact(identity,aid)

    @app.post('/sessions/{sid}/cancel')
    def cancel(sid:str,body:CancelRequest,identity=Depends(owner)):
        return store.cancel(identity,sid,block_automatic=body.block_automatic)

    @app.post('/sessions/{sid}/uploads/resume')
    def resume_uploads(sid:str,body:UploadResume,identity=Depends(owner)):
        return store.resume_uploads(identity,sid,consent=body.consent)

    return app

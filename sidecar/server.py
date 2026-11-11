"""Run the synthetic API on loopback only. No hardware or external model access."""
import argparse
import os
import tempfile
from datetime import datetime,timezone
from cryptography.hazmat.primitives.asymmetric import rsa
import uvicorn
from .core import Sidecar,SimulationClock
from .api import create_app


def seed_demo(store,owner='demo'):
    sid=store.create_session(owner,'desktop');generation=store.start(owner,sid,consent=True)
    fids=[]
    for kind,text in [('desktop_frame','合成案例：A公司合同报价100元'),('audio','合成案例：张工提出第三条需要复核')]:
        start=store.clock.mono;store.clock.advance(5)
        fids.append(store.capture(owner,sid,generation,stream=kind,kind=kind,start=start,
            end=store.clock.mono,synthetic_text=text))
    store.end(owner,sid)
    store.clock.advance(900);network=store.network(trusted=True,unmetered=True)
    for fid in fids:store.upload(owner,fid,network)
    return sid


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--synthetic-only',action='store_true',required=True)
    parser.add_argument('--port',type=int,default=8766)
    args=parser.parse_args()
    token=os.environ.get('SIDECAR_DEV_TOKEN','')
    if len(token)<32:parser.error('set SIDECAR_DEV_TOKEN to a random token of at least 32 characters')
    if not 1024<=args.port<=65535:parser.error('port must be 1024..65535')
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    with tempfile.TemporaryDirectory(prefix='sidecar-api-synthetic-') as root:
        store=Sidecar(root,SimulationClock(utc=datetime.now(timezone.utc).timestamp()),key)
        try:
            store.unlocked=True;seed_demo(store)
            uvicorn.run(create_app(store,{token:'demo'}),host='127.0.0.1',port=args.port,access_log=False,ws='none')
        finally:store.close()


if __name__=='__main__':main()

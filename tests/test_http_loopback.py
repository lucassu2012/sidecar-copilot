"""One real loopback HTTP smoke check; no external server or model service."""
import socket
import threading
import time
import httpx
import uvicorn
from cryptography.hazmat.primitives.asymmetric import rsa
from sidecar.core import Sidecar,SimulationClock
from sidecar.api import create_app
from sidecar.server import seed_demo


def test_T12_T19_loopback_auth_evidence_delete(tmp_path):
    store=Sidecar(tmp_path,SimulationClock(),rsa.generate_private_key(public_exponent=65537,key_size=2048))
    store.unlocked=True;sid=seed_demo(store)
    sock=socket.socket();sock.bind(('127.0.0.1',0))
    port=sock.getsockname()[1]
    server=uvicorn.Server(uvicorn.Config(create_app(store,{'synthetic-only-test-token':'demo'}),log_level='error',access_log=False,ws='none'))
    thread=threading.Thread(target=server.run,kwargs={'sockets':[sock]},daemon=True)
    thread.start()
    try:
        deadline=time.monotonic()+10
        while not server.started and time.monotonic()<deadline:time.sleep(.02)
        assert server.started
        with httpx.Client(base_url=f'http://127.0.0.1:{port}',trust_env=False,timeout=5) as c:
            assert c.get('/sessions').status_code==401
            c.headers['Authorization']='Bearer synthetic-only-test-token'
            hits=c.get('/search',params={'q':'A公司 张工'}).json()['results']
            assert len(hits)==1 and len(hits[0]['evidence'])==2
            assert c.post(f'/sessions/{sid}/deletions',json={'start':0,'end':10}).status_code==200
            assert c.get('/search',params={'q':'A公司 张工'}).json()['results']==[]
    finally:
        server.should_exit=True;thread.join(10);sock.close();store.close()
        assert not thread.is_alive()

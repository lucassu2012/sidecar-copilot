"""Run with python -m sidecar.demo. All data are generated, all traffic is simulated."""
import json
import tempfile
from cryptography.hazmat.primitives.asymmetric import rsa
from sidecar.core import Sidecar, SimulationClock, Blocked


def main():
    clock=SimulationClock()
    key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
    with tempfile.TemporaryDirectory(prefix='sidecar-g0-') as directory:
        app=Sidecar(directory,clock,key)
        try:
            app.unlocked=True
            sid=app.create_session('synthetic-user','in_person')
            generation=app.start('synthetic-user',sid,consent=True)
            clock.advance(5)
            fid=app.capture('synthetic-user',sid,generation,stream='synthetic-mic',kind='audio',
                start=0,end=5,synthetic_text='Synthetic meeting: quotation amount is 100.')
            app.end('synthetic-user',sid)
            network=app.network(trusted=True,unmetered=True)
            try:
                app.upload('synthetic-user',fid,network)
            except Blocked as error:
                print('Buffer gate:',error)
            clock.advance(900)
            print('Simulated upload:',app.upload('synthetic-user',fid,network))
            print('Idempotent retry:',app.upload('synthetic-user',fid,network))
            print('Evidence search:',json.dumps(app.search('synthetic-user','quotation')))
            job=app.request_job('synthetic-user',sid)
            app.finish_job('synthetic-user',job)
            print('Synthetic extract (not model summary):',app.artifact('synthetic-user',job)['body'])
            print('Deletion:',json.dumps(app.delete_range('synthetic-user',sid,1,3)))
            app.compact()
            print('Search after deletion:',app.search('synthetic-user','quotation'))
            print('External bytes sent: 0 (no network adapter); real recording: disabled')
        finally:
            app.close()


if __name__=='__main__':
    main()

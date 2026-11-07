"""Finite old/new behavioral RED harness; old broad scan is blocked before metadata read."""
import argparse,importlib.util,json,os,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
D=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser();p.add_argument('--source',choices=['original36','current'],required=True);args=p.parse_args()
root=D/'reports/original36' if args.source=='original36' else D
sys.path.insert(0,str(root/'scripts'))
import deployment as d
import supervision as s
class ReviewRegressions(unittest.TestCase):
 def test_hash_only_ack_cannot_authorize_cleanup(self):
  with tempfile.TemporaryDirectory() as td:
   base=Path(td);base.chmod(0o700);saved=base/'saved';saved.mkdir(mode=0o700);entries=[]
   for name in ['receipt.json','SAVE','public']:
    blob=b'ARBITRARY_CORRECTLY_HASHED_BYTES';(saved/name).write_bytes(blob);entries.append({'path':name,'bytes':len(blob),'sha256':d.digest(blob)})
   expected={'run_id':'run1','correlation_id':'corr1','nonce':'nonce1','resource':d.directory_identity(base),'collection_spec_sha256':'1'*64}
   ack={'schema_version':'receiver-ack.v1','authority':'independent_receiver',**expected,'saved_receipt':entries[0],'save_marker':entries[1],'artifacts':[entries[2]],'stopped':True};ap=base/'ack.json';ap.write_text(json.dumps(ack));pin=d.digest(ap.read_bytes())
   with self.assertRaises(d.Blocked):d.verify_ack(ap,pin,expected,saved)
 def test_no_broad_process_metadata_request(self):
  requested=[];original=Path.iterdir
  def bound(path):
   if str(path)=='/proc':requested.append('/proc');return iter([])
   return original(path)
  with patch.object(Path,'iterdir',bound):result=s._run_owned([sys.executable,'-B',str(D/'fixtures/public/child.py'),'failure'],0.3,lambda:None)
  self.assertEqual(result['exit_code'],7);self.assertEqual(requested,[])
 def test_primary_status_cannot_be_stolen_by_adopted_reaper(self):
  actual=s.subprocess.Popen;wait=os.waitpid;primary=[];adopted_wait_requests=[]
  def launched(*a,**kw):
   process=actual(*a,**kw);primary.append(process.pid);return process
  def adopted(pid,flags):adopted_wait_requests.append(pid);return wait(pid,flags)
  with patch.object(s.subprocess,'Popen',launched),patch.object(os,'waitpid',adopted):
   result=s._run_owned([sys.executable,'-B',str(D/'fixtures/public/child.py'),'failure'],0.3,lambda:None)
  self.assertEqual(result['exit_code'],7)
  self.assertNotIn(primary[0],adopted_wait_requests,'Popen must exclusively own primary wait/status')

unittest.main(argv=[sys.argv[0]],verbosity=2)

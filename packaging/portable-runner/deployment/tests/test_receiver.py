"""Public synthetic ACK byte/identity checks, not CLI canonical acceptance."""
import copy,tempfile,unittest
from pathlib import Path
from common import m,dump
class ReceiverTest(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory();self.root=Path(self.t.name);self.root.chmod(0o700);self.saved=self.root/'saved';self.saved.mkdir(mode=0o700);self.source=self.root/'source';self.source.mkdir(mode=0o700)
  self.expected={'run_id':'run1','correlation_id':'corr1','nonce':'nonce1','resource':m.directory_identity(self.source),'collection_spec_sha256':'1'*64}
  dump(self.source/'.deployment-owner.json',{'run_id':'run1','nonce':'nonce1'})
  entries=[]
  for name in ['receipt.json','SAVE','public.txt']:
   p=self.saved/name;p.write_bytes(b'PUBLIC_SYNTHETIC_UNIT_ONLY');entries.append({'path':name,'bytes':p.stat().st_size,'sha256':m.digest(p.read_bytes())})
  self.value={'schema_version':'receiver-ack.v1','authority':'independent_receiver',**self.expected,'saved_receipt':entries[0],'save_marker':entries[1],'artifacts':[entries[2]],'stopped':True};self.ack=self.root/'ack.json';self.pin=dump(self.ack,self.value)
 def tearDown(self):self.t.cleanup()
 def verify(self):return m.verify_ack(self.ack,self.pin,self.expected,self.saved)
 def rewrite(self):self.pin=dump(self.ack,self.value)
 def test_legacy_hashes_do_not_prove_actual_collector(self):
  with self.assertRaises(m.Blocked):self.verify()
 def test_pin_not_derived_from_tampered_producer(self):
  self.value['nonce']='other';dump(self.ack,self.value)
  with self.assertRaises(m.Blocked):self.verify()
 def test_each_identity_or_stop_mismatch(self):
  for k,v in [('run_id','other'),('correlation_id','other'),('nonce','other'),('collection_spec_sha256','0'*64),('resource',{'device':0,'inode':0,'uid':0}),('authority','producer'),('stopped',False),('stopped','true')]:
   old=self.value[k];self.value[k]=v;self.rewrite()
   with self.subTest(field=k),self.assertRaises(m.Blocked):self.verify()
   self.value[k]=old
 def test_missing_SAVE_or_receipt_retains(self):
  for name in ['SAVE','receipt.json']:
   p=self.saved/name;data=p.read_bytes();p.unlink()
   with self.assertRaises(m.Blocked):self.verify()
   p.write_bytes(data)
 def test_partial_or_changed_receiver_file(self):
  (self.saved/'public.txt').write_bytes(b'partial')
  with self.assertRaises(m.Blocked):self.verify()
 def test_path_catalog_unknown_and_duplicate(self):
  for k,v in [('path','../escape'),('bytes',8388609),('sha256','bad'),('unexpected',1)]:
   entry=self.value['artifacts'][0];old=copy.deepcopy(entry);entry[k]=v;self.rewrite()
   with self.assertRaises(m.Blocked):self.verify()
   self.value['artifacts'][0]=old
 def test_cleanup_request_never_deletes(self):
  with self.assertRaises(m.Blocked):m.cleanup_request(self.ack,self.pin,self.expected,self.saved,self.source)
  self.assertTrue(self.source.exists())
 def test_replaced_directory_identity_rejected(self):
  replacement=self.root/'replacement';replacement.mkdir(mode=0o700)
  with self.assertRaises(m.Blocked):m.cleanup_request(self.ack,self.pin,self.expected,self.saved,replacement)
 def test_current_owner_mismatch(self):
  dump(self.source/'.deployment-owner.json',{'run_id':'other','nonce':'nonce1'})
  with self.assertRaises(m.Blocked):m.cleanup_request(self.ack,self.pin,self.expected,self.saved,self.source)

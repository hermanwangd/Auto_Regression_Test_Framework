import os,tempfile,unittest
from pathlib import Path
from common import m,dump
class StageTest(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory();self.root=Path(self.t.name);self.root.chmod(0o700);self.source=self.root/'source';self.source.mkdir(mode=0o700);self.target=self.root/'target';(self.source/'public.txt').write_bytes(b'public input\n');self.manifest=self.root/'manifest.json'
  self.value={'schema_version':'deployment-stage.v1','run_id':'fixture1','nonce':'nonce1','files':[{'path':'public.txt','bytes':13,'sha256':m.digest(b'public input\n'),'executable':False}]};self.pin=dump(self.manifest,self.value)
 def tearDown(self):self.t.cleanup()
 def runstage(self):return m.stage(self.source,self.target,self.manifest,self.pin)
 def test_second_pass_unchanged_and_readonly(self):
  self.assertTrue(self.runstage()['changed']);self.assertFalse(self.runstage()['changed']);self.assertEqual((self.target/'public.txt').stat().st_mode&0o777,0o400)
 def test_checksum_failure_before_output(self):
  (self.source/'public.txt').write_bytes(b'changed')
  with self.assertRaises(m.Blocked):self.runstage()
  self.assertFalse(self.target.exists())
 def test_existing_byte_or_mode_change_blocks(self):
  self.runstage();(self.target/'public.txt').chmod(0o600)
  with self.assertRaises(m.Blocked):self.runstage()
 def test_owner_replay_blocks(self):
  self.runstage();self.value['nonce']='other';self.pin=dump(self.manifest,self.value)
  with self.assertRaises(m.Blocked):self.runstage()
 def test_source_symlink_and_target_symlink(self):
  (self.source/'public.txt').unlink();(self.source/'public.txt').symlink_to(self.manifest)
  with self.assertRaises(m.Blocked):self.runstage()
 def test_unexpected_existing_file_blocks(self):
  self.runstage();(self.target/'foreign').write_text('public')
  with self.assertRaises(m.Blocked):self.runstage()
 def test_oversized_and_traversal_catalog_blocks(self):
  for path,size in [('../escape',1),('public.txt',4194305)]:
   self.value['files'][0].update(path=path,bytes=size);self.pin=dump(self.manifest,self.value)
   with self.assertRaises(m.Blocked):self.runstage()
 def test_target_outside_manifest_root_blocks(self):
  outside=self.root/'other';outside.mkdir(mode=0o700);self.target=outside/'target'
  with self.assertRaises(m.Blocked):self.runstage()
  self.assertFalse(self.target.exists())

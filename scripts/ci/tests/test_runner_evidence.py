import hashlib,importlib.util,json,pathlib,tempfile,unittest
ROOT=pathlib.Path(__file__).resolve().parents[1]
def sha(b):return hashlib.sha256(b).hexdigest()
class RunnerStaging(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory(prefix='public-runner-stage-');self.addCleanup(self.temp.cleanup);self.root=pathlib.Path(self.temp.name);self.source=self.root/'source';self.source.mkdir();self.dest=self.root/'stage'
  (self.source/'canonical').mkdir();(self.source/'canonical/evidence.json').write_bytes(b'{"public":true}\n')
  self.receipt={'schema_version':'runner-collection-receipt.v1','run_id':'PUBLIC','collection':'COMPLETE','publication':'SAVED','framework_verdict':'UNKNOWN','files':[{'path':'canonical/evidence.json','role':'evidence','state':'SAVED','saved_sha256':sha(b'{"public":true}\n'),'saved_bytes':16}]}
  self.receipt['files'][0]['saved_bytes']=(self.source/'canonical/evidence.json').stat().st_size
 def module(self):
  p=ROOT/'stage-runner-evidence.py';self.assertTrue(p.is_file(),'Finite runner evidence staging command unavailable');s=importlib.util.spec_from_file_location('stage_runner',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
 def stage(self):
  raw=(json.dumps(self.receipt)+'\n').encode();(self.source/'collection.json').write_bytes(raw);return self.module().stage(self.source,sha(raw),self.dest)
 def test_only_admitted_files_staged_and_unlisted_secret_excluded(self):
  (self.source/'unlisted-secret.txt').write_text('public-canary-must-not-copy');r=self.stage();self.assertEqual(r['status'],'STAGED');self.assertTrue((self.dest/'canonical/evidence.json').is_file());self.assertFalse((self.dest/'unlisted-secret.txt').exists());self.assertEqual((self.dest/'canonical/evidence.json').read_bytes(),b'{"public":true}\n')
 def test_traversal_cannot_stage_outside_source(self):
  self.receipt['files'][0]['path']='../outside';r=self.stage();self.assertEqual(r['status'],'BLOCKED');self.assertFalse((self.dest/'STAGE.complete').exists())
 def test_symlink_or_hash_tamper_cannot_publish_completion(self):
  p=self.source/'canonical/evidence.json';other=self.root/'outside';p.rename(other);p.symlink_to(other);r=self.stage();self.assertEqual(r['status'],'BLOCKED');self.assertFalse((self.dest/'STAGE.complete').exists())
 def test_hash_tamper_is_refused_without_completion(self):
  (self.source/'canonical/evidence.json').write_bytes(b'{"public":false}');r=self.stage();self.assertEqual(r['status'],'BLOCKED');self.assertFalse((self.dest/'STAGE.complete').exists())
 def test_private_configuration_and_unknown_role_refused(self):
  self.receipt['files'][0]['role']='unapproved-secret';r=self.stage();self.assertEqual(r['status'],'BLOCKED');self.assertFalse((self.dest/'STAGE.complete').exists())
 def test_failed_collection_retains_safe_metadata_without_product_pass(self):
  self.receipt.update(collection='PARTIAL',framework_verdict='failed');self.receipt['files'][0]['state']='MISSING';r=self.stage();self.assertEqual(r['status'],'STAGED');self.assertEqual(r['source_collection'],'PARTIAL');self.assertEqual(r['framework_verdict'],'failed');self.assertFalse((self.dest/'canonical/evidence.json').exists())
 def test_rejected_path_has_finite_diagnostic_without_raw_canary(self):
  self.receipt['files'][0]['path']='/PUBLIC_PATH_CANARY/private';r=self.stage();self.assertEqual(r['status'],'BLOCKED');self.assertNotIn('PUBLIC_PATH_CANARY',json.dumps(r));self.assertTrue(r['reason'].startswith('STAGING_REFUSED_'))
 def test_new_directory_parent_is_forced_before_completion(self):
  m=self.module();calls=[];original=m.sync
  def observed(p):calls.append(pathlib.Path(p));original(p)
  m.sync=observed
  raw=(json.dumps(self.receipt)+'\n').encode();(self.source/'collection.json').write_bytes(raw);r=m.stage(self.source,sha(raw),self.dest);self.assertEqual(r['status'],'STAGED');self.assertIn(self.dest,calls);self.assertIn(self.dest/'canonical',calls)
 def test_parent_sync_fault_never_publishes_completion(self):
  m=self.module();original=m.sync
  def failed(p):
   if pathlib.Path(p)==self.dest:raise OSError('PUBLIC_EXCEPTION_CANARY')
   original(p)
  m.sync=failed;raw=(json.dumps(self.receipt)+'\n').encode();(self.source/'collection.json').write_bytes(raw)
  with self.assertRaises(OSError):m.stage(self.source,sha(raw),self.dest)
  self.assertFalse((self.dest/'STAGE.complete').exists())
if __name__=='__main__':unittest.main(verbosity=2)

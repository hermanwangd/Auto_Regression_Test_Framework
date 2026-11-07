import hashlib,importlib.util,json,tempfile,unittest,zipfile
from pathlib import Path
I=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
class SupportTest(unittest.TestCase):
 def setUp(self):
  p=I/'package_support.py';self.assertTrue(p.is_file(),'accepted producer and durable-readback guards absent');s=importlib.util.spec_from_file_location('support',p);self.mod=importlib.util.module_from_spec(s);s.loader.exec_module(self.mod);self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
 def pin(self,p):return {'path':str(p),'bytes':p.stat().st_size,'sha256':sha(p)}
 def producer(self):
  schema=b'PUBLIC_SYNTHETIC_SCHEMA';jar=self.root/'tool.jar'
  with zipfile.ZipFile(jar,'w') as z:z.writestr('BOOT-INF/classes/schemas/runner_collection.v1.schema.yaml',schema);z.writestr('BOOT-INF/classes/com/specdriven/regression/runner/RunnerCommand.class',b'PUBLIC')
  wrapper=self.root/'wrapper.py';wrapper.write_text('PUBLIC');review=self.root/'review.json';review.write_text('{"decision":"APPROVE_SOURCE"}');obs=self.root/'observer.json';obs.write_text('{}')
  doc={'format_version':'accepted-runner-producer.v1','accepted_source_commit':'a'*40,'accepted_source_tree':'b'*40,'acceptance_review':self.pin(review),'framework_jar':self.pin(jar),'wrapper':{**self.pin(wrapper),'source_commit':'c'*40},'runtime_schema':{'resource_path':'schemas/runner_collection.v1.schema.yaml','sha256':hashlib.sha256(schema).hexdigest()},'producer_observation':{**self.pin(obs),'observed_JAR_SHA':sha(jar),'schema_SHA':hashlib.sha256(schema).hexdigest(),'status':'JAR_OBSERVED'}};p=self.root/'producer.json';p.write_text(json.dumps(doc));return p,doc
 def test_external_pin_and_actual_jar_schema_binding(self):
  p,d=self.producer();self.assertEqual(self.mod.validate_producer(p,sha(p))['framework_jar']['sha256'],d['framework_jar']['sha256'])
 def test_neutral_review_and_observation_pins_need_no_byte_field(self):
  p,d=self.producer();d['acceptance_review'].pop('bytes');d['producer_observation'].pop('bytes');p.write_text(json.dumps(d));self.assertEqual(self.mod.validate_producer(p,sha(p))['framework_jar']['sha256'],d['framework_jar']['sha256'])
 def test_wrong_producer_pin_rejected(self):
  p,_=self.producer()
  with self.assertRaises(ValueError):self.mod.validate_producer(p,'0'*64)
 def test_observation_cannot_relabel_wrong_jar(self):
  p,d=self.producer();d['producer_observation']['observed_JAR_SHA']='0'*64;p.write_text(json.dumps(d))
  with self.assertRaises(ValueError):self.mod.validate_producer(p,sha(p))
 def test_all_container_proxies_explicitly_empty(self):
  args=self.mod.security_args();
  for key in ['HTTP_PROXY','http_proxy','HTTPS_PROXY','https_proxy','ALL_PROXY','all_proxy','NO_PROXY','no_proxy']:self.assertIn(key+'=',args)
  self.assertIn('65532:65532',args);self.assertIn('--read-only',args);self.assertIn('none',args)
 def test_local_build_cannot_inherit_remote_builder_selector(self):
  from unittest.mock import patch
  with patch.dict(self.mod.os.environ,{'DOCKER_HOST':'tcp://remote.invalid:2375','BUILDX_BUILDER':'foreign','HTTPS_PROXY':'PUBLIC_PROXY_PLACEHOLDER'}):
   env=self.mod.docker_environment(build=True)
  self.assertNotIn('DOCKER_HOST',env);self.assertNotIn('BUILDX_BUILDER',env);self.assertEqual(env['DOCKER_BUILDKIT'],'0');self.assertEqual(env['HTTPS_PROXY'],'PUBLIC_PROXY_PLACEHOLDER')
 def test_saved_metadata_forces_new_directory_entry_chain(self):
  from unittest.mock import patch
  target=self.root/'nested/deeper/report.json'
  with patch.object(self.mod,'syncdir',wraps=self.mod.syncdir) as force:self.mod.save(target,{'public':True})
  forced={Path(c.args[0]) for c in force.call_args_list}
  self.assertTrue({self.root,self.root/'nested',self.root/'nested/deeper'}<=forced)
 def collection(self,complete):
  out=self.root/'saved';out.mkdir();file=out/'logs/run.log';file.parent.mkdir();file.write_text('PUBLIC\n');receipt={'collection':'COMPLETE' if complete else 'PARTIAL','publication':'SAVED','framework_verdict':'UNKNOWN','files':[{'path':'logs/run.log','state':'SAVED','saved_sha256':sha(file),'saved_bytes':file.stat().st_size}]};(out/'collection.json').write_text(json.dumps(receipt));(out/'aliases.json').write_text('{}')
  if complete:(out/'SAVE.complete').write_text('BYTE_COLLECTION_SAVED_NOT_PRODUCT_VERDICT\n')
  return out
 def test_complete_readback_requires_exact_hashes(self):
  out=self.collection(True);self.assertTrue(self.mod.verify_saved(out,True)['verified']);(out/'logs/run.log').write_text('TAMPER')
  with self.assertRaises(ValueError):self.mod.verify_saved(out,True)
 def test_partial_never_borrows_complete_marker(self):
  out=self.collection(False);self.assertTrue(self.mod.verify_saved(out,False)['verified']);(out/'SAVE.complete').write_text('unexpected')
  with self.assertRaises(ValueError):self.mod.verify_saved(out,False)
 def test_missing_complete_marker_prevents_cleanup_authority(self):
  out=self.collection(True);(out/'SAVE.complete').unlink()
  with self.assertRaises(ValueError):self.mod.verify_saved(out,True)
if __name__=='__main__':unittest.main()

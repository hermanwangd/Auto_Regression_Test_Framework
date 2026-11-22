"""Source-only until root runtime release. Public local control, never VM/SIT."""
import copy,json,os,stat,tempfile,time,unittest
from pathlib import Path
from unittest.mock import patch
from common import D,m,module,dump
c=module('composition')
class TransferAndDurabilityTest(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory();self.root=Path(self.t.name);self.root.chmod(0o700);self.source=self.root/'source';self.dest=self.root/'dest';self.source.mkdir(mode=0o700);self.dest.mkdir(mode=0o700);(self.source/'public').write_bytes(b'PUBLIC');self.entry={'path':'public','role':'evidence','bytes':6,'sha256':m.digest(b'PUBLIC')}
 def tearDown(self):self.t.cleanup()
 def test_finite_copy_exact_and_readonly(self):
  c.copy_one(self.source,self.dest,self.entry,4194304,time.monotonic()+2)
  self.assertEqual((self.dest/'public').read_bytes(),b'PUBLIC');self.assertEqual((self.dest/'public').stat().st_mode&0o777,0o400)
 def test_path_size_and_hash_negative(self):
  for change in [{'path':'../other'},{'bytes':1},{'sha256':'0'*64}]:
   with self.subTest(change=change),self.assertRaises(m.Blocked):c.copy_one(self.source,self.dest,self.entry|change,4194304,time.monotonic()+2)
  self.assertFalse((self.dest/'public').exists())
 def test_copy_deadline_prewrite(self):
  with self.assertRaises(m.Blocked):c.copy_one(self.source,self.dest,self.entry,4194304,time.monotonic()-1)
  self.assertFalse((self.dest/'public').exists())
 def test_pre_post_mutation_retains_source(self):
  original=m.write_durable
  def mutate(path,blob,mode=0o400):
   original(path,blob,mode);(self.source/'public').unlink();(self.source/'public').write_bytes(b'PUBLIC')
  with patch.object(m,'write_durable',mutate),self.assertRaises(m.Blocked):c.copy_one(self.source,self.dest,self.entry,4194304,time.monotonic()+2)
  self.assertTrue((self.source/'public').exists())
 def test_symlink_fifo_hardlink(self):
  (self.source/'public').unlink();(self.source/'public').symlink_to(self.root/'outside')
  with self.assertRaises(m.Blocked):c.copy_one(self.source,self.dest,self.entry,4194304,time.monotonic()+2)
  (self.source/'public').unlink();os.mkfifo(self.source/'public')
  with self.assertRaises(m.Blocked):c.copy_one(self.source,self.dest,self.entry,4194304,time.monotonic()+2)
 def test_ancestor_and_file_forces_observed(self):
  calls=[];original=os.fsync
  def force(fd):calls.append(os.fstat(fd).st_mode);return original(fd)
  with patch.object(os,'fsync',force):
   nested=c.private_mkdir(self.dest/'nested');m.write_durable(nested/'x',b'PUBLIC')
  self.assertTrue(any(stat.S_ISREG(x) for x in calls));self.assertGreaterEqual(sum(stat.S_ISDIR(x) for x in calls),3)
 def test_force_failure_blocks(self):
  with patch.object(os,'fsync',side_effect=OSError('public injected failure')),self.assertRaises((m.Blocked,OSError)):c.private_mkdir(self.dest/'nested')
 def test_exact_derived_limits(self):
  spec={'payload_profile':'reviewed_grpc','canonical_result':'canonical/result.json'}
  self.assertEqual(c.file_limit('canonical','canonical/result.json',spec),8388608)
  self.assertEqual(c.file_limit('canonical','other.json',spec),4194304)
  self.assertEqual(c.file_limit('log','x',spec),1048576)
  self.assertEqual(c.file_limit('collector_metadata','collection.json',spec),2097152)
 def test_typed_JAR_does_not_relax_metadata(self):
  row={'path':str(self.source/'public'),'bytes':97097006,'sha256':c.JAR_SHA,'kind':'metadata'}
  with self.assertRaises(m.Blocked):c.pin_file(row,'metadata')
 def test_unlisted_linked_tree_refused(self):
  (self.dest/'link').symlink_to(self.source/'public')
  with self.assertRaises(m.Blocked):c.inventory(self.dest)
class SemanticProofTest(unittest.TestCase):
 def test_legacy_arbitrary_hashes_never_eligible(self):
  with self.assertRaises(m.Blocked):m.verify_ack('unused','0'*64,{},'unused')
 def test_observed_stop_never_self_typed(self):
  s=module('supervision')
  self.assertFalse(s.stopped_identities([]));self.assertFalse(s.stopped_identities([{'pid':os.getpid(),'start':s._identity(os.getpid())[1],'uid':os.getuid()}]));self.assertFalse(s.stopped_identities([{'pid':True,'start':1,'uid':os.getuid()}]))
 def test_unknown_fields_rejected_before_any_spawn(self):
  with self.assertRaises(m.Blocked):c.admit({'schema_version':'deployment-composition.v1','enabled':True,'argv':['sh']})
class ReceiverSemanticsTest(unittest.TestCase):
 def setUp(self):
  self.t=tempfile.TemporaryDirectory();self.root=Path(self.t.name);self.root.chmod(0o700);self.source=self.root/'source';self.source.mkdir(mode=0o700);self.saved=self.root/'saved';self.saved.mkdir(mode=0o700);self.call=self.root/'call.json'
  self.spec={'schema_version':'runner-collection.v1','run_id':'run1','correlation_id':'corr1','payload_profile':'standard','canonical_result':None,'deadline_ms':30000,'artifact_versions':{k:'PUBLIC_UNIT' for k in ['runner','framework','provider','source','bundle','sut']},'files':[{'path':'public','role':'evidence','bytes':6,'sha256':m.digest(b'PUBLIC'),'required':True}]}
  sp=self.root/'spec.json';pin=dump(sp,self.spec);self.w={'run_id':'run1','correlation_id':'corr1','collection_spec':{'path':str(sp),'sha256':pin},'tools':{'java':{'path':'/unit/java'},'framework_jar':{'path':'/unit/framework.jar'}}}
  self.receipt={'schema_version':'runner-collection-receipt.v1','run_id':'run1','correlation_id':'corr1','spec_sha256':pin,'artifact_versions':self.spec['artifact_versions'],'collection':'COMPLETE','publication':'SAVED','gaps':[],'validation':'NOT_RUN','framework_verdict':'UNKNOWN','validator':{'identity_state':'JAR_OBSERVED','artifact_sha256':c.JAR_SHA,'schema_sha256':'23bd700260c26a99ce9db7dd393a9b088bfd3a2bd65593f04f651e5dee210029'},'files':[{'path':'public','role':'evidence','state':'SAVED','source_hash_verified':True,'original_sha256':m.digest(b'PUBLIC'),'original_bytes':6,'saved_sha256':m.digest(b'PUBLIC'),'saved_bytes':6}]}
  dump(self.saved/'collection.json',self.receipt);(self.saved/'SAVE.complete').write_bytes(c.SAVE);(self.saved/'public').write_bytes(b'PUBLIC');dump(self.saved/'aliases.json',{'schema_version':'runner-portable-aliases.v1','aliases':[{'original_ref':'public','portable_ref':'public','original_sha256':m.digest(b'PUBLIC'),'saved_sha256':m.digest(b'PUBLIC')}]})
  call={'schema_version':'package-collector-invocation.v1','run_id':'run1','correlation_id':'corr1','spec_sha256':pin,'jar_sha256':c.JAR_SHA,'output_identity':m.directory_identity(self.saved),'source_identity':m.directory_identity(self.source),'argv':['/unit/java','-jar','/unit/framework.jar','runner-import','--collection-spec',str(sp),'--expected-spec-sha256',pin,'--source',str(self.source),'--out',str(self.saved)],'stop':{'exit_code':0,'reason':'exited','errors':[],'stop_verified':True,'unknown_children':[],'identities':[{'pid':2147483647,'start':1,'uid':os.getuid()}]}};dump(self.call,call)
 def tearDown(self):self.t.cleanup()
 def verify(self):
  with patch.object(c,'run_root',return_value=self.root),patch.object(c,'receiver_root',return_value=self.root):
   # Source path must be a fixed produced/incoming/saved location.
   self.source.rename(self.root/'produced') if self.source.exists() else None
   value=m.strict_json(self.call);value['argv'][9]=str(self.root/'produced');value['source_identity']=m.directory_identity(self.root/'produced');dump(self.call,value)
   return c.verify_saved(self.w,self.saved,self.call)
 def test_arbitrary_SAVE_bytes_rejected(self):
  (self.saved/'SAVE.complete').write_bytes(b'CORRECTLY_HASHED_BUT_NOT_CLI_MARKER')
  with self.assertRaises(m.Blocked):self.verify()
 def test_run_spec_complete_publication_join(self):
  for key,value in [('run_id','other'),('spec_sha256','0'*64),('collection','PARTIAL'),('publication','UNSAVED'),('gaps',[{'code':'missing'}]),('framework_verdict','passed')]:
   original=self.receipt[key];self.receipt[key]=value;dump(self.saved/'collection.json',self.receipt)
   with self.subTest(field=key),self.assertRaises(m.Blocked):self.verify()
   self.receipt[key]=original
 def test_wrong_producer_observation_rejected(self):
  self.receipt['validator']['artifact_sha256']='0'*64;dump(self.saved/'collection.json',self.receipt)
  with self.assertRaises(m.Blocked):self.verify()
 def test_changed_saved_bytes_rejected(self):
  (self.saved/'public').write_bytes(b'CHANGE')
  with self.assertRaises(m.Blocked):self.verify()
 def test_unlisted_empty_foreign_directory_rejected(self):
  (self.saved/'foreign').mkdir(mode=0o700)
  with self.assertRaises(m.Blocked):self.verify()
 def test_accepted_shape_fixture_is_not_eligibility(self):
  entries=self.verify();self.assertEqual(len(entries),4)
  # This fixture tests joins only. Eligibility always performs actual CLI again.
  with self.assertRaises(m.Blocked):m.verify_ack('unused','0'*64,{},self.saved)
class CleanupBudgetTest(unittest.TestCase):
 def test_expired_destructive_budget_retains_exact_source(self):
  with tempfile.TemporaryDirectory() as td:
   parent=Path(td);parent.chmod(0o700);source=parent/'run';source.mkdir(mode=0o700);(source/'public').write_bytes(b'PUBLIC');controller=parent/'controller';controller.mkdir(mode=0o700);resource=m.directory_identity(source);files=c.inventory(source)
   w={'run_parent':str(parent),'run_id':'run1','nonce':'nonce1'}
   with patch.object(c,'verify_receiver',return_value={'resource':resource}),patch.object(c,'owned_run',return_value=source),patch.object(c,'receiver_root',return_value=controller),patch.object(c,'cleanup_catalog',return_value=({},files)),patch.object(c,'stop_receipt',return_value={}),patch.object(c.time,'monotonic',side_effect=[100,103]):
    with self.assertRaises(m.Blocked):c.cleanup(w,'unused','0'*64)
   self.assertEqual((source/'public').read_bytes(),b'PUBLIC');self.assertTrue(controller.exists())

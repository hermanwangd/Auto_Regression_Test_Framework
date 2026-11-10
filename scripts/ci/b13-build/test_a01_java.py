import pathlib,tempfile,unittest,zipfile,json
from unittest import mock
import build_driver as d
class A01JavaControls(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(); self.root=pathlib.Path(self.tmp.name)
 def tearDown(self): self.tmp.cleanup()
 def budget(self): return d.Budget([self.root],total=5,reserve=.2)
 def test_counts(self):
  self.assertEqual(sum(d.ROOT_TESTS.values()),248); self.assertEqual(sum(d.MODULE_TESTS.values()),59)
  self.assertEqual(len(d.ROOT_TESTS)+len(d.MODULE_TESTS),20)
 def test_classpath_rejected(self):
  for content in ('Manifest-Version: 1.0\nClass-Path: x.jar\n','Manifest-Version: 1.0\r\nclass-path: x.jar\r\n y.jar\r\n'):
   p=self.root/'bad.jar'
   with zipfile.ZipFile(p,'w') as z:z.writestr('META-INF/MANIFEST.MF',content)
   with self.assertRaises(d.BuildError):d.validate_owner_jar(p)
 def test_duplicate_rejected(self):
  import warnings
  p=self.root/'bad.jar'
  with warnings.catch_warnings():
   warnings.simplefilter('ignore')
   with zipfile.ZipFile(p,'w') as z:z.writestr('x',b'a');z.writestr('x',b'b')
  with self.assertRaises(d.BuildError):d.validate_owner_jar(p)
 def fixture(self):
  source=self.root/'source';source.mkdir();m2=self.root/'m2';m2.mkdir();delivery=self.root/'delivery';delivery.mkdir()
  for origin,entry in d.OWNER_CLASSES:
   p=source/origin/entry;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'\xca\xfe\xba\xbe'+entry.encode())
  for rel in d.OWNER_DEPS:
   p=m2/rel;p.parent.mkdir(parents=True,exist_ok=True)
   with zipfile.ZipFile(p,'w') as z:z.writestr('fixture',b'x')
  return source,m2,delivery
 def test_owner_closure(self):
  s,m,o=self.fixture();r=d.package_owner(s,m,o,self.budget());self.assertEqual(len(r['jars']),5);self.assertEqual(len(r['class_entries']),9)
  with zipfile.ZipFile(o/'owner/owner-support.jar') as z:self.assertEqual(set(z.namelist()),{e for _,e in d.OWNER_CLASSES})
 def test_extra_nested_rejected(self):
  s,m,o=self.fixture();p=s/'target/test-classes'/d.OWNER_CLASSES[0][1];p.with_name('A01CatalogOwner$Unexpected.class').write_bytes(b'x')
  with self.assertRaises(d.BuildError):d.package_owner(s,m,o,self.budget())
 def test_missing_class_rejected(self):
  s,m,o=self.fixture();(s/d.OWNER_CLASSES[0][0]/d.OWNER_CLASSES[0][1]).unlink()
  with self.assertRaises(d.BuildError):d.package_owner(s,m,o,self.budget())
 def test_symlink_dependency_rejected(self):
  s,m,o=self.fixture();p=m/d.OWNER_DEPS[0];p.unlink();p.symlink_to('/dev/null')
  with self.assertRaises(d.BuildError):d.package_owner(s,m,o,self.budget())
 def test_owner_cap_rejected(self):
  s,m,o=self.fixture()
  with mock.patch.object(d,'OWNER_BYTES',1):
   with self.assertRaises(d.BuildError):d.package_owner(s,m,o,self.budget())
 def test_failure_reports_bounded_and_labeled(self):
  source=self.root/'source';work=self.root/'work';source.mkdir();work.mkdir();report=source/'target/surefire-reports';report.mkdir(parents=True)
  name='TEST-'+next(iter(d.ROOT_TESTS))+'.xml';(report/name).write_bytes(b'<broken>');(report/'TEST-foreign.xml').write_bytes(b'x')
  result=d.collect_diagnostics(source,work,self.budget(),'primary failure');self.assertEqual(result['status'],'FAILED_DIAGNOSTICS_ONLY');self.assertEqual(len(result['reports']),1)
  self.assertFalse((work/'diagnostics/root/TEST-foreign.xml').exists());self.assertFalse((work/'delivery').exists())
 def test_failure_oversize_omitted(self):
  source=self.root/'source';work=self.root/'work';source.mkdir();work.mkdir();report=source/'target/surefire-reports';report.mkdir(parents=True)
  name='TEST-'+next(iter(d.ROOT_TESTS))+'.xml';(report/name).write_bytes(b'x'*(512*1024+1))
  r=d.collect_diagnostics(source,work,self.budget(),'bad');self.assertEqual(r['reports'],[]);self.assertEqual(len(r['omitted']),1)
 def test_owner_exact_order(self):
  s,m,o=self.fixture();r=d.package_owner(s,m,o,self.budget());self.assertEqual(r['classpath_order'],['owner/owner-support.jar']+['owner/'+pathlib.Path(x).name for x in d.OWNER_DEPS])
 def test_failure_parent_symlink_omitted(self):
  source=self.root/'source';work=self.root/'work';source.mkdir();work.mkdir();external=self.root/'external';external.mkdir();(external/'surefire-reports').mkdir();(source/'target').symlink_to(external,target_is_directory=True)
  r=d.collect_diagnostics(source,work,self.budget(),'bad');self.assertEqual(r['reports'],[]);self.assertIn('root: unsafe directory',r['omitted'])
 def test_junit_copy_hash(self):
  source=self.root/'source';delivery=self.root/'delivery';source.mkdir();delivery.mkdir();j={}
  for label,rel in [('root','target/surefire-reports'),('module','integrations/swarm-product-test/target/surefire-reports')]:
   folder=source/rel;folder.mkdir(parents=True);p=folder/'TEST-example.Case.xml';p.write_bytes(b'x');j[label]={'reports':[{'file':p.name,'bytes':1,'sha256':d.hashlib.sha256(b'x').hexdigest()}]}
  r=d.copy_junit(source,delivery,j,self.budget());self.assertEqual(len(r),2)
 def test_junit_copy_mismatch_rejected(self):
  source=self.root/'source';delivery=self.root/'delivery';source.mkdir();delivery.mkdir();folder=source/'target/surefire-reports';folder.mkdir(parents=True);(folder/'TEST-example.Case.xml').write_bytes(b'x');j={'root':{'reports':[{'file':'TEST-example.Case.xml','bytes':1,'sha256':'0'*64}]}}
  with self.assertRaises(d.BuildError):d.copy_junit(source,delivery,j,self.budget())
if __name__=='__main__':unittest.main()

import importlib.util,unittest
from pathlib import Path
P=Path(__file__).resolve().parents[1]/'scripts/deployment.py';S=importlib.util.spec_from_file_location('deployment',P);m=importlib.util.module_from_spec(S);S.loader.exec_module(m)
class BindingTest(unittest.TestCase):
 def test_default_disabled_is_not_runnable(self):
  self.assertEqual(m.validate_binding({'schema_version':'deployment-binding.v1','enabled':False}),{'status':'DISABLED','runnable':False})
 def test_enabled_missing_operational_binding_blocks(self):
  with self.assertRaises(m.Blocked):m.validate_binding({'schema_version':'deployment-binding.v1','enabled':True},execute=True)
 def test_secret_ref_without_materializer_blocks(self):
  with self.assertRaises(m.Blocked):m.validate_binding({'schema_version':'deployment-binding.v1','enabled':True,'secret_refs':['secretref:owned'],'secret_materializer':'UNBOUND'},execute=True)
if __name__=='__main__':unittest.main()

from common import binding,dump
import copy,os,tempfile
class AdmissionTest(unittest.TestCase):
 def test_bound_static_is_never_runnable(self):self.assertFalse(m.validate_binding(binding())['runnable'])
 def test_bound_operational_execution_still_blocks(self):
  with self.assertRaises(m.Blocked):m.validate_binding(binding(),True)
 def test_each_unbound_or_injected_field(self):
  edits=[('image','example/tool:latest'),('owner_uid',0),('owner_uid',True),('framework_sha256','UNBOUND'),('transport','p0_probe'),('secret_refs',['secretref:owned']),('secret_materializer','env'),('nonce','../outside'),('command',['sh'])]
  for k,v in edits:
   with self.subTest(field=k):
    b=binding();b[k]=v
    with self.assertRaises(m.Blocked):m.validate_binding(b)
 def test_storage_owner_and_exclusivity(self):
  for k,v in [('exclusive',False),('writable_uid',0),('output_claim','input1')]:
   b=binding();b['storage'][k]=v
   with self.assertRaises(m.Blocked):m.validate_binding(b)
 def test_duplicate_and_pin(self):
  with tempfile.TemporaryDirectory() as td:
   p=Path(td)/'x';p.write_text('{"x":1,"x":2}')
   with self.assertRaises(m.Blocked):m.strict_json(p)
   p.write_text('{}')
   with self.assertRaises(m.Blocked):m.strict_json(p,'0'*64)
 def test_path_injection(self):
  for v in ['/etc/passwd','../x','a/../x','a//x','a/./x','x\\y','']:
   with self.subTest(path=v),self.assertRaises(m.Blocked):m.relative(v)
 def test_regular_only_bounds(self):
  with tempfile.TemporaryDirectory() as td:
   root=Path(td);p=root/'file';p.write_bytes(b'x');os.link(p,root/'hard')
   with self.assertRaises(m.Blocked):m.regular_bytes(p,10)
   (root/'link').symlink_to(p)
   with self.assertRaises(m.Blocked):m.regular_bytes(root/'link',10)
   os.mkfifo(root/'fifo')
   with self.assertRaises(m.Blocked):m.regular_bytes(root/'fifo',10)
   q=root/'big';q.write_bytes(b'12')
   with self.assertRaises(m.Blocked):m.regular_bytes(q,1)
 def test_depth_and_nonfinite_json(self):
  with tempfile.TemporaryDirectory() as td:
   p=Path(td)/'x'
   for text in ['{"x":NaN}','{"x":'+('[ '*110)+'0'+(' ]'*110)+'}']:
    p.write_text(text)
    with self.assertRaises(m.Blocked):m.strict_json(p)
 def test_unknown_and_nonboolean_binding(self):
  for b in [{'schema_version':'deployment-binding.v1','enabled':'false'},{'schema_version':'deployment-binding.v1','enabled':False,'command':'sh'}]:
   with self.assertRaises(m.Blocked):m.validate_binding(b)

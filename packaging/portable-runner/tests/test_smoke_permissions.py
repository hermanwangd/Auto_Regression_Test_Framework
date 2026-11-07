import importlib.util,os,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
I=Path(__file__).resolve().parents[1]
class PermissionTest(unittest.TestCase):
 def setUp(self):
  sys.path.insert(0,str(I));self.addCleanup(lambda:sys.path.remove(str(I)))
  import smoke,package_support
  self.smoke=smoke;self.support=package_support;self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.engine=smoke.LocalImageSmokes('sha256:'+'a'*64,'publicnonce',self.root,None,{},self.root)
 def test_writer_uses_measured_nonroot_security_flags(self):
  args=self.support.security_args('1000:1000');self.assertIn('1000:1000',args);self.assertIn('--read-only',args);self.assertIn('ALL_PROXY=',args);self.assertIn('none',args)
 def test_root_identity_rejected_before_action(self):
  for user in ['0:0','0:1000','1000:0']:
   with self.subTest(user=user),self.assertRaises(ValueError):self.support.security_args(user)
 def test_readonly_cases_keep_default_image_user(self):self.assertEqual(self.engine.case_user('java-start'),'65532:65532');self.assertEqual(self.engine.case_user('timeout-retention'),'65532:65532')
 def test_writer_output_private_owned_without_chown(self):
  with patch.object(self.smoke.os,'chown',side_effect=AssertionError('must_not_chown')):p=self.engine.output('cd-blocked')
  self.assertEqual(p.stat().st_uid,os.getuid());self.assertEqual(p.stat().st_mode&0o777,0o700);self.assertEqual(self.engine.case_user('cd-blocked'),str(os.getuid())+':'+str(os.getgid()))
 def test_default_readonly_output_mode(self):
  with patch.object(self.smoke.os,'chown',side_effect=AssertionError('must_not_chown')):p=self.engine.output('java-start')
  self.assertEqual(p.stat().st_mode&0o777,0o755)
 def test_unverified_readback_does_not_invoke_remove(self):
  class NoDocker:
   def run(self,*args,**kwargs):raise AssertionError('Docker action forbidden before readback')
  self.engine.commands=NoDocker()
  with self.assertRaises(ValueError):self.engine.remove({'id':'public','name':'public'},{'verified':False})
if __name__=='__main__':unittest.main()

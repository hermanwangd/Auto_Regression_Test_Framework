import hashlib,importlib.util,json,tempfile,unittest,zipfile
from pathlib import Path
I=Path(__file__).resolve().parents[1]
class ContextTest(unittest.TestCase):
 def setUp(self):
  p=I/'prepare_context.py';self.assertTrue(p.is_file(),'allowlisted accepted-producer context behavior absent');spec=importlib.util.spec_from_file_location('context',p);self.mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.mod)
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name);self.jar=self.root/'accepted.jar';self.wrapper=self.root/'wrapper.py';self.wrapper.write_text('print("PUBLIC")\n');self.out=self.root/'context'
  with zipfile.ZipFile(self.jar,'w') as z:z.writestr('BOOT-INF/classes/com/specdriven/regression/runner/RunnerCommand.class',b'PUBLIC_SYNTHETIC_CLASS')
  self.pin=hashlib.sha256(self.jar.read_bytes()).hexdigest();self.bases={'python':'docker.io/library/python@sha256:'+'a'*64,'jre':'docker.io/library/eclipse-temurin@sha256:'+'b'*64}
 def prepare(self,pin=None):return self.mod.prepare(self.jar,pin or self.pin,self.wrapper,self.bases,self.out,{'classification':'PUBLIC_SYNTHETIC_UNIT'})
 def test_allowlist_and_context_canaries_excluded(self):
  for name in ['case.yaml','config.json','data.csv','secret.env','PK.json','Docker.sock','proxy-ca.pem','cache.bin']:(self.root/name).write_text('EXCLUDED_PUBLIC_CANARY')
  self.prepare();self.assertEqual(set(p.name for p in self.out.iterdir()),{'Dockerfile','.dockerignore','entrypoint.sh','framework.jar','run-sit-postdeploy.py','runtime-source-manifest.json'})
  text=(self.out/'Dockerfile').read_text();self.assertNotIn('COPY .',text);self.assertIn('USER 65532:65532',text);self.assertIn('COPY --from=jre /opt/java/openjdk',text)
  self.assertNotIn('EXCLUDED_PUBLIC_CANARY',b''.join(p.read_bytes() for p in self.out.iterdir()).decode(errors='ignore'));self.assertEqual(hashlib.sha256((self.out/'framework.jar').read_bytes()).hexdigest(),self.pin)
 def test_wrong_accepted_jar_pin_blocks(self):
  with self.assertRaises((ValueError,OSError)):self.prepare('0'*64)
  self.assertFalse(self.out.exists())
 def test_floating_base_rejected(self):
  self.bases['python']='python:latest'
  with self.assertRaises((ValueError,OSError)):self.prepare()
 def test_missing_d2_class_blocks(self):
  self.jar.write_bytes(b'not executable corrected jar');self.pin=hashlib.sha256(self.jar.read_bytes()).hexdigest()
  with self.assertRaises((ValueError,OSError,zipfile.BadZipFile)):self.prepare()
 def test_symlink_source_refused(self):
  link=self.root/'link.jar';link.symlink_to(self.jar);self.jar=link
  with self.assertRaises((ValueError,OSError)):self.prepare()
 def test_existing_context_not_overwritten(self):
  self.out.mkdir();(self.out/'owner').write_text('old')
  with self.assertRaises((ValueError,OSError)):self.prepare()
  self.assertEqual((self.out/'owner').read_text(),'old')
if __name__=='__main__':unittest.main()

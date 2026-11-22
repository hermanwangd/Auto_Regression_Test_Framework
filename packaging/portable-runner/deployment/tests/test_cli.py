import json,os,subprocess,sys,tempfile,unittest
from pathlib import Path
from common import D,m
class CommandAdmissionTest(unittest.TestCase):
 def test_unbound_workflow_is_structured_without_traceback(self):
  with tempfile.TemporaryDirectory() as td:
   path=Path(td)/'binding.json';path.write_text('{"schema_version":"deployment-composition.v1","enabled":false}')
   result=subprocess.run([sys.executable,'-B',str(D/'scripts/deployment.py'),'execute','--binding',str(path),'--expected-sha256',m.digest(path.read_bytes())],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=5,env={'LANG':'C.UTF-8','LC_ALL':'C.UTF-8','PYTHONDONTWRITEBYTECODE':'1'})
   self.assertEqual(result.returncode,2,result.stderr.decode());self.assertEqual(json.loads(result.stdout)['status'],'BLOCKED');self.assertNotIn(b'Traceback',result.stderr)

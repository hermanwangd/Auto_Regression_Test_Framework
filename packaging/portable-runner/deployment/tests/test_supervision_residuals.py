"""DEP-COMP-1/2 direct behavior, status doubles are not product/collector evidence."""
import io,json,os,sys,tempfile,unittest
from pathlib import Path
from unittest.mock import patch
from common import D,m,module,dump
if os.environ.get('DEPLOYMENT_RESIDUAL_BASELINE')=='preserved43':
 import importlib.util
 def preserved(name):
  spec=importlib.util.spec_from_file_location(name,D/'reports/composition43-preserved/scripts'/f'{name}.py');value=importlib.util.module_from_spec(spec);sys.modules[name]=value;spec.loader.exec_module(value);return value
 m=preserved('deployment');s=preserved('supervision');c=preserved('composition')
else:s=module('supervision');c=module('composition')

def outcome(reason='exited',code=0,proven=True,errors=None):
 return {'exit_code':code,'reason':reason,'stop_verified':proven,'errors':errors or [],'unknown_children':[],'identities':[]}
class ObservationTest(unittest.TestCase):
 def test_identity_permission_error_is_unproven_not_absent(self):
  actual=Path.read_text;pid=os.getpid()
  def read(path,*a,**kw):
   if path==Path('/proc')/str(pid)/'stat':raise PermissionError('public injected identity denial')
   return actual(path,*a,**kw)
  with patch.object(Path,'read_text',read),self.assertRaises(s.StopFailure):s._identity(pid)
 def test_stable_task_missing_children_is_unproven(self):
  pid=os.getpid();identity=s._identity(pid);actual=Path.read_text
  def read(path,*a,**kw):
   if path==Path('/proc')/str(pid)/'task'/str(pid)/'children':raise FileNotFoundError('public missing interface')
   return actual(path,*a,**kw)
  with patch.object(Path,'read_text',read),self.assertRaises(s.StopFailure):s._children(pid,identity)
 def test_stable_process_missing_task_interface_is_unproven(self):
  pid=os.getpid();identity=s._identity(pid);actual=Path.iterdir
  def iterate(path):
   if path==Path('/proc')/str(pid)/'task':raise FileNotFoundError('public missing task interface')
   return actual(path)
  with patch.object(Path,'iterdir',iterate),self.assertRaises(s.StopFailure):s._children(pid,identity)
 def test_legitimate_raced_away_thread_is_allowed(self):
  pid=os.getpid();identity=s._identity(pid);actual=Path.iterdir
  def iterate(path):
   if path==Path('/proc')/str(pid)/'task':return iter([Path('/proc')/str(pid)/'task'/'2147483647'])
   return actual(path)
  with patch.object(Path,'iterdir',iterate):self.assertEqual(s._children(pid,identity),set())
 def test_confirmed_gone_and_reused_registered_pid_do_not_scan(self):
  ident=(os.getpid(),1,os.getuid(),'R')
  with patch.object(s,'_identity',return_value=None):self.assertEqual(s._children(os.getpid(),ident),set())
  with patch.object(s,'_identity',return_value=(0,2,os.getuid(),'R')):
   try:self.assertEqual(s._children(os.getpid(),ident),set())
   except s.StopFailure as exc:self.fail('confirmed reused PID should not be observed as current: '+str(exc))
 def test_missing_child_interface_after_launch_collects_without_stop_proof(self):
  # Explicit unit observation model for the supervisor's own child list; the real
  # managed kernel may lack children files. No model result is runtime evidence.
  parent=os.getpid();actual_read=Path.read_text;actual_children=s._children;actual_launch=s.subprocess.Popen;launched=[];attempts=[]
  def launch(*a,**kw):
   process=actual_launch(*a,**kw);launched.append(process.pid);return process
  def children(pid,identity):
   if pid==parent:return set(launched)
   return actual_children(pid,identity)
  def read(path,*a,**kw):
   if path.name=='children' and len(path.parts)>2 and path.parts[2]!=str(parent):raise FileNotFoundError('public missing stable child interface')
   return actual_read(path,*a,**kw)
  with patch.object(s.subprocess,'Popen',launch),patch.object(s,'_children',children),patch.object(Path,'read_text',read):
   r=s._run_owned([sys.executable,'-B',str(D/'fixtures/public/child.py'),'timeout'],0.08,lambda:attempts.append(True))
  self.assertEqual(attempts,[True]);self.assertFalse(r['stop_verified']);self.assertFalse(r['cleanup_eligible']);self.assertTrue(r['reaped'])
 def test_identity_read_error_after_launch_retains_failure_and_collects(self):
  parent=os.getpid();actual_read=Path.read_text;actual_children=s._children;actual_launch=s.subprocess.Popen;launched=[];reads=[0];attempts=[]
  def launch(*a,**kw):
   process=actual_launch(*a,**kw);launched.append(process.pid);return process
  def children(pid,identity):
   if pid==parent:return set(launched)
   return actual_children(pid,identity)
  def read(path,*a,**kw):
   if path.name=='stat' and len(path.parts)>2 and path.parts[2] in set(map(str,launched)):
    reads[0]+=1
    if reads[0]==2:raise PermissionError('public injected registered identity denial')
   return actual_read(path,*a,**kw)
  with patch.object(s.subprocess,'Popen',launch),patch.object(s,'_children',children),patch.object(Path,'read_text',read):
   result=s._run_owned([sys.executable,'-B',str(D/'fixtures/public/child.py'),'timeout'],0.08,lambda:attempts.append(True))
  self.assertEqual(attempts,[True]);self.assertFalse(result['stop_verified']);self.assertFalse(result['cleanup_eligible']);self.assertTrue(result['reaped'])
 def test_before_launch_failure_restores_subreaper(self):
  import ctypes
  libc=ctypes.CDLL(None);before=ctypes.c_int();libc.prctl(37,__import__('ctypes').byref(before),0,0,0)
  with patch.object(s,'_children',side_effect=s.StopFailure('public baseline unobservable')),self.assertRaises(s.StopFailure):s._run_owned([sys.executable,'-B',str(D/'fixtures/public/child.py'),'success'],0.1,lambda:None)
  after=ctypes.c_int();libc.prctl(37,__import__('ctypes').byref(after),0,0,0)
  try:self.assertEqual(after.value,before.value)
  finally:libc.prctl(36,before.value,0,0,0)
class OutcomeTest(unittest.TestCase):
 def execute_code(self,stop):
  buffer=io.StringIO()
  with patch.object(sys,'argv',['deployment.py','execute','--binding','unused','--expected-sha256','0'*64]),patch.object(c,'load',return_value={}),patch.object(c,'execute',return_value={'stop':stop}),patch.object(sys,'stdout',buffer):code=m.main()
  self.assertEqual(json.loads(buffer.getvalue())['stop']['exit_code'],stop['exit_code']);return code
 def test_CLI_eventual_zero_does_not_mask_guard_or_uncertain_stop(self):
  for stop in [outcome('timeout'),outcome('log_overflow'),outcome(proven=False),outcome(errors=['monitor_unobservable'])]:
   with self.subTest(stop=stop):self.assertEqual(self.execute_code(stop),1)
 def test_CLI_ordinary_zero_and_exit7_preserved(self):
  self.assertEqual(self.execute_code(outcome()),0);self.assertEqual(self.execute_code(outcome(code=7)),1)
 def test_collector_guard_eventual_zero_is_incomplete(self):
  with tempfile.TemporaryDirectory() as td:
   root=Path(td);root.chmod(0o700);source=root/'source';source.mkdir(mode=0o700);saved=root/'saved';saved.mkdir(mode=0o700);dump(saved/'collection.json',{'framework_verdict':'UNKNOWN'})
   w={'run_id':'unit','correlation_id':'corr','tools':{'java':{'path':'/unit/java'},'framework_jar':{'path':'/unit/framework.jar'}},'collection_spec':{'path':'/unit/spec','sha256':'0'*64}}
   for i,stop in enumerate([outcome('timeout'),outcome('log_overflow'),outcome(proven=False),outcome(errors=['monitor_unobservable'])]):
    with self.subTest(stop=stop),patch.object(c,'spec_document',return_value={}),patch.object(c,'_invoke',return_value=(stop,b'PUBLIC_UNIT_STATUS_DOUBLE')),patch.object(c,'verify_saved',return_value=[]):
     result=c.invoke_collector(w,source,saved,root/f'call-{i}.json');self.assertEqual(result['status'],'INCOMPLETE');self.assertEqual(result['exit_code'],0)

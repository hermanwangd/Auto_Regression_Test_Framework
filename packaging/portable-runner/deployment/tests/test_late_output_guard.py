"""Pure buffering/status observation model: no PID/proc/child/JVM/kernel proof."""
import os,unittest
from types import SimpleNamespace
from unittest.mock import Mock,patch
from common import module
s=module('supervision')
class LateOutputGuardTest(unittest.TestCase):
 def model(self,tail):
  state={'live':False,'polls':0,'selects':0,'clock':0.0};attempts=[];child=2147483646;me=os.getpid();uid=os.getuid();chunks=[b'x'*65536]*16+([b'x'] if tail else [])
  class Process:
   pid=child;stdout=Mock();stdout.fileno.return_value=99;returncode=None
   def poll(self):
    state['polls']+=1
    if state['polls']>=16:self.returncode=0;state['live']=False
    return self.returncode
  process=Process()
  class Selector:
   def register(self,*a):pass
   def unregister(self,*a):pass
   def close(self):pass
   def select(self,wait):
    state['selects']+=1
    return [(SimpleNamespace(fileobj=process.stdout),None)] if state['selects']<=len(chunks) else []
  class Lib:
   def prctl(self,op,arg,*rest):
    if op==37:arg._obj.value=0
    return 0
  def launched(*a,**kw):state['live']=True;return process
  def identity(pid):
   if pid==me:return (0,1,uid,'R')
   return (me,2,uid,'R') if state['live'] else None
  def children(pid,known):return {child} if pid==me and state['live'] else set()
  def now():state['clock']+=.001;return state['clock']
  reads=iter(chunks)
  with patch.object(s.ctypes,'CDLL',return_value=Lib()),patch.object(s,'_identity',identity),patch.object(s,'_children',children),patch.object(s.selectors,'DefaultSelector',Selector),patch.object(s.subprocess,'Popen',launched),patch.object(s.os,'set_blocking'),patch.object(s.os,'read',side_effect=lambda *a:next(reads)),patch.object(s.os,'kill',side_effect=AssertionError('no model PID may be signaled')),patch.object(s.time,'monotonic',now),patch.object(s.time,'sleep'):
   result=s._run_owned(['/unit/fixed-only-command'],1,lambda:attempts.append(True))
  self.assertEqual(result['exit_code'],0);self.assertEqual(attempts,[True]);self.assertEqual(result['captured_bytes'],1048576);return result
 def test_cleanup_tail_overflow_remains_command_failure_with_raw_zero(self):
  result=self.model(True);self.assertEqual(result['reason'],'log_overflow');self.assertFalse(s.successful(result));self.assertFalse(result['cleanup_eligible'])
 def test_exact_cap_without_extra_tail_is_ordinary_success(self):
  result=self.model(False);self.assertEqual(result['reason'],'exited');self.assertTrue(s.successful(result))

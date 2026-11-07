import sys,unittest
from common import D,module
s=module('supervision')
class LifecycleTest(unittest.TestCase):
 def setUp(self):
  import os
  try:s._children(os.getpid(),s._identity(os.getpid()))
  except s.StopFailure:self.skipTest('owned children interface unavailable; no runtime stop proof')
 def runmode(self,mode,callback=None):
  attempts=[]
  def evidence():
   attempts.append('available')
   if callback:callback()
  result=s._run_owned([sys.executable,'-B',str(D/'fixtures/public/child.py'),mode],0.3,evidence)
  self.assertEqual(attempts,['available']);self.assertFalse(result['cleanup_eligible']);return result
 def test_success_stays_exit_zero(self):self.assertEqual(self.runmode('success')['exit_code'],0)
 def test_failure_preserved(self):self.assertEqual(self.runmode('failure')['exit_code'],7)
 def test_timeout_reaps_owner(self):
  r=self.runmode('timeout');self.assertEqual(r['reason'],'timeout');self.assertTrue(r['reaped'])
 def test_nested_new_session_reaped(self):self.assertTrue(self.runmode('nested')['reaped'])
 def test_log_cap_and_reap(self):
  r=self.runmode('overflow');self.assertEqual(r['reason'],'log_overflow');self.assertEqual(r['captured_bytes'],1048576);self.assertTrue(r['reaped'])
 def test_collection_failure_prevents_cleanup(self):
  def fail():raise RuntimeError('public fixture')
  r=self.runmode('failure',fail);self.assertEqual(r['exit_code'],7);self.assertIn('evidence_attempt_failed',r['errors'])
 def test_stop_failure_still_collects_and_reaps(self):
  from unittest.mock import patch
  import os,signal
  original=os.kill
  def deny_term(pid,sig):
   if sig==signal.SIGTERM:raise PermissionError('public synthetic signal failure')
   return original(pid,sig)
  with patch.object(s.os,'kill',deny_term):r=self.runmode('timeout')
  self.assertIn('owned_process_signal_failed',r['errors']);self.assertTrue(r['reaped']);self.assertFalse(r['cleanup_eligible'])
 def test_registered_processes_only_and_primary_status_not_reaped(self):
  from unittest.mock import patch
  from pathlib import Path
  import os
  original=Path.iterdir;reaper=os.waitpid;waited=[]
  def exact(path):
   if str(path)=='/proc':raise AssertionError('broad process scan forbidden')
   return original(path)
  def track(pid,flags):waited.append(pid);return reaper(pid,flags)
  with patch.object(Path,'iterdir',exact),patch.object(os,'waitpid',track):r=self.runmode('failure')
  self.assertEqual(r['exit_code'],7)
  self.assertNotIn(r['identities'][0]['pid'],waited)
 def test_unknown_adopted_child_is_not_signaled(self):
  from unittest.mock import patch
  import os
  actual=s._children;calls=[0];sent=[];original_kill=os.kill;unknown=2147483647
  def children(pid,identity):
   result=actual(pid,identity)
   if pid==os.getpid():
    calls[0]+=1
    if calls[0]>1:return result|{unknown}
   return result
  def signal(pid,sig):sent.append(pid);return original_kill(pid,sig)
  with patch.object(s,'_children',children),patch.object(os,'kill',signal):r=self.runmode('failure')
  self.assertIn('unknown_adopted_child_STOP_UNPROVEN',r['errors']);self.assertFalse(r['stop_verified']);self.assertNotIn(unknown,sent)

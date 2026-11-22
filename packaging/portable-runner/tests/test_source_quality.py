"""Offline fault injection for the finite source-publication repair; no Docker/network."""
import io,json,os,signal,subprocess,sys,tempfile,threading,time,unittest
from pathlib import Path
from unittest.mock import Mock,patch
I=Path(__file__).resolve().parents[1]
class SourceQualityTest(unittest.TestCase):
 def setUp(self):
  sys.path.insert(0,str(I));self.addCleanup(lambda:sys.path.remove(str(I)))
  import package_support,smoke,resolve_bases,package_image
  self.support=package_support;self.smoke=smoke;self.resolver=resolve_bases;self.pkg=package_image
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name);self.children=[];self.real_popen=subprocess.Popen;self.real_kill=os.killpg
 def tearDown(self):
  for child in self.children:
   if child.poll() is None:
    try:self.real_kill(child.pid,signal.SIGKILL)
    except ProcessLookupError:pass
   child.wait(timeout=3)
 def spawn(self,code):
  def factory(args,**kwargs):
   child=self.real_popen([sys.executable,'-c',code],**kwargs);child.wait=Mock(wraps=child.wait);self.children.append(child);return child
  return factory
 def engine(self,*,running=False,log_exit=0,log_guard=None,stop_exit=0):
  root=self.root/'smoke';root.mkdir();(root/'evidence').mkdir();identity='IMAGE_PACKAGE_IDENTITY {"uid":65532,"gid":65532}\n'
  def command(label,args,**kwargs):
   out=root/(label+'.out');err=root/(label+'.err');out.write_text('a'*64 if label.endswith('-create') else 'PUBLIC_AVAILABLE\n');err.write_text(identity if label.endswith('-logs') else '')
   return {'exit_code':-9 if label.endswith('-wait') else stop_exit if label.endswith('-stop') else log_exit if label.endswith('-logs') else 0,'guard':'command_timeout' if label.endswith('-wait') else log_guard if label.endswith('-logs') else None,'stdout':{'path':str(out),'sha256':self.support.sha(out),'bytes':out.stat().st_size},'stderr':{'path':str(err),'sha256':self.support.sha(err),'bytes':err.stat().st_size}}
  cmd=Mock();cmd.run.side_effect=command;e=self.smoke.LocalImageSmokes('sha256:'+'a'*64,'publicnonce',root,cmd,{'framework_jar':{'sha256':'b'*64},'accepted_source_commit':'c'*40},self.root);e.owned=Mock(return_value={'running':running,'exit':None if running else 137});e.collect=Mock(return_value={'verified':True,'complete':True,'framework_verdict':'UNKNOWN'});e.remove=Mock();return e
 def assert_partial_snapshot(self,e):
  status=self.support.read(e.root/'evidence/timeout-retention-runtime-status.json');self.assertEqual(status.get('capture_state'),'PARTIAL');self.assertIn('CAPTURE',[x['operation'] for x in e.events]);self.assertEqual(e.collect.call_count,1);e.remove.assert_not_called();self.assertFalse(e.resources[0]['removed'])
 def test_failed_stop_still_captures_and_attempts_snapshot(self):
  e=self.engine(running=True,stop_exit=1)
  try:
   r,s=e.execute('timeout-retention',['python3','-c','PUBLIC'])
   e.diagnostic_collection(r,s)
  except ValueError:pass
  self.assert_partial_snapshot(e)
 def test_nonzero_log_fetch_never_authorizes_original_remove(self):
  e=self.engine(log_exit=1)
  try:
   r,s=e.execute('timeout-retention',['python3','-c','PUBLIC']);e.diagnostic_collection(r,s)
  except ValueError:pass
  self.assert_partial_snapshot(e)
 def test_guarded_log_fetch_never_authorizes_original_remove(self):
  e=self.engine(log_guard='command_log_limit')
  try:
   r,s=e.execute('timeout-retention',['python3','-c','PUBLIC']);e.diagnostic_collection(r,s)
  except ValueError:pass
  self.assert_partial_snapshot(e)
 def test_direct_log_retention_cannot_borrow_partial_capture_hashes(self):
  e=self.engine();out=e.root/'public.out';out.write_text('PUBLIC');record={'stdout':{'path':str(out),'sha256':self.support.sha(out)},'stderr':{'path':str(out),'sha256':self.support.sha(out)},'capture_state':'PARTIAL','capture_complete':False,'workload_running':False};e.diagnostic_collection=Mock()
  try:e.retain_logs({'name':'public','id':'a'*64,'capture_complete':False},record)
  except ValueError:pass
  e.remove.assert_not_called()
 def command_fault(self,error):
  c=self.support.Commands(self.root/'commands',self.root,os.statvfs(self.root).f_bavail*os.statvfs(self.root).f_frsize)
  with patch.object(self.support.subprocess,'Popen',side_effect=self.spawn('import time; time.sleep(30)')),patch.object(c,'guard',side_effect=[1,error]):
   with self.assertRaises(type(error)):c.run('fault',['PUBLIC'],docker=False)
  self.assertGreater(self.children[0].wait.call_count,0);self.assertIsNotNone(self.children[0].returncode)
 def test_monitor_oserror_reaps_owned_child(self):self.command_fault(OSError('PUBLIC_MONITOR_FAILURE'))
 def test_keyboard_interrupt_reaps_owned_child(self):self.command_fault(KeyboardInterrupt())
 def test_exit_between_poll_kill_still_waits(self):
  c=self.support.Commands(self.root/'commands',self.root,os.statvfs(self.root).f_bavail*os.statvfs(self.root).f_frsize)
  def raced(pid,sig):
   self.real_kill(pid,sig);raise ProcessLookupError('PUBLIC_EXIT_RACE')
  with patch.object(self.support.subprocess,'Popen',side_effect=self.spawn('import time; time.sleep(30)')),patch.object(c,'guard',side_effect=[1,ValueError('PUBLIC_LIMIT')]),patch.object(self.support.os,'killpg',side_effect=raced):
   try:c.run('race',['PUBLIC'],docker=False,allowed=None)
   except ProcessLookupError:pass
  self.assertGreater(self.children[0].wait.call_count,0)
 def test_kill_failure_still_attempts_bounded_wait_without_masking_original(self):
  child=Mock();child.pid=12345;child.poll.return_value=None;original=OSError('PUBLIC_ORIGINAL')
  with patch.object(self.support.os,'killpg',side_effect=PermissionError('PUBLIC_KILL_DENIED')):self.support.reap_owned(child,original)
  child.wait.assert_called_once_with(timeout=3);self.assertTrue(any('PermissionError' in n for n in original.__notes__))
 def test_normal_and_expected_nonzero_commands_retain_records(self):
  c=self.support.Commands(self.root/'commands',self.root,os.statvfs(self.root).f_bavail*os.statvfs(self.root).f_frsize)
  for code in [0,17]:
   r=c.run('exit-'+str(code),[sys.executable,'-c','print("PUBLIC"); raise SystemExit('+str(code)+')'],docker=False,allowed=(0,17));self.assertEqual(r['exit_code'],code);self.assertTrue((self.root/'commands'/('exit-'+str(code)+'-command.json')).exists())
 def test_fresh_metadata_directory_created_before_spawn(self):
  root=self.root/'fresh';root.mkdir()
  with patch.object(self.resolver,'I',root),patch.object(self.resolver.subprocess,'Popen',side_effect=self.spawn('print("{\\"schemaVersion\\":2}")')):
   self.assertEqual(self.resolver.run('public','PUBLIC')['schemaVersion'],2)
  self.assertTrue((root/'evidence/public-command.json').exists())
 def test_resolver_interrupt_reaps_owned_child(self):
  root=self.root/'resolver';(root/'evidence').mkdir(parents=True)
  with patch.object(self.resolver,'I',root),patch.object(self.resolver.subprocess,'Popen',side_effect=self.spawn('import time; time.sleep(30)')),patch.object(self.resolver.time,'sleep',side_effect=KeyboardInterrupt()):
   with self.assertRaises(KeyboardInterrupt):self.resolver.run('public','PUBLIC')
  self.assertGreater(self.children[0].wait.call_count,0)
 def test_resolver_does_not_overwrite_existing_stderr(self):
  root=self.root/'resolver';(root/'evidence').mkdir(parents=True);target=root/'evidence/public.stderr.log';target.write_text('PUBLIC_RETAINED')
  with patch.object(self.resolver,'I',root),patch.object(self.resolver.subprocess,'Popen',side_effect=self.spawn('print("{}")')):
   with self.assertRaises((ValueError,AssertionError,FileExistsError)):self.resolver.run('public','PUBLIC')
  self.assertEqual(target.read_text(),'PUBLIC_RETAINED')
 def test_resolver_existing_basepins_still_protected(self):
  root=self.root/'resolver';root.mkdir();(root/'base-pins.json').write_text('PUBLIC_FROZEN')
  with patch.object(self.resolver,'I',root),patch.object(self.resolver.subprocess,'Popen') as spawn:
   with self.assertRaises((ValueError,AssertionError,FileExistsError)):self.resolver.main()
  spawn.assert_not_called()
 def main_export(self,kind):
  root=self.root/'package';root.mkdir();fixture=root/'fixture';fixture.mkdir();(fixture/'valid_result.json').write_text('{}');jar=root/'tool.jar';jar.write_text('PUBLIC');wrapper=root/'wrapper.py';wrapper.write_text('PUBLIC');accepted=root/'producer.json';accepted.write_text('{}');lock=root/'lock'
  producer={'accepted_source_commit':'a'*40,'accepted_source_tree':'b'*40,'framework_jar':{'path':str(jar),'sha256':'c'*64},'wrapper':{'path':str(wrapper),'sha256':'d'*64,'source_commit':'a'*40},'acceptance_review':{'sha256':'e'*64},'runtime_schema':{'sha256':'f'*64},'producer_observation':{'sha256':'0'*64}}
  bases={'compressed_total_bytes':0,'platform':'linux/amd64','bases':[{'name':n,'pinned_ref':'PUBLIC','config_sha256':'sha256:'+n} for n in ['python','jre']]};(root/'base-pins.json').write_text(json.dumps(bases));(root/'smoke-literals.json').write_text('{}');clock=[0.];published=[];forced=[];timers=[];image='sha256:'+'a'*64
  def prepare(*args):
   p=root/'context';p.mkdir();(p/'runtime-source-manifest.json').write_text('{}')
  class FakeCommands:
   def __init__(inner,*args):inner.records=[]
   def guard(inner):return 20*1024**3
   def run(inner,label,args,**kwargs):
    f=root/(label+'.json');data={'id':'sha256:'+label.removeprefix('inspect-'),'size':1} if label.startswith('inspect-') else {'id':image,'user':'65532:65532','entrypoint':['/opt/tool/entrypoint.sh'],'layers':[]};f.write_text(json.dumps(data));return {'exit_code':1 if label=='unique-tag-check' else 0,'guard':None,'stdout':{'path':str(f)}}
  class Finished:
   pid=987654;returncode=0;stdout=io.BytesIO(b'')
   def poll(inner):return 0
   def wait(inner,**kwargs):return 0
  result={}
  def convert(stream,path,*args,**kwargs):
   if kind=='stderr':stream.read(1)
   if kind=='killrace':raise OSError('PUBLIC_CONVERT_FAILURE')
   Path(path).write_bytes(b'PUBLIC_ARCHIVE');result.update(archive_sha256=self.support.sha(path),format='PUBLIC_SYNTHETIC');return dict(result)
  def verify(*args,**kwargs):
   if kind=='readback':clock[0]=181.
   if kind=='readback_alarm':os.kill(os.getpid(),signal.SIGALRM)
   return dict(result)
  def publish(source,dest,*args):
   os.link(source,dest);published.append(Path(dest))
   if kind=='publication':clock[0]=181.
  def spawn(args,**kwargs):
   if kind in ['stderr','killrace','constructor']:
    code='import sys,time; sys.stderr.write("X"*1048577);sys.stderr.flush();time.sleep(30)' if kind=='stderr' else 'import time; time.sleep(30)';p=self.spawn(code)(args,**kwargs)
    if kind=='stderr':
     def emergency():
      if p.poll() is None:forced.append(True);self.real_kill(p.pid,signal.SIGKILL)
     timer=threading.Timer(.8,emergency);timers.append(timer);timer.start()
    return p
   return Finished()
  def raced(pid,sig):self.real_kill(pid,sig);raise ProcessLookupError('PUBLIC_EXIT_RACE')
  engine=Mock();engine.resources=[];engine.events=[];engine.run.return_value={'status':'PUBLIC_SYNTHETIC'}
  argv=['package_image','--accepted-producer',str(accepted),'--expected-producer-sha256','a'*64,'--fixture-root',str(fixture),'--lock',str(lock)]
  patches=[patch.object(self.pkg,'I',root),patch.object(self.support,'validate_producer',return_value=producer),patch.object(self.pkg,'Commands',FakeCommands),patch.object(self.pkg,'prepare',side_effect=prepare),patch.object(self.pkg,'LocalImageSmokes',return_value=engine),patch.object(self.pkg,'convert',side_effect=convert),patch.object(self.pkg,'verify_archive',side_effect=verify),patch.object(self.pkg,'publish_archive',side_effect=publish),patch.object(self.pkg.subprocess,'Popen',side_effect=spawn),patch.object(sys,'argv',argv),patch.object(self.pkg.time,'monotonic',side_effect=lambda:clock[0]),patch.object(self.pkg.os,'statvfs',return_value=Mock(f_bavail=20*1024**3,f_frsize=1))]
  if kind=='constructor':patches.append(patch.object(self.pkg.threading,'Thread',side_effect=RuntimeError('PUBLIC_THREAD_CONSTRUCTOR_FAILURE')))
  if kind=='killrace':patches.append(patch.object(self.pkg.os,'killpg',side_effect=raced))
  from contextlib import ExitStack
  error=None
  try:
   with ExitStack() as stack:
    for item in patches:stack.enter_context(item)
    try:self.pkg.main()
    except BaseException as caught:error=caught
  finally:
   for timer in timers:timer.cancel()
  return root,error,published,forced
 def test_thread_constructor_failure_reaps_owned_child_before_propagation(self):
  root,error,published,_=self.main_export('constructor');self.assertIsInstance(error,RuntimeError);self.assertIn('PUBLIC_THREAD_CONSTRUCTOR_FAILURE',str(error));self.assertGreater(self.children[0].wait.call_count,0);self.assertIsNotNone(self.children[0].returncode);self.assertFalse(list((root/'exports').glob('*.oci.tar')))
  manifests=list((root/'evidence').glob('actual-*/image-package-manifest.json'));self.assertEqual(len(manifests),1);self.assertEqual(self.support.read(manifests[0])['status'],'LOCAL_IMAGE_PACKAGE_INCOMPLETE')
 def test_normal_export_keeps_verified_publication(self):
  root,error,published,_=self.main_export('normal');self.assertIsNone(error);self.assertEqual(len(published),1);self.assertTrue(published[0].is_file())
 def test_readback_blocking_region_keeps_actual_alarm_handler(self):
  root,error,published,_=self.main_export('readback_alarm');self.assertIsInstance(error,TimeoutError);self.assertFalse(list((root/'exports').glob('*.oci.tar')))
 def test_completed_capture_retains_normal_snapshot_cleanup(self):
  e=self.engine();r,s=e.execute('timeout-retention',['python3','-c','PUBLIC']);self.assertTrue(s['capture_complete']);self.assertEqual(s['capture_state'],'COMPLETE');e.diagnostic_collection(r,s);e.remove.assert_called_once();self.assertEqual(e.collect.call_count,1)
 def test_running_resource_cannot_be_removed_with_saved_snapshot(self):
  e=self.engine(running=True);del e.remove;e.commands.run.reset_mock()
  with self.assertRaises(ValueError):e.remove({'id':'a'*64,'name':'public','capture_complete':True},{'verified':True})
  e.commands.run.assert_not_called()
 def test_snapshot_collection_failure_preserves_original_resource(self):
  e=self.engine(log_exit=1);e.collect.side_effect=OSError('PUBLIC_COLLECTION_FAILURE')
  with self.assertRaisesRegex(ValueError,'incomplete_capture'):e.execute('timeout-retention',['python3','-c','PUBLIC'])
  e.remove.assert_not_called();self.assertTrue(any(x['operation']=='AVAILABLE_SNAPSHOT_FAILED' for x in e.events))
 def test_final_command_log_cap_is_checked_after_fast_exit(self):
  c=self.support.Commands(self.root/'commands',self.root,os.statvfs(self.root).f_bavail*os.statvfs(self.root).f_frsize)
  r=c.run('fast',[sys.executable,'-c','print("X"*10000)'],docker=False,allowed=None,cap=100);self.assertEqual(r['guard'],'command_log_limit')
 def test_record_failure_occurs_after_owned_child_reap(self):
  c=self.support.Commands(self.root/'commands',self.root,os.statvfs(self.root).f_bavail*os.statvfs(self.root).f_frsize)
  with patch.object(self.support.subprocess,'Popen',side_effect=self.spawn('print("PUBLIC")')),patch.object(self.support,'save',side_effect=OSError('PUBLIC_RECORD_FAILURE')):
   with self.assertRaisesRegex(OSError,'PUBLIC_RECORD_FAILURE'):c.run('record',['PUBLIC'],docker=False)
  self.assertGreater(self.children[0].wait.call_count,0);self.assertIsNotNone(self.children[0].returncode)
 def test_whole_deadline_includes_independent_readback(self):
  root,error,published,_=self.main_export('readback');self.assertIsInstance(error,TimeoutError);self.assertFalse(list((root/'exports').glob('*.oci.tar')))
 def test_whole_deadline_includes_publication(self):
  root,error,published,_=self.main_export('publication');self.assertIsInstance(error,TimeoutError);self.assertFalse(list((root/'exports').glob('*.oci.tar')))
 def test_live_stderr_limit_interrupts_stalled_stdout(self):
  root,error,published,forced=self.main_export('stderr');self.assertIsNotNone(error);self.assertFalse(forced,'only emergency fixture timer stopped the old unbounded stderr producer');self.assertGreater(self.children[0].wait.call_count,0);self.assertFalse(list((root/'exports').glob('*.oci.tar')))
 def test_export_exit_race_reaps_and_preserves_original_failure(self):
  root,error,published,_=self.main_export('killrace');self.assertIsInstance(error,OSError);self.assertIn('PUBLIC_CONVERT_FAILURE',str(error));self.assertGreater(self.children[0].wait.call_count,0)
if __name__=='__main__':unittest.main()

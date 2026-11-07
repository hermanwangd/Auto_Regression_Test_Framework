"""Reviewed local image packaging only. Explicit external producer pins and heavy-operation lock."""
from pathlib import Path
import argparse,fcntl,json,os,re,shutil,subprocess,time,uuid,signal,threading,sys
from package_support import Commands,require,sha,read,save,durable_mkdir,reap_owned,syncdir,docker_environment
from prepare_context import prepare
from smoke import LocalImageSmokes
from docker_save_to_oci import convert,verify_archive,publish_archive
I=Path(__file__).resolve().parent

def export_image(image,archive,incomplete,stderr_path,commands):
 """One deadline covers conversion, child exit, independent readback and publication."""
 start=time.monotonic();p=None;monitor=None;stopping=threading.Event();fault=[];published_inode=None
 def bounded():
  if fault:raise ValueError(fault[0])
  if time.monotonic()-start>=180:raise TimeoutError('image_export_180s')
  commands.guard()
 def alarm(*_):
  if stopping.is_set():return
  if fault:raise ValueError(fault[0])
  raise TimeoutError('image_export_180s')
 previous=signal.signal(signal.SIGALRM,alarm);prior_timer=signal.setitimer(signal.ITIMER_REAL,180)
 try:
  with stderr_path.open('xb') as err:
   p=subprocess.Popen(['docker','--host=unix:///var/run/docker.sock','image','save',image],stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=docker_environment(),start_new_session=True)
   try:
    def capture_stderr():
     total=0
     try:
      pipe=getattr(p,'stderr',None)
      if pipe is None:return
      reader=getattr(pipe,'read1',pipe.read)
      while not stopping.is_set():
       data=reader(65536)
       if not data:break
       allowed=min(len(data),max(0,1024**2-total));err.write(data[:allowed]);err.flush();total+=len(data)
       if total>1024**2:raise ValueError('docker_save_stderr_limit')
     except BaseException as error:
      if not stopping.is_set():
       fault.append('docker_save_stderr_limit' if isinstance(error,ValueError) else 'docker_save_stderr_capture_failed');os.kill(os.getpid(),signal.SIGALRM)
    monitor=threading.Thread(target=capture_stderr,name='owned-save-stderr',daemon=True)
    monitor.start()
    exported=convert(p.stdout,incomplete,image,resource_guard=bounded);p.stdout.close();bounded();require(p.wait(timeout=max(.1,180-(time.monotonic()-start)))==0,'docker_save_failed')
    monitor.join(timeout=max(.1,180-(time.monotonic()-start)));bounded();require(not monitor.is_alive(),'docker_save_stderr_capture_incomplete');err.flush();os.fsync(err.fileno())
    verified=verify_archive(incomplete,image,resource_guard=bounded);bounded();require(verified==exported,'independent_export_readback_mismatch')
    st=incomplete.stat();published_inode=(st.st_dev,st.st_ino);publish_archive(incomplete,archive,verified['archive_sha256']);bounded()
    syncdir(archive.parent);bounded()
    return verified
   finally:
    original=sys.exc_info()[1];stopping.set();signal.setitimer(signal.ITIMER_REAL,0);reap_owned(p,original)
    if getattr(p,'stdout',None) is not None:p.stdout.close()
    if monitor is not None and monitor.ident is not None:monitor.join(timeout=3)
    if getattr(p,'stderr',None) is not None:p.stderr.close()
 except BaseException as error:
  # Revoke only our exact hard-linked publication. An unlink failure is retained as uncertainty.
  if published_inode is not None:
   try:
    st=archive.lstat()
    if (st.st_dev,st.st_ino)==published_inode:archive.unlink();syncdir(archive.parent)
   except FileNotFoundError:pass
   except BaseException as rollback:error.add_note('archive_publication_rollback_uncertain:'+type(rollback).__name__)
  raise
 finally:
  signal.setitimer(signal.ITIMER_REAL,0);signal.signal(signal.SIGALRM,previous)
  if prior_timer[0]>0:signal.setitimer(signal.ITIMER_REAL,*prior_timer)

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--accepted-producer',type=Path,required=True);parser.add_argument('--expected-producer-sha256',required=True);parser.add_argument('--fixture-root',type=Path,required=True);parser.add_argument('--lock',type=Path,required=True);args=parser.parse_args()
 from package_support import validate_producer
 producer=validate_producer(args.accepted_producer,args.expected_producer_sha256)
 require(args.fixture_root.is_dir() and (args.fixture_root/'valid_result.json').is_file(),'public_synthetic_fixture_missing')
 bases=read(I/'base-pins.json');require(bases['compressed_total_bytes']<=350*1024**2 and bases['platform']=='linux/amd64','base_resource_plan_invalid');base_refs={x['name']:x['pinned_ref'] for x in bases['bases']}
 require(not (I/'context').exists(),'context_already_exists');nonce=uuid.uuid4().hex[:12];work=I/'evidence'/('actual-'+nonce);durable_mkdir(work);resources=[];manifest={'status':'STARTED_LOCAL_IMAGE_ONLY','nonce':nonce,'registry_RepoDigest':None,'registry_publication':'NOT_ATTEMPTED','native_gateway':'NOT_IMPLEMENTED','native_product_SIT':'NOT_RUN'}
 with args.lock.open('a') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX)
  v=os.statvfs(I);free=v.f_bavail*v.f_frsize;require(free>=12*1024**3,'initial_free_under12GiB');commands=Commands(work/'commands',I,free)
  save(work/'resource-baseline.json',{'free_bytes':free,'maximum_incremental_bytes':6*1024**3,'minimum_free_bytes':6*1024**3,'lock':str(args.lock)})
  sanitized={'accepted_source_commit':producer['accepted_source_commit'],'accepted_source_tree':producer['accepted_source_tree'],'acceptance_review_sha256':producer['acceptance_review']['sha256'],'jar_sha256':producer['framework_jar']['sha256'],'schema_sha256':producer['runtime_schema']['sha256'],'wrapper_sha256':producer['wrapper']['sha256'],'wrapper_source_commit':producer['wrapper']['source_commit'],'producer_observation_sha256':producer['producer_observation']['sha256'],'identity_authority':'ROOT_EXTERNAL_ACCEPTED_PIN_NOT_SELF_REPORTED_COMMIT'}
  prepare(producer['framework_jar']['path'],producer['framework_jar']['sha256'],producer['wrapper']['path'],base_refs,I/'context',sanitized)
  save(work/'smoke-plan.json',{'literals':read(I/'smoke-literals.json'),'literals_sha256':sha(I/'smoke-literals.json'),'accepted_producer':{'path':str(args.accepted_producer),'sha256':args.expected_producer_sha256},'base_pins_sha256':sha(I/'base-pins.json'),'context_files':{p.name:{'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted((I/'context').iterdir())}})
  try:
   total_unpacked=0
   for base in bases['bases']:
    label='pull-'+base['name'];commands.run(label,['pull','--platform','linux/amd64',base['pinned_ref']],timeout=300,cap=4*1024**2)
    r=commands.run('inspect-'+base['name'],['image','inspect','--format','{"id":{{json .Id}},"size":{{.Size}}}',base['pinned_ref']]);d=read(r['stdout']['path']);require(d['id']==base['config_sha256'],'base_config_digest_mismatch');total_unpacked+=d['size'];commands.guard()
   require(total_unpacked<=int(1.5*1024**3),'unpacked_bases_over1_5GiB')
   tag='swarm-framework-tool:local-'+producer['framework_jar']['sha256'][:12]+'-'+nonce
   exists=commands.run('unique-tag-check',['image','inspect','--format','{{.Id}}',tag],allowed=None);require(exists['exit_code']!=0 and exists['guard'] is None,'local_tag_preexists')
   commands.run('buildx-capability',['buildx','version'],timeout=30,cap=1024**2,allowed=None)
   commands.run('build-local-image',['build','--platform','linux/amd64','--network','none','--pull=false','--tag',tag,str(I/'context')],timeout=300,cap=4*1024**2)
   r=commands.run('built-image',['image','inspect','--format','{"id":{{json .Id}},"size":{{.Size}},"user":{{json .Config.User}},"entrypoint":{{json .Config.Entrypoint}},"layers":{{json .RootFS.Layers}}}',tag]);image=read(r['stdout']['path']);require(re.fullmatch('sha256:[a-f0-9]{64}',image['id']) and image['user']=='65532:65532' and image['entrypoint']==['/opt/tool/entrypoint.sh'],'image_config_mismatch');manifest['image']={'tag':tag,**image};commands.guard()
   engine=LocalImageSmokes(image['id'],nonce,work,commands,producer,args.fixture_root)
   try:smokes=engine.run()
   finally:resources=engine.resources;manifest['resources']=resources;manifest['operations']=engine.events
   save(work/'smoke-results.json',smokes);manifest['smoke_status']=smokes['status']
   exports=I/'exports';durable_mkdir(exports);archive=exports/('framework-tool-'+nonce+'.oci.tar');incomplete=exports/('.'+nonce+'.incomplete.tar');export_stderr=work/'docker-save.stderr.log'
   verified=export_image(image['id'],archive,incomplete,export_stderr,commands)
   incomplete.unlink();syncdir(exports);commands.guard();manifest['export']={'path':str(archive),**verified};manifest['status']='LOCAL_IMAGE_AND_OCI_VERIFIED'
  except BaseException as error:
   manifest['status']='LOCAL_IMAGE_PACKAGE_INCOMPLETE';manifest['failure_class']=type(error).__name__;manifest['reason']=str(error)[:256];manifest['resources']=resources;raise
  finally:
   manifest['accepted_producer']={'path':str(args.accepted_producer),'sha256':args.expected_producer_sha256};manifest['base_pins']={'path':str(I/'base-pins.json'),'sha256':sha(I/'base-pins.json')};manifest['context_manifest']={'path':str(I/'context/runtime-source-manifest.json'),'sha256':sha(I/'context/runtime-source-manifest.json')};manifest['smoke_plan']={'path':str(work/'smoke-plan.json'),'sha256':sha(work/'smoke-plan.json')};manifest['commands']=[{'label':r['label'],'exit_code':r['exit_code'],'guard':r['guard']} for r in commands.records];manifest['resource_final_free_bytes']=os.statvfs(I).f_bavail*os.statvfs(I).f_frsize;save(work/'image-package-manifest.json',manifest)
 print(json.dumps({'status':manifest['status'],'manifest':str(work/'image-package-manifest.json'),'manifest_sha256':sha(work/'image-package-manifest.json'),'image_id':manifest['image']['id'],'export':manifest['export']}))
if __name__=='__main__':main()

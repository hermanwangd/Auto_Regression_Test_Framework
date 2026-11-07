"""Fixed local image packaging diagnostics; no D2 native gateway or product runner."""
from pathlib import Path
import errno,json,os,re,shutil,time
from package_support import Commands,require,sha,read,save,security_args,verify_saved,syncdir,durable_mkdir
JAVA='/opt/java/openjdk/bin/java';JAR='/opt/tool/framework.jar'
class LocalImageSmokes:
 def __init__(self,image,nonce,root,commands,producer,fixture):
  self.image=image;self.nonce=nonce;self.root=Path(root);self.commands=commands;self.producer=producer;self.fixture=Path(fixture);self.resources=[];self.results=[];self.events=[]
 def event(self,operation,**fields):
  self.events.append({'at_ns':time.time_ns(),'operation':operation,**fields})
 def writer_case(self,name):return name=='cd-blocked' or name.startswith('collector-') or name.startswith('retain-')
 def case_user(self,name):
  user=str(os.getuid())+':'+str(os.getgid()) if self.writer_case(name) else '65532:65532';security_args(user);return user
 def output(self,name):
  self.case_user(name);p=self.root/'outputs'/name;require(not p.exists(),'output_already_exists');durable_mkdir(p);p.chmod(0o700 if self.writer_case(name) else 0o755);syncdir(p);syncdir(p.parent);return p
 def owned(self,resource):
  r=self.commands.run(resource['name']+'-inspect-'+str(len(self.events)),['inspect','--format','{"id":{{json .Id}},"image":{{json .Image}},"labels":{{json .Config.Labels}},"user":{{json .Config.User}},"readonly":{{json .HostConfig.ReadonlyRootfs}},"network":{{json .HostConfig.NetworkMode}},"running":{{json .State.Running}},"exit":{{json .State.ExitCode}}}',resource['id']]);d=read(r['stdout']['path']);require(d['id']==resource['id'] and d['image']==self.image and d['labels'].get('swarm.image-package')==self.nonce,'foreign_container_identity');require(d['user']==resource['user'] and d['readonly'] and d['network']=='none','container_security_mismatch');return d
 def execute(self,name,argv,inputdir=None,deadline=30):
  out=self.output(name);user=self.case_user(name);args=['create','--name','swarm-image-'+self.nonce+'-'+name,'--label','swarm.image-package='+self.nonce,*security_args(user),'--mount','type=bind,src='+str(out)+',dst=/output']
  if inputdir is not None:args+=['--mount','type=bind,src='+str(inputdir)+',dst=/input,readonly']
  identity="import os,sys,json; print('IMAGE_PACKAGE_IDENTITY '+json.dumps({'uid':os.getuid(),'gid':os.getgid()}),file=sys.stderr,flush=True); os.execvp(sys.argv[1],sys.argv[1:])"
  args+=['--entrypoint','python3',self.image,'-c',identity,*argv]
  r=self.commands.run(name+'-create',args);cid=Path(r['stdout']['path']).read_text().strip();require(re.fullmatch('[a-f0-9]{64}',cid),'invalid_created_container_id');resource={'name':name,'id':cid,'output':str(out),'image':self.image,'user':user,'removed':False};self.resources.append(resource);self.event('CREATE',name=name,container=cid);self.owned(resource)
  self.commands.run(name+'-start',['start',cid]);self.event('START',container=cid)
  wait=self.commands.run(name+'-wait',['wait',cid],timeout=deadline,allowed=None);timed=wait['guard']=='command_timeout';stop_failure=None
  if wait['guard'] or wait['exit_code']!=0:
   self.event('WAIT_FAILURE_OR_TIMEOUT',container=cid,reason=wait['guard'])
   try:
    stop=self.commands.run(name+'-stop',['stop','--time','2',cid],timeout=10,allowed=None);stop_failure=stop['exit_code']!=0 or stop['guard'] is not None
   except Exception as error:stop_failure=type(error).__name__
   self.event('STOP_OWNED',container=cid,failure=stop_failure)
  # Even stop failure must preserve the capture/collection attempt.
  log=self.commands.run(name+'-logs',['logs',cid],allowed=None);state=self.owned(resource)
  observed=None;identity_error=None
  try:
   identities=[line.removeprefix('IMAGE_PACKAGE_IDENTITY ') for line in Path(log['stderr']['path']).read_text().splitlines() if line.startswith('IMAGE_PACKAGE_IDENTITY ')]
   require(len(identities)==1,'observed_runtime_identity_missing');observed=json.loads(identities[0]);require(str(observed['uid'])+':'+str(observed['gid'])==user and observed['uid']>0 and observed['gid']>0,'observed_runtime_identity_mismatch')
  except (ValueError,KeyError,TypeError) as error:identity_error=type(error).__name__
  complete=log['exit_code']==0 and log['guard'] is None and not state['running'] and identity_error is None
  resource['capture_complete']=complete
  result={'configured_user':user,'observed_user':observed,'identity_error':identity_error,'case':name,'container_id':cid,'exit_code':state['exit'],'timed_out':timed,'stop_failure':stop_failure,'stdout':log['stdout'],'stderr':log['stderr'],'output':str(out),'capture_state':'COMPLETE' if complete else 'PARTIAL','capture_complete':complete,'log_fetch':{'exit_code':log['exit_code'],'guard':log['guard']},'workload_running':state['running'],'framework_verdict':'UNKNOWN','claim':'LOCAL_IMAGE_PACKAGING_DIAGNOSTIC_NOT_PRODUCT_EXECUTION'};save(self.root/'evidence'/(name+'-runtime-status.json'),result);self.event('CAPTURE',container=cid,capture_state=result['capture_state'])
  if not complete:
   # A finite snapshot may be saved; it cannot authorize source removal or a full capture claim.
   if not name.startswith(('retain-','collector-')):
    try:self.diagnostic_collection(resource,result)
    except Exception as error:self.event('AVAILABLE_SNAPSHOT_FAILED',container=cid,failure_class=type(error).__name__)
   self.event('RETAIN_OWNED_INCOMPLETE',container=cid,running=state['running']);raise ValueError('incomplete_capture_owned_container_retained')
  return resource,result
 def remove(self,resource,proof):
  require(proof.get('verified') is True,'durable_retention_not_verified');require(resource.get('capture_complete') is True,'source_capture_incomplete_retained');state=self.owned(resource);require(not state['running'],'owned_container_still_running_retained');self.event('SAVE_READBACK_VERIFIED',container=resource['id'],complete=proof.get('complete'))
  self.commands.run(resource['name']+'-remove',['rm',resource['id']]);resource['removed']=True;self.event('REMOVE_OWNED',container=resource['id'])
 def retain_logs(self,resource,result):
  require(result.get('capture_complete') is True and resource.get('capture_complete') is True,'source_capture_incomplete_retained')
  for key in ['stdout','stderr']:require(sha(result[key]['path'])==result[key]['sha256'],'captured_log_changed')
  proof={'verified':True,'complete':None,'kind':'DURABLE_PACKAGING_LOGS_NO_NATIVE_ACK'};save(self.root/'evidence'/(resource['name']+'-retention.json'),proof);self.remove(resource,proof)
 def spec(self,name,files,canonical=None,run='public-image-diagnostic'):
  versions={'runner':self.producer['framework_jar']['sha256'],'framework':'0.3.0' if canonical else '0.3.2','provider':'PUBLIC_SYNTHETIC_LOCAL_PACKAGING','source':self.producer['accepted_source_commit'],'bundle':'PUBLIC_SYNTHETIC','sut':'NOT_RUN'}
  return {'schema_version':'runner-collection.v1','run_id':run,'correlation_id':'image-'+self.nonce+'-'+name,'payload_profile':'standard','deadline_ms':30000,'canonical_result':canonical,'artifact_versions':versions,'files':files}
 def collect(self,name,inputdir,spec,complete,verdict):
  save(inputdir/'spec.json',spec);resource,result=self.execute(name,[JAVA,'-jar',JAR,'runner-import','--collection-spec','/input/spec.json','--expected-spec-sha256',sha(inputdir/'spec.json'),'--source','/input/source','--out','/output/saved'],inputdir)
  require(result['exit_code']==(0 if complete else 1) and not result['timed_out'],'collector_exit_unexpected');saved=Path(result['output'])/'saved';proof=verify_saved(saved,complete);require(proof['framework_verdict']==verdict,'collector_changed_framework_verdict');save(self.root/'evidence'/(name+'-saved-readback.json'),proof);self.remove(resource,proof);self.results.append({'case':name,'collector':read(saved/'collection.json'),'readback':proof,'runtime':result});return proof
 def diagnostic_collection(self,resource,result):
  inputdir=self.root/'inputs'/('retention-'+resource['name']);source=inputdir/'source';(source/'logs').mkdir(parents=True);(source/'runtime').mkdir()
  for key in ['stdout','stderr']:shutil.copyfile(result[key]['path'],source/'logs'/(key+'.log'))
  shutil.copyfile(self.root/'evidence'/(resource['name']+'-runtime-status.json'),source/'runtime/status.json')
  gate=Path(result['output'])/'cd-blocked/gate-result.json'
  if gate.is_file():shutil.copyfile(gate,source/'runtime/cd-gate-result.json')
  files=[]
  for p in sorted(source.rglob('*')):
   if p.is_file():files.append({'path':str(p.relative_to(source)),'role':'log' if p.suffix=='.log' else 'runtime_status','bytes':p.stat().st_size,'sha256':sha(p),'required':True})
  proof=self.collect('retain-'+resource['name'],inputdir,self.spec(resource['name'],files),True,'UNKNOWN')
  save(self.root/'evidence'/(resource['name']+'-snapshot-retention.json'),{'snapshot_saved':proof.get('verified') is True,'source_capture_state':result['capture_state'],'source_cleanup_authorized':result['capture_complete'] is True,'authority':'FINITE_AVAILABLE_BYTES_NOT_COMPLETE_SOURCE_CAPTURE'})
  if result['capture_complete']:self.remove(resource,proof)
 def canonical_input(self,failed):
  name='collector-failed-fixture' if failed else 'collector-passed-fixture';inputdir=self.root/'inputs'/name;source=inputdir/'source';(source/'canonical/evidence').mkdir(parents=True)
  shutil.copyfile(self.fixture/'valid_result.json',source/'canonical/result.json')
  for p in sorted((self.fixture/'evidence').iterdir()):require(p.is_file() and not p.is_symlink(),'unsafe_public_fixture');shutil.copyfile(p,source/'canonical/evidence'/p.name)
  result=read(source/'canonical/result.json')
  if failed:
   result['status']='failed';result['test_results'][0]['status']='failed';result['failure']={'code':'ASSERTION_FAILED','classification':'ASSERTION_FAILED','reason':'public synthetic producer failure','owner_action':'review public fixture'};(source/'canonical/result.json').write_text(json.dumps(result)+'\n')
  files=[]
  for p in sorted(source.rglob('*')):
   if p.is_file():files.append({'path':str(p.relative_to(source)),'role':'canonical' if p.name=='result.json' else 'index' if p.name=='evidence_index.yaml' else 'evidence','bytes':p.stat().st_size,'sha256':sha(p),'required':True})
  save(inputdir/'classification.json',{'classification':'PUBLIC_SYNTHETIC_STATIC_CANONICAL_NOT_NATIVE_RUN','framework_verdict':result['status']});return name,inputdir,self.spec(name,files,'canonical/result.json',result['run_id']),result['status']
 def run(self):
  self.root.joinpath('inputs').mkdir();self.root.joinpath('outputs').mkdir();self.root.joinpath('evidence').mkdir(exist_ok=True)
  probe="import pathlib,hashlib,json; p=pathlib.Path('/opt/tool'); print(json.dumps({'files':sorted(x.name for x in p.iterdir()),'hashes':{x.name:hashlib.sha256(x.read_bytes()).hexdigest() for x in p.iterdir() if x.is_file()}}))"
  resource,result=self.execute('runtime-files',['python3','-c',probe]);content=read(result['stdout']['path']);require(result['exit_code']==0 and set(content['files'])=={'framework.jar','run-sit-postdeploy.py','runtime-source-manifest.json','entrypoint.sh'} and content['hashes']['framework.jar']==self.producer['framework_jar']['sha256'] and content['hashes']['run-sit-postdeploy.py']==self.producer['wrapper']['sha256'],'image_runtime_content_mismatch');self.retain_logs(resource,result);self.results.append({'case':'runtime-files','content':content,'runtime':result})
  diagnostics=[('java-start',[JAVA,'-version']),('python-start',['python3','--version']),('framework-help',[JAVA,'-jar',JAR,'--help']),('wrapper-help',['/opt/tool/entrypoint.sh','--help'])]
  for name,argv in diagnostics:
   resource,result=self.execute(name,argv);require(result['exit_code']==0 and not result['timed_out'],'startup_failed');self.retain_logs(resource,result);self.results.append(result)
  resource,result=self.execute('producer-observer',[JAVA,'-jar',JAR,'runner-observe-producer']);observed=read(result['stdout']['path']);require(result['exit_code']==0 and observed['identity_state']=='JAR_OBSERVED' and observed['artifact_sha256']==self.producer['framework_jar']['sha256'] and observed['schema_sha256']==self.producer['runtime_schema']['sha256'],'actual_image_producer_mismatch');self.retain_logs(resource,result);self.results.append({'case':'producer-observer','observation':observed,'runtime':result})
  code="import os,errno,json,pathlib; assert os.getuid()==65532; assert all(os.environ.get(k,'')=='' for k in ['HTTP_PROXY','http_proxy','HTTPS_PROXY','https_proxy','ALL_PROXY','all_proxy','NO_PROXY','no_proxy']);\ntry: pathlib.Path('/READ_ONLY_PROBE').write_text('x'); raise AssertionError('rootfs_writable')\nexcept OSError as e: assert e.errno==errno.EROFS\nprint(json.dumps({'uid':os.getuid(),'rootfs':'READ_ONLY','proxy_env':'EMPTY'}))"
  resource,result=self.execute('readonly-uid-proxy',['python3','-c',code]);require(result['exit_code']==0,'runtime_isolation_failed');self.retain_logs(resource,result);self.results.append(result)
  cd=self.root/'inputs/cd-blocked';cd.mkdir();(cd/'deployment.json').write_text('{}')
  resource,result=self.execute('cd-blocked',['/opt/tool/entrypoint.sh','--bundle','/input','--handoff','deployment.json','--expected-handoff-sha256',sha(cd/'deployment.json'),'--output','/output/cd-blocked','--java-home','/opt/java/openjdk'],cd)
  gate=read(Path(result['output'])/'cd-blocked/gate-result.json');require(result['exit_code']==1 and gate['status']=='BLOCKED' and gate['framework_launch_attempted'] is False,'blocked_cd_preflight_contract');self.diagnostic_collection(resource,result);self.results.append({'case':'cd-blocked','runtime':result,'gate':gate})
  resource,result=self.execute('unbound-runtime',[JAVA,'-jar',JAR,'runner-execute']);require(result['exit_code']==1 and 'runner_status: BLOCKED' in Path(result['stdout']['path']).read_text(),'native_gateway_must_remain_blocked');self.diagnostic_collection(resource,result);self.results.append(result)
  missing=self.root/'inputs/collector-missing';(missing/'source').mkdir(parents=True);file={'path':'missing.json','role':'evidence','bytes':2,'sha256':'0'*64,'required':True};self.collect('collector-missing',missing,self.spec('missing',[file]),False,'UNKNOWN')
  for failed in [False,True]:name,inputs,spec,verdict=self.canonical_input(failed);self.collect(name,inputs,spec,True,verdict)
  resource,result=self.execute('timeout-retention',['python3','-c',"import time; print('PUBLIC_TIMEOUT_PROBE',flush=True); time.sleep(60)"],deadline=3);require(result['timed_out'],'actual_timeout_not_observed');self.diagnostic_collection(resource,result);self.results.append(result)
  require(all(r['removed'] for r in self.resources),'owned_container_retained');return {'status':'EXPECTED_LOCAL_PACKAGING_OUTCOMES','results':self.results,'resources':self.resources,'operations':self.events,'native_gateway':'NOT_IMPLEMENTED','native_product_SIT':'NOT_RUN'}

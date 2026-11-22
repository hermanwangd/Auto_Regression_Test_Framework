"""Local image packaging bounds and readback, not a runtime transport/provider API."""
import hashlib,json,os,re,signal,subprocess,time,zipfile,sys
from pathlib import Path
PROXIES=['HTTP_PROXY','http_proxy','HTTPS_PROXY','https_proxy','ALL_PROXY','all_proxy','NO_PROXY','no_proxy']
def require(ok,reason):
 if not ok:raise ValueError(reason)
def sha(path):
 h=hashlib.sha256()
 with Path(path).open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()
def read(path):return json.loads(Path(path).read_text())
def syncdir(path):
 fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY)
 try:os.fsync(fd)
 finally:os.close(fd)
def durable_mkdir(path):
 path=Path(path)
 if path.exists():require(path.is_dir() and not path.is_symlink(),'unsafe_output_directory');return
 durable_mkdir(path.parent);path.mkdir();syncdir(path);syncdir(path.parent)
def save(path,value):
 path=Path(path);durable_mkdir(path.parent)
 with path.open('x') as f:json.dump(value,f,indent=2);f.write('\n');f.flush();os.fsync(f.fileno())
 syncdir(path.parent)
def checked(spec):
 p=Path(spec['path']);require(not p.is_symlink() and p.is_file() and p.stat().st_size<=128*1024**2 and p.stat().st_size==spec.get('bytes',p.stat().st_size) and sha(p)==spec['sha256'],'external_file_pin_mismatch');return p

def validate_producer(path,expected):
 path=Path(path);require(path.is_file() and path.stat().st_size<=1024**2 and re.fullmatch('[a-f0-9]{64}',expected or '') and sha(path)==expected,'external_producer_pin_mismatch');d=read(path)
 require(d.get('format_version')=='accepted-runner-producer.v1','unsupported_producer_manifest')
 for k in ['accepted_source_commit','accepted_source_tree']:require(re.fullmatch('[a-f0-9]{40}',d.get(k,'')),'accepted_source_identity_missing')
 require(str(read(checked(d['acceptance_review'])).get('decision','')).startswith('APPROVE'),'source_review_not_approved')
 jar=checked(d['framework_jar']);checked(d['wrapper']);checked(d['producer_observation'])
 observation=d['producer_observation'];require(observation.get('status')=='JAR_OBSERVED' and observation.get('observed_JAR_SHA')==d['framework_jar']['sha256'] and observation.get('schema_SHA')==d['runtime_schema']['sha256'],'producer_observation_identity_mismatch')
 resource=d['runtime_schema']['resource_path'].removeprefix('/');require('..' not in resource.split('/'),'invalid_schema_resource')
 with zipfile.ZipFile(jar) as z:
  paths=[name for name in [resource,'BOOT-INF/classes/'+resource] if name in z.namelist()];require(len(paths)==1,'runtime_schema_resource_missing');require(hashlib.sha256(z.read(paths[0])).hexdigest()==d['runtime_schema']['sha256'],'runtime_schema_pin_mismatch');require('BOOT-INF/classes/com/specdriven/regression/runner/RunnerCommand.class' in z.namelist(),'corrected_d2_cli_missing')
 return d

def security_args(user='65532:65532'):
 require(isinstance(user,str) and re.fullmatch('[1-9][0-9]{0,8}:[1-9][0-9]{0,8}',user),'nonroot_numeric_user_required')
 out=['--user',user,'--read-only','--network','none','--cap-drop','ALL','--security-opt','no-new-privileges','--memory','768m','--pids-limit','128','--tmpfs','/tmp:rw,noexec,nosuid,size=64m']
 for key in PROXIES:out+=['--env',key+'=']
 return out

def relative(root,name):
 require(isinstance(name,str) and name and not name.startswith('/') and '\\' not in name and all(v not in ('','.','..') for v in name.split('/')),'unsafe_retained_path');p=root/name
 for n in [p,*p.parents]:
  require(not n.is_symlink(),'symlink_retained_artifact')
  if n==root:break
 require(p.is_file(),'retained_artifact_missing');return p

def verify_saved(output,complete):
 out=Path(output);receipt=read(relative(out,'collection.json'));require(receipt['publication']=='SAVED','collection_not_saved');require(receipt['collection']==('COMPLETE' if complete else 'PARTIAL'),'unexpected_collection_state')
 require((out/'SAVE.complete').is_file()==complete,'complete_marker_mismatch');pins={}
 paths=['collection.json','aliases.json']+(['SAVE.complete'] if complete else [])
 for entry in receipt.get('files',[]):
  if entry.get('state')=='SAVED':
   p=relative(out,entry['path']);require(sha(p)==entry['saved_sha256'] and p.stat().st_size==entry['saved_bytes'],'saved_artifact_digest_mismatch');paths.append(entry['path'])
 derived=receipt.get('controller_derived_coverage')
 if derived:
  for name,key in [('coverage.json','json_sha256'),('coverage.md','md_sha256')]:
   p=relative(out,'controller-derived/coverage/'+name);require(sha(p)==derived[key],'derived_pair_digest_mismatch');paths.append('controller-derived/coverage/'+name)
 directories={out,out.parent}
 for name in paths:
  p=relative(out,name)
  with p.open('rb') as f:os.fsync(f.fileno())
  pins[name]={'sha256':sha(p),'bytes':p.stat().st_size}
  for parent in p.parents:
   directories.add(parent)
   if parent==out:break
 for directory in sorted(directories,key=lambda p:len(p.parts),reverse=True):syncdir(directory)
 require(all(sha(out/n)==v['sha256'] for n,v in pins.items()),'durable_readback_changed')
 return {'verified':True,'complete':complete,'collection':receipt['collection'],'framework_verdict':receipt.get('framework_verdict','UNKNOWN'),'files':pins,'authority':'LOCAL_PACKAGING_READBACK_NOT_NATIVE_ACK'}

def docker_environment(build=False):
 env=dict(os.environ)
 for key in ['DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH','BUILDX_BUILDER']:env.pop(key,None)
 if build:env['DOCKER_BUILDKIT']='0'  # Docker28 engine-local API, independent of selected Buildx builder.
 return env

def reap_owned(child,original=None):
 """Terminate/reap only the child session we created; preserve an active failure."""
 cleanup_error=None;rc=None
 try:
  if child.poll() is None:
   try:os.killpg(child.pid,signal.SIGKILL)
   except ProcessLookupError:pass
 except BaseException as error:cleanup_error=error
 try:rc=child.wait(timeout=3)
 except BaseException as error:
  if cleanup_error is None:cleanup_error=error
 if cleanup_error is not None:
  if original is None:raise cleanup_error
  original.add_note('owned_child_cleanup_failed:'+type(cleanup_error).__name__)
 return rc

def persist_command(path,record,original=None):
 try:save(path,record)
 except BaseException as writing:
  if original is None:raise
  original.add_note('command_record_failed:'+type(writing).__name__)

class Commands:
 def __init__(self,evidence,scope_root,initial_free):self.evidence=Path(evidence);durable_mkdir(self.evidence);self.scope=Path(scope_root);self.initial_free=initial_free;self.records=[]
 def guard(self):
  v=os.statvfs(self.scope);free=v.f_bavail*v.f_frsize;require(free>=6*1024**3,'disk_floor_5GiB_plus1GiB_margin');require(self.initial_free-free<=6*1024**3,'incremental_6GiB_budget');return free
 def run(self,label,args,timeout=30,cap=1024**2,docker=True,allowed=(0,)):
  require(re.fullmatch('[A-Za-z0-9_.-]+',label),'invalid_command_label');self.guard();out=self.evidence/(label+'.stdout.log');err=self.evidence/(label+'.stderr.log');require(not out.exists() and not err.exists(),'command_record_exists')
  argv=(['docker','--host=unix:///var/run/docker.sock'] if docker else [])+[str(x) for x in args];env=docker_environment(build=docker and args[0]=='build')
  start=time.monotonic();guard=None;p=None;rc=None
  try:
   with out.open('xb') as stdout,err.open('xb') as stderr:
    p=subprocess.Popen(argv,stdout=stdout,stderr=stderr,env=env,start_new_session=True)
    try:
     while p.poll() is None:
      try:self.guard()
      except ValueError as e:guard=str(e)
      if time.monotonic()-start>timeout:guard='command_timeout'
      if out.stat().st_size>cap or err.stat().st_size>cap:guard='command_log_limit'
      if guard:break
      time.sleep(.1)
    finally:rc=reap_owned(p,sys.exc_info()[1])
   if out.stat().st_size>cap or err.stat().st_size>cap:guard='command_log_limit'
   for logfile in [out,err]:
    with logfile.open('rb') as persisted:os.fsync(persisted.fileno())
   syncdir(self.evidence)
   result={'label':label,'argv':argv,'exit_code':rc,'seconds':round(time.monotonic()-start,3),'guard':guard,'stdout':{'path':str(out),'bytes':out.stat().st_size,'sha256':sha(out)},'stderr':{'path':str(err),'bytes':err.stat().st_size,'sha256':sha(err)}};save(self.evidence/(label+'-command.json'),result);self.records.append(result)
   if allowed is not None:require(rc in allowed and guard is None,'packaging_command_failed:'+label)
   return result
  except BaseException as error:
   if not (self.evidence/(label+'-command.json')).exists():persist_command(self.evidence/(label+'-command.json'),{'label':label,'argv':argv,'exit_code':p.returncode if p is not None else None,'guard':guard,'failure_class':type(error).__name__,'status':'INCOMPLETE'},error)
   raise

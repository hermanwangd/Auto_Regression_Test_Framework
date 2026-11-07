"""Bounded official metadata only; no image pull, credentials, or runtime execution."""
from pathlib import Path
import json,os,subprocess,time,hashlib,sys,re
from package_support import durable_mkdir,require,save,reap_owned,persist_command,syncdir
I=Path(__file__).resolve().parent
BASES=[('python','docker.io/library/python:3.12-slim-bookworm','https://github.com/docker-library/python','PSF-2.0 plus Debian base notices'),('jre','docker.io/library/eclipse-temurin:17-jre-jammy','https://github.com/adoptium/containers','GPL-2.0 WITH Classpath-exception-2.0 plus Ubuntu base notices')]
def run(label,ref):
 require(re.fullmatch('[A-Za-z0-9_.-]+',label),'invalid_command_label');evidence=I/'evidence';durable_mkdir(evidence)
 out=evidence/(label+'.stdout.json');err=evidence/(label+'.stderr.log');record=evidence/(label+'-command.json')
 require(not any(p.exists() for p in [out,err,record]),'command_record_exists')
 env=dict(os.environ)
 for key in ['DOCKER_HOST','DOCKER_CONTEXT','DOCKER_TLS','DOCKER_TLS_VERIFY','DOCKER_CERT_PATH']:env.pop(key,None)
 argv=['docker','--host=unix:///var/run/docker.sock','manifest','inspect',ref];start=time.monotonic();guard=None;p=None
 try:
  with out.open('xb') as stdout,err.open('xb') as stderr:
   p=subprocess.Popen(argv,stdout=stdout,stderr=stderr,env=env,start_new_session=True)
   try:
    while p.poll() is None:
     if time.monotonic()-start>30 or out.stat().st_size>4*1024*1024 or err.stat().st_size>1024*1024:guard='metadata_deadline_or_size';break
     time.sleep(.1)
   finally:rc=reap_owned(p,sys.exc_info()[1])
  if time.monotonic()-start>30 or out.stat().st_size>4*1024*1024 or err.stat().st_size>1024*1024:guard='metadata_deadline_or_size'
  for path in [out,err]:
   with path.open('rb') as f:os.fsync(f.fileno())
  syncdir(evidence)
  rec={'argv':argv,'exit_code':rc,'seconds':round(time.monotonic()-start,3),'guard':guard,'stdout_sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'stdout_bytes':out.stat().st_size};save(record,rec)
  require(rc==0 and guard is None,'metadata_command_failed')
  return json.loads(out.read_bytes())
 except BaseException as error:
  if not record.exists():persist_command(record,{'argv':argv,'exit_code':p.returncode if p is not None else None,'guard':guard,'failure_class':type(error).__name__,'status':'INCOMPLETE'},error)
  raise

def main():
 assert not (I/'base-pins.json').exists()
 bases=[]
 for name,ref,source,license in BASES:
  index=run('base-'+name+'-index',ref)
  matches=[m for m in index.get('manifests',[]) if m.get('platform',{}).get('os')=='linux' and m['platform'].get('architecture')=='amd64']
  assert len(matches)==1,matches
  digest=matches[0]['digest'];pinned=ref.split(':')[0]+'@'+digest
  manifest=run('base-'+name+'-amd64',pinned)
  assert manifest['schemaVersion']==2 and len(manifest['layers'])<=32
  bases.append({'name':name,'metadata_tag':ref,'pinned_ref':pinned,'platform':'linux/amd64','manifest_sha256':digest,'config_sha256':manifest['config']['digest'],'layers':manifest['layers'],'compressed_layer_bytes':sum(l['size'] for l in manifest['layers']),'source':source,'license':license,'digest_verification':'descriptor selected from official index; daemon content verification required at authorized pull'})
 total=sum(b['compressed_layer_bytes'] for b in bases);assert total<=350*1024*1024
 record={'schema':'local-image-base-pins.v1','bases':bases,'compressed_total_bytes':total,'estimated_unpacked_limit_bytes':int(1.5*1024**3),'pull_performed':False,'platform':'linux/amd64','registry_login_or_credentials_created':False}
 save(I/'base-pins.json',record);print(json.dumps({'status':'METADATA_PINNED_NO_PULL','compressed_bytes':total,'bases':[{'name':b['name'],'ref':b['pinned_ref'],'config':b['config_sha256']} for b in bases]}))
if __name__=='__main__':main()

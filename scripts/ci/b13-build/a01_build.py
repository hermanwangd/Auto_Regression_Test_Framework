#!/usr/bin/env python3
"""A01 fixture compilation only. Never starts the resulting service."""
import argparse, base64, hashlib, importlib.util, json, os, pathlib, re, shutil, sys, time, urllib.parse, urllib.request
P=pathlib.Path
HELPER_SHA='27e39f176a955390cf8e492318a36ea577a8f1759f1dbd9d0ea9c508e443667b'
hp=P(__file__).with_name('build_driver.py')
if hashlib.sha256(hp.read_bytes()).hexdigest()!=HELPER_SHA: raise RuntimeError('helper identity mismatch')
spec=importlib.util.spec_from_file_location('a01_immutable_helper',hp); d=importlib.util.module_from_spec(spec); spec.loader.exec_module(d)
GO_SHA='63d339f0da5ab53635a56f2490a7984dfe12dfcff22ad749f63edaf590168445'
GO_URL='https://dl.google.com/go/go1.27.1.linux-amd64.tar.gz'
REV='c7cb6d5bfd6ea3aed768634ebbcb98cb7a523712'
NAMES={'server.go','product_catalog.go','catalog_loader.go','go.mod','go.sum','products.json','genproto/demo.pb.go','genproto/demo_grpc.pb.go'}
OUTPUTS={'productcatalogservice','products.json','fixtures/catalog.desc.b64','build-manifest.json'}
def need(ok,why):
 if not ok: raise d.BuildError(why)
def identity(data,row):
 need(len(data)==row['bytes'] and hashlib.sha256(data).hexdigest()==row['sha256'] and hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()==row['git_blob'],'SOURCE: identity mismatch')
def patch(data,c):
 old=c['old'].encode(); new=c['new'].encode(); need(data.count(old)==1 and c['expected_occurrences']==1,'PATCH: occurrence mismatch'); out=data.replace(old,new)
 need(len(out)==c['result_bytes'] and hashlib.sha256(out).hexdigest()==c['result_sha256'],'PATCH: result mismatch'); return out
def descriptor(data):
 marker=b'var file_demo_proto_rawDesc = []byte{'; need(data.count(marker)==1,'DESCRIPTOR: block cardinality')
 tail=data.split(marker,1)[1]; need(b'\n}' in tail,'DESCRIPTOR: missing end'); body=tail.split(b'\n}',1)[0]
 need(re.fullmatch(rb'(?:\s|,|0x[0-9a-f]{2})*',body) is not None,'DESCRIPTOR: grammar')
 raw=bytes(int(x,16) for x in re.findall(rb'0x([0-9a-f]{2})',body))
 need(len(raw)==4396 and hashlib.sha256(raw).hexdigest()=='8f6b5761a04e7d699c0e40c2776d94900581f2706a112544fa80ba71169f5d54','DESCRIPTOR: raw identity')
 fds=b'\x0a\xac\x22'+raw
 need(len(fds)==4399 and hashlib.sha256(fds).hexdigest()=='b448856ccf1a0415e009fb841e8168db7d7e861938b573f280d9fdb644fc353d','DESCRIPTOR: set identity')
 out=base64.b64encode(fds); need(len(out)==5868 and hashlib.sha256(out).hexdigest()=='285bd375bc4bff89d44ed51a0ddaf205670b35ff070860311b1fc0f4bdab1921','DESCRIPTOR: base64 identity'); return out
def check_url(url):
 u=urllib.parse.urlsplit(url); need(u.scheme=='https' and u.hostname in {'dl.google.com','raw.githubusercontent.com'} and not u.username and not u.password and u.port in (None,443),'NETWORK: URL outside fixed hosts')
class Redirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,newurl):
  check_url(newurl); return super().redirect_request(req,fp,code,msg,headers,newurl)
def fetch(url,path,cap,budget,sha):
 check_url(url); need(not path.exists(),'DOWNLOAD: exists'); size=0; h=hashlib.sha256()
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),Redirect())
 with opener.open(url,timeout=15) as response,path.open('xb') as out:
  check_url(response.geturl())
  while True:
   budget.check(); chunk=response.read(65536)
   if not chunk: break
   size+=len(chunk); need(size<=cap,'DOWNLOAD: byte cap'); out.write(chunk); h.update(chunk)
   if time.monotonic()-budget.last_storage>=.5: budget.check_storage()
 need(h.hexdigest()==sha,'DOWNLOAD: digest mismatch'); budget.check_storage(); return size

def environment(work):
 env={'PATH':os.defpath,'LANG':'C.UTF-8','CI':'true','GOTOOLCHAIN':'local','CGO_ENABLED':'0','GOOS':'linux','GOARCH':'amd64','GOENV':'off','GOWORK':'off','GOMAXPROCS':'2','GOPROXY':'https://proxy.golang.org','GOSUMDB':'sum.golang.org','GOPRIVATE':'','GONOPROXY':'','GONOSUMDB':'','GOVCS':'*:off'}
 for key,name in [('HOME','home'),('GOPATH','gopath'),('GOMODCACHE','gomod'),('GOCACHE','gocache'),('TMPDIR','tmp')]: env[key]=str(d.fresh_dir(work/name))
 return env

def commands(go): return [[go,'version'],[go,'build','-p=2','-mod=readonly','-trimpath','-buildvcs=false','-o','productcatalogservice','.'],[go,'mod','verify'],[go,'version','-m','productcatalogservice']]
def unchanged(source,original):
 for name,data in original.items(): need((source/name).read_bytes()==data,'SOURCE: module files changed')
def metadata(text):
 need('go1.27.1' in text and all('\tbuild\t'+v in text for v in ('CGO_ENABLED=0','GOARCH=amd64','GOOS=linux')),'BINARY: build metadata mismatch')
def delivery_check(root):
 files=[p for p in root.rglob('*') if not p.is_dir()]; need(all(p.is_file() and not p.is_symlink() for p in files),'DELIVERY: unsafe type')
 need({p.relative_to(root).as_posix() for p in files}==OUTPUTS,'DELIVERY: output set'); need(sum(p.stat().st_size for p in files)<=150*d.MIB,'DELIVERY: byte cap')
def main():
 parser=argparse.ArgumentParser(); parser.add_argument('--work',required=True,type=P); parser.add_argument('--control',required=True,type=P); parser.add_argument('--workflow-commit',required=True); args=parser.parse_args()
 need(re.fullmatch('[0-9a-f]{40}',args.workflow_commit) is not None,'WORKFLOW: invalid SHA')
 work=args.work.resolve(); control=args.control.resolve(); need(control.is_dir() and P(__file__).resolve().is_relative_to(control) and not work.is_relative_to(control) and not control.is_relative_to(work),'ROOTS: control/work must be disjoint'); d.fresh_dir(work); budget=d.Budget([work,control],total=1440,reserve=30,storage_cap=2048*d.MIB,rss_cap=3072*d.MIB)
 env=environment(work); budget.cache_roots=[P(env['GOMODCACHE']),P(env['GOCACHE'])]; source=d.fresh_dir(work/'source')
 contract_path=P(__file__).with_name('a01-build-contract.json'); raw=contract_path.read_bytes(); need(len(raw)<32768,'CONTRACT: size'); contract=json.loads(raw)
 need(contract['source_revision']==REV and len(contract['source_files'])==8 and {r['destination'] for r in contract['source_files']}==NAMES,'CONTRACT: source closure')
 original={}
 for row in contract['source_files']:
  name=row['destination']; dest=source/name; dest.parent.mkdir(exist_ok=True); url='https://raw.githubusercontent.com/GoogleCloudPlatform/microservices-demo/'+REV+'/src/productcatalogservice/'+name
  fetch(url,dest,row['bytes'],budget,row['sha256']); data=dest.read_bytes(); identity(data,row); original[name]=data
 desc=descriptor(original['genproto/demo.pb.go']); (source/'server.go').write_bytes(patch(original['server.go'],contract['loopback_patch']))
 archive=work/'go.tar.gz'; size=fetch(GO_URL,archive,80*d.MIB,budget,GO_SHA); d.extract_archive(archive,work/'tool',budget); archive.unlink()
 go=work/'tool/go/bin/go'; need(go.is_file() and not go.is_symlink(),'TOOL: layout'); env['GOROOT']=str(work/'tool/go'); env['PATH']=str(go.parent)+':'+os.defpath
 records=[]; binary_metadata=None
 for i,cmd in enumerate(commands(str(go))):
  r=d.run_process(cmd,source,env,budget,['compiler-version','compile-fixture','verify-modules','binary-metadata'][i]); text=r.pop('stdout')
  if i==0: need(text.strip()=='go version go1.27.1 linux/amd64','TOOL: version mismatch')
  if i==2: need(text.strip()=='all modules verified','MODULES: verification failed')
  if i==3: metadata(text); binary_metadata=text
  records.append(r); unchanged(source,{k:original[k] for k in ('go.mod','go.sum')})
 for name,data in original.items():
  expected=patch(data,contract['loopback_patch']) if name=='server.go' else data
  need((source/name).read_bytes()==expected,'SOURCE: changed build input')
 binary=source/'productcatalogservice'; need(binary.is_file() and not binary.is_symlink() and binary.stat().st_size<=148*d.MIB,'BINARY: missing/large/unsafe')
 delivery=d.fresh_dir(work/'delivery'); (delivery/'fixtures').mkdir(); shutil.copyfile(binary,delivery/binary.name); (delivery/'products.json').write_bytes(original['products.json']); (delivery/'fixtures/catalog.desc.b64').write_bytes(desc)
 dependencies,cache_bytes=d.inventory(P(env['GOMODCACHE']),budget); artifacts=[]
 for name in sorted(OUTPUTS-{'build-manifest.json'}):
  p=delivery/name; artifacts.append({'path':name,'bytes':p.stat().st_size,'sha256':d.digest_file(p,'sha256',budget)})
 manifest={'schema':'a01-fixture-build.v1','status':'BUILT_NOT_RUNTIME_TESTED','workflow_commit':args.workflow_commit,'run_id':os.environ.get('GITHUB_RUN_ID'),'run_attempt':os.environ.get('GITHUB_RUN_ATTEMPT'),'driver_sha256':d.digest_file(P(__file__),'sha256',budget),'helper_sha256':HELPER_SHA,'contract_sha256':hashlib.sha256(raw).hexdigest(),'source':contract,'compiler':{'url':GO_URL,'archive_sha256':GO_SHA,'archive_bytes':size,'executable_sha256':d.digest_file(go,'sha256',budget),'version':'go1.27.1'},'binary_metadata':binary_metadata,'dependencies':{'inventory_count':len(dependencies),'inventory_sha256':hashlib.sha256(json.dumps(dependencies,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'module_records':[r for r in dependencies if r['path'].endswith(('.ziphash','.mod'))]},'module_cache_bytes':cache_bytes,'processes':records,'artifacts':artifacts,'limits':{'job_seconds':1500,'driver_seconds':1440,'cleanup_reserve_seconds':30,'sampled_group_rss_bytes':3072*d.MIB,'logical_owned_disk_bytes':2048*d.MIB,'cache_subset_bytes':1024*d.MIB,'delivery_bytes':150*d.MIB,'workers':2},'observed':{'peak_rss_bytes':budget.peak_rss,'peak_storage_bytes':budget.peak_storage},'acceptance':{'A01_runtime':'NOT_RUN','Java_baseline':'NOT_RUN','SIT':'NOT_RUN','coverage_credit':0}}
 d.write_manifest(delivery,manifest); delivery_check(delivery); budget.check_storage(); budget.check(); print(json.dumps({'status':'BUILT_NOT_RUNTIME_TESTED','artifacts':artifacts}))
if __name__=='__main__':
 try: main()
 except Exception as e:
  print(json.dumps({'status':'FAILED_CLOSED','reason':d.failure_tail(str(e).encode())})); sys.exit(1)

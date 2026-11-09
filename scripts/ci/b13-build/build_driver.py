#!/usr/bin/env python3
"""Bounded, fixed-source packaging only. No product execution or deployment."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import selectors
import shutil
import signal
import stat
import subprocess
import tarfile
import time
import urllib.request
import zipfile

SOURCE = 'b13c503cc9fadd930e11fb8adc6496308697a966'
TREE = 'a912fc45e68cf1dd31f7e4b16a795338eaec054f'
JDK_URL = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz'
JDK_SHA256 = '8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711'
MAVEN_URL = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.tar.gz'
MAVEN_SHA512 = 'bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978'
MIB = 1024 * 1024

class BuildError(RuntimeError): pass

def fresh_dir(path):
    path = Path(path)
    if path.exists() or path.is_symlink(): raise BuildError('EXISTS: fresh path required')
    path.mkdir(parents=False)
    return path

class Budget:
    def __init__(self, roots, total=840, reserve=30, log_cap=MIB,
                 storage_cap=2048*MIB, rss_cap=2048*MIB, entry_cap=50000):
        self.start=time.monotonic(); self.end=self.start+total
        self.reserve=reserve; self.roots=[Path(p) for p in roots]
        self.log_cap=log_cap; self.storage_cap=storage_cap; self.rss_cap=rss_cap
        self.entry_cap=entry_cap; self.last_storage=0; self.peak_storage=0; self.peak_rss=0
        self.cache_roots=[]; self.cache_cap=1024*MIB
    def check(self):
        if time.monotonic() >= self.end-self.reserve: raise BuildError('DEADLINE: cleanup reserve reached')
    def check_storage(self):
        self.check(); total=0; entries=0; cache=0
        for root in self.roots:
            for base, dirs, files in os.walk(root, followlinks=False):
                self.check()
                for name in dirs+files:
                    p=Path(base)/name
                    try: s=p.lstat()
                    except FileNotFoundError: continue
                    entries+=1
                    if entries>self.entry_cap: raise BuildError('STORAGE: entry cap')
                    if not stat.S_ISDIR(s.st_mode):
                        total+=s.st_size
                        if any(p.is_relative_to(c) for c in self.cache_roots): cache+=s.st_size
                    if total>self.storage_cap: raise BuildError('STORAGE: byte cap')
                    if cache>self.cache_cap: raise BuildError('CACHE: byte cap')
        self.peak_storage=max(total,self.peak_storage); self.last_storage=time.monotonic()
        return total

def pid_running(pid):
    try:
        text=Path('/proc',str(pid),'stat').read_text()
        return text[text.rfind(')')+2:].split()[0] != 'Z'
    except (FileNotFoundError,ProcessLookupError): return False

def group_state(pgid):
    live=[]; rss=0
    for name in os.listdir('/proc'):
        if not name.isdigit(): continue
        try:
            row=Path('/proc',name,'stat').read_text()
            fields=row[row.rfind(')')+2:].split()
            if int(fields[2])==pgid and fields[0]!='Z':
                live.append(int(name)); rss+=int(fields[21])*os.sysconf('SC_PAGE_SIZE')
        except (FileNotFoundError,ProcessLookupError): continue
    return live,rss

def cleanup_process(proc, end):
    for sig in (signal.SIGTERM,signal.SIGKILL):
        try: os.killpg(proc.pid,sig)
        except ProcessLookupError: pass
        deadline=min(end,time.monotonic()+(.15 if sig==signal.SIGTERM else .5))
        while time.monotonic()<deadline:
            proc.poll()
            if not group_state(proc.pid)[0]: break
            time.sleep(.01)
    try: proc.wait(timeout=max(.001,min(.2,end-time.monotonic())))
    except subprocess.TimeoutExpired: raise BuildError('CLEANUP: parent did not terminate')
    if group_state(proc.pid)[0]: raise BuildError('CLEANUP: surviving process group')

def run_process(argv,cwd,env,budget,label):
    budget.check(); started=time.monotonic(); output=bytearray(); proc=None
    sel=selectors.DefaultSelector()
    try:
        proc=subprocess.Popen(argv,cwd=cwd,env=env,stdin=subprocess.DEVNULL,
                              stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
        os.set_blocking(proc.stdout.fileno(),False); sel.register(proc.stdout,selectors.EVENT_READ)
        while True:
            budget.check()
            live,rss=group_state(proc.pid); budget.peak_rss=max(budget.peak_rss,rss)
            if rss>budget.rss_cap: raise BuildError('MEMORY: process group RSS cap')
            if time.monotonic()-budget.last_storage>=.5: budget.check_storage()
            for key,_ in sel.select(.02):
                chunk=os.read(key.fileobj.fileno(),65536)
                if chunk:
                    if len(output)+len(chunk)>budget.log_cap: raise BuildError('OUTPUT: process log cap')
                    output.extend(chunk)
                else: sel.unregister(key.fileobj)
            if proc.poll() is not None:
                if live and any(pid!=proc.pid for pid in live): raise BuildError('CLEANUP: descendant after parent exit')
                if not sel.get_map(): break
        if proc.returncode: raise BuildError('EXIT: %s=%s' % (label,proc.returncode))
        return {'label':label,'exit_code':proc.returncode,'seconds':round(time.monotonic()-started,6),
                'output_bytes':len(output),'output_sha256':hashlib.sha256(output).hexdigest(),
                'stdout':output.decode('utf-8','replace')}
    finally:
        sel.close()
        if proc is not None:
            try: cleanup_process(proc,budget.end)
            finally: proc.stdout.close()

def digest_file(path,algorithm,budget):
    h=hashlib.new(algorithm)
    with Path(path).open('rb') as f:
        while True:
            budget.check(); b=f.read(65536)
            if not b: break
            h.update(b)
    return h.hexdigest()

def verify_digest(path,algorithm,expected,budget):
    if digest_file(path,algorithm,budget)!=expected: raise BuildError('DIGEST: tool archive mismatch')

def download(url,path,algorithm,expected,max_bytes,budget,exact_size=None):
    budget.check()
    if path.exists(): raise BuildError('EXISTS: download')
    request=urllib.request.Request(url,headers={'User-Agent':'b13-build-only/1'})
    with urllib.request.urlopen(request,timeout=min(15,max(1,budget.end-budget.reserve-time.monotonic()))) as response, path.open('xb') as out:
        if not response.geturl().startswith('https://'): raise BuildError('DOWNLOAD: insecure redirect')
        size=0
        while True:
            budget.check(); chunk=response.read(65536)
            if not chunk: break
            size+=len(chunk)
            if size>max_bytes: raise BuildError('DOWNLOAD: archive byte cap')
            out.write(chunk)
    if exact_size is not None and size!=exact_size: raise BuildError('DOWNLOAD: archive size mismatch')
    verify_digest(path,algorithm,expected,budget); budget.check_storage()
    return {'url':url,'bytes':size,algorithm:expected}

def extract_archive(archive,dest,budget):
    fresh_dir(dest); root=dest.resolve(); count=0; expanded=0
    with tarfile.open(archive,'r:gz') as tar:
        for member in tar:
            budget.check(); count+=1; expanded+=max(0,member.size)
            if count>20000 or expanded>768*MIB: raise BuildError('ARCHIVE: extraction cap')
            path=dest/member.name
            if member.name.startswith('/') or '..' in Path(member.name).parts or not path.resolve().is_relative_to(root):
                raise BuildError('ARCHIVE: unsafe path')
            if member.isdir(): path.mkdir(parents=True,exist_ok=True); continue
            path.parent.mkdir(parents=True,exist_ok=True)
            if path.exists() or path.is_symlink(): raise BuildError('ARCHIVE: duplicate path')
            if member.isfile():
                with tar.extractfile(member) as src, path.open('xb') as out:
                    while True:
                        budget.check(); chunk=src.read(65536)
                        if not chunk: break
                        out.write(chunk)
                path.chmod(member.mode & 0o755)
            elif member.issym():
                target=path.parent/member.linkname
                if Path(member.linkname).is_absolute() or not target.resolve().is_relative_to(root): raise BuildError('ARCHIVE: unsafe link')
                path.symlink_to(member.linkname)
            elif member.islnk():
                target=dest/member.linkname
                if not target.resolve().is_relative_to(root) or not target.is_file(): raise BuildError('ARCHIVE: unsafe hardlink')
                os.link(target,path)
            else: raise BuildError('ARCHIVE: special file')
    budget.check_storage()

def source_identity(source,env,budget):
    result=run_process(['git','rev-parse','HEAD','HEAD^{tree}'],source,env,budget,'source-identity')
    if result['stdout'].splitlines()!=[SOURCE,TREE]: raise BuildError('SOURCE: commit/tree mismatch')
    run_process(['git','diff','--quiet','HEAD','--'],source,env,budget,'source-clean')
    return {'commit':SOURCE,'tree':TREE,'tracked_inputs_unchanged':True}

def inventory(root,budget):
    result=[]; total=0
    for base,dirs,files in os.walk(root,followlinks=False):
        dirs.sort(); files.sort(); budget.check()
        for name in files:
            p=Path(base)/name
            if p.is_symlink() or not p.is_file(): raise BuildError('CACHE: unexpected file type')
            size=p.stat().st_size; total+=size
            if total>1024*MIB or len(result)>=20000: raise BuildError('CACHE: cap')
            result.append({'path':p.relative_to(root).as_posix(),'bytes':size,'sha256':digest_file(p,'sha256',budget)})
    return result,total

def write_manifest(delivery,manifest,max_bytes=MIB):
    data=(json.dumps(manifest,sort_keys=True,indent=2)+'\n').encode()
    if len(data)>max_bytes: raise BuildError('MANIFEST: byte cap')
    target=Path(delivery)/'build-manifest.json'
    if target.exists(): raise BuildError('EXISTS: manifest')
    tmp=Path(delivery)/'build-manifest.json.partial'
    with tmp.open('xb') as f: f.write(data)
    tmp.replace(target)

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--work',type=Path,required=True)
    parser.add_argument('--workflow-commit',required=True)
    args=parser.parse_args()
    if len(args.workflow_commit)!=40 or any(c not in '0123456789abcdef' for c in args.workflow_commit): raise BuildError('WORKFLOW: invalid commit')
    source=args.source.resolve(); work=args.work.resolve()
    if source==work or source.is_relative_to(work) or work.is_relative_to(source): raise BuildError('ROOTS: must be disjoint')
    fresh_dir(work)
    budget=Budget([source,work]); env={'PATH':os.defpath,'HOME':str(fresh_dir(work/'home')),'LANG':'C.UTF-8','CI':'true'}
    before=source_identity(source,env,budget)
    for rel in ('target','integrations/swarm-product-test/target'):
        if (source/rel).exists(): raise BuildError('EXISTS: stale build output')
    budget.check_storage()
    jdk=download(JDK_URL,work/'jdk.tar.gz','sha256',JDK_SHA256,193594705,budget,193594705)
    maven=download(MAVEN_URL,work/'maven.tar.gz','sha512',MAVEN_SHA512,20*MIB,budget)
    extract_archive(work/'jdk.tar.gz',work/'jdk',budget)
    extract_archive(work/'maven.tar.gz',work/'maven',budget)
    java_home=work/'jdk'/'jdk-17.0.21+9'; maven_home=work/'maven'/'apache-maven-3.9.11'
    if not (java_home/'bin/java').is_file() or not (maven_home/'bin/mvn').is_file(): raise BuildError('TOOLS: expected layout missing')
    env.update({'JAVA_HOME':str(java_home),'PATH':str(java_home/'bin')+':'+os.defpath,
                'MAVEN_OPTS':'-Xmx1024m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=128m'})
    m2=fresh_dir(work/'m2'); budget.cache_roots=[m2]; settings=work/'settings.xml'
    settings.write_text('<settings><mirrors><mirror><id>official-central-only</id><mirrorOf>*</mirrorOf><url>https://repo.maven.apache.org/maven2</url></mirror></mirrors></settings>\n')
    records=[]
    for command,token,label in (([str(java_home/'bin/java'),'-version'],'17.0.21','java-version'),([str(maven_home/'bin/mvn'),'-version'],'Apache Maven 3.9.11','maven-version')):
        r=run_process(command,source,env,budget,label)
        if token not in r['stdout']: raise BuildError('TOOLS: unexpected version')
        records.append({k:v for k,v in r.items() if k!='stdout'})
    base=[str(maven_home/'bin/mvn'),'-B','-ntp','-s',str(settings),'-Dmaven.repo.local='+str(m2),'-Dmaven.test.skip=true','-DskipITs','-Djacoco.skip=true']
    commands=[base+['-Pno-ibm-mq','package'],base+['-f','integrations/swarm-product-test/pom.xml','package']]
    for i,cmd in enumerate(commands):
        r=run_process(cmd,source,env,budget,'package-'+str(i+1)); records.append({k:v for k,v in r.items() if k!='stdout'})
    after=source_identity(source,env,budget)
    jars=[source/'target/spec-driven-auto-regression-0.3.2.jar',source/'integrations/swarm-product-test/target/swarm-product-test-1.0.0-cli.jar']
    for p in jars:
        if not p.is_file() or p.is_symlink(): raise BuildError('ARTIFACT: missing or unsafe')
    if sum(p.stat().st_size for p in jars)>255*MIB: raise BuildError('ARTIFACT: delivery cap')
    with zipfile.ZipFile(jars[0]) as jar:
        names=jar.namelist()
        if any('com.ibm.mq.allclient' in n for n in names): raise BuildError('ARTIFACT: IBM payload present')
        prop='BOOT-INF/classes/META-INF/regression/runner-build.properties'
        if jar.getinfo(prop).file_size>4096: raise BuildError('ARTIFACT: variant metadata oversized')
        metadata=jar.read(prop).decode('utf-8')
        if 'variant=no-ibm-mq' not in metadata or 'ibm_mq=unsupported' not in metadata: raise BuildError('ARTIFACT: wrong variant')
    deps,cache_bytes=inventory(m2,budget)
    delivery=fresh_dir(work/'delivery'); artifacts=[]
    for p in jars:
        budget.check(); dst=delivery/p.name; shutil.copyfile(p,dst)
        artifacts.append({'name':p.name,'bytes':dst.stat().st_size,'sha256':digest_file(dst,'sha256',budget)})
    budget.check_storage(); budget.check()
    manifest={'schema':'b13-build-only.v1','status':'BUILT_NOT_TESTED','source_pre':before,'source_post':after,
      'workflow_commit':args.workflow_commit,'driver_sha256':digest_file(Path(__file__),'sha256',budget),
      'run':{k:os.environ.get(k) for k in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','ImageOS','ImageVersion')},
      'tools':{'jdk':jdk,'maven':maven},'commands':[['mvn',*cmd[1:]] for cmd in commands],
      'process_results':records,'elapsed_seconds':round(time.monotonic()-budget.start,6),
      'resource_limits':{'driver_seconds':840,'cleanup_reserve_seconds':30,'aggregate_rss_bytes':2048*MIB,'storage_bytes':2048*MIB,'cache_bytes':1024*MIB,'per_process_output_bytes':MIB,'delivery_bytes':256*MIB,'manifest_bytes':MIB,'entry_cap':50000},
      'observed':{'peak_process_group_rss_bytes':budget.peak_rss,'peak_logical_storage_bytes':budget.peak_storage,'cache_bytes':cache_bytes},
      'dependencies':deps,'artifacts':artifacts,'packaged_variant':metadata.strip(),
      'acceptance':{'framework_module':'NOT_RUN','P04':'NOT_RUN','SIT':'NOT_RUN','coverage_credit':0,'tests':'SKIPPED','reproducibility_proven':False}}
    write_manifest(delivery,manifest)
    print(json.dumps({'status':'BUILT_NOT_TESTED','artifacts':artifacts,'manifest_sha256':digest_file(delivery/'build-manifest.json','sha256',budget)}))

if __name__=='__main__':
    try: main()
    except Exception as e:
        print(json.dumps({'status':'FAILED_CLOSED','reason':type(e).__name__+': '+str(e)}))
        raise SystemExit(1)

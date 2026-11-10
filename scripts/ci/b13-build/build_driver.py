#!/usr/bin/env python3
"""Bounded fixed-source focused unit tests and packaging; no deployment."""
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
import xml.etree.ElementTree as ET

SOURCE = '6d65b300a673adac70821fd53206dde96b399dfe'
DIAGNOSTIC_CONTEXT = None
TREE = '38952890ac9d97e25315a69a6bef982c42b73a1c'
JDK_URL = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz'
JDK_SHA256 = '8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711'
MAVEN_URL = 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.tar.gz'
MAVEN_SHA512 = 'bcfe4fe305c962ace56ac7b5fc7a08b87d5abd8b7e89027ab251069faebee516b0ded8961445d6d91ec1985dfe30f8153268843c89aa392733d1a3ec956c9978'
MIB = 1024 * 1024

ROOT_TESTS = {
    'com.specdriven.regression.provider.grpc.A01CatalogProfileTest': 23,
    'com.specdriven.regression.provider.grpc.A01CatalogProofTest': 38,
    'com.specdriven.regression.provider.grpc.A01CatalogOwnerTest': 5,
    'com.specdriven.regression.producttest.P04DescriptorAdmissionTest': 2,
    'com.specdriven.regression.producttest.ReviewedGrpcCapabilityTest': 3,
    'com.specdriven.regression.contract.v03.ReviewedGrpcIntegrationTest': 31,
    'com.specdriven.regression.provider.grpc.RouteGuideProfileTest': 32,
    'com.specdriven.regression.provider.grpc.GrpcClientExternalEndpointSupportTest': 2,
    'com.specdriven.regression.provider.grpc.GrpcMetadataDeadlineContractTest': 8,
    'com.specdriven.regression.evidence.ReviewedGrpcProofIdentityTest': 62,
    'com.specdriven.regression.provider.grpc.P04OwnedStreamingTest': 1,
    'com.specdriven.regression.provider.grpc.P04FourCardinalityTest': 1,
}
MODULE_TESTS = {
    'com.featuredeliveryintelligence.fdi.producttest.A01CatalogProofTest': 38,
    'com.featuredeliveryintelligence.fdi.producttest.A01LaunchArgumentsTest': 14,
    'com.featuredeliveryintelligence.fdi.producttest.GrpcDescriptorAdmissionProofTest': 1,
    'com.featuredeliveryintelligence.fdi.producttest.GrpcBindingTest': 1,
    'com.featuredeliveryintelligence.fdi.producttest.GrpcOwnedStreamingProofTest': 1,
    'com.featuredeliveryintelligence.fdi.producttest.GrpcFourCardinalityProofTest': 4,
}

class BuildError(RuntimeError): pass

def failure_tail(output):
    text = bytes(output[-8192:]).decode('utf-8', 'replace')
    encoded = json.dumps(text, ensure_ascii=True)
    # This quoted tail is embedded in reason, which main JSON-serializes again.
    while len(json.dumps(encoded, ensure_ascii=True).encode('utf-8')) > 8192:
        text = text[max(1, len(text)//4):]
        encoded = json.dumps(text, ensure_ascii=True)
    return encoded

def validate_junit(folder, expected, budget, max_bytes=4*MIB):
    folder = Path(folder)
    if not folder.is_dir() or folder.is_symlink(): raise BuildError('JUNIT: report directory missing/unsafe')
    paths = []
    for p in folder.iterdir():
        budget.check()
        if p.name.startswith('TEST-') and p.suffix == '.xml':
            paths.append(p)
            if len(paths) > len(expected): raise BuildError('JUNIT: excess XML reports')
    wanted = {'TEST-'+name+'.xml' for name in expected}
    if {p.name for p in paths} != wanted: raise BuildError('JUNIT: missing/foreign report class')
    reports = []; identities = set(); total_bytes = 0; counts = {}
    for path in sorted(paths):
        budget.check()
        if path.is_symlink() or not path.is_file(): raise BuildError('JUNIT: unsafe report type')
        if path.stat().st_size > 512*1024: raise BuildError('JUNIT: report byte cap')
        with path.open('rb') as handle: data = handle.read(512*1024+1)
        total_bytes += len(data)
        if len(data) > 512*1024 or total_bytes > max_bytes: raise BuildError('JUNIT: aggregate/report byte cap')
        try: text = data.decode('utf-8')
        except UnicodeDecodeError as error: raise BuildError('JUNIT: non-UTF8 report') from error
        if '<!DOCTYPE' in text.upper() or '<!ENTITY' in text.upper(): raise BuildError('JUNIT: forbidden XML declaration')
        try: suite = ET.fromstring(text)
        except ET.ParseError as error: raise BuildError('JUNIT: invalid XML') from error
        if suite.tag != 'testsuite': raise BuildError('JUNIT: testsuite root required')
        classname = path.name[len('TEST-'):-len('.xml')]
        if suite.get('name') not in (None, classname): raise BuildError('JUNIT: suite identity mismatch')
        cases = suite.findall('testcase')
        if len(list(suite.iter('testcase'))) != len(cases): raise BuildError('JUNIT: nested testcase')
        if any(list(suite.iter(tag)) for tag in ('failure', 'error', 'skipped')): raise BuildError('JUNIT: nonpassing node')
        for key, value in {'tests': len(cases), 'failures': 0, 'errors': 0, 'skipped': 0}.items():
            if suite.get(key) != str(value): raise BuildError('JUNIT: counter mismatch')
        if len(cases) != expected[classname]:
            raise BuildError('JUNIT: unexpected testcase count: %s expected=%s actual=%s' % (classname, expected[classname], len(cases)))
        names = []
        for case in cases:
            name = case.get('name'); identity = (case.get('classname'), name)
            if identity[0] != classname or not name or identity in identities:
                raise BuildError('JUNIT: foreign/duplicate testcase identity')
            identities.add(identity); names.append(name)
        counts[classname] = len(cases)
        reports.append({'file': path.name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(),
                        'classname': classname, 'testcases': names, 'tests': len(cases),
                        'failures': 0, 'errors': 0, 'skipped': 0})
    return {'status': 'PASS', 'testcase_count': len(identities), 'class_counts': counts,
            'xml_bytes': total_bytes, 'reports': reports}

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
                live,rss=group_state(proc.pid); budget.peak_rss=max(budget.peak_rss,rss)
                if rss>budget.rss_cap: raise BuildError('MEMORY: process group RSS cap')
                descendants=[pid for pid in live if pid!=proc.pid]
                if descendants:
                    raise BuildError('CLEANUP: descendant after parent exit; %s=%s; count=%s; pids=%s; output_tail=%s' %
                                     (label,proc.returncode,len(descendants),descendants[:3],failure_tail(output)))
                if not sel.get_map(): break
        if proc.returncode: raise BuildError('EXIT: %s=%s; output_tail=%s' % (label,proc.returncode,failure_tail(output)))
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

def read_framework_metadata(path):
    # Boot 3.5.16 keeps this META-INF resource at the archive root.
    # Match b13 RunnerBuildVariant.parse; do not accept a guessed fallback path.
    prop='META-INF/regression/runner-build.properties'
    with zipfile.ZipFile(path) as jar:
        names=jar.namelist()
        if any('com.ibm.mq.allclient' in n for n in names): raise BuildError('ARTIFACT: IBM payload present')
        if names.count(prop)!=1 or 'BOOT-INF/classes/'+prop in names:
            candidates=[n[:200] for n in names if n.endswith('runner-build.properties')][:8]
            raise BuildError('ARTIFACT: missing/duplicate descriptor; candidates='+json.dumps(candidates))
        if jar.getinfo(prop).file_size>4096: raise BuildError('ARTIFACT: variant metadata oversized')
        try: metadata=jar.read(prop).decode('utf-8')
        except UnicodeDecodeError as error: raise BuildError('ARTIFACT: malformed descriptor text') from error
        values={}
        for line in metadata.replace('\r\n','\n').split('\n'):
            if not line: continue
            key,separator,value=line.partition('=')
            if not separator or not key or key in values: raise BuildError('ARTIFACT: malformed/duplicate descriptor key')
            values[key]=value
        if values!={'schema':'runner-build.v1','variant':'no-ibm-mq','ibm_mq':'unsupported'}:
            raise BuildError('ARTIFACT: wrong descriptor shape or variant')
    return metadata

def write_manifest(delivery,manifest,max_bytes=MIB):
    data=(json.dumps(manifest,sort_keys=True,indent=2)+'\n').encode()
    if len(data)>max_bytes: raise BuildError('MANIFEST: byte cap')
    target=Path(delivery)/'build-manifest.json'
    if target.exists(): raise BuildError('EXISTS: manifest')
    tmp=Path(delivery)/'build-manifest.json.partial'
    with tmp.open('xb') as f: f.write(data)
    tmp.replace(target)


OWNER_BYTES=32*MIB
OWNER_PACKAGE='com/specdriven/regression/provider/grpc/'
OWNER_CLASSES=[('target/test-classes',OWNER_PACKAGE+n+'.class') for n in ('A01CatalogOwner','A01CatalogOwner$Lifecycle')]+[('target/classes',OWNER_PACKAGE+n+'.class') for n in ('ReviewedA01CatalogPolicy','ReviewedA01CatalogProof','ReviewedLoopbackGrpcPolicy','ReviewedLoopbackGrpcPolicy$Profile','ReviewedLoopbackGrpcPolicy$Fixture','GrpcDescriptorSupport','GrpcDescriptorSupport$StreamingDescriptorException')]
OWNER_DEPS=['com/fasterxml/jackson/core/jackson-annotations/2.22/jackson-annotations-2.22.jar','com/fasterxml/jackson/core/jackson-core/2.22.0/jackson-core-2.22.0.jar','com/fasterxml/jackson/core/jackson-databind/2.22.0/jackson-databind-2.22.0.jar','com/google/protobuf/protobuf-java/4.33.4/protobuf-java-4.33.4.jar']

def validate_owner_jar(path):
    if path.is_symlink() or not path.is_file(): raise BuildError('OWNER: unsafe jar')
    with zipfile.ZipFile(path) as jar:
        infos=jar.infolist(); names=[x.filename for x in infos]
        if len(names)>20000 or len(set(names))!=len(names): raise BuildError('OWNER: duplicate/too many entries')
        for info in infos:
            if info.filename.startswith('/') or '..' in Path(info.filename).parts or info.flag_bits&1 or stat.S_IFMT(info.external_attr>>16) not in (0,stat.S_IFREG,stat.S_IFDIR): raise BuildError('OWNER: unsafe jar entry')
        if 'META-INF/MANIFEST.MF' in names:
            info=jar.getinfo('META-INF/MANIFEST.MF')
            if info.file_size>65536: raise BuildError('OWNER: manifest cap')
            text=jar.read(info).decode('utf-8').replace('\r\n','\n').replace('\n ','')
            if any(line.partition(':')[0].lower()=='class-path' for line in text.splitlines()): raise BuildError('OWNER: external Class-Path')

def package_owner(source,m2,delivery,budget):
    inputs=[]; names=[]; class_bytes=0
    for origin,entry in OWNER_CLASSES:
        budget.check(); p=source/origin/entry
        if any(x.is_symlink() for x in [p,*p.parents] if x!=source.parent) or not p.is_file() or p.stat().st_size>2*MIB: raise BuildError('OWNER: missing/unsafe/oversized class')
        class_bytes+=p.stat().st_size; inputs.append(p); names.append(entry)
    for origin,entry in OWNER_CLASSES:
        if '$' in Path(entry).name: continue
        p=source/origin/entry; stem=p.stem
        expected={Path(e).name for o,e in OWNER_CLASSES if o==origin and (Path(e).stem==stem or Path(e).stem.startswith(stem+'$'))}
        observed={x.name for x in p.parent.iterdir() if x.name==stem+'.class' or (x.name.startswith(stem+'$') and x.name.endswith('.class'))}
        if observed!=expected: raise BuildError('OWNER: extra/missing class closure')
    dependencies=[m2/rel for rel in OWNER_DEPS]
    for p in dependencies:
        if any(x.is_symlink() for x in [p,*p.parents] if x!=m2.parent): raise BuildError('OWNER: symlink dependency path')
        validate_owner_jar(p)
    if len(dependencies)+1>8 or class_bytes+sum(p.stat().st_size for p in dependencies)>OWNER_BYTES: raise BuildError('OWNER: aggregate cap')
    folder=fresh_dir(delivery/'owner'); support=folder/'owner-support.jar'
    with zipfile.ZipFile(support,'x',compression=zipfile.ZIP_STORED) as jar:
        for p,entry in zip(inputs,names):
            budget.check(); info=zipfile.ZipInfo(entry); info.external_attr=0o100644<<16; jar.writestr(info,p.read_bytes())
    validate_owner_jar(support)
    output=[support]
    for p in dependencies:
        budget.check(); target=folder/p.name; shutil.copyfile(p,target); output.append(target); budget.check_storage()
    if sum(p.stat().st_size for p in output)>OWNER_BYTES: raise BuildError('OWNER: aggregate cap')
    rows=[{'path':p.relative_to(delivery).as_posix(),'bytes':p.stat().st_size,'sha256':digest_file(p,'sha256',budget)} for p in output]
    return {'class_entries':names,'class_inputs':[{'path':p.relative_to(source).as_posix(),'sha256':digest_file(p,'sha256',budget)} for p in inputs],'dependency_paths':OWNER_DEPS,'jars':rows,'classpath_order':[r['path'] for r in rows]}

def copy_junit(source,delivery,junit,budget):
    folder=fresh_dir(delivery/'junit'); records=[]
    for label,rel in [('root','target/surefire-reports'),('module','integrations/swarm-product-test/target/surefire-reports')]:
        dest=fresh_dir(folder/label)
        for report in junit[label]['reports']:
            budget.check(); src=source/rel/report['file']; target=dest/report['file']; shutil.copyfile(src,target)
            if target.stat().st_size!=report['bytes'] or digest_file(target,'sha256',budget)!=report['sha256']: raise BuildError('JUNIT: copied report mismatch')
            records.append({'path':target.relative_to(delivery).as_posix(),'bytes':report['bytes'],'sha256':report['sha256']}); budget.check_storage()
    return records

def collect_diagnostics(source,work,budget,reason):
    folder=fresh_dir(work/'diagnostics'); rows=[]; omitted=[]; total=0
    for label,rel,expected in [('root','target/surefire-reports',ROOT_TESTS),('module','integrations/swarm-product-test/target/surefire-reports',MODULE_TESTS)]:
        dest=fresh_dir(folder/label); report_root=source/rel
        if any(x.is_symlink() for x in [report_root,*report_root.parents] if x!=source.parent): omitted.append(label+': unsafe directory'); continue
        for cls in expected:
            budget.check(); name='TEST-'+cls+'.xml'; p=report_root/name
            if not p.exists() and not p.is_symlink(): continue
            if p.is_symlink() or not p.is_file() or p.stat().st_size>512*1024: omitted.append(label+'/'+name); continue
            data=p.read_bytes()
            if len(data)>512*1024 or total+len(data)>4*MIB: omitted.append(label+'/'+name); continue
            total+=len(data); (dest/name).write_bytes(data); rows.append({'path':label+'/'+name,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()}); budget.check_storage()
    record={'status':'FAILED_DIAGNOSTICS_ONLY','reason':failure_tail(reason.encode()),'reports':rows,'omitted':omitted,'jars_eligible':False}
    data=(json.dumps(record,sort_keys=True,indent=2)+'\n').encode()
    if len(data)>65536: raise BuildError('DIAGNOSTICS: status cap')
    (folder/'failure.json').write_bytes(data); budget.check_storage(); return record

def main():
    global DIAGNOSTIC_CONTEXT
    if len(SOURCE)!=40 or len(TREE)!=40: raise BuildError('SOURCE: reviewed publication binding pending')
    parser=argparse.ArgumentParser()
    parser.add_argument('--source',type=Path,required=True)
    parser.add_argument('--work',type=Path,required=True)
    parser.add_argument('--workflow-commit',required=True)
    args=parser.parse_args()
    if len(args.workflow_commit)!=40 or any(c not in '0123456789abcdef' for c in args.workflow_commit): raise BuildError('WORKFLOW: invalid commit')
    source=args.source.resolve(); work=args.work.resolve()
    if source==work or source.is_relative_to(work) or work.is_relative_to(source): raise BuildError('ROOTS: must be disjoint')
    fresh_dir(work)
    budget=Budget([source,work,Path(__file__).resolve().parents[3]]); env={'PATH':os.defpath,'HOME':str(fresh_dir(work/'home')),'LANG':'C.UTF-8','CI':'true'}
    DIAGNOSTIC_CONTEXT=(source,work,budget)
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
    base=[str(maven_home/'bin/mvn'),'-B','-ntp','-s',str(settings),'-Dmaven.repo.local='+str(m2),'-DskipITs','-Djacoco.skip=true']
    test_base=base+['-DfailIfNoTests=true','-Dsurefire.failIfNoSpecifiedTests=true']
    test_specs=[('root', ROOT_TESTS, source/'target/surefire-reports', ['-Pno-ibm-mq']),
                ('module', MODULE_TESTS, source/'integrations/swarm-product-test/target/surefire-reports',
                 ['-f','integrations/swarm-product-test/pom.xml'])]
    commands=[]; junit={}
    for label, expected, report_dir, options in test_specs:
        # The entire target was absent before any Maven command; also refuse a stale report directory.
        if report_dir.exists() or report_dir.is_symlink(): raise BuildError('JUNIT: stale report directory')
        selector=','.join(expected)
        cmd=test_base+options+['-Dtest='+selector,'test']; commands.append(cmd)
        r=run_process(cmd,source,env,budget,'focused-tests-'+label)
        records.append({k:v for k,v in r.items() if k!='stdout'})
        junit[label]=validate_junit(report_dir,expected,budget,4*MIB-sum(v['xml_bytes'] for v in junit.values()))
    package_commands=[base+['-Dmaven.test.skip=true','-Pno-ibm-mq','package'],
                      base+['-Dmaven.test.skip=true','-f','integrations/swarm-product-test/pom.xml','package']]
    for i,cmd in enumerate(package_commands):
        commands.append(cmd)
        r=run_process(cmd,source,env,budget,'package-'+str(i+1)); records.append({k:v for k,v in r.items() if k!='stdout'})
    # Preserve the exact accepted test reports through packaging.
    for label, expected, report_dir, _ in test_specs:
        if validate_junit(report_dir,expected,budget) != junit[label]: raise BuildError('JUNIT: reports changed after validation')
    after=source_identity(source,env,budget)
    jars=[source/'target/spec-driven-auto-regression-0.3.2.jar',source/'integrations/swarm-product-test/target/swarm-product-test-1.0.0-cli.jar']
    for p in jars:
        if not p.is_file() or p.is_symlink(): raise BuildError('ARTIFACT: missing or unsafe')
    if sum(p.stat().st_size for p in jars)>255*MIB: raise BuildError('ARTIFACT: delivery cap')
    metadata=read_framework_metadata(jars[0])
    deps,cache_bytes=inventory(m2,budget)
    delivery=fresh_dir(work/'delivery'); artifacts=[]
    for p in jars:
        budget.check(); dst=delivery/p.name; shutil.copyfile(p,dst)
        artifacts.append({'name':p.name,'bytes':dst.stat().st_size,'sha256':digest_file(dst,'sha256',budget)})
    owner_support=package_owner(source,m2,delivery,budget)
    junit_artifacts=copy_junit(source,delivery,junit,budget)
    artifacts+=owner_support['jars']+junit_artifacts
    if sum(row['bytes'] for row in artifacts)>255*MIB: raise BuildError('ARTIFACT: aggregate delivery cap')
    budget.check_storage(); budget.check()
    manifest={'schema':'a01-focused-java-build.v1','status':'FOCUSED_TESTS_PASSED_BUILT','source_pre':before,'source_post':after,
      'workflow_commit':args.workflow_commit,'driver_sha256':digest_file(Path(__file__),'sha256',budget),
      'run':{k:os.environ.get(k) for k in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','ImageOS','ImageVersion')},
      'tools':{'jdk':jdk,'maven':maven},'commands':[['mvn',*cmd[1:]] for cmd in commands],
      'owner_support':owner_support,'process_results':records,'focused_junit':junit,'elapsed_seconds':round(time.monotonic()-budget.start,6),
      'resource_limits':{'driver_seconds':840,'cleanup_reserve_seconds':30,'aggregate_rss_bytes':2048*MIB,'storage_bytes':2048*MIB,'cache_bytes':1024*MIB,'per_process_output_bytes':MIB,'junit_per_report_bytes':512*1024,'junit_total_bytes':4*MIB,'failure_tail_json_bytes':8192,'delivery_bytes':256*MIB,'manifest_bytes':MIB,'entry_cap':50000},
      'observed':{'peak_process_group_rss_bytes':budget.peak_rss,'peak_logical_storage_bytes':budget.peak_storage,'cache_bytes':cache_bytes},
      'dependencies':deps,'artifacts':artifacts,'packaged_variant':metadata.strip(),
      'packaged_variant_entry':'META-INF/regression/runner-build.properties',
      'packaged_variant_sha256':hashlib.sha256(metadata.encode('utf-8')).hexdigest(),
      'acceptance':{'framework_module':'NOT_RUN','P04':'NOT_RUN','A01':'NOT_RUN','SIT':'NOT_RUN','coverage_credit':0,'tests':'FOCUSED_UNIT_TESTS_ONLY','reproducibility_proven':False}}
    write_manifest(delivery,manifest)
    budget.check_storage(); budget.check()
    print(json.dumps({'status':'FOCUSED_TESTS_PASSED_BUILT','focused_tests':{k:v['testcase_count'] for k,v in junit.items()},'artifacts':artifacts,'manifest_sha256':digest_file(delivery/'build-manifest.json','sha256',budget)}))

if __name__=='__main__':
    try: main()
    except Exception as e:
        if DIAGNOSTIC_CONTEXT is not None:
            try: collect_diagnostics(*DIAGNOSTIC_CONTEXT,str(e))
            except Exception as diagnostic_error: print(json.dumps({'diagnostics':'INCOMPLETE','reason':failure_tail(str(diagnostic_error).encode())}))
        print(json.dumps({'status':'FAILED_CLOSED','reason':type(e).__name__+': '+str(e)}))
        raise SystemExit(1)

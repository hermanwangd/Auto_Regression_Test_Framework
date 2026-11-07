"""Exact runtime-only context, independently pinned accepted producer supplied by root."""
import hashlib,json,os,re,shutil,tempfile,zipfile
from pathlib import Path
NAMES={'Dockerfile','.dockerignore','entrypoint.sh','framework.jar','run-sit-postdeploy.py','runtime-source-manifest.json'}
def require(ok,msg):
 if not ok:raise ValueError(msg)
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def prepare(jar,expected_pin,wrapper,bases,destination,producer):
 jar,wrapper,dest=Path(jar),Path(wrapper),Path(destination)
 require(not dest.exists(),'context_exists');require(not jar.is_symlink() and jar.is_file() and not wrapper.is_symlink() and wrapper.is_file(),'unsafe_runtime_input')
 require(re.fullmatch('[a-f0-9]{64}',expected_pin or '') and sha(jar)==expected_pin,'accepted_jar_pin_mismatch');require(jar.stat().st_size<=128*1024**2 and wrapper.stat().st_size<=1024**2,'runtime_input_size')
 with zipfile.ZipFile(jar) as z:require('BOOT-INF/classes/com/specdriven/regression/runner/RunnerCommand.class' in z.namelist(),'corrected_d2_cli_class_missing')
 for name,repo in [('python','docker.io/library/python'),('jre','docker.io/library/eclipse-temurin')]:require(re.fullmatch(re.escape(repo)+'@sha256:[a-f0-9]{64}',bases.get(name,'')),'immutable_official_base_required')
 dest.parent.mkdir(parents=True,exist_ok=True)
 with tempfile.TemporaryDirectory(prefix='runtime-context-',dir=dest.parent) as tmp:
  temp=Path(tmp)
  dockerfile=(Path(__file__).parent/'Dockerfile.template').read_text().replace('${JRE_BASE}',bases['jre']).replace('${PYTHON_BASE}',bases['python'])
  (temp/'Dockerfile').write_text(dockerfile);(temp/'.dockerignore').write_text('**\n'+''.join('!'+n+'\n' for n in sorted(NAMES)))
  shutil.copyfile(Path(__file__).parent/'entrypoint.sh',temp/'entrypoint.sh');shutil.copyfile(jar,temp/'framework.jar');shutil.copyfile(wrapper,temp/'run-sit-postdeploy.py')
  manifest={'schema':'runtime-source-manifest.v1','producer':producer,'framework_jar_sha256':expected_pin,'wrapper_sha256':sha(wrapper),'base_pins':bases,'context_allowlist':sorted(NAMES),'external_inputs':'NOT_BAKED_IN','source_files':{p.name:{'sha256':sha(p),'bytes':p.stat().st_size} for p in temp.iterdir()}}
  (temp/'runtime-source-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n');require({p.name for p in temp.iterdir()}==NAMES,'context_allowlist_violation')
  os.mkdir(dest)
  for p in temp.iterdir():shutil.copyfile(p,dest/p.name)
 return manifest

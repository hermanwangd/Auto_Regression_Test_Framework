#!/usr/bin/env python3
"""Stage only digest-admitted SAVED collector artifacts; never a product verdict gate."""
import argparse,hashlib,importlib.util,json,os,stat
from pathlib import Path
_contract=Path(__file__).resolve().parents[1]/'cd/run-sit-postdeploy.py'
_spec=importlib.util.spec_from_file_location('existing_cd_contract',_contract)
cd=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(cd)
TOTAL=64*1024*1024

def digest(raw):return hashlib.sha256(raw).hexdigest()
def sync(path):
 fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY)
 try:os.fsync(fd)
 finally:os.close(fd)
def read(root,name,limit):
 p=root.joinpath(*cd.relative(name).parts)
 cd.require(p.stat(follow_symlinks=False).st_nlink==1,'Hardlinked artifacts prohibited')
 return cd.read_file(root,name,limit)
def write(root,name,raw):
 p=root.joinpath(*cd.relative(name).parts)
 parent=root
 for component in cd.relative(name).parts[:-1]:
  child=parent/component
  if not child.exists():child.mkdir(mode=0o700);sync(parent);sync(child)
  cd.require(child.is_dir() and not child.is_symlink(),'Unsafe staging directory')
  parent=child
 with p.open('xb') as out:out.write(raw);out.flush();os.fsync(out.fileno())
 cd.require(p.read_bytes()==raw,'Staged artifact changed');sync(p.parent)
def stage(source,expected_receipt_sha256,destination):
 source=Path(source);destination=Path(destination)
 cd.require(not destination.exists() and not destination.is_symlink(),'Fresh staging destination required')
 for p in destination.parents:cd.require(not p.is_symlink(),'Symlink staging parent prohibited')
 destination.mkdir(mode=0o700);sync(destination.parent);sync(destination)
 result={'schema_version':'runner-ci-stage.v1','status':'BLOCKED','claim':'BYTE_STAGING_ONLY_NOT_VERDICT_OR_RUNTIME_ACCEPTANCE','files':[]}
 try:
  cd.require(isinstance(expected_receipt_sha256,str) and cd.SHA.fullmatch(expected_receipt_sha256),'Independent receipt digest required')
  receipt_raw=read(source,'collection.json',2*1024*1024);cd.require(digest(receipt_raw)==expected_receipt_sha256,'Collector receipt digest mismatch')
  receipt=cd.document(receipt_raw);cd.require(receipt.get('schema_version')=='runner-collection-receipt.v1' and receipt.get('publication')=='SAVED','Saved collector receipt required')
  entries=receipt.get('files');cd.require(isinstance(entries,list) and len(entries)<=4096,'Finite collector catalog required')
  catalog={};total=len(receipt_raw)
  for entry in entries:
   cd.require(isinstance(entry,dict),'Invalid collector entry')
   if entry.get('state')!='SAVED':continue
   name=entry.get('path');cd.relative(name);cd.require(name not in catalog and name not in ('collection.json','stage-result.json','STAGE.complete'),'Duplicate/reserved artifact path')
   role=entry.get('role');cd.require(role in {'canonical','index','summary','evidence','report','coverage_json','coverage_md','code_coverage','log','runtime_status'},'Unapproved collector output role')
   cd.require(not any(p.lower() in {'.ssh','.git','.aws','.env','secrets','credentials','kubeconfig'} for p in cd.relative(name).parts),'Private configuration artifact excluded')
   limit=8*1024*1024 if role=='canonical' else 4*1024*1024
   raw=read(source,name,limit);cd.require(type(entry.get('saved_bytes')) is int and len(raw)==entry['saved_bytes'] and digest(raw)==entry.get('saved_sha256'),'Saved artifact bytes/digest mismatch')
   total+=len(raw);cd.require(total<=TOTAL,'Staging total byte limit');catalog[name]=raw
  derived=receipt.get('controller_derived_coverage')
  if derived is not None:
   cd.require(isinstance(derived,dict),'Invalid derived pair identity')
   for name,key in [('controller-derived/coverage/coverage.json','json_sha256'),('controller-derived/coverage/coverage.md','md_sha256')]:
    raw=read(source,name,8*1024*1024);cd.require(digest(raw)==derived.get(key),'Derived coverage pair digest mismatch');total+=len(raw);cd.require(total<=TOTAL,'Staging total byte limit');catalog[name]=raw
  # Aliases without a receipt-bound digest are not trusted/staged. The immutable
  # original receipt paths plus saved SHA already identify every admitted file.
  catalog['collection.json']=receipt_raw
  for name,raw in catalog.items():write(destination,name,raw);result['files'].append({'path':name,'bytes':len(raw),'sha256':digest(raw)})
  result.update(status='STAGED',run_id=receipt.get('run_id'),source_collection=receipt.get('collection'),framework_verdict=receipt.get('framework_verdict'),source_receipt_sha256=expected_receipt_sha256,aliases='NOT_STAGED_UNBOUND_DIGEST')
 except (cd.Blocked,OSError,ValueError,TypeError,KeyError) as error:result['reason']='STAGING_REFUSED_'+type(error).__name__
 raw=(json.dumps(result,sort_keys=True,indent=2)+'\n').encode();write(destination,'stage-result.json',raw)
 if result['status']=='STAGED':write(destination,'STAGE.complete',b'SAFE_BYTES_STAGED_NOT_PRODUCT_VERDICT\n')
 return result

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);p.add_argument('--expected-receipt-sha256',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 try:r=stage(a.source,a.expected_receipt_sha256,a.output)
 except (cd.Blocked,OSError,ValueError,TypeError) as error:print(json.dumps({'status':'BLOCKED','reason':'STAGING_REFUSED_'+type(error).__name__}));return 1
 print(json.dumps(r,sort_keys=True));return 0 if r['status']=='STAGED' else 1
if __name__=='__main__':raise SystemExit(main())

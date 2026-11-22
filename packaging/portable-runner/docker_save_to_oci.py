"""Bounded Docker-save format conversion. Never extracts filesystem layer contents."""
import hashlib,io,json,os,re,shutil,tarfile,tempfile
from pathlib import Path,PurePosixPath
DIGEST=re.compile(r'sha256:[a-f0-9]{64}');LAYER='application/vnd.oci.image.layer.v1.tar';CONFIG='application/vnd.oci.image.config.v1+json';MANIFEST='application/vnd.oci.image.manifest.v1+json'
def require(ok,reason):
 if not ok:raise ValueError(reason)
def sha(data):return hashlib.sha256(data).hexdigest()
def strict(data):
 def pairs(items):
  out={}
  for k,v in items:require(k not in out,'duplicate_json_key');out[k]=v
  return out
 return json.loads(data,object_pairs_hook=pairs,parse_constant=lambda _:require(False,'nonfinite_json'))
def encoded(value):return json.dumps(value,separators=(',',':'),sort_keys=True).encode()
def syncdir(path):
 fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY)
 try:os.fsync(fd)
 finally:os.close(fd)
def mkdir(path):
 if path.exists():require(path.is_dir() and not path.is_symlink(),'unsafe_directory');return
 mkdir(path.parent);path.mkdir();syncdir(path);syncdir(path.parent)
def write(path,data):
 mkdir(path.parent)
 with path.open('xb') as f:f.write(data);f.flush();os.fsync(f.fileno())
 syncdir(path.parent)
def safe_name(name):
 require(isinstance(name,str) and name and '\\' not in name and not name.startswith('/'),'unsafe_tar_path')
 value=name.removesuffix('/')
 require(all(p not in ('','.','..') for p in value.split('/')),'unsafe_tar_path')
 require(str(PurePosixPath(value))==value,'noncanonical_tar_path');return value
class Limited:
 def __init__(self,source,limit):self.source=source;self.limit=limit;self.count=0
 def read(self,n=-1):
  if n<0:n=self.limit-self.count+1
  data=self.source.read(min(n,self.limit-self.count+1));self.count+=len(data);require(self.count<=self.limit,'archive_total_limit');return data
 def close(self):pass

def read_tar(source,spool,max_total,max_layer,max_meta,max_entries,resource_guard=None):
 limited=Limited(source,max_total);files={};seen=set();count=0
 with tarfile.open(fileobj=limited,mode='r|') as archive:
  for member in archive:
   if resource_guard:resource_guard()
   count+=1;require(count<=max_entries,'archive_entry_limit');name=safe_name(member.name);require(name not in seen,'duplicate_tar_name');seen.add(name)
   if member.isdir():continue
   require(member.isreg() and not member.issym() and not member.islnk(),'tar_link_or_special_member')
   require(0<=member.size<=max_layer,'layer_size_limit')
   if name in ('manifest.json','repositories','index.json','oci-layout') or name.endswith('/json') or name.endswith('/VERSION'):require(member.size<=max_meta,'metadata_size_limit')
   dest=spool/str(count);digest=hashlib.sha256();remaining=member.size
   with dest.open('xb') as output:
    stream=archive.extractfile(member)
    while remaining:
     if resource_guard:resource_guard()
     data=stream.read(min(1024*1024,remaining));require(bool(data),'truncated_tar_member');output.write(data);digest.update(data);remaining-=len(data)
    output.flush();os.fsync(output.fileno())
   files[name]={'path':dest,'size':member.size,'sha256':digest.hexdigest()}
  # Require both zero end records; tarfile alone also accepts a truncated outer trailer.
  require(archive.fileobj.tell()>=archive.offset+512,'missing_first_tar_end_record')
  trailer=0
  # Consume buffered and remaining trailer, disallow concealed second archives/nonzero data.
  while True:
   rest=archive.fileobj.read(65536)
   if not rest:break
   trailer+=len(rest);require(not rest.strip(b'\0'),'nonzero_tar_trailer')
  require(trailer>=512,'missing_second_tar_end_record')
 return files

def verify_archive(path,expected_config,max_total=2*1024**3,max_layer=1024**3,max_meta=8*1024**2,max_entries=2048,resource_guard=None):
 path=Path(path);require(path.is_file() and not path.is_symlink(),'archive_missing_or_symlink')
 with tempfile.TemporaryDirectory(prefix='oci-verify-',dir=path.parent) as tmp:
  with path.open('rb') as stream:files=read_tar(stream,Path(tmp),max_total,max_layer,max_meta,max_entries,resource_guard)
  def document(name):require(name in files and files[name]['size']<=max_meta,'missing_or_oversized_oci_metadata');return strict(files[name]['path'].read_bytes())
  require(document('oci-layout')=={'imageLayoutVersion':'1.0.0'},'invalid_oci_layout')
  index=document('index.json');require(index.get('schemaVersion')==2 and len(index.get('manifests',[]))==1,'invalid_oci_index')
  required={'oci-layout','index.json'}
  def descriptor(spec,media):
   require(isinstance(spec,dict) and spec.get('mediaType')==media and DIGEST.fullmatch(spec.get('digest','')),'invalid_descriptor')
   name='blobs/sha256/'+spec['digest'][7:];required.add(name);require(name in files,'missing_descriptor_blob');item=files[name]
   require(item['sha256']==spec['digest'][7:] and item['size']==spec.get('size'),'descriptor_digest_or_size_mismatch');return name
  manifest_desc=index['manifests'][0];manifest_name=descriptor(manifest_desc,MANIFEST);manifest=document(manifest_name)
  require(manifest.get('schemaVersion')==2 and manifest.get('mediaType')==MANIFEST,'invalid_oci_manifest')
  config_name=descriptor(manifest['config'],CONFIG);config=document(config_name)
  require(manifest['config']['digest']==expected_config,'config_identity_mismatch')
  require(config.get('os')=='linux' and config.get('architecture')=='amd64','unsupported_platform')
  diffs=config.get('rootfs',{}).get('diff_ids');require(config.get('rootfs',{}).get('type')=='layers' and isinstance(diffs,list) and len(diffs)==len(manifest.get('layers',[])),'rootfs_layer_count')
  for layer,diff in zip(manifest['layers'],diffs):descriptor(layer,LAYER);require(layer['digest']==diff,'rootfs_diffid_mismatch')
  require(set(files)==required,'unreferenced_oci_member')
 return {'manifest_digest':manifest_desc['digest'],'config_digest':expected_config,'archive_sha256':file_sha(path),'archive_bytes':path.stat().st_size,'format':'OCI_IMAGE_LAYOUT_TAR'}
def file_sha(path):
 h=hashlib.sha256()
 with Path(path).open('rb') as f:
  for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
 return h.hexdigest()

def go_json(value):
 # Go encoding/json RawMessage preserves nested field order and HTML escaping.
 return json.dumps(value,ensure_ascii=False,separators=(',',':')).replace('&','\\u0026').replace('<','\\u003c').replace('>','\\u003e').replace('\u2028','\\u2028').replace('\u2029','\\u2029')

def docker28_metadata(files,config,config_name,layers,allowed,max_meta):
 """Moby v28.4 save.go + image/v1.CreateID; admit a closed, bound compatibility graph."""
 modern={'index.json','oci-layout'}&set(files)
 if not modern:return allowed
 require(modern=={'index.json','oci-layout'},'incomplete_docker28_oci_metadata')
 def document(name):
  require(name in files and files[name]['size']<=max_meta,'missing_or_oversized_docker28_metadata');return strict(files[name]['path'].read_bytes())
 require(document('oci-layout')=={'imageLayoutVersion':'1.0.0'},'docker28_layout_invalid')
 index=document('index.json');require(index.get('schemaVersion')==2 and index.get('mediaType')=='application/vnd.oci.image.index.v1+json' and len(index.get('manifests',[]))==1,'docker28_index_invalid')
 def checked_descriptor(desc,media):
  require(isinstance(desc,dict) and desc.get('mediaType')==media and isinstance(desc.get('digest'),str) and DIGEST.fullmatch(desc['digest']),'docker28_descriptor_invalid')
  name='blobs/sha256/'+desc['digest'][7:];require(name in files and files[name]['sha256']==desc['digest'][7:] and files[name]['size']==desc.get('size'),'docker28_descriptor_mismatch');return name
 root_name=checked_descriptor(index['manifests'][0],MANIFEST);root=document(root_name);require(root.get('schemaVersion')==2 and root.get('mediaType')==MANIFEST,'docker28_manifest_invalid')
 require(checked_descriptor(root['config'],CONFIG)==config_name,'docker28_config_join_mismatch');require(len(root.get('layers',[]))==len(layers),'docker28_layers_count')
 for desc,name in zip(root['layers'],layers):require(checked_descriptor(desc,LAYER)==name,'docker28_ordered_layers_mismatch')
 allowed.add(root_name);extras=set(files)-allowed
 if not extras:return allowed
 require(len(extras)==len(layers),'docker28_legacy_count_or_unreferenced_blob')
 nodes={}
 for name in extras:
  item=files[name];require(name=='blobs/sha256/'+item['sha256'],'docker28_legacy_content_address');node=document(name);identity=node.get('id');require(isinstance(identity,str) and re.fullmatch('[a-f0-9]{64}',identity) and identity not in nodes,'docker28_legacy_identity_invalid');nodes[identity]=(name,node)
 zero={'Hostname':'','Domainname':'','User':'','AttachStdin':False,'AttachStdout':False,'AttachStderr':False,'Tty':False,'OpenStdin':False,'StdinOnce':False,'Env':None,'Cmd':None,'Image':'','Volumes':None,'WorkingDir':'','Entrypoint':None,'OnBuild':None,'Labels':None}
 v1keys={'comment','created','container','container_config','docker_version','author','config','architecture','variant','os','Size'}
 parent='';chain='';used=set()
 for i,diff in enumerate(config['rootfs']['diff_ids']):
  chain=diff if not chain else 'sha256:'+sha((chain+' '+diff).encode());matches=[(identity,name,node) for identity,(name,node) in nodes.items() if node.get('parent','')==parent];require(len(matches)==1,'docker28_legacy_parent_graph')
  identity,name,node=matches[0];require(identity not in used and node.get('os')==config['os'] and (bool(parent)==('parent' in node)),'docker28_legacy_cycle_or_os');used.add(identity)
  body={k:v for k,v in node.items() if k not in ('id','parent')}
  if i<len(layers)-1:require(set(body)=={'created','container_config','os'} and body['created']=='1970-01-01T00:00:00Z' and encoded(body['container_config'])==encoded(zero),'docker28_legacy_intermediate_shape')
  else:
   expected={k:v for k,v in config.items() if k in v1keys};expected.setdefault('created',None);expected.setdefault('container_config',zero)
   require(encoded(body)==encoded(expected),'docker28_legacy_final_config_join')
  material=dict(body)
  if i<len(layers)-1:material.pop('os') # save.go sets OS only after CreateID for intermediate zero V1Image.
  material['layer_id']=chain
  if parent:material['parent']='sha256:'+parent
  v1bytes=('{'+','.join(go_json(k)+':'+go_json(material[k]) for k in sorted(material))+'}').encode();require(sha(v1bytes)==identity,'docker28_legacy_diffid_binding');parent=identity;allowed.add(name)
 require(len(used)==len(nodes),'docker28_unreachable_legacy_blob');return allowed

def publish_archive(incomplete,destination,expected_sha):
 incomplete,dest=Path(incomplete),Path(destination);require(not dest.exists() and not dest.is_symlink(),'destination_exists');require(file_sha(incomplete)==expected_sha,'incomplete_archive_changed')
 linked=False
 try:
  os.link(incomplete,dest);linked=True;syncdir(dest.parent);require(file_sha(dest)==expected_sha,'published_archive_changed')
 except BaseException:
  if linked:
   try:
    a,b=incomplete.stat(),dest.stat()
    if (a.st_dev,a.st_ino)==(b.st_dev,b.st_ino):dest.unlink();syncdir(dest.parent)
   except OSError:pass
  raise

def convert(source,destination,expected_config,max_total=2*1024**3,max_layer=1024**3,max_meta=8*1024**2,max_entries=2048,resource_guard=None):
 dest=Path(destination);require(not dest.exists() and not dest.is_symlink(),'destination_exists');require(DIGEST.fullmatch(expected_config or '') is not None,'invalid_expected_config');mkdir(dest.parent)
 with tempfile.TemporaryDirectory(prefix='oci-convert-',dir=dest.parent) as tmp:
  work=Path(tmp);spool=work/'raw';mkdir(spool)
  files=read_tar(source,spool,max_total,max_layer,max_meta,max_entries,resource_guard)
  require('manifest.json' in files and files['manifest.json']['size']<=max_meta,'docker_manifest_missing')
  manifests=strict(files['manifest.json']['path'].read_bytes());require(isinstance(manifests,list) and len(manifests)==1,'exactly_one_docker_image_required');manifest=manifests[0]
  config_name=safe_name(manifest['Config']);layers=manifest['Layers'];require(isinstance(layers,list) and len(layers)<=max_entries,'invalid_docker_layers');require(config_name in files,'docker_config_missing');config_entry=files[config_name]
  require(config_entry['size']<=max_meta and 'sha256:'+config_entry['sha256']==expected_config,'config_identity_mismatch')
  config=strict(config_entry['path'].read_bytes());require(config.get('architecture')=='amd64' and config.get('os')=='linux','unsupported_platform');diffs=config.get('rootfs',{}).get('diff_ids');require(config.get('rootfs',{}).get('type')=='layers' and isinstance(diffs,list) and len(diffs)==len(layers),'rootfs_layer_count')
  allowed={'manifest.json','repositories','index.json','oci-layout',config_name};descriptors=[]
  for name,diff in zip(layers,diffs):
   name=safe_name(name);allowed.add(name);require(name in files,'docker_layer_missing');entry=files[name];require('sha256:'+entry['sha256']==diff,'rootfs_diffid_mismatch')
   with entry['path'].open('rb') as content:prefix=content.read(2)
   require(entry['size']>=1024 and prefix!=b'\x1f\x8b','unsupported_compressed_or_short_layer')
   for suffix in ('json','VERSION'):allowed.add(str(PurePosixPath(name).parent/suffix))
   descriptors.append({'mediaType':LAYER,'digest':diff,'size':entry['size']})
  allowed=docker28_metadata(files,config,config_name,layers,allowed,max_meta)
  require(set(files)<=allowed,'unknown_docker_archive_shape')
  layout=work/'layout';mkdir(layout/'blobs/sha256')
  for name in [config_name,*layers]:
   entry=files[name];target=layout/'blobs/sha256'/entry['sha256']
   if not target.exists():os.rename(entry['path'],target);syncdir(target.parent)
  oci={'schemaVersion':2,'mediaType':MANIFEST,'config':{'mediaType':CONFIG,'digest':expected_config,'size':config_entry['size']},'layers':descriptors}
  mbytes=encoded(oci);mdigest='sha256:'+sha(mbytes);write(layout/'blobs/sha256'/mdigest[7:],mbytes)
  write(layout/'index.json',encoded({'schemaVersion':2,'mediaType':'application/vnd.oci.image.index.v1+json','manifests':[{'mediaType':MANIFEST,'digest':mdigest,'size':len(mbytes),'platform':{'os':'linux','architecture':'amd64'}}]}));write(layout/'oci-layout',encoded({'imageLayoutVersion':'1.0.0'}))
  archive=work/'image.incomplete.tar'
  with archive.open('xb') as output:
   with tarfile.open(fileobj=output,mode='w',format=tarfile.USTAR_FORMAT) as tar:
    for path in sorted(layout.rglob('*')):
     if path.is_file():
      info=tarfile.TarInfo(str(path.relative_to(layout)));info.size=path.stat().st_size;info.mode=0o644;info.uid=info.gid=0;info.mtime=0
      with path.open('rb') as content:tar.addfile(info,content)
   output.flush();os.fsync(output.fileno())
  verified=verify_archive(archive,expected_config,max_total,max_layer,max_meta,max_entries,resource_guard)
  publish_archive(archive,dest,verified['archive_sha256']);return verified

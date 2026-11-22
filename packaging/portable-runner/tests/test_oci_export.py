import hashlib,importlib.util,io,json,tarfile,tempfile,unittest
from pathlib import Path
I=Path(__file__).resolve().parents[1]
def sha(b):return hashlib.sha256(b).hexdigest()
def rawtar(entries):
 out=io.BytesIO()
 with tarfile.open(fileobj=out,mode='w',format=tarfile.USTAR_FORMAT) as t:
  for name,data,kind in entries:
   info=tarfile.TarInfo(name);info.type=kind;info.size=len(data) if kind==tarfile.REGTYPE else 0
   if kind==tarfile.SYMTYPE:info.linkname='/etc/passwd'
   t.addfile(info,io.BytesIO(data) if info.size else None)
 return out.getvalue()
class OciExportTest(unittest.TestCase):
 def setUp(self):
  p=I/'docker_save_to_oci.py';self.assertTrue(p.is_file(),'approved OCI conversion behavior absent')
  spec=importlib.util.spec_from_file_location('oci',p);self.mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.mod)
  self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.out=Path(self.tmp.name)/'image.oci.tar'
  self.layer=rawtar([('public.txt',b'PUBLIC\n',tarfile.REGTYPE)])
  self.config=json.dumps({'architecture':'amd64','os':'linux','rootfs':{'type':'layers','diff_ids':['sha256:'+sha(self.layer)]},'config':{'User':'65532:65532'}}).encode();self.cid='sha256:'+sha(self.config);self.cfg=sha(self.config)+'.json'
  self.manifest=json.dumps([{'Config':self.cfg,'RepoTags':None,'Layers':['one/layer.tar']}]).encode()
  self.entries=[(self.cfg,self.config,tarfile.REGTYPE),('one/layer.tar',self.layer,tarfile.REGTYPE),('manifest.json',self.manifest,tarfile.REGTYPE)]
 def convert(self,entries=None,**kw):return self.mod.convert(io.BytesIO(rawtar(entries or self.entries)),self.out,self.cid,**kw)
 def test_actual_oci_graph_config_diffid_and_archive_hashes(self):
  result=self.convert();self.assertEqual(result['config_digest'],self.cid)
  verified=self.mod.verify_archive(self.out,self.cid);self.assertEqual(verified['manifest_digest'],result['manifest_digest']);self.assertEqual(result['archive_sha256'],sha(self.out.read_bytes()))
  with tarfile.open(self.out) as t:
   names=t.getnames();self.assertIn('oci-layout',names);self.assertIn('index.json',names);self.assertNotIn('manifest.json',names)
 def reject(self,entries=None,**kw):
  with self.assertRaises((ValueError,OSError,tarfile.TarError)):self.convert(entries,**kw)
  self.assertFalse(self.out.exists())
 def test_wrong_config_identity_rejects(self):
  self.cid='sha256:'+'0'*64;self.reject()
 def test_wrong_rootfs_diffid_rejects(self):
  self.config=json.dumps({'architecture':'amd64','os':'linux','rootfs':{'type':'layers','diff_ids':['sha256:'+'0'*64]}}).encode();self.cid='sha256:'+sha(self.config);self.cfg=sha(self.config)+'.json';self.entries[0]=(self.cfg,self.config,tarfile.REGTYPE);self.entries[2]=('manifest.json',json.dumps([{'Config':self.cfg,'Layers':['one/layer.tar']}]).encode(),tarfile.REGTYPE);self.reject()
 def test_missing_layer_rejects(self):self.reject([self.entries[0],self.entries[2]])
 def test_duplicate_name_rejects(self):self.reject(self.entries+[self.entries[0]])
 def test_traversal_rejects(self):self.reject(self.entries+[('../escape',b'x',tarfile.REGTYPE)])
 def test_link_rejects(self):self.reject(self.entries+[('link',b'',tarfile.SYMTYPE)])
 def test_layer_limit_rejects(self):self.reject(max_layer=100)
 def test_total_limit_rejects(self):self.reject(max_total=100)
 def test_entry_limit_rejects(self):self.reject(max_entries=2)
 def test_truncated_stream_rejects(self):
  with self.assertRaises((ValueError,OSError,tarfile.TarError)):self.mod.convert(io.BytesIO(rawtar(self.entries)[:1200]),self.out,self.cid)
  self.assertFalse(self.out.exists())
 def test_unknown_shape_rejects(self):self.reject(self.entries+[('unlisted',b'x',tarfile.REGTYPE)])
 def test_metadata_duplicate_keys_rejects(self):self.entries[2]=('manifest.json',('[{"Config":"'+self.cfg+'","Config":"'+self.cfg+'","Layers":["one/layer.tar"]}]').encode(),tarfile.REGTYPE);self.reject()
 def test_concealed_nonzero_trailer_rejected(self):
  with self.assertRaises((ValueError,OSError,tarfile.TarError)):self.mod.convert(io.BytesIO(rawtar(self.entries)+b'CONCEALED_NONZERO'),self.out,self.cid)
  self.assertFalse(self.out.exists())
 def test_truncated_outer_tar_end_markers_rejected(self):
  data=rawtar(self.entries)
  with tarfile.open(fileobj=io.BytesIO(data)) as archive:end=max(m.offset_data+((m.size+511)//512)*512 for m in archive.getmembers())
  with self.assertRaises((ValueError,OSError,tarfile.TarError)):self.mod.convert(io.BytesIO(data[:end+512]),self.out,self.cid)
  self.assertFalse(self.out.exists())
 def test_metadata_limit_rejects(self):self.reject(max_meta=4)
 def test_hardlink_rejects(self):self.reject(self.entries+[('hard',b'',tarfile.LNKTYPE)])
 def test_missing_manifest_rejects(self):self.reject(self.entries[:2])
 def test_preexisting_archive_not_overwritten(self):
  self.out.write_bytes(b'old');
  with self.assertRaises((ValueError,OSError)):self.convert()
  self.assertEqual(self.out.read_bytes(),b'old')
 def docker28(self):
  fixture=json.loads((I/'tests/docker28-shape.json').read_text());self.cid=fixture['image_config_digest'];entries=[(n,t.encode(),tarfile.REGTYPE) for n,t in fixture['metadata_files'].items()]
  for text,digest in zip(fixture['layer_payloads'],fixture['layer_digests']):
   data=rawtar([('public.txt',text.encode(),tarfile.REGTYPE)]);self.assertEqual('sha256:'+sha(data),digest);entries.append(('blobs/sha256/'+digest[7:],data,tarfile.REGTYPE))
  return entries
 def mutate_docker28_manifest(self,entries,field):
  values={n:data for n,data,_ in entries};index=json.loads(values['index.json']);name='blobs/sha256/'+index['manifests'][0]['digest'][7:];root=json.loads(values.pop(name))
  if field=='config':root['config']['digest']='sha256:'+'0'*64
  else:root['layers'].reverse()
  raw=json.dumps(root,separators=(',',':')).encode();new='blobs/sha256/'+sha(raw);values[new]=raw;index['manifests'][0].update(digest='sha256:'+sha(raw),size=len(raw));values['index.json']=json.dumps(index).encode();return [(n,b,tarfile.REGTYPE) for n,b in values.items()]
 def test_actual_docker28_oci_and_bound_legacy_chain(self):self.convert(self.docker28());self.mod.verify_archive(self.out,self.cid)
 def test_docker28_wrong_index_rejects(self):
  entries=self.docker28();entries=[(n,json.dumps({'schemaVersion':2,'manifests':[{'mediaType':'application/vnd.oci.image.manifest.v1+json','digest':'sha256:'+'0'*64,'size':10}]}).encode() if n=='index.json' else b,t) for n,b,t in entries];self.reject(entries)
 def test_docker28_wrong_config_join_rejects(self):self.reject(self.mutate_docker28_manifest(self.docker28(),'config'))
 def test_docker28_wrong_ordered_layer_join_rejects(self):self.reject(self.mutate_docker28_manifest(self.docker28(),'layers'))
 def test_docker28_unreferenced_blob_rejects(self):
  entries=self.docker28();entries.append(('blobs/sha256/'+sha(b'{}'),b'{}',tarfile.REGTYPE));self.reject(entries)
 def test_docker28_wrong_legacy_parent_rejects(self):
  entries=self.docker28();changed=[]
  for n,b,t in entries:
   if n.startswith('blobs/sha256/') and b.startswith(b'{') and b.find(b'"id"')>=0:
    d=json.loads(b)
    if 'parent' in d:d['parent']='0'*64;b=json.dumps(d,separators=(',',':')).encode();n='blobs/sha256/'+sha(b)
   changed.append((n,b,t))
  self.reject(changed)
 def test_docker28_invalid_legacy_id_rejects(self):
  entries=self.docker28();changed=[]
  for n,b,t in entries:
   if n.startswith('blobs/sha256/') and b.startswith(b'{') and b.find(b'"id"')>=0:
    d=json.loads(b);d['id']='0'*64;b=json.dumps(d,separators=(',',':')).encode();n='blobs/sha256/'+sha(b)
   changed.append((n,b,t))
  self.reject(changed)
 def test_final_directory_force_failure_removes_published_archive(self):
  original=self.mod.syncdir
  def force(path):
   if Path(path)==self.out.parent and self.out.exists():raise OSError('PUBLIC_FORCE_FAILURE')
   return original(path)
  self.mod.syncdir=force
  with self.assertRaises(OSError):self.convert()
  self.assertFalse(self.out.exists())
 def test_independent_verifier_rejects_tampered_blob(self):
  self.convert()
  with tarfile.open(self.out) as t:entries=[(m.name,t.extractfile(m).read(),tarfile.REGTYPE) for m in t if m.isfile()]
  for n,(name,data,typ) in enumerate(entries):
   if name=='blobs/sha256/'+sha(self.layer):entries[n]=(name,data+b'changed',typ)
  self.out.write_bytes(rawtar(entries))
  with self.assertRaises((ValueError,OSError)):self.mod.verify_archive(self.out,self.cid)
if __name__=='__main__':unittest.main()

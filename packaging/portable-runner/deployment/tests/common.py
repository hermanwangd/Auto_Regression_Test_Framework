import importlib.util,json,tempfile,sys
from pathlib import Path
D=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(D/'scripts'))
def module(name):
 s=importlib.util.spec_from_file_location(name,D/'scripts'/f'{name}.py');m=importlib.util.module_from_spec(s);sys.modules[name]=m;s.loader.exec_module(m);return m
m=module('deployment')
def dump(p,v):
 data=(json.dumps(v,sort_keys=True)+'\n').encode();p.write_bytes(data);return m.digest(data)
def binding():
 return {'schema_version':'deployment-binding.v1','enabled':True,'run_id':'run1','correlation_id':'corr1','nonce':'nonce1','image':'example.invalid/tool@sha256:'+'1'*64,'framework_sha256':'2'*64,'handoff_sha256':'3'*64,'owner_uid':65532,'storage':{'input_claim':'input1','output_claim':'output1','exclusive':True,'writable_uid':65532},'transport':'retained_source_only','secret_refs':[],'secret_materializer':'none'}

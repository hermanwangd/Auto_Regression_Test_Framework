"""Protected fixed CD/local-collector composition; no native gateway or verdict engine."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import time
import deployment as d
import supervision as s

PRODUCER_SHA='da522369a989d05684ced7e64dcc08a570589ca02117ba5f0ea87e5cf8ca59b2'
JAR_SHA='f1c96830382b8fd86c2b596209d90ad4b0d988aff247c7466fbb364ed9a4b27b'
JAR_BYTES=97097006
WRAPPER_SHA='0f410359080b645914f1a102f102fa1cbbd50c69a8683a178c8c6294d7ded70a'
SAVE=b'BYTE_COLLECTION_SAVED_NOT_PRODUCT_VERDICT\n'
META=2*1024*1024;TOTAL=64*1024*1024
ROLES={'canonical','index','summary','evidence','report','coverage_json','coverage_md','code_coverage','log','runtime_status','collector_metadata','derived_coverage'}

def require(ok,code):
    if not ok:raise d.Blocked(code)

def data(value):return (json.dumps(value,sort_keys=True)+'\n').encode()

def force_dir(path):
    fd=d._open_directory(path)
    try:os.fsync(fd)
    finally:os.close(fd)

def private_mkdir(path):
    path=Path(path);d.directory_identity(path.parent)
    try:d._mkdir_owned(path)
    except OSError as exc:raise d.Blocked('directory_durability_failed') from exc
    return path

def put(path,value):d.write_durable(path,data(value))

def pin_file(row,kind):
    d.exact(row,['path','bytes','sha256','kind'],['path','bytes','sha256','kind'])
    limits={'framework_jar':96*1024*1024,'java':16*1024*1024,'python':32*1024*1024,'wrapper':META,'producer':META,'metadata':META}
    require(row['kind']==kind and type(row['bytes']) is int and 0<row['bytes']<=limits[kind] and isinstance(row['sha256'],str) and d.SHA.fullmatch(row['sha256']),'tool_pin_type_or_limit')
    path=Path(row['path']);require(path.is_absolute() and '..' not in path.parts,'absolute_tool_path_required')
    for item in [path,*path.parents]:require(not item.is_symlink(),'tool_symlink')
    parent,name=d._open_parent(path)
    try:fd=os.open(name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=parent)
    finally:os.close(parent)
    try:
        before=os.fstat(fd);require(stat.S_ISREG(before.st_mode) and before.st_nlink==1 and before.st_size==row['bytes'],'tool_file_identity')
        h=hashlib.sha256();count=0
        while True:
            chunk=os.read(fd,65536)
            if not chunk:break
            count+=len(chunk);require(count<=row['bytes'],'tool_stream_limit');h.update(chunk)
        after=os.fstat(fd)
        require((before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns,before.st_ctime_ns)==(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns,after.st_ctime_ns) and after.st_nlink==1 and count==row['bytes'] and h.hexdigest()==row['sha256'],'tool_changed_or_pin_mismatch')
    finally:os.close(fd)
    return path

def load(path,pin):
    value=d.strict_json(path,pin);admit(value);return value

def admit(w):
    fields=['schema_version','enabled','run_id','correlation_id','nonce','deadline_seconds','target','run_parent','receiver_parent','tools','bundle','collection_spec','source_catalog','cleanup_catalog']
    d.exact(w,fields,fields);require(w['schema_version']=='deployment-composition.v1' and type(w['enabled']) is bool,'composition_schema')
    require(w['enabled'],'composition_disabled')
    for key in ('run_id','correlation_id','nonce'):require(isinstance(w[key],str) and d.TOKEN.fullmatch(w[key]),'composition_identity')
    require(type(w['deadline_seconds']) is int and 1<=w['deadline_seconds']<=600,'composition_deadline')
    target=w['target'];d.exact(target,['mode','uid','root_identity','inventory_id','host_key_sha256'],['mode','uid','root_identity','inventory_id','host_key_sha256'])
    require(target['mode'] in ['public_local_fixture','bound_vm'] and type(target['uid']) is int and target['uid']==os.getuid()!=0,'target_nonroot_uid')
    root=Path(w['run_parent']);receiver=Path(w['receiver_parent']);require(root.is_absolute() and receiver.is_absolute() and root!=receiver and not receiver.is_relative_to(root) and not root.is_relative_to(receiver),'separate_private_roots')
    require(d.directory_identity(root)==target['root_identity'],'target_root_identity')
    d.directory_identity(receiver)
    if target['mode']=='public_local_fixture':require(target['inventory_id']=='localhost' and target['host_key_sha256'] is None,'local_fixture_identity')
    else:
        require(isinstance(target['inventory_id'],str) and d.TOKEN.fullmatch(target['inventory_id']) and isinstance(target['host_key_sha256'],str) and d.SHA.fullmatch(target['host_key_sha256']),'vm_host_identity_unbound')
        # Only an actual separately authorized role invocation may establish this
        # target. Helper-only command line cannot fabricate SSH host-key proof.
        raise d.Blocked('bound_VM_transport_identity_NOT_OBSERVED')
    tools=w['tools'];d.exact(tools,['producer','framework_jar','wrapper','java','python'],['producer','framework_jar','wrapper','java','python'])
    for key,row in tools.items():pin_file(row,key)
    require(tools['producer']['sha256']==PRODUCER_SHA and tools['framework_jar']['sha256']==JAR_SHA and tools['framework_jar']['bytes']==JAR_BYTES and tools['wrapper']['sha256']==WRAPPER_SHA,'accepted_producer_pins_required')
    producer=d.strict_json(tools['producer']['path'],PRODUCER_SHA)
    require(producer['framework_jar']['sha256']==JAR_SHA and producer['framework_jar']['bytes']==JAR_BYTES and producer['wrapper']['sha256']==WRAPPER_SHA,'accepted_producer_join')
    bundle=w['bundle'];d.exact(bundle,['root','handoff','sha256'],['root','handoff','sha256'])
    bundle_root=Path(bundle['root']);require(bundle_root.is_relative_to(root) and not bundle_root.is_relative_to(run_root(w)),'external_bundle_root')
    d.directory_identity(bundle_root);d.relative(bundle['handoff']);d.strict_json(bundle_root/bundle['handoff'],bundle['sha256'])
    for name in ['collection_spec','source_catalog','cleanup_catalog']:
        row=w[name]
        if row is not None:
            pin_file(row,'metadata');require(Path(row['path']).is_relative_to(root) and not Path(row['path']).is_relative_to(run_root(w)),'external_metadata_authority')
    require(shutil.disk_usage(root).free>=5*1024**3+256*1024**2,'disk_reserve')
    return w

def run_root(w):return Path(w['run_parent'])/(w['run_id']+'-'+w['nonce'])

def receiver_root(w):return Path(w['receiver_parent'])/(w['run_id']+'-'+w['nonce'])

def fixed_owner(w):
    return {'schema_version':'deployment-owner.v1','run_id':w['run_id'],'correlation_id':w['correlation_id'],'nonce':w['nonce'],'target':w['target'],'tools_sha256':d.digest(data(w['tools'])),'bundle_sha256':d.digest(data(w['bundle']))}

def owned_run(w,create=False):
    root=run_root(w)
    if create:
        private_mkdir(root);put(root/'.deployment-owner.json',fixed_owner(w))
    else:
        d.directory_identity(root);require(d.strict_json(root/'.deployment-owner.json')==fixed_owner(w),'run_owner_changed')
    return root

def stop_receipt(w):
    root=owned_run(w);record=d.strict_json(root/'execution.json')
    require(record.get('schema_version')=='package-execution.v1' and record.get('run_id')==w['run_id'] and record.get('nonce')==w['nonce'] and record.get('tools_sha256')==d.digest(data(w['tools'])),'execution_identity')
    stop=record.get('stop',{})
    require(stop.get('stop_verified') is True and stop.get('unknown_children')==[] and stop.get('nonce')==w['nonce'] and s.stopped_identities(stop.get('identities')),'observed_tree_not_stopped')
    return record

def _invoke(w,argv,seconds,attempt=lambda:None):
    try:record=s._run_owned(argv,seconds,attempt,nonce=w['nonce'])
    except s.StopFailure as exc:raise d.Blocked(str(exc)) from exc
    output=record.pop('output');record['output_sha256']=d.digest(output)
    return record,output

def execute(w):
    admit(w);root=owned_run(w,True);attempts=[]
    def available():
        if w['collection_spec'] is None:attempts.append({'status':'BLOCKED_independent_spec_missing','available_evidence_attempted':True})
        if w['collection_spec'] is not None:
            try:attempts.append(collect(w,check_stop=False))
            except (d.Blocked,OSError):attempts.append({'status':'INCOMPLETE'})
    java=Path(w['tools']['java']['path']);require(java.name=='java' and java.parent.name=='bin','java_home_shape')
    argv=[w['tools']['python']['path'],'-B',w['tools']['wrapper']['path'],'--bundle',w['bundle']['root'],'--handoff',w['bundle']['handoff'],'--expected-handoff-sha256',w['bundle']['sha256'],'--output',str(root/'produced'),'--java-home',str(java.parent.parent)]
    stop,output=_invoke(w,argv,w['deadline_seconds'],available)
    d.write_durable(root/'execution.log',output)
    gate=None
    try:gate=d.strict_json(root/'produced/gate-result.json')
    except (d.Blocked,OSError):pass
    result={'schema_version':'package-execution.v1','run_id':w['run_id'],'correlation_id':w['correlation_id'],'nonce':w['nonce'],'argv':argv,'tools_sha256':d.digest(data(w['tools'])),'stop':stop,'gate':gate,'available_collection_attempts':attempts,'native_acceptance':'NOT_CLAIMED'}
    put(root/'execution.json',result);force_dir(root);force_dir(root.parent)
    return result

def spec_document(w):
    require(w['collection_spec'] is not None,'independent_spec_missing');row=w['collection_spec'];spec=d.strict_json(row['path'],row['sha256'])
    require(spec.get('schema_version')=='runner-collection.v1' and spec.get('run_id')==w['run_id'] and spec.get('correlation_id')==w['correlation_id'] and spec.get('payload_profile') in ['standard','reviewed_grpc'],'collection_spec_identity')
    return spec

def collect(w,check_stop=True):
    admit(w);root=owned_run(w)
    if check_stop:stop_receipt(w)
    spec=spec_document(w);source=root/'produced';d.directory_identity(source)
    saved=root/'local-saved';require(not saved.exists(),'fresh_collection_required')
    result=invoke_collector(w,source,saved,root/'collector-invocation.json')
    if result['status']=='SAVED' and check_stop:
        candidate={'schema_version':'deployment-sealed-catalog.v1','run_id':w['run_id'],'correlation_id':w['correlation_id'],'nonce':w['nonce'],'resource':d.directory_identity(saved),'spec_sha256':w['collection_spec']['sha256'],'files':verify_saved(w,saved,root/'collector-invocation.json')}
        put(root/'source-catalog.candidate.json',candidate)
        result['catalog_candidate']=str(root/'source-catalog.candidate.json')
    return result

def invoke_collector(w,source,saved,invocation):
    spec=spec_document(w);argv=[w['tools']['java']['path'],'-jar',w['tools']['framework_jar']['path'],'runner-import','--collection-spec',w['collection_spec']['path'],'--expected-spec-sha256',w['collection_spec']['sha256'],'--source',str(source),'--out',str(saved)]
    stop,output=_invoke(w,argv,30);d.write_durable(Path(invocation).with_suffix('.log'),output)
    result={'schema_version':'package-collector-invocation.v1','run_id':w['run_id'],'correlation_id':w['correlation_id'],'spec_sha256':w['collection_spec']['sha256'],'jar_sha256':JAR_SHA,'source_identity':d.directory_identity(source),'output_identity':d.directory_identity(saved) if saved.exists() else None,'argv':argv,'stop':stop}
    put(invocation,result)
    if s.successful(stop):
        verify_saved(w,saved,invocation)
        return {'status':'SAVED','receipt_sha256':d.digest(d.regular_bytes(Path(saved)/'collection.json',META)),'framework_verdict':d.strict_json(Path(saved)/'collection.json')['framework_verdict']}
    return {'status':'INCOMPLETE','exit_code':stop['exit_code']}

def file_limit(role,path,spec):
    if role=='collector_metadata':return META
    if role=='derived_coverage':
        require(path in ['controller-derived/coverage/coverage.json','controller-derived/coverage/coverage.md'],'derived_path')
        return 8*1024*1024
    require(role in ROLES,'transfer_role')
    if role=='log':return 1024*1024
    if role=='canonical' and spec['payload_profile']=='reviewed_grpc' and path==spec['canonical_result']:return 8*1024*1024
    return 4*1024*1024

def inventory(root):
    """Enumerate only exact approved directory FDs, never follow a replaced parent."""
    result={};count=[0]
    def scan(fd,prefix=''):
        names=os.listdir(fd)
        require(not prefix or bool(names),'unlisted_empty_directory')
        for name in names:
            count[0]+=1;require(count[0]<=4096,'saved_entry_limit');d.relative(name)
            path=prefix+name;info=os.stat(name,dir_fd=fd,follow_symlinks=False)
            require(info.st_uid==os.getuid(),'saved_owner')
            if stat.S_ISDIR(info.st_mode):
                child=os.open(name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
                try:
                    actual=os.fstat(child);require((actual.st_dev,actual.st_ino)==(info.st_dev,info.st_ino),'saved_directory_race');scan(child,path+'/')
                finally:os.close(child)
            else:
                require(stat.S_ISREG(info.st_mode) and info.st_nlink==1,'saved_regular_single_link_required');result[path]=info
    descriptor=d._open_directory(root)
    try:scan(descriptor)
    finally:os.close(descriptor)
    return result

def verify_saved(w,saved,invocation):
    """Join actual fixed accepted CLI invocation to its immutable receipt/SAVE."""
    spec=spec_document(w);saved=Path(saved);d.directory_identity(saved);call=d.strict_json(invocation)
    require(call.get('schema_version')=='package-collector-invocation.v1' and call.get('run_id')==w['run_id'] and call.get('correlation_id')==w['correlation_id'] and call.get('spec_sha256')==w['collection_spec']['sha256'] and call.get('jar_sha256')==JAR_SHA and call.get('output_identity')==d.directory_identity(saved),'collector_invocation_join')
    stop=call.get('stop',{});require(s.successful(stop) and s.stopped_identities(stop.get('identities')),'collector_exit_or_stop')
    expected_argv=[w['tools']['java']['path'],'-jar',w['tools']['framework_jar']['path'],'runner-import','--collection-spec',w['collection_spec']['path'],'--expected-spec-sha256',w['collection_spec']['sha256'],'--source',call['argv'][9] if isinstance(call.get('argv'),list) and len(call['argv'])==12 else '', '--out',str(saved)]
    # Exact option positions and identity are checked, with source identity bound
    # independently in the invocation; no caller command is ever executed here.
    require(call.get('argv')==expected_argv,'collector_argv_join')
    source=Path(call['argv'][9]);require(source in [run_root(w)/'produced',receiver_root(w)/'incoming',receiver_root(w)/'saved'] and call.get('source_identity')==d.directory_identity(source),'collector_source_identity')
    receipt=d.strict_json(saved/'collection.json');require(receipt.get('schema_version')=='runner-collection-receipt.v1' and receipt.get('run_id')==w['run_id'] and receipt.get('correlation_id')==w['correlation_id'] and receipt.get('spec_sha256')==w['collection_spec']['sha256'] and receipt.get('artifact_versions')==spec.get('artifact_versions') and receipt.get('collection')=='COMPLETE' and receipt.get('publication')=='SAVED' and receipt.get('gaps')==[],'collector_complete_receipt_required')
    validator=receipt.get('validator',{});require(validator.get('identity_state')=='JAR_OBSERVED' and validator.get('artifact_sha256')==JAR_SHA and validator.get('schema_sha256')=='23bd700260c26a99ce9db7dd393a9b088bfd3a2bd65593f04f651e5dee210029','accepted_collector_producer_observation')
    canonical=spec.get('canonical_result')
    require((canonical is None and receipt.get('validation')=='NOT_RUN' and receipt.get('framework_verdict')=='UNKNOWN') or (canonical is not None and receipt.get('validation')=='VALID' and receipt.get('framework_verdict') in ['passed','failed','blocked','skipped']),'collector_validation_join')
    require(d.regular_bytes(saved/'SAVE.complete',len(SAVE))==SAVE,'accepted_SAVE_marker_required')
    expected={'collection.json':('collector_metadata',d.regular_bytes(saved/'collection.json',META)), 'aliases.json':('collector_metadata',d.regular_bytes(saved/'aliases.json',META)), 'SAVE.complete':('collector_metadata',SAVE)}
    rows=receipt.get('files');require(isinstance(rows,list) and len(rows)==len(spec['files']),'collector_file_count')
    for promised,row in zip(spec['files'],rows):
        name=promised['path'];require(row.get('path')==name and row.get('role')==promised['role'] and row.get('state')=='SAVED' and row.get('source_hash_verified') is True and row.get('original_sha256')==promised['sha256'] and row.get('original_bytes')==promised['bytes'],'collector_file_join')
        blob=d.regular_bytes(saved/name,file_limit(row['role'],name,spec));require(type(row.get('saved_bytes')) is int and len(blob)==row.get('saved_bytes') and d.digest(blob)==row.get('saved_sha256'),'collector_saved_bytes')
        if row['role']!='log':require(blob and d.digest(blob)==promised['sha256'] or (not blob and promised['bytes']==0 and d.digest(blob)==promised['sha256']),'canonical_bytes_unchanged')
        expected[name]=(row['role'],blob)
    aliases=d.strict_json(saved/'aliases.json');require(aliases.get('schema_version')=='runner-portable-aliases.v1' and isinstance(aliases.get('aliases'),list) and len(aliases['aliases'])==len(rows),'collector_alias_metadata')
    for alias,row in zip(aliases['aliases'],rows):
        require(alias=={'original_ref':row['path'],'portable_ref':row['path'],'original_sha256':row['original_sha256'],'saved_sha256':row['saved_sha256']},'collector_alias_join')
    if canonical is not None:
        derived=receipt.get('controller_derived_coverage',{});require(derived.get('identity')=='NEW_CONTROLLER_DERIVATION_NOT_PRODUCER_REPORT' and derived.get('canonical_sha256')==d.digest(expected[canonical][1]),'derived_coverage_join')
        for suffix,key in [('json','json_sha256'),('md','md_sha256')]:
            name='controller-derived/coverage/coverage.'+suffix;blob=d.regular_bytes(saved/name,8*1024*1024);require(d.digest(blob)==derived.get(key),'derived_pair_hash');expected[name]=('derived_coverage',blob)
    require(set(inventory(saved))==set(expected),'unlisted_saved_content')
    entries=[];total=0;logs=0;derived_total=0
    for name,(role,blob) in sorted(expected.items()):
        total+=len(blob)
        if role=='log':logs+=len(blob);require(blob.count(b'\n')+(bool(blob) and not blob.endswith(b'\n'))<=2000,'log_lines')
        if role=='derived_coverage':derived_total+=len(blob)
        require(total<=TOTAL and logs<=4*1024*1024 and derived_total<=16*1024*1024,'saved_total_limit')
        entries.append({'path':name,'role':role,'bytes':len(blob),'sha256':d.digest(blob)})
    return entries

def source_catalog(w):
    require(w['source_catalog'] is not None,'independent_source_catalog_missing');row=w['source_catalog'];value=d.strict_json(row['path'],row['sha256']);fields=['schema_version','run_id','correlation_id','nonce','resource','spec_sha256','files'];d.exact(value,fields,fields)
    require(value['schema_version']=='deployment-sealed-catalog.v1' and all(value[k]==w[k] for k in ('run_id','correlation_id','nonce')) and value['spec_sha256']==w['collection_spec']['sha256'],'sealed_catalog_identity')
    root=owned_run(w)/'local-saved';require(value['resource']==d.directory_identity(root),'sealed_root_identity');stop_receipt(w)
    actual=verify_saved(w,root,owned_run(w)/'collector-invocation.json');require(data(value['files'])==data(actual),'sealed_catalog_bytes')
    for entry in actual:
        p=root/entry['path'];p.chmod(0o400);fd=os.open(p,os.O_RDONLY|os.O_NOFOLLOW)
        try:os.fsync(fd)
        finally:os.close(fd)
    force_tree(root)
    return value

def force_tree(root):
    paths=inventory(root)
    for name in paths:
        p=Path(root)/name;fd=os.open(p,os.O_RDONLY|os.O_NOFOLLOW)
        try:os.fsync(fd)
        finally:os.close(fd)
        parent=p.parent
        while parent!=Path(root):force_dir(parent);parent=parent.parent
    force_dir(root);force_dir(Path(root).parent)


def copy_one(source,destination,entry,limit,deadline):
    require(time.monotonic()<deadline,'transfer_deadline')
    name=str(d.relative(entry['path']));source=Path(source)/name;destination=Path(destination)/name
    # Secure no-follow traversal in both roots. Opening a FIFO never blocks.
    before=source.stat(follow_symlinks=False)
    blob=d.regular_bytes(source,min(limit,entry['bytes']));require(len(blob)==entry['bytes'] and d.digest(blob)==entry['sha256'],'transfer_source_pin')
    parent=destination.parent;missing=[]
    while not parent.exists():missing.append(parent);parent=parent.parent
    d.directory_identity(parent)
    for parent in reversed(missing):private_mkdir(parent)
    d.write_durable(destination,blob)
    after=source.stat(follow_symlinks=False)
    require((before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns,before.st_ctime_ns)==(after.st_dev,after.st_ino,after.st_size,after.st_mtime_ns,after.st_ctime_ns) and time.monotonic()<deadline and d.regular_bytes(source,limit)==blob,'transfer_source_changed')
    require(d.regular_bytes(destination,limit)==blob,'receiver_reread')

def cleanup_catalog(w):
    require(w['cleanup_catalog'] is not None,'independent_cleanup_catalog_missing')
    row=w['cleanup_catalog'];cat=d.strict_json(row['path'],row['sha256']);fields=['schema_version','run_id','correlation_id','nonce','resource','files'];d.exact(cat,fields,fields)
    require(cat['schema_version']=='deployment-cleanup-catalog.v1' and all(cat[k]==w[k] for k in ('run_id','correlation_id','nonce')),'cleanup_catalog_identity')
    root=owned_run(w);require(cat['resource']==d.directory_identity(root),'cleanup_catalog_resource')
    actual=inventory(root);require(isinstance(cat['files'],list) and 1<=len(cat['files'])<=4096 and len(actual)==len(cat['files']),'cleanup_catalog_count')
    expected={};total=0;jar_count=0;spec=spec_document(w)
    handoff=d.strict_json(Path(w['bundle']['root'])/w['bundle']['handoff'],w['bundle']['sha256'])
    framework_ref=handoff.get('framework',{}).get('path') if isinstance(handoff.get('framework'),dict) else None
    jar_path='produced/snapshot/'+str(framework_ref) if framework_ref is not None else None
    for entry in cat['files']:
        d.exact(entry,['path','role','bytes','sha256'],['path','role','bytes','sha256']);name=str(d.relative(entry['path']));require(name not in expected and name in actual and type(entry['bytes']) is int and entry['bytes']>=0,'cleanup_catalog_entry')
        if entry['role']=='framework_jar':
            require(name==jar_path and entry['sha256']==JAR_SHA and entry['bytes']==JAR_BYTES,'typed_snapshot_JAR_identity');limit=96*1024*1024;jar_count+=1
        else:
            candidate=name.removeprefix('local-saved/').removeprefix('produced/')
            limit=file_limit(entry['role'],candidate,spec)
        require(entry['bytes']<=limit and isinstance(entry['sha256'],str) and d.SHA.fullmatch(entry['sha256']),'cleanup_file_limit')
        # Streaming pin helper is the sole separately typed 96MiB JAR exception.
        if entry['role']=='framework_jar':pin_file({'path':str(root/name),'kind':'framework_jar','bytes':entry['bytes'],'sha256':entry['sha256']},'framework_jar')
        else:
            blob=d.regular_bytes(root/name,limit);require(len(blob)==entry['bytes'] and d.digest(blob)==entry['sha256'],'cleanup_catalog_hash')
        expected[name]=entry;total+=entry['bytes']
    require(set(actual)==set(expected) and jar_count<=1 and total<=TOTAL+96*1024*1024+5*META,'cleanup_total_or_unlisted')
    return cat,actual

def receive(w):
    admit(w);root=owned_run(w);cat=source_catalog(w);cleanup_catalog(w);saved_source=root/'local-saved';controller=private_mkdir(receiver_root(w));incoming=private_mkdir(controller/'incoming');deadline=time.monotonic()+30;spec=spec_document(w)
    for entry in cat['files']:
        require(d.directory_identity(saved_source)==cat['resource'],'source_directory_replaced')
        copy_one(saved_source,incoming,entry,file_limit(entry['role'],entry['path'],spec),deadline)
    # Final all-file/root postcheck prevents a changing snapshot from borrowing ACK.
    require(source_catalog(w)==cat,'source_snapshot_changed');force_tree(incoming)
    result=invoke_collector(w,incoming,controller/'saved',controller/'receiver-invocation.json')
    require(result['status']=='SAVED','receiver_collection_incomplete')
    entries=verify_saved(w,controller/'saved',controller/'receiver-invocation.json')
    record={'schema_version':'package-receiver.v1','run_id':w['run_id'],'correlation_id':w['correlation_id'],'nonce':w['nonce'],'source_catalog_sha256':w['source_catalog']['sha256'],'cleanup_catalog_sha256':w['cleanup_catalog']['sha256'],'collection_spec_sha256':w['collection_spec']['sha256'],'resource':d.directory_identity(root),'receiver_resource':d.directory_identity(controller),'saved_entries':entries,'receiver_invocation_sha256':d.digest(d.regular_bytes(controller/'receiver-invocation.json',META)),'execution_sha256':d.digest(d.regular_bytes(root/'execution.json',META)),'status':'VALIDATED_SAVED'}
    d.write_durable(controller/'execution.json',d.regular_bytes(root/'execution.json',META))
    d.write_durable(controller/'execution.log',d.regular_bytes(root/'execution.log',1024*1024))
    put(controller/'receiver.json',record);force_tree(controller)
    marker=controller/'RECEIVER.complete'
    try:d.write_durable(marker,b'PACKAGE_RECEIVER_SAVED_NOT_PRODUCT_VERDICT\n');force_tree(controller)
    except (OSError,d.Blocked):
        try:marker.unlink();force_dir(controller)
        except FileNotFoundError:pass
        raise
    # A candidate is not authority. Expected pin must be supplied independently.
    candidate={'schema_version':'receiver-ack.v2','authority':'independent_receiver','run_id':w['run_id'],'correlation_id':w['correlation_id'],'nonce':w['nonce'],'resource':d.directory_identity(root),'collection_spec_sha256':w['collection_spec']['sha256'],'source_catalog_sha256':w['source_catalog']['sha256'],'cleanup_catalog_sha256':w['cleanup_catalog']['sha256'],'receiver_receipt_sha256':d.digest(d.regular_bytes(controller/'receiver.json',META)),'execution_sha256':record['execution_sha256']}
    put(controller/'ACK.candidate.json',candidate);force_tree(controller)
    return {'status':'RECEIVER_SAVED_ACK_NOT_AUTHORIZED','framework_verdict':result['framework_verdict'],'candidate':str(controller/'ACK.candidate.json'),'cleanup_eligible':False}

def verify_receiver(w,ack_path,expected_ack):
    admit(w);root=owned_run(w);stop_receipt(w);cat=source_catalog(w);cleanup_catalog(w);controller=receiver_root(w);d.directory_identity(controller)
    ack=d.strict_json(ack_path,expected_ack);expected_keys=['schema_version','authority','run_id','correlation_id','nonce','resource','collection_spec_sha256','source_catalog_sha256','cleanup_catalog_sha256','receiver_receipt_sha256','execution_sha256'];d.exact(ack,expected_keys,expected_keys)
    require(ack['schema_version']=='receiver-ack.v2' and ack['authority']=='independent_receiver' and all(ack[k]==w[k] for k in ('run_id','correlation_id','nonce')) and ack['resource']==d.directory_identity(root) and ack['collection_spec_sha256']==w['collection_spec']['sha256'] and ack['source_catalog_sha256']==w['source_catalog']['sha256'] and ack['cleanup_catalog_sha256']==w['cleanup_catalog']['sha256'],'external_ack_join')
    authority=Path(ack_path);require(authority.is_absolute() and authority.parent==Path(w['run_parent']) and not authority.is_relative_to(root) and not authority.is_relative_to(controller) and not authority.is_relative_to(Path(w['bundle']['root'])),'external_ACK_authority_required')
    require(d.regular_bytes(controller/'RECEIVER.complete',128)==b'PACKAGE_RECEIVER_SAVED_NOT_PRODUCT_VERDICT\n','receiver_durable_marker')
    record=d.strict_json(controller/'receiver.json',ack['receiver_receipt_sha256']);require(record.get('schema_version')=='package-receiver.v1' and record.get('status')=='VALIDATED_SAVED' and all(record.get(k)==w[k] for k in ('run_id','correlation_id','nonce')) and record.get('resource')==d.directory_identity(root) and record.get('receiver_resource')==d.directory_identity(controller) and record.get('collection_spec_sha256')==ack['collection_spec_sha256'] and record.get('source_catalog_sha256')==ack['source_catalog_sha256'] and record.get('cleanup_catalog_sha256')==ack['cleanup_catalog_sha256'],'receiver_receipt_join')
    require(d.digest(d.regular_bytes(root/'execution.json',META))==ack['execution_sha256']==record.get('execution_sha256')==d.digest(d.regular_bytes(controller/'execution.json',META)),'observed_stop_receipt_join')
    invocation=controller/'receiver-invocation.json';require(d.digest(d.regular_bytes(invocation,META))==record.get('receiver_invocation_sha256'),'receiver_invocation_hash')
    require(verify_saved(w,controller/'saved',invocation)==record['saved_entries'],'receiver_complete_semantic_readback')
    # Never grant eligibility from correctly hashed arbitrary JSON alone: the
    # accepted CLI independently revalidates current receiver bytes on this call.
    fresh=controller/('verified-'+expected_ack[:16])
    require(not fresh.exists(),'ACK_replay_or_verification_output_exists')
    check=invoke_collector(w,controller/'saved',fresh,controller/('verification-'+expected_ack[:16]+'.json'))
    require(check['status']=='SAVED','current_receiver_CLI_revalidation_failed')
    force_tree(controller)
    return {'status':'ACK_VERIFIED','cleanup_eligible':True,'resource':d.directory_identity(root)}

def cleanup(w,ack_path,ack_pin):
    verified=verify_receiver(w,ack_path,ack_pin);root=owned_run(w);controller=receiver_root(w)
    require(root.parent==Path(w['run_parent']) and not controller.is_relative_to(root),'cleanup_root_boundary')
    _,files=cleanup_catalog(w)
    stop_receipt(w)
    deadline=time.monotonic()+2
    descriptor=d._open_directory(root);parent=d._open_directory(root.parent)
    try:
        fs=os.fstat(descriptor);require({'device':fs.st_dev,'inode':fs.st_ino,'uid':fs.st_uid}==verified['resource'],'cleanup_root_replaced')
        def remove_tree(fd,prefix=''):
            names=os.listdir(fd);require(len(names)<=4096,'cleanup_entry_limit')
            for name in names:
                require(time.monotonic()<deadline,'exact_cleanup_deadline_UNCERTAIN')
                d.relative(name);path=prefix+name;before=os.stat(name,dir_fd=fd,follow_symlinks=False)
                require(before.st_uid==os.getuid(),'cleanup_owner_changed')
                if stat.S_ISDIR(before.st_mode):
                    child=os.open(name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
                    try:
                        current=os.fstat(child);require((current.st_dev,current.st_ino)==(before.st_dev,before.st_ino),'cleanup_directory_race');remove_tree(child,path+'/')
                    finally:os.close(child)
                    current=os.stat(name,dir_fd=fd,follow_symlinks=False);require((current.st_dev,current.st_ino)==(before.st_dev,before.st_ino),'cleanup_directory_replaced');os.rmdir(name,dir_fd=fd);os.fsync(fd)
                else:
                    require(path in files and stat.S_ISREG(before.st_mode) and before.st_nlink==1 and (before.st_dev,before.st_ino,before.st_size,before.st_mtime_ns)==(files[path].st_dev,files[path].st_ino,files[path].st_size,files[path].st_mtime_ns),'cleanup_file_changed_or_unlisted')
                    os.unlink(name,dir_fd=fd);os.fsync(fd)
        remove_tree(descriptor)
        require(time.monotonic()<deadline,'exact_cleanup_deadline_UNCERTAIN')
        current=os.stat(root.name,dir_fd=parent,follow_symlinks=False);require((current.st_dev,current.st_ino)==(fs.st_dev,fs.st_ino),'cleanup_final_identity');os.rmdir(root.name,dir_fd=parent);os.fsync(parent)
        require(time.monotonic()<deadline,'exact_cleanup_deadline_UNCERTAIN')
    finally:os.close(descriptor);os.close(parent)
    put(controller/'cleanup.json',{'status':'EXACT_OWNED_RUN_REMOVED','run_id':w['run_id'],'nonce':w['nonce'],'resource':verified['resource'],'external_ack_sha256':ack_pin,'controller_saved_retained':True})
    return {'status':'EXACT_OWNED_RUN_REMOVED','controller_saved_retained':True}

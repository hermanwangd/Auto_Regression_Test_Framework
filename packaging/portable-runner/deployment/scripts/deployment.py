"""Finite deployment packaging. This module implements no native runner or transport.

Operational execution remains blocked until a reviewed native lifecycle and transport
binding exist. Local staging and independently pinned receiver ACK checks are usable.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys

MAX_JSON = 2 * 1024 * 1024
SHA = re.compile(r'[0-9a-f]{64}')
TOKEN = re.compile(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}')
class Blocked(ValueError):
    """An admission condition was not met; no operational fallback is permitted."""

def digest(data):
    return hashlib.sha256(data).hexdigest()

def strict_json(path, expected=None):
    data = regular_bytes(Path(path), MAX_JSON)
    if expected is not None and (not isinstance(expected, str) or not SHA.fullmatch(expected) or digest(data) != expected):
        raise Blocked('independent_pin_mismatch')
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise Blocked('duplicate_key')
            result[key] = value
        return result
    try:
        value = json.loads(data, object_pairs_hook=pairs, parse_constant=lambda _: (_ for _ in ()).throw(Blocked('nonfinite_number')))
    except (UnicodeError, json.JSONDecodeError, RecursionError) as exc:
        raise Blocked('invalid_json') from exc
    if not isinstance(value, dict):
        raise Blocked('object_required')
    def bounded(node, depth=0):
        if depth > 100:
            raise Blocked('json_depth')
        if isinstance(node, str) and len(node) > 1024*1024:
            raise Blocked('json_string_limit')
        if isinstance(node, dict):
            for key, child in node.items():
                bounded(key, depth+1); bounded(child, depth+1)
        elif isinstance(node, list):
            for child in node: bounded(child, depth+1)
    bounded(value)
    return value

def _open_directory(path):
    path=Path(path)
    if '..' in path.parts:raise Blocked('unnormalized_directory')
    absolute=path.absolute();fd=os.open(absolute.anchor,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
    try:
        for name in absolute.parts[1:]:
            next_fd=os.open(name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=fd)
            os.close(fd);fd=next_fd
        return fd
    except OSError as exc:
        os.close(fd);raise Blocked('secure_directory_required') from exc

def _open_parent(path):
    path=Path(path)
    if '..' in path.parts:raise Blocked('unnormalized_file')
    return _open_directory(path.parent),path.name

def regular_bytes(path, limit):
    try:
        parent,name=_open_parent(path)
        try:fd=os.open(name,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=parent)
        finally:os.close(parent)
    except OSError as exc:raise Blocked('regular_file_required') from exc
    try:
        before=os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink!=1 or before.st_size>limit:
            raise Blocked('file_type_links_or_size')
        with os.fdopen(fd,'rb',closefd=False) as stream:blob=stream.read(limit+1)
        after=os.fstat(fd)
        keys=lambda x:(x.st_dev,x.st_ino,x.st_uid,x.st_nlink,x.st_size,x.st_mtime_ns,x.st_ctime_ns)
        if len(blob)>limit or keys(before)!=keys(after):raise Blocked('file_changed_or_oversized')
        return blob
    finally:os.close(fd)

def relative(value):
    if not isinstance(value, str) or not value or len(value) > 240 or '\\' in value or ':' in value or '\x00' in value:
        raise Blocked('unsafe_relative_path')
    p = PurePosixPath(value)
    if p.is_absolute() or any(part in ('', '.', '..') for part in value.split('/')):
        raise Blocked('unsafe_relative_path')
    if len(p.parts)>16 or any(part.lower() in {'.ssh','.git','.aws','.env','secrets','credentials','kubeconfig'} for part in p.parts):raise Blocked('private_path_excluded')
    return p

def exact(value, allowed, required):
    if not isinstance(value, dict) or set(value) - set(allowed) or set(required) - set(value):
        raise Blocked('unknown_or_missing_field')

def validate_binding(value, execute=False):
    exact(value, ['schema_version','enabled','run_id','correlation_id','nonce','image','framework_sha256','handoff_sha256','owner_uid','storage','transport','secret_refs','secret_materializer'], ['schema_version','enabled'])
    if value['schema_version'] != 'deployment-binding.v1' or type(value['enabled']) is not bool:
        raise Blocked('binding_schema_or_type')
    if not value['enabled']:
        return {'status':'DISABLED','runnable':False}
    required = ['run_id','correlation_id','nonce','image','framework_sha256','handoff_sha256','owner_uid','storage','transport','secret_refs','secret_materializer']
    if any(k not in value for k in required):
        raise Blocked('operational_binding_missing')
    for key in ('run_id','correlation_id','nonce'):
        if not isinstance(value[key], str) or not TOKEN.fullmatch(value[key]):
            raise Blocked('identity_invalid')
    if not isinstance(value['image'], str) or not re.fullmatch(r'[a-z0-9][a-z0-9./:_-]*@sha256:[0-9a-f]{64}', value['image']):
        raise Blocked('immutable_image_required')
    if any(not isinstance(value[k],str) or not SHA.fullmatch(value[k]) for k in ('framework_sha256','handoff_sha256')):
        raise Blocked('artifact_pin_required')
    if type(value['owner_uid']) is not int or value['owner_uid'] <= 0:
        raise Blocked('nonroot_owner_required')
    exact(value['storage'], ['input_claim','output_claim','exclusive','writable_uid'], ['input_claim','output_claim','exclusive','writable_uid'])
    storage=value['storage']
    if storage['exclusive'] is not True or storage['writable_uid'] != value['owner_uid'] or storage['input_claim'] == storage['output_claim'] or any(not isinstance(storage[k],str) or not re.fullmatch('[a-z0-9][a-z0-9.-]{0,62}',storage[k]) for k in ('input_claim','output_claim')):
        raise Blocked('storage_ownership_unbound')
    if value['transport'] != 'retained_source_only':
        raise Blocked('full_transport_not_implemented')
    if not isinstance(value['secret_refs'],list) or value['secret_refs'] or value['secret_materializer'] != 'none':
        raise Blocked('secret_materializer_not_implemented')
    if execute:
        raise Blocked('native_lifecycle_and_full_transport_not_implemented')
    return {'status':'STATIC_BOUND','runnable':False,'transport':'retained_source_only'}

def _mkdir_owned(path,exist_ok=False):
    parent,name=_open_parent(path);fresh=True
    try:
        try:os.mkdir(name,mode=0o700,dir_fd=parent)
        except FileExistsError:
            if not exist_ok:raise
            fresh=False
        child=os.open(name,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW,dir_fd=parent)
        try:
            info=os.fstat(child)
            if info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o700:raise Blocked('private_directory_required')
            os.fsync(child);os.fsync(parent)
        finally:os.close(child)
    finally:os.close(parent)
    return fresh

def _force_directory(path):
    fd=_open_directory(path)
    try:os.fsync(fd)
    finally:os.close(fd)

def write_durable(path, data, mode=0o400):
    parent,name=_open_parent(path)
    try:
        fd=os.open(name,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,mode,dir_fd=parent)
        try:
            with os.fdopen(fd,'wb',closefd=False) as stream:
                stream.write(data);stream.flush()
            os.fchmod(fd,mode);os.fsync(fd)
        finally:os.close(fd)
        os.fsync(parent)
    finally:os.close(parent)
    if regular_bytes(path,len(data))!=data:raise Blocked('durable_readback_mismatch')

def catalog(value):
    exact(value,['schema_version','run_id','nonce','files'],['schema_version','run_id','nonce','files'])
    if value['schema_version']!='deployment-stage.v1' or any(not isinstance(value[k],str) or not TOKEN.fullmatch(value[k]) for k in ('run_id','nonce')):
        raise Blocked('stage_identity')
    if not isinstance(value['files'],list) or not 1<=len(value['files'])<=128:
        raise Blocked('stage_count')
    names=set(); total=0
    for item in value['files']:
        exact(item,['path','bytes','sha256','executable'],['path','bytes','sha256','executable'])
        relative(item['path'])
        if item['path']=='.deployment-owner.json' or item['path'] in names or type(item['bytes']) is not int or not 0<=item['bytes']<=4*1024*1024 or not isinstance(item['sha256'],str) or not SHA.fullmatch(item['sha256']) or type(item['executable']) is not bool:
            raise Blocked('stage_entry')
        names.add(item['path']); total+=item['bytes']
    if total>16*1024*1024:
        raise Blocked('stage_total')
    return value

def directory_identity(root):
    fd=_open_directory(root)
    try:info=os.fstat(fd)
    finally:os.close(fd)
    if info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o700 or os.getuid()==0:
        raise Blocked('private_nonroot_directory_required')
    return {'device':info.st_dev,'inode':info.st_ino,'uid':info.st_uid}

def stage(source, target, manifest, manifest_pin):
    manifest=Path(manifest); value=catalog(strict_json(manifest,manifest_pin)); source=Path(source); target=Path(target)
    directory_identity(manifest.parent)
    directory_identity(source)
    if target.parent.resolve()!=manifest.parent.resolve() or not source.resolve().is_relative_to(manifest.parent.resolve()) or target==manifest.parent:
        raise Blocked('stage_outside_owned_manifest_root')
    # Validate every byte before creating output; no partial stage on invalid input.
    admitted=[]
    for item in value['files']:
        data=regular_bytes(source/str(relative(item['path'])),4*1024*1024)
        if len(data)!=item['bytes'] or digest(data)!=item['sha256']:
            raise Blocked('stage_source_pin_mismatch')
        admitted.append((item,data))
    owner={'schema_version':'deployment-owner.v1','run_id':value['run_id'],'nonce':value['nonce'],'manifest_sha256':manifest_pin}
    owner_data=(json.dumps(owner,sort_keys=True)+'\n').encode()
    if target.exists() or target.is_symlink():
        directory_identity(target)
        if regular_bytes(target/'.deployment-owner.json',MAX_JSON)!=owner_data:
            raise Blocked('stage_owner_mismatch')
        expected={'.deployment-owner.json'}|{i['path'] for i,_ in admitted}
        actual={p.relative_to(target).as_posix() for p in target.rglob('*') if not p.is_dir() or p.is_symlink()}
        if actual!=expected: raise Blocked('stage_unexpected_entry')
        for item,data in admitted:
            p=target/item['path']; mode=0o500 if item['executable'] else 0o400
            if regular_bytes(p,len(data))!=data or stat.S_IMODE(p.stat().st_mode)!=mode or p.stat().st_uid!=os.getuid():
                raise Blocked('stage_existing_changed')
        return {'status':'STAGED','changed':False,'files':len(admitted)}
    directory_identity(target.parent)
    _mkdir_owned(target)
    for item,data in admitted:
        p=target/item['path']
        parent=target
        for part in relative(item['path']).parts[:-1]:
            parent=parent/part
            _mkdir_owned(parent,exist_ok=True)
        write_durable(p,data,0o500 if item['executable'] else 0o400)
    write_durable(target/'.deployment-owner.json',owner_data)
    return {'status':'STAGED','changed':True,'files':len(admitted)}

def verify_ack(ack, expected_pin, expected, saved_root):
    # v1 only checks byte claims and cannot prove actual collector validation/stop.
    # No legacy proof is eligible. Operational workflow uses composition.verify_receiver.
    raise Blocked('legacy_ACK_has_no_observed_collector_and_stop_context')

def cleanup_request(ack, pin, expected, saved, source):
    raise Blocked('legacy_ACK_has_no_observed_collector_and_stop_context')

def main():
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest='action',required=True)
    for action in ['execute','collect','receive','cleanup']:
        command=sub.add_parser(action);command.add_argument('--binding',required=True);command.add_argument('--expected-sha256',required=True)
        if action=='cleanup':command.add_argument('--ack',required=True);command.add_argument('--expected-ack-sha256',required=True)
    v=sub.add_parser('validate');v.add_argument('--binding',required=True);v.add_argument('--expected-sha256',required=True);v.add_argument('--execute',action='store_true')
    s=sub.add_parser('stage');s.add_argument('--source',required=True);s.add_argument('--target',required=True);s.add_argument('--manifest',required=True);s.add_argument('--expected-sha256',required=True)
    args=p.parse_args()
    try:
        if args.action=='validate':result=validate_binding(strict_json(args.binding,args.expected_sha256),args.execute)
        elif args.action=='stage':result=stage(args.source,args.target,args.manifest,args.expected_sha256)
        else:
            import composition
            workflow=composition.load(args.binding,args.expected_sha256)
            if args.action=='cleanup':result=composition.cleanup(workflow,args.ack,args.expected_ack_sha256)
            else:result=getattr(composition,args.action)(workflow)
        print(json.dumps(result,sort_keys=True))
        if args.action=='execute':return 0 if composition.s.successful(result['stop']) else 1
        if args.action=='collect' and result['status']!='SAVED':return 1
        return 0
    except (Blocked,OSError) as exc:
        # Never echo untrusted paths/input or exception text containing payload bytes.
        print(json.dumps({'status':'BLOCKED','reason':str(exc) if isinstance(exc,Blocked) else 'filesystem_error'}));return 2
if __name__=='__main__':
    # Composition must share the same admission exception class as this entrypoint.
    sys.modules['deployment']=sys.modules[__name__]
    sys.exit(main())

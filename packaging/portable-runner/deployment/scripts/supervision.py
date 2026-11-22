"""Private fixed-command supervision using only exact registered process identities.

No /proc-wide enumeration. Unknown adopted children prevent stop/cleanup proof.
The direct Popen child is reaped only by Popen, preserving its real exit status.
"""
import ctypes
import os
from pathlib import Path
import selectors
import signal
import subprocess
import time

class StopFailure(RuntimeError): pass

def _identity(pid):
    root=Path('/proc')/str(pid)
    try:
        text=(root/'stat').read_text();tail=text[text.rfind(')')+2:].split()
        return (int(tail[1]),int(tail[19]),root.stat().st_uid,tail[0])
    except FileNotFoundError as exc:
        # ENOENT for stat alone is not absence if the process directory remains.
        try:root.stat()
        except FileNotFoundError:return None
        except OSError as unavailable:raise StopFailure('process_identity_unobservable') from unavailable
        raise StopFailure('process_identity_interface_missing') from exc
    except OSError as exc:raise StopFailure('process_identity_unobservable') from exc
    except (ValueError,IndexError) as exc:raise StopFailure('process_identity_invalid') from exc

def _children(pid,identity):
    """Read only exact registered identities; missing live interfaces are unknown."""
    def current():
        now=_identity(pid)
        return now is not None and now[1:3]==identity[1:3]
    if not current():return set()  # Confirmed gone/reused: never inspect the new PID.
    result=set();task_root=Path('/proc')/str(pid)/'task'
    try:tasks=list(task_root.iterdir())
    except FileNotFoundError as exc:
        if not current():return set()
        raise StopFailure('owned_task_interface_unobservable') from exc
    except OSError as exc:raise StopFailure('owned_task_interface_unobservable') from exc
    if not tasks:
        if not current():return set()
        raise StopFailure('owned_task_interface_unobservable')
    if len(tasks)>1024:raise StopFailure('owned_thread_limit')
    for task in tasks:
        if not task.name.isdigit():continue
        try:result.update(map(int,(task/'children').read_text().split()))
        except FileNotFoundError as exc:
            if not current():return set()
            try:task.stat()
            except FileNotFoundError:continue  # Confirmed raced-away thread.
            except OSError as unavailable:raise StopFailure('owned_children_unobservable') from unavailable
            raise StopFailure('owned_children_interface_missing') from exc
        except (OSError,ValueError) as exc:raise StopFailure('owned_children_unobservable') from exc
    if len(result)>128:raise StopFailure('owned_child_limit')
    return result

def successful(record):
    """Guard/monitor outcome is independent from the eventual raw child rc."""
    return (isinstance(record,dict) and type(record.get('exit_code')) is int
            and record['exit_code']==0 and record.get('reason')=='exited'
            and record.get('stop_verified') is True and record.get('errors')==[]
            and record.get('unknown_children')==[])

def stopped_identities(records):
    if not isinstance(records,list) or not records:return False
    for row in records:
        if not isinstance(row,dict) or set(row)!={'pid','start','uid'} or any(type(row[k]) is not int for k in row) or row['pid']<=1 or row['uid']!=os.getuid():return False
        try:now=_identity(row['pid'])
        except StopFailure:return False
        if now and now[1:3]==(row['start'],row['uid']):return False
    return True

def _run_owned(argv,timeout,evidence_attempt,cleanup_seconds=2,nonce='public-unit',cwd=None):
    """Only internally constructed fixed argv may call this private primitive."""
    if os.getuid()==0 or not 0<timeout<=600 or cleanup_seconds!=2:raise StopFailure('unsupported_supervision_boundary')
    me=os.getpid();mine=_identity(me)
    if mine is None:raise StopFailure('supervisor_identity_missing')
    baseline=_children(me,mine)  # No changed subreaper state on prelaunch failure.
    libc=ctypes.CDLL(None,use_errno=True);old=ctypes.c_int()
    if libc.prctl(37,ctypes.byref(old),0,0,0)!=0 or libc.prctl(36,1,0,0,0)!=0:raise StopFailure('subreaper_required')
    p=None;known={};unknown=set();errors=[];captured=bytearray();reason='exited';selector=selectors.DefaultSelector();status=None
    def discover():
        # Discover only a registered process's own children. A previously unseen
        # adopted child is never guessed to belong to the workload or signaled.
        pending=list(known)
        while pending:
            parent=pending.pop();ident=known[parent]
            for pid in _children(parent,ident):
                if pid in known:continue
                child=_identity(pid)
                if child is None:continue
                if child[0]!=parent or child[2]!=os.getuid():raise StopFailure('descendant_identity_unbound')
                known[pid]=child;pending.append(pid)
                if len(known)>128:raise StopFailure('owned_child_limit')
        own=_children(me,mine)
        unknown.update(own-baseline-set(known))
    def observe():
        try:discover()
        except StopFailure as exc:
            if str(exc) not in errors:errors.append(str(exc))
    def send(sig):
        primary_unobservable=p is not None and p.pid not in known
        for pid,identity in list(known.items()):
            try:now=_identity(pid)
            except StopFailure as exc:
                if str(exc) not in errors:errors.append(str(exc))
                if p is not None and pid==p.pid:primary_unobservable=True
                continue
            if now and now[1:3]==identity[1:3] and now[3]!='Z':
                try:os.kill(pid,sig)
                except ProcessLookupError:pass
                except OSError:errors.append('owned_process_signal_failed')
        if primary_unobservable and p.poll() is None:
            # Direct unreaped Popen child cannot have its PID reused. This handle
            # permits stopping only that primary; descendant proof stays failed.
            try:p.send_signal(sig)
            except ProcessLookupError:pass
            except OSError:errors.append('primary_signal_failed')
    def drain(wait=0.01):
        nonlocal reason
        for key,_ in selector.select(wait):
            data=os.read(key.fileobj.fileno(),65536)
            if not data:selector.unregister(key.fileobj);continue
            available=1024*1024-len(captured);captured.extend(data[:available])
            if len(data)>available:
                if reason=='exited':reason='log_overflow'
                if 'log_output_limit_exceeded' not in errors:errors.append('log_output_limit_exceeded')
                return False
        return True
    try:
        p=subprocess.Popen(argv,cwd=cwd,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True,env={'PATH':'/usr/bin:/bin','LANG':'C.UTF-8','LC_ALL':'C.UTF-8','PYTHONDONTWRITEBYTECODE':'1'})
        try:identity=_identity(p.pid)
        except StopFailure as exc:errors.append(str(exc));identity=None
        if identity is not None:known[p.pid]=identity
        else:
            # A very short child may already be gone. Popen remains its sole
            # status owner; absent identity makes stop/cleanup unproven.
            errors.append('owner_identity_missing')
        os.set_blocking(p.stdout.fileno(),False);selector.register(p.stdout,selectors.EVENT_READ);deadline=time.monotonic()+timeout
        while True:
            observe()
            if not drain():reason='log_overflow';break
            status=p.poll()
            if status is not None:break
            if time.monotonic()>=deadline:reason='timeout';break
    finally:
        try:
            if p is not None:
                observe();send(signal.SIGTERM);end=time.monotonic()+0.25
                while time.monotonic()<end:
                    observe();p.poll();drain(0);time.sleep(0.01)
                send(signal.SIGKILL);end=time.monotonic()+1.70
                while time.monotonic()<end:
                    p.poll();observe()
                    for pid in known:
                        if pid==p.pid:continue  # Popen exclusively owns primary status.
                        try:os.waitpid(pid,os.WNOHANG)
                        except ChildProcessError:pass
                    if stopped_identities([{'pid':pid,'start':x[1],'uid':x[2]} for pid,x in known.items()]):break
                    time.sleep(0.01)
                status=p.poll()
                if status is None:errors.append('primary_reap_failed')
                if not stopped_identities([{'pid':pid,'start':x[1],'uid':x[2]} for pid,x in known.items()]):errors.append('owned_process_reap_failed')
                if unknown:errors.append('unknown_adopted_child_STOP_UNPROVEN')
        finally:
            try:evidence_attempt()
            except Exception:errors.append('evidence_attempt_failed')
            selector.close()
            if p is not None:p.stdout.close()
            libc.prctl(36,old.value,0,0,0)
    stop_ok=bool(known) and not any(x!='evidence_attempt_failed' for x in errors)
    return {'schema_version':'package-stop.v1','nonce':nonce,'exit_code':status,'reason':reason,'captured_bytes':len(captured),'output':bytes(captured),'identities':[{'pid':pid,'start':x[1],'uid':x[2]} for pid,x in known.items()],'unknown_children':sorted(unknown),'stop_verified':stop_ok,'reaped':bool(known) and stopped_identities([{'pid':pid,'start':x[1],'uid':x[2]} for pid,x in known.items()]),'errors':errors,'cleanup_eligible':False}

import io
import json
import os
import pathlib
import sys
import tarfile
import tempfile
import time
import unittest
import zipfile
import warnings
from unittest import mock
import build_driver as d

class DriverTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='b13-driver-test-')
        self.root = pathlib.Path(self.tmp.name)
        self.env = {'PATH': os.defpath, 'HOME': str(self.root), 'LANG': 'C.UTF-8'}
    def tearDown(self): self.tmp.cleanup()
    def budget(self, **kw): return d.Budget(roots=[self.root], total=kw.pop('total', 4), reserve=.3, **kw)
    def run_child(self, text, budget=None):
        return d.run_process([sys.executable, '-I', '-c', text], self.root, self.env, budget or self.budget(), 'fixture')
    def test_normal_completion(self):
        r=self.run_child("print('ok')")
        self.assertEqual(r['exit_code'],0); self.assertEqual(r['output_bytes'],3)
        self.assertEqual(r['stdout'], 'ok\n')
    def test_nonzero_fails(self):
        with self.assertRaisesRegex(d.BuildError,'EXIT'): self.run_child('raise SystemExit(3)')
    def test_timeout_kills_descendant(self):
        marker=self.root/'pid'
        text="import subprocess,sys,pathlib,time; p=subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)']); pathlib.Path(%r).write_text(str(p.pid)); time.sleep(30)" % str(marker)
        start=time.monotonic()
        with self.assertRaisesRegex(d.BuildError,'DEADLINE'): self.run_child(text,self.budget(total=.8))
        self.assertLess(time.monotonic()-start,2)
        if marker.exists(): self.assertFalse(d.pid_running(int(marker.read_text())))
    def test_output_cap_fails(self):
        with self.assertRaisesRegex(d.BuildError,'OUTPUT'): self.run_child("print('x'*5000)",self.budget(log_cap=1024))
    def test_stale_directory_rejected(self):
        p=self.root/'delivery'; p.mkdir(); (p/'old.jar').write_text('old')
        with self.assertRaisesRegex(d.BuildError,'EXISTS'): d.fresh_dir(p)
        self.assertEqual((p/'old.jar').read_text(),'old')
    def test_storage_cap_fails(self):
        (self.root/'oversize').write_bytes(b'x'*2048)
        with self.assertRaisesRegex(d.BuildError,'STORAGE'): self.budget(storage_cap=1024).check_storage()
    def test_source_mismatch_fails_closed(self):
        with mock.patch.object(d,'run_process',return_value={'stdout':'wrong\n','exit_code':0}):
            with self.assertRaisesRegex(d.BuildError,'SOURCE'): d.source_identity(self.root,self.env,self.budget())
    def test_rss_cap_fails_and_cleans(self):
        actual=d.group_state; calls=0
        def oversized_once(pgid):
            nonlocal calls
            calls+=1
            return ([pgid],4096) if calls==1 else actual(pgid)
        with mock.patch.object(d,'group_state',side_effect=oversized_once):
            with self.assertRaisesRegex(d.BuildError,'MEMORY'): self.run_child('import time; time.sleep(2)',self.budget(rss_cap=1024))
    def test_cache_cap_checked_during_process(self):
        cache=self.root/'m2'; cache.mkdir(); (cache/'payload').write_bytes(b'x'*2048)
        b=self.budget(); b.cache_roots=[cache]; b.cache_cap=1024
        with self.assertRaisesRegex(d.BuildError,'CACHE'): self.run_child('import time; time.sleep(.1)',b)
    def test_archive_traversal_rejected(self):
        archive=self.root/'bad.tar.gz'
        with tarfile.open(archive,'w:gz') as t:
            x=tarfile.TarInfo('../escape'); x.size=1; t.addfile(x,io.BytesIO(b'x'))
        with self.assertRaisesRegex(d.BuildError,'ARCHIVE'): d.extract_archive(archive,self.root/'extract',self.budget())
        self.assertFalse((self.root.parent/'escape').exists())
    def test_manifest_too_large_no_success_file(self):
        out=self.root/'delivery'; out.mkdir()
        with self.assertRaisesRegex(d.BuildError,'MANIFEST'): d.write_manifest(out,{'x':'z'*1024},max_bytes=100)
        self.assertFalse((out/'build-manifest.json').exists())
    def test_manifest_published_atomically(self):
        out=self.root/'delivery'; out.mkdir()
        d.write_manifest(out,{'status':'BUILT_NOT_TESTED'})
        self.assertEqual(json.loads((out/'build-manifest.json').read_text())['status'],'BUILT_NOT_TESTED')
    def test_wrong_download_digest_rejected(self):
        p=self.root/'archive'; p.write_bytes(b'fixture')
        with self.assertRaisesRegex(d.BuildError,'DIGEST'): d.verify_digest(p,'sha256','0'*64,self.budget())

    def jar_fixture(self, entries):
        p=self.root/'fixture.jar'
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',UserWarning)
            with zipfile.ZipFile(p,'w') as z:
                for name,body in entries: z.writestr(name,body)
        return p
    def test_descriptor_correct_root_location(self):
        body='schema=runner-build.v1\nvariant=no-ibm-mq\nibm_mq=unsupported\n'
        p=self.jar_fixture([('META-INF/regression/runner-build.properties',body)])
        self.assertEqual(d.read_framework_metadata(p),body)
    def test_descriptor_boot_only_location_rejected(self):
        body='schema=runner-build.v1\nvariant=no-ibm-mq\nibm_mq=unsupported\n'
        p=self.jar_fixture([('BOOT-INF/classes/META-INF/regression/runner-build.properties',body)])
        with self.assertRaises(d.BuildError): d.read_framework_metadata(p)
    def test_descriptor_duplicate_resources_rejected(self):
        root='META-INF/regression/runner-build.properties'
        body='schema=runner-build.v1\nvariant=no-ibm-mq\nibm_mq=unsupported\n'
        for other in (root,'BOOT-INF/classes/'+root):
            with self.subTest(other=other):
                p=self.jar_fixture([(root,body),(other,body)])
                with self.assertRaises(d.BuildError): d.read_framework_metadata(p)
    def test_descriptor_wrong_values_rejected(self):
        for body in ('schema=runner-build.v2\nvariant=no-ibm-mq\nibm_mq=unsupported\n',
                     'schema=runner-build.v1\nvariant=no-ibm-mq\nibm_mq=unsupported\nextra=1\n',
                     'schema=runner-build.v1\nvariant=no-ibm-mq\nibm_mq=unsupported\nibm_mq=available\n',
                     'schema=runner-build.v1\nvariant=full\nibm_mq=available\n'):
            with self.subTest(body=body):
                p=self.jar_fixture([('META-INF/regression/runner-build.properties',body)])
                with self.assertRaises(d.BuildError): d.read_framework_metadata(p)
    def test_descriptor_missing_and_oversized_rejected(self):
        for entries in ([],[('META-INF/regression/runner-build.properties','x'*4097)]):
            with self.subTest(entries=len(entries)):
                with self.assertRaises(d.BuildError): d.read_framework_metadata(self.jar_fixture(entries))
    def test_descriptor_ibm_payload_rejected(self):
        body='schema=runner-build.v1\nvariant=no-ibm-mq\nibm_mq=unsupported\n'
        p=self.jar_fixture([('META-INF/regression/runner-build.properties',body),('BOOT-INF/lib/com.ibm.mq.allclient-10.0.0.0.jar',b'fixture')])
        with self.assertRaisesRegex(d.BuildError,'IBM'): d.read_framework_metadata(p)

    def junit_fixture(self, text, name='example.Case'):
        folder=self.root/'reports'; folder.mkdir(exist_ok=True)
        (folder/('TEST-'+name+'.xml')).write_text(text)
        return folder
    def test_junit_exact_nodes_and_counts_pass(self):
        p=self.junit_fixture('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="example.Case" name="caseOne"/></testsuite>')
        result=d.validate_junit(p,{'example.Case':1},self.budget())
        self.assertEqual(result['testcase_count'],1)
        with self.assertRaisesRegex(d.BuildError,'JUNIT'):
            d.validate_junit(p,{'example.Case':1},self.budget(),max_bytes=1)
    def test_junit_absent_class_fails(self):
        folder=self.root/'reports'; folder.mkdir()
        with self.assertRaisesRegex(d.BuildError,'JUNIT'): d.validate_junit(folder,{'example.Case':1},self.budget())
    def test_junit_bad_counter_or_duplicate_fails(self):
        for text in ('<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase classname="example.Case" name="caseOne"/></testsuite>',
                     '<testsuite tests="2" failures="0" errors="0" skipped="0"><testcase classname="example.Case" name="caseOne"/><testcase classname="example.Case" name="caseOne"/></testsuite>'):
            with self.subTest(text=text):
                with self.assertRaisesRegex(d.BuildError,'JUNIT'): d.validate_junit(self.junit_fixture(text),{'example.Case':1},self.budget())
    def test_junit_failure_error_or_skip_fails(self):
        for kind in ('failure','error','skipped'):
            text='<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="example.Case" name="caseOne"><'+kind+'/></testcase></testsuite>'
            with self.subTest(kind=kind):
                with self.assertRaisesRegex(d.BuildError,'JUNIT'): d.validate_junit(self.junit_fixture(text),{'example.Case':1},self.budget())
    def test_junit_entities_and_foreign_class_fail(self):
        for text in ('<!DOCTYPE testsuite [<!ENTITY x "fake">]><testsuite tests="0" failures="0" errors="0" skipped="0"/>',
                     '<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="other.Case" name="caseOne"/></testsuite>'):
            with self.subTest(text=text):
                with self.assertRaisesRegex(d.BuildError,'JUNIT'): d.validate_junit(self.junit_fixture(text),{'example.Case':1},self.budget())
    def test_failure_tail_is_bounded(self):
        try: self.run_child("print('x'*10000); raise SystemExit(2)")
        except d.BuildError as error:
            self.assertIn('output_tail=',str(error)); self.assertLess(len(str(error)),8400)
        else: self.fail('nonzero must fail')
        for raw in (b'\x00'*10000, b'\xff'*10000, ('中'*10000).encode(), b'"\\'*5000):
            tail=d.failure_tail(raw); self.assertLessEqual(len(tail.encode()),8192)
            self.assertIsInstance(json.loads(tail),str)
            self.assertLessEqual(len(json.dumps(tail).encode()),8192)
            reason='BuildError: EXIT: focused-tests-root=2; output_tail='+tail
            wire=json.dumps({'status':'FAILED_CLOSED','reason':reason})
            self.assertLess(len(wire.encode()),8400)

if __name__=='__main__': unittest.main()

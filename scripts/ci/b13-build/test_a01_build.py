import base64, hashlib, json, os, pathlib, tempfile, unittest
from unittest import mock
import a01_build as a
class Controls(unittest.TestCase):
 def test_descriptor_real(self):
  p=pathlib.Path(__file__).with_name('descriptor-control.txt')
  data=a.descriptor(p.read_bytes()); self.assertEqual(len(data),5868)
  for bad in (p.read_bytes()+p.read_bytes(),p.read_bytes().replace(b'0x0a',b'0x0b',1),b'wrong'):
   with self.assertRaises(a.d.BuildError): a.descriptor(bad)
 def test_descriptor_grammar(self):
  with self.assertRaises(a.d.BuildError): a.descriptor(b'var file_demo_proto_rawDesc = []byte{\n0x00, EVIL\n}')
 def test_patch(self):
  c={'old':'old','new':'new','expected_occurrences':1,'result_bytes':3,'result_sha256':hashlib.sha256(b'new').hexdigest()}
  self.assertEqual(a.patch(b'old',c),b'new')
  for s in (b'',b'oldold',b'old!'):
   with self.assertRaises(a.d.BuildError): a.patch(s,c)
 def test_identity(self):
  row={'bytes':1,'sha256':hashlib.sha256(b'x').hexdigest(),'git_blob':hashlib.sha1(b'blob 1\0x').hexdigest()}
  a.identity(b'x',row)
  with self.assertRaises(a.d.BuildError): a.identity(b'y',row)
 def test_env(self):
  with tempfile.TemporaryDirectory() as t:
   e=a.environment(pathlib.Path(t)); self.assertEqual(e['GOTOOLCHAIN'],'local'); self.assertEqual(e['GOPROXY'],'https://proxy.golang.org'); self.assertEqual(e['GOENV'],'off'); self.assertNotIn('TOKEN',e); self.assertEqual(e['GOMAXPROCS'],'2')
 def test_url(self):
  for s in ('http://go.dev/x','https://evil.test/x','https://go.dev@evil.test/x'):
   with self.assertRaises(a.d.BuildError): a.check_url(s)
  a.check_url('https://dl.google.com/go/go1.27.1.linux-amd64.tar.gz')
 def test_commands(self):
  c=a.commands('/go'); self.assertEqual(c[1][1:],['build','-p=2','-mod=readonly','-trimpath','-buildvcs=false','-o','productcatalogservice','.']); self.assertFalse(any('test' in x or 'run' in x for x in c))
 def test_delivery(self):
  with tempfile.TemporaryDirectory() as t:
   p=pathlib.Path(t); (p/'extra').write_text('x')
   with self.assertRaises(a.d.BuildError): a.delivery_check(p)
 def test_unchanged(self):
  with tempfile.TemporaryDirectory() as t:
   p=pathlib.Path(t); (p/'go.mod').write_bytes(b'x'); (p/'go.sum').write_bytes(b'y')
   a.unchanged(p,{'go.mod':b'x','go.sum':b'y'})
   with self.assertRaises(a.d.BuildError): a.unchanged(p,{'go.mod':b'z','go.sum':b'y'})
 def test_build_metadata(self):
  a.metadata('x: go1.27.1\n\tbuild\tCGO_ENABLED=0\n\tbuild\tGOARCH=amd64\n\tbuild\tGOOS=linux\n')
  with self.assertRaises(a.d.BuildError): a.metadata('wrong')
 def test_metadata_each_binding(self):
  good='x: go1.27.1\n\tbuild\tCGO_ENABLED=0\n\tbuild\tGOARCH=amd64\n\tbuild\tGOOS=linux\n'
  for token in ('go1.27.1','CGO_ENABLED=0','GOARCH=amd64','GOOS=linux'):
   with self.assertRaises(a.d.BuildError): a.metadata(good.replace(token,'wrong'))
 def test_delivery_exact_and_cap(self):
  with tempfile.TemporaryDirectory() as t:
   p=pathlib.Path(t); (p/'fixtures').mkdir()
   for name in a.OUTPUTS: (p/name).write_bytes(b'x')
   a.delivery_check(p)
   with mock.patch.object(a.d,'MIB',0):
    with self.assertRaises(a.d.BuildError): a.delivery_check(p)
 def test_delivery_symlink(self):
  with tempfile.TemporaryDirectory() as t:
   p=pathlib.Path(t); (p/'productcatalogservice').symlink_to('/dev/null')
   with self.assertRaises(a.d.BuildError): a.delivery_check(p)
 def test_identity_size_and_blob(self):
  row={'bytes':1,'sha256':hashlib.sha256(b'x').hexdigest(),'git_blob':hashlib.sha1(b'blob 1\0x').hexdigest()}
  for key in row:
   bad=dict(row); bad[key]=2 if key=='bytes' else '0'*64
   with self.assertRaises(a.d.BuildError): a.identity(b'x',bad)
 def test_fetch_digest_and_limit(self):
  import io
  for payload,cap,sha in [(b'x',1,'0'*64),(b'xx',1,hashlib.sha256(b'xx').hexdigest())]:
   with tempfile.TemporaryDirectory() as t:
    root=pathlib.Path(t); response=io.BytesIO(payload); response.geturl=lambda:'https://dl.google.com/go/x'; opener=mock.Mock(); opener.open.return_value=response
    with mock.patch.object(a.urllib.request,'build_opener',return_value=opener):
     with self.assertRaises(a.d.BuildError): a.fetch('https://dl.google.com/go/x',root/'x',cap,a.d.Budget([root],total=3,reserve=.1),sha)
 def test_redirect_rejected_before_follow(self):
  with self.assertRaises(a.d.BuildError): a.Redirect().redirect_request(None,None,302,'',{},'https://evil.test/x')
 def test_budget_caps_preserved(self):
  with tempfile.TemporaryDirectory() as t:
   b=a.d.Budget([t],total=1440,reserve=30,storage_cap=2048*a.d.MIB,rss_cap=3072*a.d.MIB)
   self.assertEqual(b.cache_cap,1024*a.d.MIB); self.assertEqual(b.storage_cap,2048*a.d.MIB); self.assertEqual(b.reserve,30)
if __name__=='__main__': unittest.main()

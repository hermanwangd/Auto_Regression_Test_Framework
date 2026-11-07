"""Source invariants only; these tests do not substitute for Helm lint/template."""
import json,unittest
from common import D
class ChartSourceTest(unittest.TestCase):
 def setUp(self):self.root=D/'helm/framework-runner';self.values=json.loads((self.root/'values.yaml').read_text());self.job=(self.root/'templates/job.yaml').read_text()
 def test_default_disabled(self):self.assertIs(self.values['deployment']['enabled'],False);self.assertIn('if .Values.deployment.enabled',self.job)
 def test_no_bundled_testware_or_dependencies(self):self.assertFalse((self.root/'charts').exists());self.assertFalse(any(line.startswith('dependencies:') for line in (self.root/'Chart.yaml').read_text().splitlines()))
 def test_no_cluster_lookup_or_template_injection(self):
  source='\n'.join(p.read_text() for p in (self.root/'templates').iterdir())
  for text in ['lookup ', 'tpl ', 'randAlpha', 'now ', 'hostPath:', 'hostNetwork:', 'ttlSecondsAfterFinished:', 'kind: Secret','kind: Role','kind: ServiceAccount']:
   self.assertNotIn(text,source)
 def test_nonroot_readonly_and_retention(self):
  for text in ['runAsUser: 65532','runAsNonRoot: true','readOnlyRootFilesystem: true','allowPrivilegeEscalation: false','automountServiceAccountToken: false','helm.sh/resource-policy: keep','activeDeadlineSeconds:','readOnly: true','drop: ["ALL"]']:
   self.assertIn(text,self.job)
 def test_fixed_actual_CLI_seams(self):
  for text in ['--expected-handoff-sha256','runner-import','--expected-spec-sha256','--collection-spec','--source','--out']:
   self.assertIn(text,self.job)
  self.assertNotIn('runner-execute',self.job);self.assertNotIn('runner-export',self.job)
 def test_schema_disallows_user_command_or_env(self):
  schema=json.loads((self.root/'values.schema.json').read_text());self.assertFalse(schema['additionalProperties']);self.assertNotIn('command',schema['properties']);self.assertNotIn('env',schema['properties'])

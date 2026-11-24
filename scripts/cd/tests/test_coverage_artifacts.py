"""Static Azure staging contracts; no live Azure execution."""
import fnmatch
import json
from pathlib import Path
import tempfile
import unittest
from scripts.cd.collect_coverage_evidence import collect

ROOT = Path(__file__).resolve().parents[3]


class CoverageArtifactsTests(unittest.TestCase):
    def test_ci_allowlist_retains_only_named_coverage_and_instrumentation(self):
        text = (ROOT / 'azure-pipelines.yml').read_text()
        required = ['target/site/jacoco/jacoco.xml', 'target/site/jacoco/jacoco.csv', 'target/site/jacoco/**',
                    'integrations/swarm-product-test/target/site/jacoco/jacoco.xml',
                    'integrations/swarm-product-test/target/site/jacoco/jacoco.csv',
                    'integrations/swarm-product-test/target/site/jacoco/**',
                    '**/outputs/product-test-*/coverage/coverage.json', '**/outputs/product-test-*/coverage/coverage.md']
        for path in required:
            self.assertIn(path, text)
        patterns = [line.strip() for line in text.splitlines() if '/coverage/' in line and not line.lstrip().startswith('#')]
        for path in ('run/outputs/product-test-1/coverage/raw.log', 'run/outputs/product-test-1/coverage/secret.json'):
            self.assertFalse(any(fnmatch.fnmatch(path, pattern) for pattern in patterns))
        self.assertIn('failTaskOnMissingResultsFile: true', text)
        self.assertIn('condition: always()', text)

    def test_cd_collector_adds_pair_outside_canonical_tree_without_raw_logs(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / 'source'; source.mkdir()
            target = Path(tmp) / 'stage'
            (source / 'gate-result.json').write_text(json.dumps({'framework_verdict': 'failed'}))
            cov = source / 'runtime/target/coverage/run'; cov.mkdir(parents=True)
            for name in ('coverage.json', 'coverage.md', 'raw.log', 'secret.json'):
                (cov / name).write_text('{}')
            self.assertIn('python3 -B scripts/cd/collect_coverage_evidence.py',
                          (ROOT / 'pipelines/azure/sit-postdeploy.yml').read_text())
            collect(source, target)
            files = json.loads((target / 'collection.json').read_text())['files']
            self.assertIn('runtime/target/coverage/run/coverage.json', files)
            self.assertIn('runtime/target/coverage/run/coverage.md', files)
            self.assertNotIn('runtime/target/coverage/run/raw.log', files)
            self.assertNotIn('runtime/target/coverage/run/secret.json', files)

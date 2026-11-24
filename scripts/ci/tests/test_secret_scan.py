import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCANNER = Path(__file__).resolve().parents[1] / "secret-scan.sh"
RULE = "exact_raw_artifact_sha_and_attestor_authorized_by_external_pinned_authority"
SCHEMA = "schemas/coverage_authority.v1.schema.yaml"
PACKAGED_SCHEMA = "docs/02-architecture/contracts/coverage_authority.v1.schema.yaml"


class SecretScanTest(unittest.TestCase):
    def check_scan(self, files, expected):
        for engine in ("rg", "python"):
            with self.subTest(engine=engine), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                script = root / "scripts/ci/secret-scan.sh"
                script.parent.mkdir(parents=True)
                shutil.copyfile(SCANNER, script)
                commands = root / "bin"
                commands.mkdir()
                (commands / "dirname").symlink_to(shutil.which("dirname"))
                (commands / "python3").symlink_to(sys.executable)
                if engine == "rg":
                    rg = shutil.which("rg")
                    if rg is None:
                        continue
                    (commands / "rg").symlink_to(rg)
                for name, content in files.items():
                    dest = root / name
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_text(content + "\n")
                result = subprocess.run(
                    ["/bin/bash", str(script)], cwd=root,
                    env={"PATH": str(commands), "LC_ALL": "C"}, capture_output=True, text=True,
                )
                self.assertEqual(result.returncode, expected, result.stdout + result.stderr)
                marker = "failed" if expected else "passed"
                self.assertIn("secret_scan_status: " + marker, result.stdout + result.stderr)

    def test_schema_rule_and_unbound_protocol_are_clean(self):
        self.check_scan({
            SCHEMA: "  authorization: " + RULE,
            PACKAGED_SCHEMA: "  authorization: " + RULE,
            "Url.java": 'String url = "jdbc:' + 'mariadb://" + endpoint;',
        }, 0)

    def test_raw_values_are_still_rejected(self):
        for name, text in [
            (SCHEMA, "  authorization: " + RULE + "_unexpected"),
            (SCHEMA, "  authorization: " + RULE.upper()),
            (PACKAGED_SCHEMA, "  authorization: " + RULE + "\n  password: " + "example-only-123"),
            ("settings.yml", "authorization: " + "example-only-123"),
            ("settings.yml", "password: " + RULE),
            ("settings.yml", "authorization: " + RULE),
            ("Db.java", 'String url = "jdbc:' + 'mariadb://db.invalid/example";'),
            ("Db.java", 'String url = "jdbc:' + 'oracle:thin:@db.invalid";'),
            ("settings.yml", "token: " + "example-only-123"),
            ("settings.yml", "api_key: " + "example-only-123"),
            ("key.txt", "-----BEGIN " + "RSA PRIVATE KEY-----"),
        ]:
            with self.subTest(name=name, text=text):
                self.check_scan({name: text}, 1)


if __name__ == "__main__":
    unittest.main()

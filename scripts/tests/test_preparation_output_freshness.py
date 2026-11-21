"""Evidence output freshness only; never launches Java, HTTP, retrieval or provider processes."""
import importlib.util
import pathlib
import tempfile
import unittest

SOURCE = pathlib.Path(__file__).resolve().parents[1] / "verify-knowledge-preparation.py"
spec = importlib.util.spec_from_file_location("preparation_verifier", SOURCE)
subject = importlib.util.module_from_spec(spec)
spec.loader.exec_module(subject)

class OutputFreshnessTest(unittest.TestCase):
    def test_default_runs_cannot_inherit_previous_success(self):
        with tempfile.TemporaryDirectory() as path:
            module = pathlib.Path(path)
            old = module / "target/knowledge-preparation"
            old.mkdir(parents=True)
            (old / "manifest.json").write_text("OLD_SUCCESS")
            first = subject.fresh_output_dir(module)
            (first / "manifest.json").write_text("FIRST_SUCCESS")
            second = subject.fresh_output_dir(module)
            self.assertNotEqual(first, second)
            self.assertEqual([], list(second.iterdir()))
            self.assertEqual("OLD_SUCCESS", (old / "manifest.json").read_text())
            self.assertEqual(0o700, second.stat().st_mode & 0o777)

    def test_explicit_output_reuse_rejected_without_touching_old_receipt(self):
        with tempfile.TemporaryDirectory() as path:
            module = pathlib.Path(path)
            output = subject.fresh_output_dir(module, module / "exact-run")
            (output / "receipt.json").write_text("PRESERVE")
            with self.assertRaises(FileExistsError):
                subject.fresh_output_dir(module, output)
            self.assertEqual("PRESERVE", (output / "receipt.json").read_text())
            self.assertEqual(0o700, output.stat().st_mode & 0o777)

if __name__ == "__main__":
    unittest.main()

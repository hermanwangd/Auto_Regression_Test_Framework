"""Regression checks across the three independently distributable sample runners."""
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parent
READERS = (
    ROOT / "kimi-cli-validation/scripts/junit_reader.py",
    ROOT / "kimi-cli-validation/r4-fix1/scripts/junit_reader.py",
    ROOT / "kimi-db-validation/fix1/junit_reader.py",
)


class JUnitReaderTests(unittest.TestCase):
    def test_standalone_readers_have_the_same_policy(self):
        implementation = READERS[0].read_bytes()
        for path in READERS[1:]:
            self.assertEqual(implementation, path.read_bytes(), str(path))

    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("junit_reader", READERS[0])
        cls.reader = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.reader)

    def test_reads_character_references_and_direct_children(self):
        root = self.reader.parse_report(
            b'<testsuite><testcase name="a&amp;b"><failure>one&#10;two</failure></testcase></testsuite>'
        )
        case, = root.iter("testcase")
        self.assertEqual("a&b", case.attrib["name"])
        self.assertEqual("one\ntwo", case.find("failure").text)
        self.assertIsNone(root.find("failure"))

    def test_rejects_doctype_in_supported_encodings(self):
        for encoding in ("utf-8", "utf-16"):
            with self.subTest(encoding=encoding), self.assertRaisesRegex(self.reader.ParseError, "DTD"):
                self.reader.parse_report("<!DOCTYPE testsuite><testsuite/>".encode(encoding))

    def test_rejects_excessive_nesting_and_elements(self):
        for report in (
            b"<a>" * (self.reader.MAX_DEPTH + 1) + b"</a>" * (self.reader.MAX_DEPTH + 1),
            b"<a>" + b"<b/>" * self.reader.MAX_ELEMENTS + b"</a>",
        ):
            with self.assertRaisesRegex(self.reader.ParseError, "limit"):
                self.reader.parse_report(report)

    def test_bounds_regular_file_reads_and_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as folder:
            report = Path(folder) / "report.xml"
            report.write_bytes(b"<testsuite/>")
            self.assertEqual(self.reader.read_report(report), b"<testsuite/>")
            with report.open("wb") as stream:
                stream.truncate(self.reader.MAX_BYTES + 1)
            with self.assertRaisesRegex(self.reader.ParseError, "limit"):
                self.reader.read_report(report)
            alias = Path(folder) / "alias.xml"
            try:
                alias.symlink_to(report)
            except OSError as exc:
                if os.name != "nt" or exc.winerror != 1314:
                    raise
                # Standard Windows users can create a directory junction even
                # without the Create Symbolic Links privilege.
                import _winapi
                target = Path(folder) / "junction-target"
                target.mkdir()
                _winapi.CreateJunction(str(target), str(alias))
            with self.assertRaises(OSError):
                self.reader.read_report(alias)

    def test_rejects_non_regular_device(self):
        device = "NUL" if os.name == "nt" else "/dev/null"
        with self.assertRaisesRegex(self.reader.ParseError, "regular"):
            self.reader.read_report(device)


if __name__ == "__main__":
    unittest.main()

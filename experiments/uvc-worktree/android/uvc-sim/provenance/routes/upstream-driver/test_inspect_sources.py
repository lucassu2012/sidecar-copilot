#!/usr/bin/env python3
"""Offline integrity regression checks; exercises only our Python verifier."""
import argparse
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.dont_write_bytecode = True
import inspect_sources as subject

parser = argparse.ArgumentParser()
parser.add_argument("--cache", type=Path, default=subject.DEFAULT_CACHE)
options = parser.parse_args()


class IntegrityChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.lock = json.loads((subject.HERE / "inputs.lock.json").read_text(encoding="utf-8"))
        cls.inputs = subject.read_inputs(cls.lock, options.cache)
        cls.evidence = subject.make_report(cls.lock, cls.inputs)
        cls.item = next(i for i in cls.lock["inputs"] if i["name"] == "libusb/libusb/version.h")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="integrity-check-", dir=options.cache)
        self.directory = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def test_01_full_locked_offline_replay(self):
        subject.check_report(subject.HERE / "evidence.json", self.evidence)
        self.assertEqual(49, len(self.inputs))

    def test_02_missing_input_fails_offline(self):
        with self.assertRaisesRegex(ValueError, "missing locked input"):
            subject.read_inputs({"inputs": [self.item]}, self.directory)

    def test_03_same_length_modified_input_fails(self):
        data = self.inputs[self.item["name"]].encode()
        with self.assertRaisesRegex(ValueError, "SHA/size mismatch"):
            subject.verify_bytes(bytes([data[0] ^ 1]) + data[1:], self.item)

    def test_04_payload_plus_receipt_does_not_override_tracked_lock(self):
        target = subject.safe_path(self.directory, self.item["name"])
        target.parent.mkdir(parents=True)
        data = b"x" * self.item["bytes"]
        target.write_bytes(data)
        target.with_suffix(".receipt.json").write_text(json.dumps({"sha256": subject.digest(data)}))
        with self.assertRaisesRegex(ValueError, "SHA/size mismatch"):
            subject.read_inputs({"inputs": [self.item]}, self.directory)

    def test_05_missing_report_fails(self):
        with self.assertRaisesRegex(ValueError, "report missing"):
            subject.check_report(self.directory / "missing.json", self.evidence)

    def test_06_modified_report_fails(self):
        target = self.directory / "evidence.json"
        target.write_text("{}\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "differs"):
            subject.check_report(target, self.evidence)

    def test_07_explicit_write_does_not_replace_changed_report(self):
        target = self.directory / "evidence.json"
        target.write_text("{}\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
            subject.check_report(target, self.evidence, write=True)

    def test_08_cache_escape_rejected(self):
        for name in ("../elsewhere", "/absolute", "C:/absolute", "x\\y"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                subject.safe_path(self.directory, name)

    def test_09_tree_identity_rejected(self):
        inputs = copy.copy(self.inputs)
        tree = json.loads(inputs["libuvc-tree.json"])
        tree["truncated"] = True
        inputs["libuvc-tree.json"] = json.dumps(tree)
        with self.assertRaisesRegex(ValueError, "incomplete or mismatched tree"):
            subject.make_report(self.lock, inputs)

    def test_10_release_identity_rejected(self):
        inputs = copy.copy(self.inputs)
        release = json.loads(inputs["libuvc-release.json"])
        release["tag_name"] = "v0.0.8"
        inputs["libuvc-release.json"] = json.dumps(release)
        with self.assertRaisesRegex(ValueError, "release/tag identity mismatch"):
            subject.make_report(self.lock, inputs)

    def test_11_blob_identity_rejected(self):
        item = copy.copy(self.item)
        item["git_blob_sha1"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "Git blob mismatch"):
            subject.verify_bytes(self.inputs[item["name"]].encode(), item)


if __name__ == "__main__":
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(IntegrityChecks))
    sys.exit(0 if result.wasSuccessful() else 1)

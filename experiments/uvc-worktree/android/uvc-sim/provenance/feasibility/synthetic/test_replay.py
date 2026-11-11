"""Research harness integrity checks; no native dependencies or source execution."""
import io
import base64
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import pixel_contracts as model


class ReplayIntegrity(unittest.TestCase):
    def setUp(self):
        model.DEFAULT_CACHE.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="integrity-", dir=model.DEFAULT_CACHE)
        self.addCleanup(self.temporary.cleanup)
        self.cache = Path(self.temporary.name)
        self.payload = b"fixed research input\n"
        self.entry = {"name": "fixture.bin", "size": len(self.payload),
                      "sha256": model.sha(self.payload), "url": "https://example.invalid/fixed"}

    def test_missing_offline_fails_without_network(self):
        with patch.object(model.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
            with self.assertRaisesRegex(ValueError, "offline input missing"):
                model.load_locked(self.entry, self.cache, [])

    def test_corrupt_first_cache_fails_even_if_fallback_is_good(self):
        (self.cache / self.entry["name"]).write_bytes(b"tampered")
        fallback = self.cache / "fallback"
        fallback.mkdir()
        (fallback / self.entry["name"]).write_bytes(self.payload)
        with self.assertRaisesRegex(ValueError, "locked input mismatch"):
            model.load_locked(self.entry, self.cache, [fallback], fetch=True)
        self.assertEqual((self.cache / self.entry["name"]).read_bytes(), b"tampered")

    def test_empty_cache_fetch_checks_lock_then_replays_offline(self):
        with patch.object(model.urllib.request, "urlopen", return_value=io.BytesIO(self.payload)):
            self.assertEqual(model.load_locked(self.entry, self.cache, [], fetch=True), self.payload)
        with patch.object(model.urllib.request, "urlopen", side_effect=AssertionError("network forbidden")):
            self.assertEqual(model.load_locked(self.entry, self.cache, []), self.payload)

    def test_wrong_download_never_becomes_cache(self):
        with patch.object(model.urllib.request, "urlopen", return_value=io.BytesIO(b"different")):
            with self.assertRaisesRegex(ValueError, "locked input mismatch"):
                model.load_locked(self.entry, self.cache, [], fetch=True)
        self.assertFalse((self.cache / self.entry["name"]).exists())

    def test_gitiles_text_locks_decoded_bytes_and_records_raw_transport(self):
        entry = {**self.entry, "transport_encoding": "base64"}
        raw = base64.b64encode(self.payload) + b"\n"
        with patch.object(model.urllib.request, "urlopen", return_value=io.BytesIO(raw)):
            self.assertEqual(model.load_locked(entry, self.cache, [], fetch=True), self.payload)
        receipt = json.loads((self.cache / "fixture.bin.transport.json").read_text())
        self.assertEqual(receipt["observed_transport_sha256"], model.sha(raw))
        self.assertEqual(receipt["verified_content_sha256"], self.entry["sha256"])
        self.assertEqual(model.load_locked(entry, self.cache, []), self.payload)

    def test_same_size_byte_flip_fails_hash(self):
        modified = bytearray(self.payload)
        modified[1] ^= 1
        (self.cache / self.entry["name"]).write_bytes(modified)
        with self.assertRaisesRegex(ValueError, "locked input mismatch"):
            model.load_locked(self.entry, self.cache, [])

    def test_recorded_numerical_report_matches_reexecution(self):
        record = json.loads((model.HERE / "results.json").read_text(encoding="utf-8"))
        record.pop("source_evidence")
        recorded_script_sha = record.pop("experiment_script_sha256")
        self.assertEqual(recorded_script_sha, model.sha(Path(model.__file__).read_bytes()))
        self.assertEqual(record, model.experiment())

    def test_hardcoded_oracle_catches_wrong_red_blue_shuffle(self):
        with patch.object(model, "abgr_bytes_to_argb", side_effect=lambda pixels: bytes(pixels)):
            with self.assertRaisesRegex(ValueError, "oracle failed"):
                model.experiment()

    def test_hardcoded_oracle_catches_preserving_rgbx_alpha(self):
        original = model.to_owned_rgba

        def wrong_alpha(borrowed, width, height, stride, encoding, capacity, budget, before_pixel=None):
            return original(borrowed, width, height, stride, "RGBA", capacity, budget, before_pixel)

        with patch.object(model, "to_owned_rgba", side_effect=wrong_alpha):
            with self.assertRaisesRegex(ValueError, "oracle failed: RGBX color bars"):
                model.experiment()

    def test_hardcoded_oracle_catches_wrong_yuy2_rounding_branch(self):
        original = model.argb_bytes_to_yuy2
        with patch.object(model, "argb_bytes_to_yuy2",
                          side_effect=lambda data, w, h, branch="sum_round":
                          original(data, w, h, "pavgb_truncate")):
            with self.assertRaisesRegex(ValueError, "oracle failed"):
                model.experiment()


if __name__ == "__main__":
    unittest.main()

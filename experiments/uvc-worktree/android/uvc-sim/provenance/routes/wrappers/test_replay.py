import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import inspect_wrappers as study


class IntegrityTests(unittest.TestCase):
    def setUp(self):
        study.CACHE.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="check-", dir=study.CACHE)
        self.addCleanup(self.temporary.cleanup)
        self.cache = Path(self.temporary.name)
        self.data = b"fixed UTF-8 source\r\n"
        self.entry = {"path": "fixture.c", "size": len(self.data),
                      "git_blob_sha": study.blob(self.data), "sha256": study.sha(self.data),
                      "cache_file": "fixture.txt", "url": "https://example.invalid/fixed/fixture.c"}

    def test_missing_offline_source_refused(self):
        with self.assertRaisesRegex(ValueError, "offline source missing"):
            study.source_bytes(self.entry, self.cache, False)

    def test_corrupt_existing_source_not_replaced(self):
        bad = bytes([self.data[0] ^ 1]) + self.data[1:]
        file = self.cache / self.entry["cache_file"]
        file.write_bytes(bad)
        with patch.object(study.urllib.request, "urlopen", side_effect=AssertionError("no refetch")):
            with self.assertRaisesRegex(ValueError, "Git blob mismatch"):
                study.source_bytes(self.entry, self.cache, True)
        self.assertEqual(file.read_bytes(), bad)

    def test_empty_cache_download_preserves_source_bytes(self):
        with patch.object(study.urllib.request, "urlopen", return_value=io.BytesIO(self.data)):
            self.assertEqual(study.source_bytes(self.entry, self.cache, True), self.data)
        self.assertEqual(study.source_bytes(self.entry, self.cache, False), self.data)

    def test_wrong_download_is_not_saved(self):
        with patch.object(study.urllib.request, "urlopen", return_value=io.BytesIO(b"different")):
            with self.assertRaisesRegex(ValueError, "source size mismatch"):
                study.source_bytes(self.entry, self.cache, True)
        self.assertFalse((self.cache / self.entry["cache_file"]).exists())

    def test_metadata_serialization_does_not_change_projection(self):
        data = {"full_name": "owner/repo", "archived": False, "default_branch": "main"}
        formatted = json.loads(json.dumps(data, indent=7, sort_keys=True))
        self.assertEqual(study.project("repo", data), study.project("repo", formatted))

    def test_popularity_excluded_but_archived_drift_detectable(self):
        data = {"full_name": "owner/repo", "archived": False, "stargazers_count": 1}
        more_stars = {**data, "stargazers_count": 9999}
        archived = {**data, "archived": True}
        self.assertEqual(study.project("repo", data), study.project("repo", more_stars))
        self.assertNotEqual(study.project("repo", data), study.project("repo", archived))

    def test_existing_lock_cannot_be_bootstrapped_again(self):
        with self.assertRaisesRegex(ValueError, "bootstrap refuses existing lock"):
            study.bootstrap(self.cache / "nonexistent.json", self.cache)

    def test_stored_report_replays(self):
        record = json.loads((study.HERE / "static-report.json").read_text(encoding="utf-8"))
        self.assertEqual(record.pop("script_sha256"), study.sha(Path(study.__file__).read_bytes()))
        self.assertEqual(study.replay(study.CACHE), record)


if __name__ == "__main__":
    unittest.main()

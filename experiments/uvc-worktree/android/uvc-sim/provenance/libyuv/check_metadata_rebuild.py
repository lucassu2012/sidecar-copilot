"""Rebuild just the two GitHub metadata inputs from an empty cache; no archive processing."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
CACHE = HERE.parents[1] / "build" / "provenance" / "libyuv"
EMPTY = CACHE / ("metadata-rebuild-" + uuid.uuid4().hex)
EMPTY.mkdir(parents=True)
report_before = hashlib.sha256((HERE / "report.json").read_bytes()).hexdigest()
results = []


def check(name, success, offline=True, diagnostic=None):
    command = [sys.executable, str(HERE / "verify_libyuv.py"), "--metadata-only", "--cache-dir", str(EMPTY)]
    if offline:
        command.append("--offline")
    result = subprocess.run(command, text=True, encoding="utf-8", capture_output=True, timeout=90)
    assert (result.returncode == 0) == success, name + ": " + result.stdout + result.stderr
    if diagnostic:
        assert diagnostic in result.stderr, name + ": unexpected failure"
    results.append({"check": name, "command": command, "exit_code": result.returncode,
                    "expected_success": success, "stdout": result.stdout, "stderr": result.stderr})


check("empty_metadata_cache_downloads_and_verifies", True, offline=False)
assert sorted(p.name for p in EMPTY.iterdir()) == ["import-tree.json", "vendor-history.json"]
check("downloaded_metadata_offline", True)
history = EMPTY / "vendor-history.json"
raw = history.read_bytes()
obj = json.loads(raw)
# Display-only GitHub account data is deliberately not source-provenance evidence.
obj[0]["author"] = {"login": "changed-display-only", "avatar_url": "changed-display-only"}
history.write_text(json.dumps(obj, indent=7), encoding="utf-8")
check("format_and_account_display_changes_preserve_identity", True)
obj[0]["commit"]["message"] += " TAMPER"
history.write_text(json.dumps(obj), encoding="utf-8")
check("commit_message_tampering_rejected", False, diagnostic="Cached artifact SHA-256/size mismatch")
history.write_bytes(raw)
tree = EMPTY / "import-tree.json"
tree_obj = json.loads(tree.read_bytes())
tree_obj["tree"][0]["sha"] = "0" * 40
tree.write_text(json.dumps(tree_obj), encoding="utf-8")
check("tree_blob_tampering_rejected", False, diagnostic="Cached artifact SHA-256/size mismatch")
assert hashlib.sha256((HERE / "report.json").read_bytes()).hexdigest() == report_before
output = {"schema": 1, "cache_started_empty": True, "downloaded_files": ["import-tree.json", "vendor-history.json"],
          "source_archives_downloaded_or_processed": False, "source_report_sha256_unchanged": report_before,
          "results": results}
(HERE / "metadata-rebuild-checks.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
print("PASS metadata rebuild: empty-cache HTTP and offline checks pass; formatting/display changes pass; commit/tree tampering fails")

"""Check replay and fail-closed integrity in an ignored cache copy."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
CACHE = HERE.parents[2] / "build" / "native-research" / "libyuv"
COPY = CACHE / ("replay-check-" + uuid.uuid4().hex)
COPY.mkdir(parents=True)
lock = json.loads((HERE / "inputs.json").read_text(encoding="utf-8"))
for name in lock["inputs"]:
    shutil.copyfile(CACHE / name, COPY / name)
before = hashlib.sha256((HERE / "report.json").read_bytes()).hexdigest()
results = []


def check(name, success, options, expected_error=None):
    command = [sys.executable, str(HERE / "verify_sources.py"), "--cache-dir", str(COPY), *options]
    completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", timeout=60)
    assert (completed.returncode == 0) == success, name + completed.stdout + completed.stderr
    if expected_error:
        assert expected_error in completed.stderr, name + " failed for an unexpected reason"
    results.append({"check": name, "command": command, "exit_code": completed.returncode,
                    "stdout": completed.stdout, "stderr": completed.stderr})


check("offline_replay_equals_recorded_report", True, ["--check-report"])
archive = COPY / "libyuv-official.tar.gz"
archive.write_bytes(archive.read_bytes() + b"tamper")
check("changed_source_archive_rejected", False, ["--inputs-only"], "cache integrity mismatch")
shutil.copyfile(CACHE / archive.name, archive)
missing = COPY / "saki-frame.c"
missing.rename(COPY / "saki-frame.c.missing")
check("missing_source_cache_rejected", False, ["--inputs-only"], "missing locked cache input")
shutil.copyfile(CACHE / missing.name, missing)
metadata = COPY / "import.commit.json"
raw = metadata.read_bytes()
obj = json.loads(raw)
obj["verification"] = {"verified_at": "display-only-update"}
metadata.write_text(json.dumps(obj, indent=7), encoding="utf-8")
check("metadata_format_and_display_changes_accepted", True, ["--inputs-only"])
obj["message"] += " altered"
metadata.write_text(json.dumps(obj), encoding="utf-8")
check("metadata_commit_change_rejected", False, ["--inputs-only"], "cache integrity mismatch")
metadata.write_bytes(raw)
metadata.rename(COPY / "import.commit.json.before-download")
check("missing_metadata_rebuilt_by_verified_http", True, ["--inputs-only", "--fetch-missing"])
tampered_report = COPY / "modified-report.json"
report = json.loads((HERE / "report.json").read_text(encoding="utf-8"))
report["unit_count"] += 1
tampered_report.write_text(json.dumps(report), encoding="utf-8")
check("modified_report_rejected", False, ["--check-report", "--report-file", str(tampered_report)], "Stored report differs")
assert hashlib.sha256((HERE / "report.json").read_bytes()).hexdigest() == before
(HERE / "replay-checks.json").write_text(json.dumps({"schema": 1, "unchanged_report_sha256": before,
    "note": "Only ignored cache copies were changed; no upstream code was executed.", "results": results}, indent=2) + "\n", encoding="utf-8")
print("PASS replay, missing/tampered source rejection, semantic metadata checks and report consistency")

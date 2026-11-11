"""Exercise the provenance verifier against copied ignored caches, never upstream code."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
CACHE = HERE.parents[1] / "build" / "provenance" / "libyuv"
COPY = CACHE / ("integrity-check-" + uuid.uuid4().hex)
COPY.mkdir(parents=True)
lock = json.loads((HERE / "artifact-lock.json").read_text(encoding="utf-8"))
for name in lock["artifacts"]:
    shutil.copyfile(CACHE / name, COPY / name)
report_before = hashlib.sha256((HERE / "report.json").read_bytes()).hexdigest()
results = []


def check(name, expected, extra=(), diagnostic=None):
    command = [sys.executable, str(HERE / "verify_libyuv.py"), "--offline", "--cache-dir", str(COPY), *extra]
    result = subprocess.run(command, text=True, encoding="utf-8", capture_output=True, timeout=45)
    assert (result.returncode == 0) == expected, name + ": " + result.stdout + result.stderr
    if diagnostic:
        assert diagnostic in result.stderr, name + ": unexpected failure: " + result.stderr
    results.append({"check": name, "command": command, "exit_code": result.returncode,
                    "expected_success": expected, "stdout": result.stdout, "stderr": result.stderr})


check("offline_intact_cache", True)
tamper = COPY / "3e38ce50589d9319badc0501f96d6c5b2b177472.tar.gz"
tamper.write_bytes(tamper.read_bytes() + b"tamper-test")
check("offline_tampered_official_archive", False, diagnostic="Cached artifact SHA-256/size mismatch")
shutil.copyfile(CACHE / tamper.name, tamper)
missing = COPY / "3e38ce50589d9319badc0501f96d6c5b2b177472.LICENSE.base64"
missing.rename(missing.with_suffix(".missing"))
check("offline_missing_license_material", False, diagnostic="Offline artifact missing")
assert hashlib.sha256((HERE / "report.json").read_bytes()).hexdigest() == report_before, "Verifier silently changed report.json"
output = {"schema": 1, "source_report_sha256_unchanged": report_before,
          "cache_copy": str(COPY), "results": results,
          "note": "Only copied cache files changed; primary cache, historical evidence and upstream code were not modified/executed."}
(HERE / "integrity-checks.json").write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
print("PASS integrity verifier: intact cache exits 0; tampered/missing cache exits nonzero; report unchanged")

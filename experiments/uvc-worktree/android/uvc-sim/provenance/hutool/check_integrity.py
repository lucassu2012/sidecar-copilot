"""Negative cache checks for trace.py; run with python -O as well.

Writes tiny synthetic fixtures only below the ignored provenance cache. Does not
change real downloaded inputs, access the network, or execute downloaded code.
"""
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import sys
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--existing-artifacts", required=True)
    args = parser.parse_args()
    here = Path(__file__).resolve().parent
    api = runpy.run_path(str(here / "trace.py"))
    globals_ = api["main"].__globals__
    fixtures = api["CACHE"] / "integrity-cases"
    fixtures.mkdir(parents=True, exist_ok=True)
    globals_["CACHE"] = fixtures
    result = {"python_optimization": sys.flags.optimize, "checks": {}}
    url = "https://repo.maven.apache.org/maven2/static-check-only"

    def expect_failure(name, function, fragment):
        try:
            function()
        except (ValueError, RuntimeError) as error:
            if fragment not in str(error):
                raise RuntimeError(f"wrong failure for {name}: {error}") from error
            result["checks"][name] = {"passed": True, "error": str(error)}
        else:
            raise RuntimeError(f"integrity gate did not reject: {name}")

    missing = "missing-" + uuid.uuid4().hex
    good = b"reviewed fixture"
    bad = b"tampered fixture"
    locked = {"request_url": url, "sha256": api["sha256"](good), "bytes": len(good)}
    expect_failure("offline_missing_input", lambda: api["download"]((missing, url), True, locked), "missing cached input")
    (fixtures / "tamper.bin").write_bytes(bad)
    (fixtures / "tamper.bin.http.json").write_text(json.dumps({"request_url": url, "sha256": api["sha256"](good)}))
    expect_failure("changed_payload", lambda: api["download"](("tamper.bin", url), True, locked), "cache metadata mismatch")
    (fixtures / "tamper.bin.http.json").write_text(json.dumps({"request_url": url, "sha256": api["sha256"](bad)}))
    expect_failure("changed_payload_and_cache_metadata", lambda: api["download"](("tamper.bin", url), True, locked), "locked input mismatch")
    report = here / "trace.json"
    before = hashlib.sha256(report.read_bytes()).hexdigest()
    original_argv = sys.argv
    try:
        sys.argv = ["trace.py", "--offline", "--existing-artifacts", args.existing_artifacts]
        expect_failure("offline_main_missing_inputs", api["main"], "missing cached input")
    finally:
        sys.argv = original_argv
    after = hashlib.sha256(report.read_bytes()).hexdigest()
    if before != after:
        raise RuntimeError("failed run modified trace.json")
    result["failed_run_preserved_report_sha256"] = after
    (here / "integrity-checks.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf8")
    print(json.dumps({"passed": len(result["checks"]), "python_optimization": sys.flags.optimize, "report_preserved": before == after}))


if __name__ == "__main__":
    main()

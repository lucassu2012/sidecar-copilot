"""Guard checks using ignored temporary copies; never runs a candidate binary.

Default checks do not rewrite recorded evidence. Use --write-report explicitly
to create the first integrity-results.json or replace a reviewed prior record.
"""
import argparse
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
CACHE = HERE.parents[2] / "build" / "routes" / "platform"
SCRIPT = HERE / "audit_platform_route.py"
PROTECTED = [HERE / "INPUT_LOCK.json", HERE / "static-evidence.json"]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(label, args, expected_success, marker, command=None):
    before = [digest(path) for path in PROTECTED]
    process = subprocess.run(command or [sys.executable, "-B", str(SCRIPT)] + args,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if (process.returncode == 0) != expected_success or marker not in process.stdout + process.stderr:
        raise AssertionError(label + ": " + process.stdout + process.stderr)
    if before != [digest(path) for path in PROTECTED]:
        raise AssertionError(label + ": trusted evidence changed")
    return {"label": label, "expected_success": expected_success, "return_code": process.returncode,
            "matched_diagnostic": marker, "trusted_evidence_unchanged": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write-report", action="store_true",
                        help="explicitly create or replace integrity-results.json")
    options = parser.parse_args()
    checks = [run("offline result replay", [], True, '"inputs_verified": 20')]
    first = json.loads(PROTECTED[0].read_text(encoding="utf-8"))["inputs"][0]
    original = (CACHE / (first["id"] + ".raw")).read_bytes()
    with tempfile.TemporaryDirectory(prefix="integrity-", dir=CACHE) as directory:
        temporary = Path(directory).resolve()
        if not temporary.is_relative_to(CACHE.resolve()):
            raise RuntimeError("temporary test directory escaped the designated cache")
        checks.append(run("missing cache", ["--cache-dir", str(temporary)], False, "missing locked input"))
        (temporary / "collection.lock").write_text("held-by-check", encoding="ascii")
        checks.append(run("busy collection lock", ["--cache-dir", str(temporary)], False, "collection lock busy"))
        if (temporary / "collection.lock").read_text() != "held-by-check":
            raise AssertionError("busy-lock check changed the existing lock contents")
        (temporary / "collection.lock").unlink()
        changed = bytearray(original)
        changed[0] ^= 1
        (temporary / (first["id"] + ".raw")).write_bytes(changed)
        for label, args in (("offline changed cache", []), ("online changed cache", ["--online"])):
            checks.append(run(label, args + ["--cache-dir", str(temporary)], False, "input integrity mismatch"))
        checks.append(run("existing lock cannot be bootstrapped again", ["--online", "--record-inputs", "--write-report"],
                          False, "bootstrap refuses to overwrite existing input lock"))
        # Altered/missing report checks redirect only HERE to a temporary copy;
        # fixed validated sources remain in the normal cache. No tracked file is
        # edited, and -B prevents import bytecode from being written.
        (temporary / "INPUT_LOCK.json").write_bytes(PROTECTED[0].read_bytes())
        changed_report = json.loads(PROTECTED[1].read_text(encoding="utf-8"))
        changed_report["inputs_verified"] = -1
        (temporary / "static-evidence.json").write_text(json.dumps(changed_report), encoding="utf-8")
        wrapper = ("import importlib.util,pathlib,sys; "
                   "s=importlib.util.spec_from_file_location('audit',sys.argv[1]); "
                   "m=importlib.util.module_from_spec(s); s.loader.exec_module(m); "
                   "m.HERE=pathlib.Path(sys.argv[2]); sys.argv=[sys.argv[1]]; m.main()")
        command = [sys.executable, "-B", "-c", wrapper, str(SCRIPT), str(temporary)]
        checks.append(run("changed computed report", [], False, "recomputed evidence differs", command))
        (temporary / "static-evidence.json").unlink()
        checks.append(run("missing computed report", [], False, "missing tracked report", command))
    if (CACHE / (first["id"] + ".raw")).read_bytes() != original:
        raise AssertionError("integrity checks changed the original cache")
    output = {"python": sys.version, "result": "pass", "scope": "Audit-tool guards only; not runtime, native, USB or PRD acceptance tests",
              "input_lock_sha256": digest(PROTECTED[0]), "evidence_sha256": digest(PROTECTED[1]), "checks": checks}
    if options.write_report:
        (HERE / "integrity-results.json").write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("PASS: offline replay, input/report tamper and missing-input/report rejection, busy lock, no repinning; evidence unchanged")


if __name__ == "__main__":
    main()

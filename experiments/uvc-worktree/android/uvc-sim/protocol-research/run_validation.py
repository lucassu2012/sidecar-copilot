"""Run only original Python protocol substitutes and record exact local inputs."""
import argparse
import ast
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RESULT = HERE / "validation.json"
COMMANDS = ["test_protocol.py", "explore_interleavings.py"]


def manifest():
    result = {}
    for path in sorted(HERE.rglob("*")):
        if not path.is_file() or path == RESULT or "__pycache__" in path.parts:
            continue
        if path.suffix not in {".py", ".md", ".json"} and path.name != ".gitattributes":
            raise ValueError("Unexpected research artifact: " + str(path))
        data = path.read_bytes()
        decoded = data.decode("utf-8")
        if "\0" in decoded or "\ufffd" in decoded:
            raise ValueError("Invalid text artifact: " + str(path))
        result[path.relative_to(HERE).as_posix()] = {
            "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()
        }
    return result


def imports():
    summary = {}
    for path in sorted(HERE.glob("*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
        found = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                found.update(name.name for name in node.names)
            elif isinstance(node, ast.ImportFrom) and node.module:
                found.add(node.module)
        summary[path.name] = sorted(found)
    return summary


def verify_local_source_baseline():
    baseline = json.loads((HERE / "source-baseline.json").read_text(encoding="utf-8"))
    expected_commit = "c8874f6f39dddcd39a63289787fc3ba7a42b2cad"
    if baseline["workspace_base_commit"] != expected_commit:
        raise ValueError("Unexpected source mapping baseline")
    entries = baseline["local_inputs"] + [baseline["mapping_document"]]
    for entry in entries:
        path = (ROOT / entry["path"]).resolve()
        if not path.is_relative_to(ROOT):
            raise ValueError("Baseline path escapes repository")
        data = path.read_bytes()
        if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError("Inherited source baseline differs: " + entry["path"])
    return len(entries)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true",
                        help="Create first validation record only; no silent overwrite")
    args = parser.parse_args()
    before = manifest()
    local_baseline_count = verify_local_source_baseline()
    if args.record:
        if RESULT.exists():
            raise ValueError("Validation record already exists")
    else:
        expected = json.loads(RESULT.read_text(encoding="utf-8"))
        if expected["files"] != before:
            raise ValueError("Research inputs differ from the recorded manifest")
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONUTF8="1")
    results = []
    for name in COMMANDS:
        process = subprocess.run([sys.executable, "-B", str(HERE / name)],
                                 cwd=HERE, env=env, capture_output=True,
                                 text=True, encoding="utf-8", timeout=180)
        result = {"command": ["python", "-B", name], "exit_code": process.returncode,
                  "stdout": process.stdout, "stderr": process.stderr}
        if name == "explore_interleavings.py" and process.returncode == 0:
            result["exploration"] = json.loads(process.stdout)
        results.append(result)
        print(("PASS " if process.returncode == 0 else "FAIL ") + name, flush=True)
        if process.returncode:
            raise RuntimeError(process.stdout + process.stderr)
    if before != manifest():
        raise ValueError("Checks changed research deliverables")
    output = {
        "schema": 1,
        "baseline_commit": "c8874f6f39dddcd39a63289787fc3ba7a42b2cad",
        "observed_at_utc": datetime.now(timezone.utc).isoformat(),
        "python": sys.version,
        "scope": "Original deterministic Python model and bounded interleavings only",
        "device_accessed": False,
        "ui_emulator_used": False,
        "third_party_runtime_downloaded_or_loaded": False,
        "native_or_jni_executed": False,
        "native_driver_tests_executed": 0,
        "inherited_local_files_and_mapping_verified": local_baseline_count,
        "files": before,
        "static_import_inventory": imports(),
        "import_inventory_limit": "AST inventory for review, not a sandbox or complete dynamic-call proof",
        "results": results,
    }
    if args.record:
        RESULT.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8", newline="\n")
    print(json.dumps({"command_groups_passed": len(results),
                      "manifest_files": len(before), "local_baseline_files_verified": local_baseline_count,
                      "native_driver_tests_executed": 0}))


if __name__ == "__main__":
    main()

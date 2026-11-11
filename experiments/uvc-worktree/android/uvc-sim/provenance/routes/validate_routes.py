"""Sequential, offline checks of research evidence; never invokes native code."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
RECORD = HERE / "validation.json"
PREFIX = "android/uvc-sim/provenance/routes/"
COMMANDS = [
    [PREFIX + "upstream-driver/inspect_sources.py"],
    [PREFIX + "upstream-driver/test_inspect_sources.py"],
    [PREFIX + "platform/audit_platform_route.py"],
    [PREFIX + "platform/check_integrity.py"],
    [PREFIX + "wrappers/inspect_wrappers.py"],
    ["-m", "unittest", "discover", "-s", PREFIX + "wrappers", "-p", "test_replay.py", "-v"],
    [PREFIX + "mjpeg-decoder/audit_decoder.py"],
]


def file_manifest():
    result = {}
    for path in sorted(HERE.rglob("*")):
        if not path.is_file() or path == RECORD or "__pycache__" in path.parts:
            continue
        if path.suffix not in {".md", ".py", ".json"}:
            raise ValueError("Unexpected deliverable type: " + str(path))
        data = path.read_bytes()
        text = data.decode("utf-8")
        if "\0" in text or "\ufffd" in text:
            raise ValueError("Non-text or replacement character: " + str(path))
        result[path.relative_to(HERE).as_posix()] = {
            "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()
        }
    return result


def local_links():
    count = 0
    for path in HERE.rglob("*.md"):
        for target in re.findall(r"\]\(([^)]+)\)", path.read_text(encoding="utf-8")):
            if target.startswith(("https://", "http://", "#")):
                continue
            linked = (path.parent / target.split("#", 1)[0]).resolve()
            if not linked.is_relative_to(ROOT) or not linked.is_file():
                raise ValueError("Invalid local link in " + str(path) + ": " + target)
            count += 1
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--record", action="store_true",
                        help="Create the first validation record; refuses overwrite")
    args = parser.parse_args()
    before = file_manifest()
    if args.record:
        if RECORD.exists():
            raise ValueError("Validation record already exists; no silent replacement")
    else:
        recorded = json.loads(RECORD.read_text(encoding="utf-8"))
        if recorded["files"] != before:
            raise ValueError("Research deliverables differ from recorded file manifest")
    # The report links to the record being produced; treat it as present only
    # during this first run, without writing a placeholder trusted result.
    if args.record:
        count = 0
        for path in HERE.rglob("*.md"):
            for target in re.findall(r"\]\(([^)]+)\)", path.read_text(encoding="utf-8")):
                if target.startswith(("https://", "http://", "#")):
                    continue
                linked = (path.parent / target.split("#", 1)[0]).resolve()
                if not linked.is_relative_to(ROOT) or (linked != RECORD and not linked.is_file()):
                    raise ValueError("Invalid local link in " + str(path) + ": " + target)
                count += 1
    else:
        count = local_links()
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", PYTHONUTF8="1")
    results = []
    for command in COMMANDS:
        proc = subprocess.run([sys.executable, "-B"] + command, cwd=ROOT, env=env,
                              capture_output=True, text=True, encoding="utf-8",
                              timeout=60)
        results.append({"command": ["python", "-B"] + command, "exit_code": proc.returncode,
                        "stdout": proc.stdout, "stderr": proc.stderr})
        print(("PASS " if proc.returncode == 0 else "FAIL ") + " ".join(command), flush=True)
        if proc.returncode:
            raise RuntimeError(proc.stdout + proc.stderr)
    if before != file_manifest():
        raise ValueError("A check changed the research deliverables")
    if args.record:
        record = {"schema": 1, "baseline_commit": "bbd053664bebb06ce83539a3f95e6fa5758745b7",
                  "observed_at_utc": datetime.now(timezone.utc).isoformat(),
                  "python": sys.version, "scope": "Offline evidence integrity only",
                  "upstream_native_executed": False, "hardware_accessed": False,
                  "native_capture_or_decode_tests_executed": 0,
                  "command_groups_passed": len(results), "local_links_checked": count,
                  "files": before, "results": results}
        RECORD.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8", newline="\n")
    print(json.dumps({"command_groups_passed": len(results),
                      "manifest_files": len(before), "local_links_checked": count,
                      "native_tests_executed": 0, "network_used_by_this_runner": False}))


if __name__ == "__main__":
    main()

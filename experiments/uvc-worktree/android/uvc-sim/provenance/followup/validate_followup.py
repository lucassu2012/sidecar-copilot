"""Sequential replay of reviewed audit tools; never runs downloaded native code."""
import argparse
import datetime
import hashlib
import json
import pathlib
import platform
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[3]
PREFIX = "android/uvc-sim/provenance/followup/"
BASELINE = "e189341cdf339be8bd6d72284dcec44bccfa7319"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video-existing-cache", type=pathlib.Path)
    parser.add_argument("--platform-rebuilt-cache", type=pathlib.Path)
    args = parser.parse_args()
    video = [PREFIX + "video-alternatives/inspect_sources.py"]
    if args.video_existing_cache:
        video += ["--existing-cache", str(args.video_existing_cache)]
    commands = [
        ("libyuv replay and integrity", [PREFIX + "libyuv/check_replay.py"]),
        ("UAC replay and integrity", [PREFIX + "uac/check_replay.py"]),
        ("video source replay", video),
        ("video integrity", ["-m", "unittest", "discover", "-s",
                             PREFIX + "video-alternatives", "-p", "test_inspection.py", "-v"]),
        ("platform replay and integrity", [PREFIX + "platform-alternatives/check_integrity.py"]),
    ]
    if args.platform_rebuilt_cache:
        commands.append(("platform independently reconstructed cache replay",
                         [PREFIX + "platform-alternatives/audit_platform.py", "--offline",
                          "--cache", str(args.platform_rebuilt_cache)]))
    results = []
    for label, options in commands:
        command = [sys.executable, "-B", *options]
        print("Running: " + label, flush=True)
        completed = subprocess.run(command, cwd=REPO, capture_output=True, text=True,
                                   encoding="utf-8", errors="strict", timeout=180)
        results.append({"name": label, "command": command, "exit_code": completed.returncode,
                        "stdout": completed.stdout, "stderr": completed.stderr})
        print(label + ": exit " + str(completed.returncode), flush=True)
        if completed.returncode:
            break
    files = []
    for path in sorted(HERE.rglob("*")):
        if not path.is_file() or "__pycache__" in path.parts or path.name == "VALIDATION.json":
            continue
        data = path.read_bytes()
        files.append({"path": path.relative_to(HERE).as_posix(), "size": len(data),
                      "sha256": hashlib.sha256(data).hexdigest()})
    passed = len(results) == len(commands) and all(row["exit_code"] == 0 for row in results)
    result = {"schema": 1, "recorded_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "baseline": BASELINE, "python": sys.version, "platform": platform.platform(),
              "working_directory": str(REPO), "passed": passed, "commands": results,
              "delivery_files": files,
              "scope": "Static audit input/replay integrity only. No application, native build or hardware tests.",
              "network_scope": "The libyuv integrity check re-fetches one missing fixed commit record into an isolated cache; other commands are offline.",
              "manifest_scope": "Hashes describe this delivery before commit. Re-running checks may regenerate timestamp/path-bearing validation logs.",
              "not_run": ["upstream code execution", "native compilation or loading", "real device capture",
                          "application regression (runtime unchanged)", "actual Gradle variant resolution"]}
    (HERE / "VALIDATION.json").write_bytes((json.dumps(result, indent=2, ensure_ascii=False) + "\n").encode())
    print(json.dumps({"passed": passed, "command_groups": len(results), "delivery_files": len(files)}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())

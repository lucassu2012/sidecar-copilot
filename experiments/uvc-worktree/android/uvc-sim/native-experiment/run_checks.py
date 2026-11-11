"""Compile and exercise only the isolated Java adapter with controlled backends."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BASELINE = HERE / "contract-baseline"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def locked_file(path, entry):
    data = path.read_bytes()
    if len(data) != entry["bytes"] or sha(data) != entry["sha256"]:
        raise ValueError("Input differs from fixed baseline: " + str(path))
    return data


def verify_baselines():
    lock = json.loads((BASELINE / "manifest.json").read_text(encoding="utf-8"))
    for entry in lock["snapshot_files"]:
        path = (BASELINE / entry["snapshot"]).resolve()
        if not path.is_relative_to(BASELINE):
            raise ValueError("Snapshot escapes baseline directory")
        locked_file(path, entry)
    review = json.loads((HERE / "upstream-review.json").read_text(encoding="utf-8"))
    for entry in review["references"]:
        path = (ROOT / entry["path"]).resolve()
        if not path.is_relative_to(ROOT):
            raise ValueError("Review escapes repository")
        locked_file(path, entry)
    return {"contract_text_inputs": len(lock["snapshot_files"]),
            "inherited_source_reviews": len(review["references"]),
            "main_contract_sources_rewritten": False}


def source_manifest(paths):
    return {path.relative_to(ROOT).as_posix(): {"bytes": path.stat().st_size,
             "sha256": sha(path.read_bytes())} for path in sorted(paths)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=Path(r"E:\tools\jdk-17.0.19+10"))
    args = parser.parse_args()
    baseline_check = verify_baselines()
    java = args.jdk / "bin/java.exe"
    javac = args.jdk / "bin/javac.exe"
    if not java.is_file() or not javac.is_file():
        raise FileNotFoundError("Provide an already installed JDK with --jdk")
    sources = sorted((BASELINE / "src").rglob("*.java"))
    own_sources = sorted((HERE / "src").rglob("*.java")) + sorted((HERE / "test").rglob("*.java"))
    if len(sources) != 3 or not own_sources or not any(p.name == "AdapterChecks.java" for p in own_sources):
        raise ValueError("Missing fixed public types or adapter test entry")
    sources += own_sources
    inputs = sources + [HERE / "run_checks.py", HERE / "upstream-review.json", BASELINE / "manifest.json"]
    before = source_manifest(inputs)
    output = (HERE / "build" / ("run-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid.uuid4().hex[:8])).resolve()
    if not output.is_relative_to((HERE / "build").resolve()):
        raise ValueError("Build output escapes research directory")
    output.mkdir(parents=True, exist_ok=False)
    classes = output / "classes"
    classes.mkdir()
    empty_path = output / "empty-path"
    empty_path.mkdir()
    environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    removed_environment = []
    for key in list(environment):
        if key.upper() in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH", "JDK_JAVAC_OPTIONS"}:
            removed_environment.append(key)
            del environment[key]
    commands = []

    def run(label, command, timeout):
        try:
            process = subprocess.run([str(a) for a in command], cwd=ROOT, capture_output=True,
                                     timeout=timeout, encoding="utf-8", errors="replace", text=True,
                                     env=environment)
        except subprocess.TimeoutExpired as error:
            stdout = error.stdout or ""
            stderr = error.stderr or ""
            if isinstance(stdout, bytes): stdout = stdout.decode("utf-8", errors="replace")
            if isinstance(stderr, bytes): stderr = stderr.decode("utf-8", errors="replace")
            (output / (label + ".stdout.txt")).write_text(stdout, encoding="utf-8", newline="\n")
            (output / (label + ".stderr.txt")).write_text(stderr, encoding="utf-8", newline="\n")
            commands.append({"label": label, "argv": [str(a) for a in command],
                             "exit_code": None, "timed_out": True, "stdout": stdout, "stderr": stderr})
            raise RuntimeError(label + " exceeded harness deadline; this is a test failure, not a drain receipt") from error
        (output / (label + ".stdout.txt")).write_text(process.stdout, encoding="utf-8", newline="\n")
        (output / (label + ".stderr.txt")).write_text(process.stderr, encoding="utf-8", newline="\n")
        commands.append({"label": label, "argv": [str(a) for a in command],
                         "exit_code": process.returncode, "stdout": process.stdout, "stderr": process.stderr})
        if process.returncode != 0:
            raise RuntimeError(label + " failed: " + process.stdout + process.stderr)
        return process

    failure = None
    try:
        run("jdk-version", [java, "-version"], 15)
        run("compile", [javac, "--release", "8", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
                        "-proc:none", "-implicit:none", "-classpath", empty_path, "-sourcepath", empty_path,
                        "-d", classes] + sources, 60)
        run("adapter-checks", [java, "-cp", classes, "local.sidecar.copilot.uvcadapter.AdapterChecks"], 90)
        if before != source_manifest(inputs):
            raise ValueError("Source changed during adapter checks")
        verify_baselines()
    except Exception as error:
        failure = str(error)
    result = {"schema": 1, "adapter_git_baseline": "e02b3fd93da49d4e1a6f76c3e3dd0f7d99c325c5",
              "scope": "Original Java CaptureSession adapter and controlled backend only",
              "completed_utc": datetime.now(timezone.utc).isoformat(),
              "pass": failure is None, "failure": failure, "baseline_check": baseline_check,
              "removed_java_environment_keys": sorted(removed_environment),
              "inputs": before, "commands": commands,
              "jdk_launchers": {str(path): sha(path.read_bytes()) for path in (java, javac)},
              "not_executed": ["old 352 public-contract suite", "old C++/ASan experiments",
                               "old Python model/exploration", "libusb/libuvc/generator execution",
                               "JNI or USB backend", "Android build/ADB/emulator/physical device",
                               "capture, UAC audio, deployment or publication"]}
    result_path = output / "result.json"
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8", newline="\n")
    print(json.dumps({"pass": result["pass"], "result": str(result_path),
                      "result_sha256": sha(result_path.read_bytes()), "java_sources": len(sources)}))
    if failure:
        print(failure, file=sys.stderr)
        raise SystemExit(1)
    print(commands[-1]["stdout"].strip())


if __name__ == "__main__":
    main()

"""Compile the isolated USB permission/owner boundary and exercise controlled Java ports."""
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
PARENT = HERE.parent
ROOT = HERE.parents[3]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def manifest(paths):
    return {p.relative_to(ROOT).as_posix(): {"bytes": len(p.read_bytes()),
            "sha256": sha(p.read_bytes())} for p in sorted(paths)}


def verify_lock():
    lock = json.loads((HERE / "inputs-lock.json").read_text(encoding="utf-8"))
    for name, expected in lock["inherited_files"].items():
        path = (ROOT / name).resolve()
        if not path.is_relative_to(ROOT):
            raise ValueError("Inherited path escapes repository")
        data = path.read_bytes()
        if len(data) != expected["bytes"] or sha(data) != expected["sha256"]:
            raise ValueError("Inherited input differs from fixed baseline: " + name)
    return len(lock["inherited_files"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=Path(r"E:\tools\jdk-17.0.19+10"))
    args = parser.parse_args()
    verified = verify_lock()
    java, javac = args.jdk / "bin/java.exe", args.jdk / "bin/javac.exe"
    if not java.is_file() or not javac.is_file():
        raise FileNotFoundError("Provide an already installed JDK with --jdk")
    public = sorted((PARENT / "contract-baseline/src").rglob("*.java"))
    adapter = sorted((PARENT / "src").rglob("*.java"))
    own = sorted((HERE / "src").rglob("*.java")) + sorted((HERE / "test").rglob("*.java"))
    if len(public) != 3 or len(adapter) != 3 or not any(p.name == "OwnerChecks.java" for p in own):
        raise ValueError("Missing fixed public types, isolated adapter or owner test entry")
    sources = public + adapter + own
    inputs = sources + [HERE / "run_checks.py", HERE / "inputs-lock.json"]
    before = manifest(inputs)
    output = HERE / "build" / ("run-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid.uuid4().hex[:8])
    output.mkdir(parents=True, exist_ok=False)
    classes, empty = output / "classes", output / "empty-path"
    classes.mkdir()
    empty.mkdir()
    environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    removed = []
    for key in list(environment):
        if key.upper() in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH", "JDK_JAVAC_OPTIONS"}:
            removed.append(key)
            del environment[key]
    commands = []

    def run(label, argv, timeout):
        command = [str(a) for a in argv]
        timed_out = False
        try:
            result = subprocess.run(command, cwd=ROOT, capture_output=True, timeout=timeout,
                                    encoding="utf-8", errors="replace", env=environment)
            stdout, stderr, code = result.stdout, result.stderr, result.returncode
        except subprocess.TimeoutExpired as error:
            stdout, stderr, code = error.stdout or "", error.stderr or "", None
            if isinstance(stdout, bytes): stdout = stdout.decode("utf-8", errors="replace")
            if isinstance(stderr, bytes): stderr = stderr.decode("utf-8", errors="replace")
            timed_out = True
        for stream, value in (("stdout", stdout), ("stderr", stderr)):
            (output / (label + "." + stream + ".txt")).write_text(value, encoding="utf-8", newline="\n")
        commands.append({"label": label, "argv": command, "exit_code": code,
                         "timed_out": timed_out, "stdout": stdout, "stderr": stderr})
        if timed_out:
            raise RuntimeError(label + " exceeded test deadline; not a cleanup receipt")
        if code != 0:
            raise RuntimeError(label + " failed: " + stdout + stderr)

    failure = None
    try:
        run("jdk-version", [java, "-version"], 15)
        run("compile", [javac, "--release", "8", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
                        "-proc:none", "-implicit:none", "-classpath", empty, "-sourcepath", empty,
                        "-d", classes] + sources, 60)
        run("owner-checks", [java, "-cp", classes, "local.sidecar.copilot.usbowner.OwnerChecks"], 90)
        if before != manifest(inputs):
            raise ValueError("Source changed during owner checks")
        verify_lock()
    except Exception as error:
        failure = str(error)
    result = {"schema": 1, "git_baseline": "ff46084debcfef8b29420fdb786c5ddfc3e19981",
              "scope": "Original USB permission and connection owner boundary with controlled Java ports",
              "completed_utc": datetime.now(timezone.utc).isoformat(), "pass": failure is None,
              "failure": failure, "inherited_files_verified": verified, "inputs": before,
              "removed_java_environment_keys": sorted(removed), "commands": commands,
              "jdk_launchers": {str(p): sha(p.read_bytes()) for p in (java, javac)},
              "not_executed": ["parent adapter or public-contract test suites", "old C++/ASan or Python model",
                               "Android SDK/receiver/PendingIntent implementation", "JNI or native backend",
                               "real permission dialogs, USB FD or device", "ADB/emulator/APK build",
                               "capture, UAC, deployment or publication"]}
    path = output / "result.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"pass": result["pass"], "result": str(path),
                      "result_sha256": sha(path.read_bytes()), "java_sources": len(sources)}))
    if failure:
        print(failure, file=sys.stderr)
        raise SystemExit(1)
    print(commands[-1]["stdout"].strip())


if __name__ == "__main__":
    main()

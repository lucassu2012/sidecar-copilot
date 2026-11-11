"""Offline, isolated Windows/JVM JNI and pinned C-body experiment. No USB backend.

Creates a unique build directory and compiles immutable input snapshots. Nothing is
installed, signed, deployed, or loaded into Android. A process deadline is only a
harness deadlock guard, never evidence that native work drained.
"""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

ROOT = Path(__file__).resolve().parent
PARENT = ROOT.parent
JDK = Path(r"E:\tools\jdk-17.0.19+10")
VS = Path(r"C:\Program Files (x86)\Microsoft Visual Studio\18\BuildTools")
VC = VS / "VC/Tools/MSVC/14.50.35717"
CL = VC / "bin/Hostx64/x64/cl.exe"
VCVARS = VS / "VC/Auxiliary/Build/vcvars64.bat"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def descriptor(path):
    return {"bytes": path.stat().st_size, "sha256": sha(path)}


def write_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


def main():
    started = datetime.now(timezone.utc)
    out = ROOT / "build" / (started.strftime("run-%Y%m%dT%H%M%S-") + uuid.uuid4().hex[:8])
    out.mkdir(parents=True, exist_ok=False)
    env = os.environ.copy()
    for key in tuple(env):
        if key.upper() in {"CL", "_CL_", "LINK", "_LINK_", "CLASSPATH", "JAVA_TOOL_OPTIONS",
                           "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTIONS"}:
            del env[key]
    commands, inputs, results = [], [], []
    failure = None

    def run(label, argv, timeout=60, expected=0, stderr_empty=True):
        entry = {"label": label, "command": [str(a) for a in argv], "cwd": str(out),
                 "started_utc": datetime.now(timezone.utc).isoformat(), "timeout_seconds": timeout}
        try:
            proc = subprocess.run(entry["command"], cwd=out, env=env, capture_output=True,
                                  timeout=timeout, creationflags=subprocess.CREATE_NO_WINDOW)
            stdout, stderr = proc.stdout, proc.stderr
            entry.update(exit_code=proc.returncode, timed_out=False)
        except subprocess.TimeoutExpired as exc:
            stdout, stderr = exc.stdout or b"", exc.stderr or b""
            entry.update(exit_code=None, timed_out=True,
                         boundary="Process deadline hit: no drain or cleanup success claimed.")
        for name, data in (("stdout", stdout), ("stderr", stderr)):
            log = out / (label + "." + name + ".log")
            log.write_bytes(data)
            entry[name] = dict(path=log.name, **descriptor(log))
        entry["ended_utc"] = datetime.now(timezone.utc).isoformat()
        commands.append(entry)
        write_json(out / "commands.json", commands)
        if entry["exit_code"] != expected or (stderr_empty and stderr):
            raise RuntimeError("Command failed or unexpected stderr: " + label)
        return stdout.decode("utf-8", "replace"), stderr.decode("utf-8", "replace")

    def snapshot(source, relative):
        before = descriptor(source)
        target = out / "source" / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
        if descriptor(target) != before or descriptor(source) != before:
            raise RuntimeError("Input changed while snapshotting: " + str(source))
        inputs.append(dict(source=str(source), snapshot=str(target.relative_to(out)), **before))
        return target

    try:
        lock = json.loads((ROOT / "inputs-lock.json").read_text(encoding="utf-8"))
        for entry in lock["inherited"]:
            p = PARENT / entry["path"]
            if descriptor(p) != {k: entry[k] for k in ("bytes", "sha256")}:
                raise RuntimeError("Inherited input mismatch: " + entry["path"])
            snapshot(p, Path("inherited") / entry["path"])
        for entry in lock["tools"]:
            p = Path(entry["path"])
            if descriptor(p) != {k: entry[k] for k in ("bytes", "sha256")}:
                raise RuntimeError("Tool input mismatch: " + entry["path"])
        # Default verifier is offline and checks fixed upstream bytes, blob IDs,
        # and literal source anchors before any compilation.
        run("verify-sources", [sys.executable, "-B", ROOT / "source-evidence/verify_sources.py"])
        for base in ("src", "native", "tests", "upstream-probe"):
            for p in sorted((ROOT / base).rglob("*")):
                if p.is_file() and p.suffix in {".java", ".c", ".cpp", ".h", ".json"}:
                    snapshot(p, Path("experiment") / p.relative_to(ROOT))
        for name in ("run_checks.py", "inputs-lock.json", "source-evidence/inputs.json",
                     "source-evidence/anchors.json", "source-evidence/verify_sources.py"):
            snapshot(ROOT / name, Path("experiment") / name)
        sources = json.loads((ROOT / "source-evidence/inputs.json").read_text(encoding="utf-8"))
        for entry in sources["sources"]:
            if entry["role"] == "compile_candidate":
                snapshot(ROOT / "source-evidence" / entry["cache_path"],
                         Path("upstream") / entry["repo"] / entry["path"])
        write_json(out / "inputs.json", inputs)
        # Capture only selected compiler environment fields; never save the full
        # inherited environment, which may contain credentials.
        setup = out / "compiler-environment.cmd"
        setup.write_bytes(("@echo off\r\ncall \"" + str(VCVARS) + "\" >nul\r\n"
                           "if errorlevel 1 exit /b %errorlevel%\r\nset\r\n").encode("ascii"))
        setup_proc = subprocess.run(["cmd.exe", "/d", "/c", str(setup)], env=env,
                                    capture_output=True, timeout=45,
                                    creationflags=subprocess.CREATE_NO_WINDOW)
        write_json(out / "setup.json", {"exit_code": setup_proc.returncode,
            "script_sha256": sha(setup), "stdout_bytes": len(setup_proc.stdout),
            "stdout_sha256": hashlib.sha256(setup_proc.stdout).hexdigest(),
            "stderr_bytes": len(setup_proc.stderr),
            "raw_environment_saved": False})
        if setup_proc.returncode != 0 or setup_proc.stderr:
            raise RuntimeError("Existing MSVC environment setup failed")
        for line in setup_proc.stdout.decode("mbcs", "strict").splitlines():
            key, sep, value = line.partition("=")
            if sep and key and not key.startswith("="):
                env[key] = value
        write_json(out / "toolchain.json", {
            "locked_files": lock["tools"],
            "selected_environment": {k: v for k, v in env.items() if k.upper() in {
                "INCLUDE", "LIB", "LIBPATH", "VCTOOLSVERSION", "WINDOWSSDKVERSION"}},
            "android_ndk": "Not present under inspected E:/tools/android-sdk; not used",
            "sanitizers": "Not run; JVM -Xcheck:jni plus explicit resource/ordering assertions only"})
        run("java-version", [JDK / "bin/java.exe", "-version"], stderr_empty=False)
        run("javac-version", [JDK / "bin/javac.exe", "-version"])
        run("compiler-version", [CL, "/Bv"], expected=2, stderr_empty=False)
        experiment = out / "source/experiment"
        inherited = out / "source/inherited"
        classes, headers, empty = out / "classes", out / "generated", out / "empty"
        for p in (classes, headers, empty, out / "obj"):
            p.mkdir()
        production = sorted(inherited.rglob("*.java")) + sorted((experiment / "src").rglob("*.java"))
        run("javac-production", [JDK / "bin/javac.exe", "--release", "8", "-Xlint:all", "-Werror",
            "-proc:none", "-implicit:none", "-classpath", empty, "-sourcepath", empty,
            "-h", headers, "-d", classes] + production)
        production_hashes = {str(p.relative_to(classes)): sha(p) for p in classes.rglob("*.class")}
        tests = sorted((experiment / "tests").rglob("*.java"))
        test_classes = out / "test-classes"
        test_classes.mkdir()
        run("javac-tests", [JDK / "bin/javac.exe", "--release", "8", "-Xlint:all", "-Werror",
            "-proc:none", "-implicit:none", "-classpath", classes, "-sourcepath", empty,
            "-d", test_classes] + tests)
        for p in test_classes.rglob("*.class"):
            if (classes / p.relative_to(test_classes)).exists():
                raise RuntimeError("Test shadows a production class")
        dll = out / "sidecar_jni_fixture.dll"
        run("build-jni", [CL, "/nologo", "/std:c++17", "/EHsc", "/W4", "/WX", "/Od", "/MD", "/LD",
            "/I" + str(JDK / "include"), "/I" + str(JDK / "include/win32"), "/I" + str(headers),
            "/Fo" + str(out / "obj") + "\\", "/Fd" + str(out / "jni-compile.pdb")]
            + sorted((experiment / "native").glob("*.cpp"))
            + ["/link", "/OUT:" + str(dll), "/INCREMENTAL:NO"])
        run("jni-imports", [VC / "bin/Hostx64/x64/dumpbin.exe", "/imports", dll])
        # Probe commands and test entrypoint are intentionally explicit below;
        # generated build metadata may describe inputs but never execute code.
        run_probe_and_jni(run, out, experiment, classes, test_classes, dll, results)
        if production_hashes != {str(p.relative_to(classes)): sha(p) for p in classes.rglob("*.class")}:
            raise RuntimeError("Production classes changed during tests")
        for entry in inputs:
            if descriptor(Path(entry["source"])) != {k: entry[k] for k in ("bytes", "sha256")}:
                raise RuntimeError("Input changed during run: " + entry["source"])
        for entry in lock["tools"]:
            if descriptor(Path(entry["path"])) != {k: entry[k] for k in ("bytes", "sha256")}:
                raise RuntimeError("Tool changed during run: " + entry["path"])
        write_json(out / "artifacts.json", [dict(path=str(p.relative_to(out)), **descriptor(p))
            for p in sorted(out.rglob("*")) if p.is_file() and p.suffix in {".dll", ".exe", ".class", ".obj", ".h"}
            and "source" not in p.relative_to(out).parts])
    except Exception as exc:
        failure = type(exc).__name__ + ": " + str(exc)
    result = {"schema": 1, "baseline": "62eb3485a056c91720609f38ba436122bfdcb420",
        "scope": "Controlled actual host JNI + original upstream control-body probe, not an Android UVC driver",
        "started_utc": started.isoformat(), "ended_utc": datetime.now(timezone.utc).isoformat(),
        "run_directory": str(out), "pass": failure is None, "failure": failure,
        "actual_input_count": len(inputs), "results": results,
        "commands": commands,
        "not_executed": ["Android NDK/ABI/ART/JNI", "complete libusb/libuvc build or runtime",
            "real USB FD wrap", "physical device/USB/ADB/emulator", "image decoding/masking/publication",
            "upstream status-transfer drain patch", "sanitizers", "PRD hardware acceptance or distribution"]}
    write_json(out / "result.json", result)
    print(json.dumps({"pass": result["pass"], "failure": failure, "receipt": str(out / "result.json")}, ensure_ascii=True))
    return 0 if result["pass"] else 1


def run_probe_and_jni(run, out, experiment, classes, test_classes, dll, results):
    upstream = out / "source/upstream"
    probe = experiment / "upstream-probe"
    includes = ["/I" + str(probe / "fixtures"), "/I" + str(upstream / "libusb/libusb"),
                "/I" + str(upstream / "libuvc/include")]
    flags = [CL, "/nologo", "/TC", "/std:c11", "/W3", "/Od", "/MD"]
    run("compile-public-api-signatures", flags + ["/WX"] + includes + ["/c", probe / "api_signature_gate.c",
        "/Fo" + str(out / "api-signatures.obj")])
    results.append({"name": "public_api_signatures", "pass": True, "declarations": 7,
                    "boundary": "Compile only on Windows; no driver library linked or invoked"})
    binary = out / "upstream_control_probe.exe"
    # Keep the original upstream C warnings visible; project harness/fixtures
    # and declaration gate use /WX. This is not a warning-free upstream build.
    run("compile-upstream-control", flags + includes + ["/c", upstream / "libuvc/src/ctrl-gen.c",
        "/Fo" + str(out / "ctrl-gen.obj")])
    run("compile-control-harness", flags + ["/WX"] + includes + ["/c", probe / "control_probe.c",
        "/Fo" + str(out / "control_probe.obj")])
    run("link-upstream-control-probe", [VC / "bin/Hostx64/x64/link.exe", "/nologo",
        out / "ctrl-gen.obj", out / "control_probe.obj", "/OUT:" + str(binary), "/INCREMENTAL:NO"])
    text, _ = run("upstream-control-probe", [binary])
    match = re.search(r"UPSTREAM_CONTROL_PROBE_OK cases=(\d+) checks=(\d+) calls=(\d+) timeout_zero_calls=(\d+)", text)
    if not match or tuple(int(x) for x in match.groups()[::2]) != (14, 14):
        raise RuntimeError("Missing upstream probe result or wrong case count")
    metrics = dict(zip(("cases", "assertions", "calls", "timeout_zero_calls"), map(int, match.groups())))
    if metrics["timeout_zero_calls"] != 14 or "GET_ZERO_RETURNS_SUCCESS_WITH_UNCHANGED_OUTPUT" not in text \
            or "SET_ZERO_RETURNS_SUCCESS_WITHOUT_FULL_WRITE" not in text:
        raise RuntimeError("Expected upstream timeout/short-transfer behavior missing")
    results.append({"name": "upstream_control_probe", "pass": True, **metrics})
    _, err = run("upstream-request-negative-control", [binary, "--negative-request"],
                 expected=1, stderr_empty=False)
    if err.strip() != "PROBE_ASSERTION_FAILED case=negative_request_oracle check=scripted_request_code":
        raise RuntimeError("Wrong upstream request oracle rejection")
    results.append({"name": "upstream_request_negative_control", "pass": True,
                    "expected_violation": "scripted_request_code"})
    text, _ = run("jni-boundary-checks", [JDK / "bin/java.exe", "-Xcheck:jni", "-ea", "-cp",
        str(classes) + os.pathsep + str(test_classes),
        "local.sidecar.copilot.jnihost.NativeBoundaryChecks", dll], timeout=90)
    if re.search(r"WARNING.*JNI|FATAL ERROR|WARNING in native method", text, re.IGNORECASE):
        raise RuntimeError("JVM JNI checker emitted a warning")
    match = re.search(r"JNI_BOUNDARY_OK cases=(\d+) assertions=(\d+) negative_controls=(\d+)", text)
    if not match:
        raise RuntimeError("Missing JNI boundary test result")
    jni_metrics = dict(zip(("cases", "assertions", "negative_controls"), map(int, match.groups())))
    if jni_metrics["cases"] != 8 or jni_metrics["negative_controls"] != 2:
        raise RuntimeError("Wrong JNI test case/negative-control count")
    results.append({"name": "jni_boundary", "pass": True, **jni_metrics})


if __name__ == "__main__":
    sys.exit(main())

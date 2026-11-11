"""Build against the real Android 35 SDK, then run the same classes with isolated Android doubles."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
PARENT = HERE.parent
ROOT = HERE.parents[3]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def identity(path):
    data = path.read_bytes()
    return {"bytes": len(data), "sha256": sha(data)}


def inputs(paths):
    return {p.relative_to(ROOT).as_posix(): identity(p) for p in sorted(paths)}


def verify_lock(sdk):
    lock = json.loads((HERE / "inputs-lock.json").read_text(encoding="utf-8"))
    for name, expected in lock["inherited_files"].items():
        path = (ROOT / name).resolve()
        if not path.is_relative_to(ROOT) or identity(path) != expected:
            raise ValueError("Inherited input mismatch: " + name)
    for name, expected in lock["android_sdk_files"].items():
        path = (sdk / name).resolve()
        if not path.is_relative_to(sdk.resolve()) or identity(path) != expected:
            raise ValueError("SDK input mismatch: " + name)
    return {"inherited_files": len(lock["inherited_files"]), "sdk_files": len(lock["android_sdk_files"])}


def check_manifest():
    root = ET.parse(HERE / "AndroidManifest.xml").getroot()
    a = "{http://schemas.android.com/apk/res/android}"
    sdk = root.find("uses-sdk")
    if sdk is None or sdk.get(a + "minSdkVersion") != "30" or sdk.get(a + "targetSdkVersion") != "35":
        raise ValueError("Manifest differs from fixed SDK scope")
    receivers = root.findall("application/receiver")
    if len(receivers) != 1 or receivers[0].get(a + "name") != "local.sidecar.copilot.usbbridge.PermissionReceiver":
        raise ValueError("Unexpected permission receiver component")
    if receivers[0].get(a + "exported") != "false" or receivers[0].find("intent-filter") is not None:
        raise ValueError("Permission receiver must be private and explicit-only")
    if root.findall("application/activity") or root.findall("application/service"):
        raise ValueError("Isolated manifest must not start an app or capture service")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=Path(r"E:\tools\jdk-17.0.19+10"))
    parser.add_argument("--sdk", type=Path, default=Path(r"E:\tools\android-sdk"))
    args = parser.parse_args()
    baseline = verify_lock(args.sdk)
    check_manifest()
    java, javac, jar = (args.jdk / ("bin/" + name + ".exe") for name in ("java", "javac", "jar"))
    if not all(p.is_file() for p in (java, javac, jar)):
        raise FileNotFoundError("An installed JDK is required")
    android = args.sdk / "platforms/android-35/android.jar"
    d8 = args.sdk / "build-tools/35.0.0/lib/d8.jar"
    aapt2 = args.sdk / "build-tools/35.0.0/aapt2.exe"
    public = sorted((PARENT / "contract-baseline/src").rglob("*.java"))
    adapter = sorted((PARENT / "src").rglob("*.java"))
    owner = sorted((PARENT / "usb-owner-experiment/src").rglob("*.java"))
    production = public + adapter + owner + sorted((HERE / "src").rglob("*.java"))
    doubles = sorted((HERE / "test-android").rglob("*.java"))
    tests = sorted((HERE / "test").rglob("*.java"))
    if len(public) != 3 or len(adapter) != 3 or len(owner) != 2 or not doubles or not any(p.name == "BridgeChecks.java" for p in tests):
        raise ValueError("Missing fixed source groups or test entry")
    metadata = [HERE / "source-evidence" / name for name in
                ("inputs.json", "anchors.json", "verify_sources.py", ".gitignore", "validation.json")]
    files = production + doubles + tests + metadata + [HERE / "run_checks.py", HERE / "inputs-lock.json", HERE / "AndroidManifest.xml"]
    before = inputs(files)
    output = HERE / "build" / ("run-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S") + "-" + uuid.uuid4().hex[:8])
    output.mkdir(parents=True, exist_ok=False)
    product_classes, fake_classes, test_classes, empty, dex = (output / name for name in ("product-classes", "fake-classes", "test-classes", "empty-path", "dex"))
    for path in (product_classes, fake_classes, test_classes, empty, dex): path.mkdir()
    environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    removed = []
    for key in list(environment):
        if key.upper() in {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH", "JDK_JAVAC_OPTIONS"}:
            removed.append(key); del environment[key]
    commands = []
    artifacts = {}

    def run(label, argv, timeout=60):
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
        commands.append({"label": label, "argv": command, "exit_code": code, "timed_out": timed_out,
                         "stdout": stdout, "stderr": stderr})
        if timed_out: raise RuntimeError(label + " timed out; not cleanup or device evidence")
        if code != 0: raise RuntimeError(label + " failed: " + stdout + stderr)
        if label == "bridge-checks" and stderr.strip():
            raise RuntimeError("Host checks wrote unexpected stderr: " + stderr)

    def compile_sources(label, source_files, destination, classpath):
        run(label, [javac, "--release", "8", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
                    "-proc:none", "-implicit:none", "-sourcepath", empty, "-classpath", classpath,
                    "-d", destination] + source_files)

    failure = None
    try:
        run("jdk-version", [java, "-version"], 15)
        run("aapt2-version", [aapt2, "version"], 15)
        run("d8-version", [java, "-cp", d8, "com.android.tools.r8.D8", "--version"], 30)
        compile_sources("android-sdk-compile", production, product_classes, android)
        product_before = inputs(list(product_classes.rglob("*.class")))
        run("product-jar", [jar, "cf", output / "bridge.jar", "-C", product_classes, "."])
        run("dex-build", [java, "-cp", d8, "com.android.tools.r8.D8", "--lib", android,
                          "--min-api", "30", "--output", dex, output / "bridge.jar"])
        run("manifest-link", [aapt2, "link", "-I", android, "--manifest", HERE / "AndroidManifest.xml",
                              "-o", output / "manifest-check.apk"])
        compile_sources("android-doubles-compile", doubles, fake_classes, empty)
        compile_sources("host-tests-compile", tests, test_classes, os.pathsep.join(map(str, (product_classes, fake_classes))))
        product_names = {p.relative_to(product_classes).as_posix() for p in product_classes.rglob("*.class")}
        fake_names = {p.relative_to(fake_classes).as_posix() for p in fake_classes.rglob("*.class")}
        test_names = {p.relative_to(test_classes).as_posix() for p in test_classes.rglob("*.class")}
        if product_names & (fake_names | test_names) or fake_names & test_names:
            raise ValueError("Test classes shadow production classes")
        if not all(name.startswith("android/") for name in fake_names):
            raise ValueError("Android double output escaped android namespace")
        run("bridge-checks", [java, "-cp", os.pathsep.join(map(str, (test_classes, fake_classes, product_classes))),
                              "local.sidecar.copilot.usbbridge.BridgeChecks"], 90)
        if inputs(list(product_classes.rglob("*.class"))) != product_before:
            raise ValueError("SDK-compiled product classes changed during host checks")
        if inputs(files) != before: raise ValueError("Source changed during build/checks")
        verify_lock(args.sdk)
        check_manifest()
        for path in [output / "bridge.jar", output / "manifest-check.apk"] + sorted(dex.glob("*.dex")):
            artifacts[path.relative_to(output).as_posix()] = identity(path)
    except Exception as error:
        failure = str(error)
    result = {"schema": 1, "git_baseline": "3e5abb1b880040ceff775c5cd43d5ad61115af9b",
              "scope": "Android SDK compilation/DEX/manifest validation plus host doubles; no Android runtime execution",
              "completed_utc": datetime.now(timezone.utc).isoformat(), "pass": failure is None, "failure": failure,
              "locked_inputs_verified": baseline, "inputs": before, "commands": commands, "artifacts": artifacts,
              "source_counts": {"production": len(production), "android_doubles": len(doubles), "tests": len(tests)},
              "removed_java_environment_keys": sorted(removed),
              "jdk_launchers": {str(p): identity(p) for p in (java, javac, jar)},
              "not_executed": ["old owner/adapter/public/C++/Python suites", "Android framework or Binder runtime",
                               "actual permission dialog/USB FD/physical device", "ADB/emulator/installation/signing",
                               "JNI/native backend/real capture/UAC", "deployment or publication"]}
    path = output / "result.json"
    path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"pass": result["pass"], "result": str(path), "result_sha256": sha(path.read_bytes()),
                      "source_counts": result["source_counts"]}))
    if failure:
        print(failure, file=sys.stderr); raise SystemExit(1)
    print(commands[-1]["stdout"].strip())


if __name__ == "__main__":
    main()

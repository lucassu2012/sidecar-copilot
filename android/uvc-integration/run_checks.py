"""Offline SDK compilation and host checks of the exact isolated UVC integration inputs.

Every invocation creates a new evidence directory, including failed preflight runs.
This runner never invokes historical runners, builds the main app, signs, installs,
uses ADB, downloads dependencies, or treats host doubles as Android/native acceptance.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import time
import traceback
import uuid
import xml.etree.ElementTree as ET
import zipfile


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
CONTRACT = ROOT / "android/uvc-contract"
JAVA_ENVIRONMENT = {"JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS",
                    "CLASSPATH", "JDK_JAVAC_OPTIONS"}
GROUP_COUNTS = {"adapter/src": 3, "adapter/test": 3, "owner/src": 2,
                "owner/test": 4, "bridge/src": 4, "bridge/test": 4,
                "bridge/test-android": 14}
CONTRACT_NAMES = {"CaptureSession.java", "StopBarrier.java", "UvcContract.java"}
SDK_NAMES = {"platforms/android-35/android.jar", "platforms/android-35/source.properties",
             "build-tools/35.0.0/lib/d8.jar", "build-tools/35.0.0/aapt2.exe",
             "build-tools/35.0.0/source.properties"}
ORIGINS = {"adapter": "ff46084debcfef8b29420fdb786c5ddfc3e19981",
           "owner": "3e5abb1b880040ceff775c5cd43d5ad61115af9b",
           "bridge": "62eb3485a056c91720609f38ba436122bfdcb420"}


def utc():
    return datetime.now(timezone.utc).isoformat()


def identity_bytes(data):
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def identity(path):
    return identity_bytes(path.read_bytes())


def contained(base, name):
    """Resolve a manifest path without accepting traversal, aliases or external links."""
    relative = PurePosixPath(name)
    if (not isinstance(name, str) or "\\" in name or relative.is_absolute()
            or not relative.parts or ":" in name or any(p in (".", "..") for p in relative.parts)
            or relative.as_posix() != name):
        raise ValueError("Invalid relative input path: " + repr(name))
    path = base.joinpath(*relative.parts)
    if not path.resolve().is_relative_to(base.resolve()):
        raise ValueError("Input escapes its fixed root: " + name)
    cursor = path
    while cursor != base:
        if cursor.is_symlink():
            raise ValueError("Linked input is not an immutable imported file: " + str(path))
        cursor = cursor.parent
    return path


def imported_inventory():
    files = set()
    for directory in ("adapter", "owner", "bridge", "provenance"):
        for path in (HERE / directory).rglob("*"):
            if path.is_file() and path != HERE / "provenance/source-manifest.json":
                files.add(path.relative_to(HERE).as_posix())
    return files


def integration_java_inventory():
    return {p.relative_to(HERE).as_posix() for p in HERE.rglob("*.java")
            if not p.is_relative_to(HERE / "build")}


def load_imports():
    manifest = json.loads((HERE / "provenance/source-manifest.json").read_text(encoding="utf-8"))
    if manifest.get("schema") != 1 or manifest.get("main_app_enabled") is not False:
        raise ValueError("Unexpected import schema or app enablement")
    if manifest.get("runtime_dependencies_added") != []:
        raise ValueError("This offline runner admits no added runtime dependencies")
    entries = manifest["files"]
    if len(entries) != 44 or len({entry["target"] for entry in entries}) != 44:
        raise ValueError("Expected exactly 44 distinct imported files")
    expected = {entry["target"] for entry in entries}
    if imported_inventory() != expected:
        raise ValueError("Imported files missing/extra: " + repr(sorted(imported_inventory() ^ expected)))
    expected_java = {name for name in expected if name.endswith(".java")}
    if integration_java_inventory() != expected_java:
        raise ValueError("Unexpected or missing Java source, including any contract-baseline copy")
    origins = manifest["origins"]
    if len(origins) != 3 or {x["module"]: x["commit"] for x in origins} != ORIGINS:
        raise ValueError("Imports differ from the three reviewed commits")
    for entry in entries:
        path = contained(HERE, entry["target"])
        data = path.read_bytes()
        if identity_bytes(data) != {"bytes": entry["bytes"], "sha256": entry["sha256"]}:
            raise ValueError("Imported byte hash mismatch: " + entry["target"])
        blob = hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()
        if blob != entry["git_blob"] or entry["source_commit"] != ORIGINS[entry["module"]]:
            raise ValueError("Imported Git blob/commit mismatch: " + entry["target"])
    contracts = manifest["main_contract_sources"]
    actual_contract = {p.relative_to(ROOT).as_posix() for p in (CONTRACT / "src").rglob("*.java")}
    if (len(contracts) != 3 or actual_contract != set(contracts)
            or {PurePosixPath(name).name for name in contracts} != CONTRACT_NAMES):
        raise ValueError("Expected exactly the three main public contract source files")
    for name, expected_identity in contracts.items():
        if not name.startswith("android/uvc-contract/src/") or identity(contained(ROOT, name)) != expected_identity:
            raise ValueError("Main public contract differs from imported comparison: " + name)
    for origin in origins:
        comparisons = origin["contract_comparisons"]
        if len(comparisons) != 3 or {item["main_path"] for item in comparisons} != set(contracts):
            raise ValueError("Incomplete imported contract comparison")
        for item in comparisons:
            data = contained(ROOT, item["main_path"]).read_bytes()
            blob = hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()
            if identity_bytes(data) != {"bytes": item["bytes"], "sha256": item["sha256"]} or blob != item["git_blob"]:
                raise ValueError("Contract comparison is internally inconsistent")
    groups = {}
    for name, count in GROUP_COUNTS.items():
        paths = sorted((HERE / name).rglob("*.java"))
        if len(paths) != count:
            raise ValueError("Wrong source count in " + name)
        groups[name] = paths
    contract_test = CONTRACT / "test/ContractChecks.java"
    if not contract_test.is_file():
        raise ValueError("Main ContractChecks is missing")
    files = [contained(HERE, entry["target"]) for entry in entries]
    files += [HERE / "import_sources.py", HERE / "run_checks.py", HERE / "provenance/source-manifest.json"]
    files += [contained(ROOT, name) for name in sorted(contracts)] + [contract_test]
    if len(files) != 51 or len(set(files)) != 51:
        raise ValueError("The fixed source/script/context input set must contain 51 files")
    return manifest, groups, files


def check_manifest(path):
    root = ET.fromstring(path.read_bytes())
    android = "{http://schemas.android.com/apk/res/android}"
    if root.tag != "manifest" or root.attrib != {"package": "local.sidecar.copilot"}:
        raise ValueError("Unexpected isolated manifest root")
    if [child.tag for child in root] != ["uses-sdk", "application"]:
        raise ValueError("Isolated manifest permits no permissions, features or startup declarations")
    uses_sdk, application = list(root)
    if uses_sdk.attrib != {android + "minSdkVersion": "30", android + "targetSdkVersion": "35"} or list(uses_sdk):
        raise ValueError("Manifest must declare exactly minSdk 30 / targetSdk 35")
    if application.attrib or len(list(application)) != 1:
        raise ValueError("Isolated application cannot declare automatic startup or other components")
    receiver = list(application)[0]
    if (receiver.tag != "receiver" or receiver.attrib != {
            android + "name": "local.sidecar.copilot.usbbridge.PermissionReceiver",
            android + "exported": "false"} or list(receiver)):
        raise ValueError("Receiver must be private, explicit-only and have no intent filter")
    return {"min_sdk": 30, "target_sdk": 35, "private_explicit_receiver": True,
            "activities": 0, "services": 0, "permissions": 0, "automatic_startup": False}


def sdk_inputs(sdk):
    lock = json.loads((HERE / "provenance/bridge/inputs-lock.json").read_text(encoding="utf-8"))
    files = lock["android_sdk_files"]
    if set(files) != SDK_NAMES:
        raise ValueError("Expected the five reviewed Android SDK 35 input files")
    actual = {}
    for name, expected in sorted(files.items()):
        path = contained(sdk, name)
        value = identity(path)
        if value != expected:
            raise ValueError("Installed SDK differs from lock: " + name)
        actual[str(path)] = value
    return actual


def class_inventory(directory):
    return {p.relative_to(directory).as_posix(): identity(p)
            for p in sorted(directory.rglob("*.class"))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=Path, default=Path(r"E:\tools\jdk-17.0.19+10"))
    parser.add_argument("--sdk", type=Path, default=Path(r"E:\tools\android-sdk"))
    args = parser.parse_args()
    output = HERE / "build" / ("run-" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:10])
    output.mkdir(parents=True, exist_ok=False)
    result = {"schema": 1, "started_utc": utc(), "status": "running", "pass": False,
              "output": str(output), "scope": "SDK-compiled production classes, DEX and isolated manifest; host JVM/doubles only",
              "inputs": {}, "steps": [], "tools": {}, "artifacts": {}, "failures": [],
              "not_executed": ["main app build/integration", "historical runners or inherited test results",
                               "Android framework/Binder runtime", "actual permission or USB input",
                               "ADB, emulator, device, signing, installation", "JNI/native backend/UAC",
                               "dependency download/network", "publication or deployment"],
              "readme_excluded": "The in-progress integration README is not an execution input; imported provenance documents are included."}
    environment = dict(os.environ)
    removed = []
    for key in list(environment):
        if key.upper() in JAVA_ENVIRONMENT:
            removed.append(key)
            del environment[key]
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    result["removed_java_environment_keys"] = sorted(removed)
    files = []
    sdk_before = {}
    tools_before = {}
    product_before = None
    product_classes = output / "product-classes"

    def save():
        pending = output / "result.pending.json"
        pending.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
        pending.replace(output / "result.json")

    def validation(label, action):
        record = {"label": label, "kind": "internal-validation", "started_utc": utc(), "status": "running"}
        result["steps"].append(record)
        started = time.monotonic()
        log = output / (str(len(result["steps"])).zfill(2) + "-" + label + ".log")
        record["log"] = log.name
        save()
        try:
            value = action()
            record["status"] = "passed"
            log.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            return value
        except BaseException as error:
            record["status"] = "failed"
            record["error"] = str(error)
            log.write_text(traceback.format_exc(), encoding="utf-8")
            raise
        finally:
            record["finished_utc"] = utc()
            record["duration_seconds"] = round(time.monotonic() - started, 6)
            if log.exists():
                record["log_identity"] = identity(log)
            save()

    def run(label, arguments, timeout=60, clean_stderr=False):
        command = [str(arg) for arg in arguments]
        record = {"label": label, "kind": "subprocess", "argv": command, "cwd": str(output),
                  "started_utc": utc(), "status": "running", "timeout_seconds": timeout,
                  "exit_code": None, "timed_out": False}
        result["steps"].append(record)
        prefix = str(len(result["steps"])).zfill(2) + "-" + label
        stdout_path, stderr_path = output / (prefix + ".stdout.log"), output / (prefix + ".stderr.log")
        record.update({"stdout_log": stdout_path.name, "stderr_log": stderr_path.name})
        started = time.monotonic()
        stdout, stderr = b"", b""
        save()
        try:
            completed = subprocess.run(command, cwd=output, env=environment, capture_output=True,
                                       timeout=timeout, shell=False)
            stdout, stderr = completed.stdout, completed.stderr
            record["exit_code"] = completed.returncode
            if completed.returncode != 0:
                raise RuntimeError(label + " exited " + str(completed.returncode))
            if clean_stderr and stderr.strip():
                raise RuntimeError(label + " wrote unexpected stderr")
            record["status"] = "passed"
            return stdout.decode("utf-8", errors="replace"), stderr.decode("utf-8", errors="replace")
        except subprocess.TimeoutExpired as error:
            stdout, stderr = error.stdout or b"", error.stderr or b""
            record["timed_out"] = True
            record["status"] = "failed"
            record["error"] = "Host subprocess timeout; not evidence of native/Android cleanup"
            raise RuntimeError(label + " timed out") from error
        except BaseException as error:
            record["status"] = "failed"
            record["error"] = str(error)
            if record["exit_code"] is None:
                stderr += traceback.format_exc().encode("utf-8")
            raise
        finally:
            stdout_path.write_bytes(stdout)
            stderr_path.write_bytes(stderr)
            record["stdout_identity"] = identity(stdout_path)
            record["stderr_identity"] = identity(stderr_path)
            record["finished_utc"] = utc()
            record["duration_seconds"] = round(time.monotonic() - started, 6)
            save()

    save()
    try:
        loaded = {}

        def preflight():
            manifest, groups, paths = load_imports()
            loaded.update(manifest=manifest, groups=groups, paths=paths)
            return {"imported_files": 44, "main_contract_files": 3, "execution_input_files": 51,
                    "origins": ORIGINS, "source_counts": GROUP_COUNTS}

        result["verified_imports"] = validation("import-byte-and-source-set-check", preflight)
        files = loaded["paths"]
        groups = loaded["groups"]

        def snapshots():
            for path in files:
                relative = path.relative_to(ROOT).as_posix()
                data = path.read_bytes()
                value = identity_bytes(data)
                destination = output / "inputs" / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                with destination.open("xb") as snapshot:
                    snapshot.write(data)
                if identity(destination) != value or identity(path) != value:
                    raise ValueError("Input changed while snapshotting: " + relative)
                result["inputs"][relative] = {**value, "snapshot": destination.relative_to(output).as_posix()}
            return {"snapshotted_files": len(result["inputs"]), "sources_compiled_from_snapshots": True}

        validation("snapshot-execution-inputs", snapshots)

        def copied(path):
            return output / "inputs" / path.relative_to(ROOT)

        result["manifest_validation"] = validation("isolated-manifest-check", lambda: check_manifest(copied(HERE / "bridge/AndroidManifest.xml")))
        jdk, sdk = args.jdk.resolve(), args.sdk.resolve()
        sdk_before = validation("sdk-five-file-lock-check", lambda: sdk_inputs(sdk))
        result["sdk_inputs"] = sdk_before
        java, javac, jar = (jdk / ("bin/" + name + ".exe") for name in ("java", "javac", "jar"))
        android = sdk / "platforms/android-35/android.jar"
        d8 = sdk / "build-tools/35.0.0/lib/d8.jar"
        aapt2 = sdk / "build-tools/35.0.0/aapt2.exe"
        tool_paths = [java, javac, jar, jdk / "release", jdk / "lib/modules", jdk / "bin/server/jvm.dll", Path(sys.executable).resolve()]

        def record_tools():
            for path in tool_paths:
                tools_before[str(path)] = identity(path)
            if not re.search(r'^JAVA_VERSION="17(?:\.|\")', (jdk / "release").read_text(encoding="utf-8"), re.MULTILINE):
                raise ValueError("This runner requires the installed JDK 17")
            return {"files": tools_before, "python_version": sys.version, "sdk_tools": {str(d8): identity(d8), str(aapt2): identity(aapt2)}}

        result["tools"] = validation("tool-identities", record_tools)
        public = sorted(contained(ROOT, name) for name in loaded["manifest"]["main_contract_sources"])
        production = public + groups["adapter/src"] + groups["owner/src"] + groups["bridge/src"]
        doubles = groups["bridge/test-android"]
        tests = groups["adapter/test"] + groups["owner/test"] + groups["bridge/test"] + [CONTRACT / "test/ContractChecks.java"]
        result["source_counts"] = {"production": len(production), "android_doubles": len(doubles), "tests": len(tests)}
        if result["source_counts"] != {"production": 12, "android_doubles": 14, "tests": 12}:
            raise ValueError("Wrong compile input cardinalities")
        result["compile_source_groups"] = {
            label: [p.relative_to(ROOT).as_posix() for p in paths]
            for label, paths in (("production", production), ("android_doubles", doubles), ("tests", tests))}
        fake_classes, test_classes, empty, dex = (output / name for name in ("android-doubles", "test-classes", "empty-path", "dex"))
        for directory in (product_classes, fake_classes, test_classes, empty, dex):
            directory.mkdir()

        def compile_sources(label, paths, destination, classpath):
            run(label, [javac, "--release", "8", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
                        "-proc:none", "-implicit:none", "-sourcepath", empty, "-classpath", classpath,
                        "-d", destination] + [copied(p) for p in paths])

        run("java-version", [java, "-version"], 15)
        run("javac-version", [javac, "-version"], 15)
        run("aapt2-version", [aapt2, "version"], 15)
        run("d8-version", [java, "-cp", d8, "com.android.tools.r8.D8", "--version"], 30)
        compile_sources("sdk-production-compile-once", production, product_classes, android)
        product_before = class_inventory(product_classes)
        if not product_before or any(not name.startswith("local/sidecar/copilot/") for name in product_before):
            raise ValueError("Production classes escaped the approved namespace")
        result["sdk_compiled_production_classes"] = product_before
        product_jar = output / "production.jar"
        run("production-jar", [jar, "cf", product_jar, "-C", product_classes, "."])
        run("dex-min-api-30", [java, "-cp", d8, "com.android.tools.r8.D8", "--lib", android,
                               "--min-api", "30", "--output", dex, product_jar])
        manifest_apk = output / "manifest-only.apk"
        run("isolated-manifest-link", [aapt2, "link", "-I", android, "--manifest", copied(HERE / "bridge/AndroidManifest.xml"),
                                        "--min-sdk-version", "30", "--target-sdk-version", "35", "-o", manifest_apk])

        def check_artifacts():
            with zipfile.ZipFile(product_jar) as archive:
                classes = {name for name in archive.namelist() if name.endswith(".class")}
                if classes != set(product_before):
                    raise ValueError("Production JAR is not exactly the SDK-compiled class set")
                for name in classes:
                    if identity_bytes(archive.read(name)) != product_before[name]:
                        raise ValueError("Production JAR changed an SDK-compiled class")
            with zipfile.ZipFile(manifest_apk) as archive:
                names = {item.filename for item in archive.infolist() if not item.is_dir()}
                if "AndroidManifest.xml" not in names or not names <= {"AndroidManifest.xml", "resources.arsc"}:
                    raise ValueError("Manifest artifact contains unexpected payload, DEX or signature files")
            if b"APK Sig Block 42" in manifest_apk.read_bytes():
                raise ValueError("Isolated manifest artifact must not be signed")
            dex_files = sorted(dex.glob("*.dex"))
            if not dex_files or any(not p.read_bytes().startswith(b"dex\n") for p in dex_files):
                raise ValueError("D8 did not produce valid-looking DEX output")
            for path in [product_jar, manifest_apk] + dex_files:
                result["artifacts"][path.relative_to(output).as_posix()] = identity(path)
            return {"manifest_only_members": sorted(names), "dex_kept_separate_from_apk": True,
                    "production_jar_class_count": len(product_before), "artifacts": result["artifacts"]}

        validation("isolated-artifact-payload-check", check_artifacts)
        compile_sources("android-doubles-compile", doubles, fake_classes, empty)
        compile_sources("tests-compile-only", tests, test_classes, os.pathsep.join(map(str, (product_classes, fake_classes))))

        def no_shadowing():
            products, fakes, test_outputs = class_inventory(product_classes), class_inventory(fake_classes), class_inventory(test_classes)
            if products != product_before:
                raise ValueError("Production classes changed while compiling doubles/tests")
            if not fakes or not test_outputs or any(not name.startswith("android/") for name in fakes):
                raise ValueError("Android double classes must exist only in the android namespace")
            if (set(products) & (set(fakes) | set(test_outputs))) or set(fakes) & set(test_outputs):
                raise ValueError("Test output shadows production or double classes")
            if any(name.startswith("android/") for name in test_outputs):
                raise ValueError("Test helpers cannot inject Android classes")
            result["android_double_classes"] = fakes
            result["test_classes"] = test_outputs
            return {"production_classes_unchanged": True, "class_counts": {
                "production": len(products), "android_doubles": len(fakes), "tests": len(test_outputs)}, "shadowing": False}

        validation("class-output-separation", no_shadowing)
        no_doubles = os.pathsep.join(map(str, (product_classes, test_classes)))
        with_doubles = os.pathsep.join(map(str, (product_classes, test_classes, fake_classes)))
        suites = [
            ("contract-checks", "local.sidecar.copilot.uvccontract.ContractChecks", no_doubles, r"PASS UVC contract: (\d+) assertions"),
            ("adapter-checks", "local.sidecar.copilot.uvcadapter.AdapterChecks", no_doubles, r"ADAPTER_CHECKS_OK cases=(\d+) assertions=(\d+)"),
            ("owner-checks", "local.sidecar.copilot.usbowner.OwnerChecks", no_doubles, r"OWNER_CHECKS_OK cases=(\d+) assertions=(\d+)"),
            ("bridge-checks", "local.sidecar.copilot.usbbridge.BridgeChecks", with_doubles, r"BRIDGE_CHECKS_OK cases=(\d+) assertions=(\d+)"),
        ]
        result["suites"] = {}
        for label, entry, classpath, pattern in suites:
            stdout, _ = run(label, [java, "-Xmx512m", "-cp", classpath, entry], 60, clean_stderr=True)
            matched = re.search(pattern, stdout)
            if not matched:
                raise ValueError("Successful process did not emit its expected suite receipt: " + label)
            counts = [int(value) for value in matched.groups()]
            result["suites"][label] = {"assertions": counts[-1], "cases": counts[0] if len(counts) == 2 else None,
                                       "android_doubles_on_classpath": label == "bridge-checks", "receipt": matched.group(0)}
        validation("product-and-input-final-check", lambda: {
            "production_classes_unchanged": class_inventory(product_classes) == product_before,
            "manifest": check_manifest(copied(HERE / "bridge/AndroidManifest.xml"))})
        if class_inventory(product_classes) != product_before:
            raise ValueError("SDK-compiled production classes changed during host tests")
    except BaseException as error:
        result["failures"].append({"utc": utc(), "type": type(error).__name__, "error": str(error)})
        (output / "failure.log").write_text(traceback.format_exc(), encoding="utf-8")
    finally:
        def final_drift_check():
            drift = []
            for relative, expected in result["inputs"].items():
                path = contained(ROOT, relative)
                wanted = {"bytes": expected["bytes"], "sha256": expected["sha256"]}
                if not path.is_file() or identity(path) != wanted:
                    drift.append(relative)
                copied_input = contained(output, expected["snapshot"])
                if not copied_input.is_file() or identity(copied_input) != wanted:
                    drift.append("snapshot:" + relative)
            for collection in (sdk_before, tools_before):
                for name, expected in collection.items():
                    path = Path(name)
                    if not path.is_file() or identity(path) != expected:
                        drift.append(name)
            if files:
                load_imports()
            if sdk_before:
                sdk_inputs(args.sdk.resolve())
            if drift:
                raise ValueError("Input/tool drift: " + repr(drift))
            return {"unchanged_recorded_source_inputs": len(result["inputs"]), "unchanged_sdk_inputs": len(sdk_before),
                    "unchanged_tool_inputs": len(tools_before), "source_set_rechecked": bool(files),
                    "all_sources_snapshotted": len(result["inputs"]) == 51}

        try:
            result["drift_check"] = validation("always-check-final-input-drift", final_drift_check)
        except BaseException as error:
            result["failures"].append({"utc": utc(), "type": type(error).__name__, "error": str(error), "during": "final drift check"})
        result["finished_utc"] = utc()
        result["pass"] = not result["failures"]
        result["status"] = "passed" if result["pass"] else "failed"
        save()
    receipt = output / "result.json"
    print(json.dumps({"pass": result["pass"], "result": str(receipt), "result_sha256": identity(receipt)["sha256"],
                      "source_counts": result.get("source_counts"), "suites": result.get("suites", {}),
                      "failures": result["failures"]}, ensure_ascii=False))
    return 0 if result["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

"""Build/run the isolated self-authored host experiment; no device or dependency access.

All generated artifacts and exact receipts go under the local ignored build/.
ASan fault processes are expected negative controls, not successful ownership runs.
"""
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent
VS = Path(r"C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools")
COMPILER = VS / "VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64/cl.exe"
ENV_SCRIPT = VS / "VC/Auxiliary/Build/vcvars64.bat"
SOURCES = ("ownership_checks.cpp", "asan_smoke.cpp", "run_checks.py", ".gitignore")
ASAN_SCENARIOS = (
    "callback_before_submit_return", "terminal_before_cancel_return", "cancel_not_found",
    "submit_busy", "control_outer_timeout", "status_resubmit_stop", "missing_callback", "late_open",
    "status_resubmit_admitted",
)
POSITIVE_ONLY = ("byte_budget", "negative_fd", "binding_entrypoints", "stopping_borrow_admission", "concurrent_close")
INVARIANT_NEGATIVES = {
    "fd_zero": "CHECK_FAILED: wrongFdValid",
    "app_buffer_stop": "CHECK_FAILED: !falseReceipt",
    "stale_binding": "CHECK_FAILED: !wrongFdIdentity",
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


def main():
    started = datetime.now(timezone.utc)
    run_dir = ROOT / "build" / started.strftime("run-%Y%m%dT%H%M%S.%fZ")
    run_dir.mkdir(parents=True, exist_ok=False)
    inputs = {name: {"bytes": (ROOT / name).stat().st_size, "sha256": digest(ROOT / name)} for name in SOURCES}
    write_json(run_dir / "inputs.json", inputs)
    for name in SOURCES:
        target = run_dir / "source" / name
        target.parent.mkdir(exist_ok=True)
        target.write_bytes((ROOT / name).read_bytes())
        if digest(target) != inputs[name]["sha256"]:
            raise RuntimeError("Input changed while making source snapshot: " + name)
    results, commands = [], []
    env = os.environ.copy()

    def run(label, command, timeout=30):
        entry = {"label": label, "command": [str(x) for x in command], "cwd": str(run_dir),
                 "started_utc": datetime.now(timezone.utc).isoformat(), "timeout_seconds": timeout}
        try:
            proc = subprocess.run(entry["command"], cwd=run_dir, env=env,
                                  capture_output=True, timeout=timeout, creationflags=subprocess.CREATE_NO_WINDOW)
            stdout, stderr = proc.stdout, proc.stderr
            entry.update(exit_code=proc.returncode, timed_out=False)
        except subprocess.TimeoutExpired as exc:
            stdout, stderr = exc.stdout or b"", exc.stderr or b""
            entry.update(exit_code=None, timed_out=True,
                         boundary="Harness process timeout; no drain/ownership success claimed.")
        entry["ended_utc"] = datetime.now(timezone.utc).isoformat()
        for stream, data in (("stdout", stdout), ("stderr", stderr)):
            path = run_dir / (label + "." + stream + ".log")
            path.write_bytes(data)
            entry[stream] = {"path": path.name, "bytes": len(data), "sha256": digest(path)}
        commands.append(entry)
        write_json(run_dir / "commands.json", commands)
        return entry, stdout.decode("utf-8", errors="replace"), stderr.decode("utf-8", errors="replace")

    try:
        if not COMPILER.exists() or not ENV_SCRIPT.exists():
            raise RuntimeError("Required existing MSVC installation missing; no install attempted")
        # vcvars is an existing trusted toolchain script. Raw environment output
        # is kept only in memory; receipts retain only relevant compiler paths.
        setup_script = run_dir / "compiler-environment.cmd"
        setup_script.write_bytes(('@echo off\r\ncall "' + str(ENV_SCRIPT)
                                  + '" >nul\r\nif errorlevel 1 exit /b %errorlevel%\r\nset\r\n').encode("ascii"))
        setup_command = ["cmd.exe", "/d", "/c", str(setup_script)]
        setup = subprocess.run(setup_command, capture_output=True, timeout=30,
                               creationflags=subprocess.CREATE_NO_WINDOW)
        write_json(run_dir / "setup-receipt.json", {"command": setup_command, "exit_code": setup.returncode,
            "script_sha256": digest(setup_script), "stdout_bytes": len(setup.stdout),
            "stdout_sha256": hashlib.sha256(setup.stdout).hexdigest(),
            "stderr": setup.stderr.decode("mbcs", errors="replace"),
            "raw_stdout_not_saved": "May contain environment secrets; only selected toolchain keys retained on success."})
        if setup.returncode != 0:
            raise RuntimeError("Existing vcvars64 setup failed, exit=" + str(setup.returncode))
        for line in setup.stdout.decode("mbcs", errors="strict").splitlines():
            key, separator, value = line.partition("=")
            if separator and key and not key.startswith("="):
                env[key] = value
        env["ASAN_OPTIONS"] = "halt_on_error=1:exitcode=86:detect_leaks=0"
        runtime_root = VS / "VC/Tools/MSVC/14.44.35207"
        runtime_paths = [runtime_root / path for path in (
            "bin/Hostx64/x64/clang_rt.asan_dynamic-x86_64.dll",
            "lib/x64/clang_rt.asan_dynamic-x86_64.lib",
            "lib/x64/clang_rt.asan_dynamic_runtime_thunk-x86_64.lib",
            "bin/Hostx64/x64/c1xx.dll", "bin/Hostx64/x64/c2.dll", "bin/Hostx64/x64/link.exe",
        )]
        write_json(run_dir / "toolchain.json", {
            "compiler": str(COMPILER), "compiler_sha256": digest(COMPILER),
            "vcvars": str(ENV_SCRIPT), "vcvars_sha256": digest(ENV_SCRIPT),
            "setup_command": setup_command, "setup_exit_code": setup.returncode,
            "selected_toolchain_files": [{"path": str(path), "sha256": digest(path), "bytes": path.stat().st_size}
                                         for path in runtime_paths],
            "environment": {key: value for key, value in env.items()
                            if key.upper() in {"PATH", "INCLUDE", "LIB", "LIBPATH", "VCTOOLSVERSION",
                                               "WINDOWSSDKVERSION", "ASAN_OPTIONS"}},
            "limitations": ["No TSan/LeakSanitizer/Android/JNI/libusb/libuvc/device execution",
                            "MSVC ASan detection verified separately; allocator ledger checks owned allocations"],
        })
        run("compiler-version", [COMPILER, "/Bv"]) # Expected MSVC exit 2 without source input.
        for stem in ("asan_smoke", "ownership_checks"):
            command = [COMPILER, "/nologo", "/std:c++17", "/EHsc", "/W4", "/WX", "/Od", "/Zi", "/MD",
                       "/fsanitize=address", str(run_dir / "source" / (stem + ".cpp")),
                       "/Fo" + str(run_dir / (stem + ".obj")), "/Fd" + str(run_dir / (stem + ".compile.pdb")),
                       "/Fe" + str(run_dir / (stem + ".exe")), "/link", "/INCREMENTAL:NO"]
            receipt, _, _ = run("build-" + stem, command, 60)
            if receipt["exit_code"] != 0:
                raise RuntimeError("Compilation failed: " + stem)
        smoke = run_dir / "asan_smoke.exe"
        receipt, out, err = run("smoke-positive", [smoke, "positive"])
        ok = receipt["exit_code"] == 0 and "ASAN_SMOKE_POSITIVE_PASS" in out and "AddressSanitizer:" not in err
        results.append({"name": "asan_smoke_positive", "pass": ok, "category": "detector_smoke"})
        if not ok: raise RuntimeError("ASan positive smoke failed")
        receipt, out, err = run("smoke-negative", [smoke, "negative"])
        ok = receipt["exit_code"] == 86 and "ERROR: AddressSanitizer: heap-use-after-free" in out + err
        results.append({"name": "asan_smoke_negative", "pass": ok, "category": "expected_asan_failure"})
        if not ok: raise RuntimeError("ASan failed to detect explicit use-after-free smoke")
        binary = run_dir / "ownership_checks.exe"
        scenarios = ASAN_SCENARIOS + tuple(INVARIANT_NEGATIVES) + POSITIVE_ONLY
        for name in scenarios:
            receipt, out, err = run("positive-" + name, [binary, "positive", name])
            match = re.search(r"OWNERSHIP_PASS scenario=" + re.escape(name)
                              + r" allocated=(\d+) destroyed=(\d+) live_bytes=(\d+) peak_bytes=(\d+) cleared_bytes=(\d+)", out)
            ok = receipt["exit_code"] == 0 and match is not None and "AddressSanitizer:" not in out + err
            metrics = dict(zip(("allocated", "destroyed", "live_bytes", "peak_bytes", "cleared_bytes"),
                               map(int, match.groups()))) if match else None
            results.append({"name": name, "pass": ok, "category": "correct_policy", "memory": metrics})
            if not ok: raise RuntimeError("Correct policy failed: " + name)
        for name in ASAN_SCENARIOS + tuple(INVARIANT_NEGATIVES):
            receipt, out, err = run("negative-" + name, [binary, "negative", name])
            witness = "EXPECTED_FAULT_INJECTION scenario=" + name in out
            if name in ASAN_SCENARIOS:
                ok = witness and receipt["exit_code"] == 86 and "ERROR: AddressSanitizer: heap-use-after-free" in out + err
                category = "expected_asan_failure"
            else:
                ok = witness and receipt["exit_code"] == 2 and INVARIANT_NEGATIVES[name] in err
                category = "expected_invariant_failure"
            results.append({"name": name, "pass": ok, "category": category})
            if not ok: raise RuntimeError("Wrong policy not detected by expected oracle: " + name)
        if inputs != {name: {"bytes": (ROOT / name).stat().st_size, "sha256": digest(ROOT / name)} for name in SOURCES}:
            raise RuntimeError("Source files changed during this run")
        failure = None
    except Exception as exc:
        failure = str(exc)
    write_json(run_dir / "result.json", {
        "schema": 1, "scope": "Self-authored controlled Windows host native ownership experiment only",
        "started_utc": started.isoformat(), "ended_utc": datetime.now(timezone.utc).isoformat(),
        "pass": failure is None, "failure": failure, "results": results,
        "not_executed": ["TSan", "LeakSanitizer", "upstream libusb/libuvc compilation", "JNI", "Android ABI",
                         "USB/ADB/emulator/device", "physical acquisition or PRD gate acceptance"],
    })
    index = {str(path.relative_to(run_dir)).replace("\\", "/"): {"bytes": path.stat().st_size, "sha256": digest(path)}
             for path in sorted(run_dir.rglob("*")) if path.is_file() and path.name != "index.json"}
    write_json(run_dir / "index.json", index)
    print(json.dumps({"pass": failure is None, "failure": failure, "evidence": str(run_dir),
                      "result_sha256": digest(run_dir / "result.json"), "checks": len(results)}, ensure_ascii=True))
    return 0 if failure is None else 1


if __name__ == "__main__":
    sys.exit(main())

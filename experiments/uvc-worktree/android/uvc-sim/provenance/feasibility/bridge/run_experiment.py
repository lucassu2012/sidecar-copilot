"""Compile only project-owned JVM sources and run an isolated synthetic pixel bridge."""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import uuid

HERE = pathlib.Path(__file__).resolve().parent
SLICE = HERE.parents[2]
REPO = SLICE.parents[1]
BASELINE = "c1afb3f61fa309bde0437fa2019c352ddc1792ba"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", type=pathlib.Path, default=pathlib.Path("E:/tools/jdk-17.0.19+10"))
    parser.add_argument("--write-report", action="store_true")
    args = parser.parse_args()
    report_path = HERE / "results.json"
    if not args.write_report and not report_path.exists():
        raise FileNotFoundError("missing recorded results; explicit --write-report required for first capture")
    javac, java = args.jdk / "bin/javac.exe", args.jdk / "bin/java.exe"
    if not javac.is_file() or not java.is_file():
        raise FileNotFoundError("provide an existing JDK; this experiment installs nothing")
    sources = sorted((SLICE / "src").rglob("*.java"))
    records = []
    for source in sources:
        relative = source.relative_to(REPO).as_posix()
        committed = subprocess.check_output(["git", "show", BASELINE + ":" + relative], cwd=REPO)
        data = source.read_bytes()
        if data.replace(b"\r\n", b"\n") != committed.replace(b"\r\n", b"\n"):
            raise ValueError("existing source differs from fixed baseline: " + relative)
        records.append({"path": relative, "bytes": len(data), "sha256": sha(data),
                        "git_bytes_sha256": sha(committed),
                        "comparison": "CRLF-to-LF only; source content must match baseline"})
    local = HERE / "PixelBridgeExperiment.java"
    records.append({"path": local.relative_to(REPO).as_posix(), "bytes": local.stat().st_size,
                    "sha256": sha(local.read_bytes())})
    output = SLICE / "build/feasibility/bridge" / uuid.uuid4().hex
    output.mkdir(parents=True)
    empty_path = output / "empty"
    empty_path.mkdir()
    launch_env = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "JDK_JAVAC_OPTIONS", "CLASSPATH"):
        launch_env.pop(key, None)
    commands = [[str(javac), "--release", "8", "-proc:none", "-implicit:none",
                 "-classpath", str(empty_path), "-sourcepath", str(empty_path), "-encoding", "UTF-8", "-d", str(output),
                 *map(str, sources), str(local)],
                [str(java), "-cp", str(output), "local.sidecar.copilot.uvcsim.PixelBridgeExperiment"]]
    runs = []
    for command in commands:
        completed = subprocess.run(command, cwd=REPO, capture_output=True, text=True,
                                   encoding="utf-8", timeout=60, env=launch_env)
        runs.append({"command": [part.replace(str(output), "<ignored-build-dir>").replace(str(REPO), "<worktree>") for part in command],
                     "exit_code": completed.returncode, "stdout": completed.stdout.strip(),
                     "stderr": completed.stderr.strip()})
        if completed.returncode:
            raise RuntimeError(json.dumps(runs[-1]))
        if command[0] == str(java) and completed.stderr.strip():
            raise RuntimeError("unexpected JVM stderr: " + completed.stderr)
    outcome = json.loads(runs[-1]["stdout"])
    if outcome.get("passed") is not True or outcome.get("scenarios") != 4:
        raise ValueError("unexpected synthetic experiment result")
    fault_command = [str(java), "-cp", str(output), "local.sidecar.copilot.uvcsim.PixelBridgeExperiment", "--fault-hook"]
    fault = subprocess.run(fault_command, cwd=REPO, capture_output=True, text=True,
                           encoding="utf-8", timeout=60, env=launch_env)
    if fault.returncode == 0 or "no hook or asynchronous pipeline failure" not in fault.stderr:
        raise ValueError("injected asynchronous failure was not rejected: " + fault.stdout + fault.stderr)
    fault_control = {"command": [part.replace(str(output), "<ignored-build-dir>") for part in fault_command],
                     "exit_code": fault.returncode, "stdout": fault.stdout.strip(),
                     "stderr": fault.stderr.strip(), "expected_rejection": True}
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True,
                             encoding="utf-8", timeout=10, check=True, env=launch_env)
    result = {"schema": 1, "baseline": BASELINE, "project_sources": records,
              "jdk_version": (version.stdout + version.stderr).strip(), "runs": runs,
              "outcome": outcome, "fault_control": fault_control,
              "launch_isolation": "Annotation processing and implicit source compilation disabled; explicit empty javac classpath/sourcepath; Java option/classpath environment variables removed for child processes.",
              "scope": "Project-owned Java copy experiment against unchanged fixture pipeline, not libyuv/UVC/JNI execution.",
              "not_proven": ["official libyuv C/SIMD behavior", "native callback buffer lifetime",
                             "USB transport or actual negotiated format", "production performance or memory usage",
                             "real device stop barrier", "desktop/audio PRD acceptance"]}
    if args.write_report:
        report_path.write_bytes((json.dumps(result, indent=2) + "\n").encode())
    elif json.loads(report_path.read_bytes()) != result:
        raise ValueError("recomputed experiment differs from recorded results")
    print(json.dumps({"passed": True, "outcome": outcome, "results_sha256": sha(report_path.read_bytes())}))


if __name__ == "__main__":
    main()

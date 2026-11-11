"""Replay feasibility evidence sequentially; audio plans receive structure checks only."""
import argparse
import datetime
import hashlib
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parents[3]
PREFIX = "android/uvc-sim/provenance/feasibility/"


def audio_structure():
    path = HERE / "audio-gap/scenario-contract.json"
    data = json.loads(path.read_bytes())
    if data["execution_status"] != "design_only_not_implemented_or_run":
        raise ValueError("audio plan must remain explicitly unexecuted")
    if any(value is not False for value in data["constraints"].values()):
        raise ValueError("audio plan scope constraint changed")
    keys = [item["key"] for item in data["scenarios"]]
    if len(keys) != len(set(keys)) or len(keys) != 11:
        raise ValueError("unexpected or duplicate audio scenario keys")
    references = []
    def visit(node):
        if isinstance(node, dict):
            if "predicate" in node:
                references.append(node["predicate"])
                if node["predicate"] not in data["predicates"]:
                    raise ValueError("undeclared audio predicate")
            for child in node.values():
                visit(child)
        elif isinstance(node, list):
            for child in node:
                visit(child)
    visit(data["scenarios"])
    return {"structure_valid": True, "scenarios": len(keys), "declared_predicates": len(data["predicates"]),
            "predicate_references_checked": len(references), "scenarios_executed": 0,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-cache", type=pathlib.Path, required=True)
    parser.add_argument("--synthetic-cache", type=pathlib.Path, required=True)
    args = parser.parse_args()
    commands = [
        ("fixed source and dependency inventory", ["-O", PREFIX + "capabilities/inventory.py", "--source-cache", str(args.source_cache)]),
        ("inventory integrity", ["-O", "-m", "unittest", "discover", "-s", PREFIX + "capabilities", "-p", "test_inventory.py", "-v"]),
        ("scalar pixel contract and fixed source replay", [PREFIX + "synthetic/pixel_contracts.py", "--cache", str(args.synthetic_cache)]),
        ("scalar experiment integrity and negative controls", ["-m", "unittest", "discover", "-s", PREFIX + "synthetic", "-p", "test_replay.py", "-v"]),
        ("owned JVM bridge and worker-failure control", [PREFIX + "bridge/run_experiment.py"]),
    ]
    results = []
    for title, options in commands:
        command = [sys.executable, "-B", *options]
        print("Running: " + title, flush=True)
        completed = subprocess.run(command, cwd=REPO, capture_output=True, text=True,
                                   encoding="utf-8", errors="strict", timeout=180)
        results.append({"name": title, "command": command, "exit_code": completed.returncode,
                        "stdout": completed.stdout, "stderr": completed.stderr})
        print(title + ": exit " + str(completed.returncode), flush=True)
        if completed.returncode:
            break
    plan_check = audio_structure()
    passed = len(results) == len(commands) and all(row["exit_code"] == 0 for row in results)
    delivery = []
    for path in sorted(HERE.rglob("*")):
        if not path.is_file() or "__pycache__" in path.parts or path.name == "VALIDATION.json":
            continue
        payload = path.read_bytes()
        delivery.append({"path": path.relative_to(HERE).as_posix(), "bytes": len(payload),
                         "sha256": hashlib.sha256(payload).hexdigest()})
    result = {"schema": 1, "recorded_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "baseline": "c1afb3f61fa309bde0437fa2019c352ddc1792ba", "python": sys.version,
              "passed": passed, "commands": results, "audio_plan_structure": plan_check,
              "delivery_files": delivery,
              "scope": "Fixed static input replay, project-owned scalar/JVM experiments, and unexecuted audio-plan structure only.",
              "network_used_by_this_runner": False, "native_upstream_code_executed": False,
              "devices_accessed": False, "application_dependencies_changed": False,
              "not_established": ["official libyuv binary behavior", "UVC or UAC driver operation",
                                  "production memory or performance", "audio scenario execution",
                                  "device support or PRD phase exit", "native distribution clearance"]}
    (HERE / "VALIDATION.json").write_bytes((json.dumps(result, indent=2, ensure_ascii=False) + "\n").encode())
    print(json.dumps({"passed": passed, "command_groups": len(results),
                      "audio_plan_structure": plan_check, "delivery_files": len(delivery)}))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())

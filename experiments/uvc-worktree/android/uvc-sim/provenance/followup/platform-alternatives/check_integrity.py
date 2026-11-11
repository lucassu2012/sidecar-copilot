"""Exercise lock/replay failures in isolated ignored caches; no native execution."""
import hashlib
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
CACHE = HERE.parents[2] / "build/native-research/platform-alternatives"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    before = {name: digest(HERE / name) for name in ["INPUT_LOCK.json", "static-evidence.json"]}
    CACHE.mkdir(parents=True, exist_ok=True)
    root = pathlib.Path(tempfile.mkdtemp(prefix="integrity-", dir=CACHE)).resolve()
    if not root.is_relative_to(CACHE.resolve()):
        raise RuntimeError("validation path escaped audit cache")
    lock = json.loads((HERE / "INPUT_LOCK.json").read_bytes())
    checks = []
    for scenario, expected in [("offline", None), ("missing", "missing locked input"),
                               ("tamper", "input integrity mismatch"),
                               ("report", "replay differs from tracked report"),
                               ("missing-report", "missing tracked report"),
                               ("bootstrap", "bootstrap refuses")]:
        case = root / scenario
        code = case / "code"
        cache = case / "cache"
        code.mkdir(parents=True)
        cache.mkdir()
        for name in ["audit_platform.py", "INPUT_LOCK.json", "static-evidence.json"]:
            shutil.copy2(HERE / name, code / name)
        for item in lock["inputs"]:
            shutil.copy2(CACHE / (item["id"] + ".raw"), cache / (item["id"] + ".raw"))
        if scenario == "missing":
            (cache / "external-device.raw").unlink()
        elif scenario == "tamper":
            path = cache / "external-device.raw"
            content = bytearray(path.read_bytes())
            content[len(content) // 2] ^= 1
            path.write_bytes(content)
        elif scenario == "missing-report":
            (code / "static-evidence.json").unlink()
        elif scenario == "report":
            path = code / "static-evidence.json"
            data = json.loads(path.read_bytes())
            data["inputs_verified"] = 999
            path.write_text(json.dumps(data), encoding="utf-8")
        args = [sys.executable, str(code / "audit_platform.py"), "--cache", str(cache)]
        args += ["--record-inputs"] if scenario == "bootstrap" else ["--offline"]
        run = subprocess.run(args, text=True, capture_output=True, check=False)
        success = run.returncode == 0 if expected is None else run.returncode != 0 and expected in run.stderr
        checks.append({"scenario": scenario, "passed": success, "exit_code": run.returncode,
                       "expected_diagnostic": expected, "stdout": run.stdout.strip(),
                       "stderr": run.stderr.strip().replace(str(root), "<isolated-validation-cache>")})
    unchanged = all(digest(HERE / name) == value for name, value in before.items())
    result = {"schema": 1, "passed": unchanged and all(x["passed"] for x in checks),
              "python": sys.version.split()[0], "script_sha256": digest(HERE / "audit_platform.py"),
              "tracked_inputs_unchanged": unchanged, "tracked_hashes": before, "checks": checks}
    (HERE / "integrity-checks.json").write_bytes((json.dumps(result, indent=2, sort_keys=True) + "\n").encode())
    print(json.dumps({"passed": result["passed"], "checks": len(checks), "tracked_inputs_unchanged": unchanged}))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())

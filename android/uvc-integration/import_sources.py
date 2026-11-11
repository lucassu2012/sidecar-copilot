"""One-time, offline import of the three reviewed Git trees; never overwrite a target."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
EXTERNAL = ROOT.parent.parent / "task-4"
PREFIX = "android/uvc-sim/native-experiment/"
SOURCES = (
    ("adapter", "sidecar-uvc-adapter", "ff46084debcfef8b29420fdb786c5ddfc3e19981", PREFIX,
     ("src/", "test/"), ("CONTRACT_MAPPING.md", "GAPS_AND_LICENSE.md", "upstream-review.json")),
    ("owner", "sidecar-usb-owner", "3e5abb1b880040ceff775c5cd43d5ad61115af9b", PREFIX + "usb-owner-experiment/",
     ("src/", "test/"), ("SOURCE_AND_GAPS.md", "inputs-lock.json")),
    ("bridge", "sidecar-android-usb-bridge", "62eb3485a056c91720609f38ba436122bfdcb420", PREFIX + "android-bridge-experiment/",
     ("src/", "test/", "test-android/"),
     ("SOURCE_AND_GAPS.md", "inputs-lock.json", "source-evidence/inputs.json", "source-evidence/anchors.json")),
)


def identity(data):
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def git(repo, *arguments):
    return subprocess.run(["git", "-C", str(repo), *arguments], check=True, capture_output=True).stdout


def main():
    lock = HERE / "provenance/source-manifest.json"
    if lock.exists() or any((HERE / name).exists() for name in ("adapter", "owner", "bridge", "provenance")):
        raise RuntimeError("Import targets already exist; refusing to overwrite or merge")
    planned, origins, contracts = [], [], {}
    for module, name, commit, prefix, groups, documents in SOURCES:
        repo = EXTERNAL / name
        if git(repo, "rev-parse", "HEAD").decode().strip() != commit:
            raise RuntimeError("External HEAD differs from reviewed commit: " + name)
        if git(repo, "status", "--porcelain").strip():
            raise RuntimeError("External working tree has changes: " + name)
        files = git(repo, "ls-tree", "-r", "--name-only", commit, "--", prefix).decode().splitlines()
        selected = []
        for path in files:
            relative = path[len(prefix):]
            is_java = relative.endswith(".java") and relative.startswith(groups)
            is_manifest = module == "bridge" and relative == "AndroidManifest.xml"
            if is_java or is_manifest:
                selected.append((path, module + "/" + relative, "production" if relative.startswith("src/") else "test-or-isolated-manifest"))
            elif relative in documents:
                selected.append((path, "provenance/" + module + "/" + relative, "archived-source-context-not-current-result"))
        for document in documents:
            if prefix + document not in {p for p, _, _ in selected}:
                raise RuntimeError("Missing required provenance: " + document)
        for path, target, role in selected:
            data = git(repo, "show", commit + ":" + path)
            if (repo / path).read_bytes() != data:
                raise RuntimeError("Working bytes differ from Git object: " + path)
            planned.append((target, data, {"module": module, "source_repository": str(repo), "source_commit": commit,
                "source_path": path, "git_blob": git(repo, "rev-parse", commit + ":" + path).decode().strip(),
                "target": target, "role": role, **identity(data)}))
        comparisons = []
        for current in sorted((ROOT / "android/uvc-contract/src").rglob("*.java")):
            relative = current.relative_to(ROOT / "android/uvc-contract/src").as_posix()
            old_path = PREFIX + "contract-baseline/src/" + relative
            old = git(repo, "show", commit + ":" + old_path)
            if old != current.read_bytes():
                raise RuntimeError("Public contract mismatch; coordinate with main owner: " + relative)
            key = current.relative_to(ROOT).as_posix()
            contracts[key] = identity(old)
            comparisons.append({"main_path": key, "source_path": old_path,
                "git_blob": git(repo, "rev-parse", commit + ":" + old_path).decode().strip(), **identity(old)})
        origins.append({"module": module, "repository": str(repo), "commit": commit,
                        "clean_worktree_at_import": True, "contract_comparisons": comparisons})
    if len(contracts) != 3:
        raise RuntimeError("Expected exactly three main public contract source files")
    targets = [target for target, _, _ in planned]
    if len(set(targets)) != len(targets):
        raise RuntimeError("Duplicate import target")
    for target, data, _ in planned:
        destination = HERE / target
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("xb") as output:
            output.write(data)
    record = {"schema": 1, "imported_utc": datetime.now(timezone.utc).isoformat(),
        "scope": "Verbatim reviewed Java modules and selected source context; no historical result inheritance",
        "origins": origins, "files": [entry for _, _, entry in planned], "main_contract_sources": contracts,
        "not_copied": ["all contract-baseline directories", "historical test/build results", "upstream runners",
                       "third-party native source/binaries", "AOSP text caches", "main app configuration"],
        "runtime_dependencies_added": [], "main_app_enabled": False}
    with lock.open("x", encoding="utf-8", newline="\n") as output:
        output.write(json.dumps(record, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"imported_files": len(planned), "main_contract_files": len(contracts), "manifest": str(lock)}))


if __name__ == "__main__":
    main()

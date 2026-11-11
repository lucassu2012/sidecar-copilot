"""Static, dependency-free libyuv provenance comparison; never builds upstream code.

Only this script's JSON report is written outside ignored build/provenance/libyuv.
Downloaded archives are read as data without extraction or code execution.
"""
import argparse
import concurrent.futures
import datetime
import difflib
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile
import urllib.request

HERE = Path(__file__).resolve().parent
CACHE = HERE.parents[1] / "build" / "provenance" / "libyuv"
DEFAULT_CACHE = CACHE
LOCK_PATH = HERE / "artifact-lock.json"
LOCK = {}
OBSERVED = {}
OFFLINE = False
RECORD = False
OFFICIAL = "https://chromium.googlesource.com/libyuv/libyuv"
CANDIDATES = ["65e7c9d5706a77d1949da59bfcb0817c252ef8d6", "3e38ce50589d9319badc0501f96d6c5b2b177472"]
BEFORE = "1c5a8bb17ac4092da557e55cf519bf4df105d8f1"
AFTER = "f71c83552d373f0ff41833b17e2880632d8561d7"
LICENSE_NAMES = ("LICENSE", "PATENTS", "AUTHORS")
METADATA_FORMATS = {"import-tree.json": "github-tree-semantic-v1", "vendor-history.json": "github-commit-history-semantic-v1"}


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def blob_sha(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def lf(data):
    # Deliberately only normalize CRLF. No trim, encoding conversion or whitespace edits.
    return data.replace(b"\r\n", b"\n")


def integrity_bytes(data, name):
    """Only two GitHub API metadata responses use the documented semantic projection.

    Raw response hashes remain observations. Source archives and all other artifacts
    retain their original byte-integrity checks. This is not RFC 8785 JSON canonicalization.
    """
    if name not in METADATA_FORMATS:
        return data
    def unique_object(pairs):
        obj = {}
        for key, value in pairs:
            if key in obj:
                raise ValueError("Duplicate JSON key: " + key)
            obj[key] = value
        return obj
    obj = json.loads(data.decode("utf-8"), object_pairs_hook=unique_object,
                     parse_constant=lambda value: (_ for _ in ()).throw(ValueError("Invalid JSON constant: " + value)))
    if name == "import-tree.json":
        entries = []
        for entry in obj["tree"]:
            row = {key: entry[key] for key in ("path", "mode", "type", "sha")}
            if entry["type"] == "blob":
                row["size"] = entry["size"]
            entries.append(row)
        if len({row["path"] for row in entries}) != len(entries):
            raise ValueError("Duplicate tree path")
        projected = {"sha": obj["sha"], "truncated": obj["truncated"],
                     "tree": sorted(entries, key=lambda row: row["path"])}
    else:
        projected = []
        for entry in obj:
            commit = entry["commit"]
            projected.append({"sha": entry["sha"], "commit": {
                "author": {key: commit["author"][key] for key in ("name", "email", "date")},
                "committer": {key: commit["committer"][key] for key in ("name", "email", "date")},
                "message": commit["message"], "tree": {"sha": commit["tree"]["sha"]}},
                "parents": [{"sha": parent["sha"]} for parent in entry["parents"]],
                "html_url": "https://github.com/shiyinghan/UVCAndroid/commit/" + entry["sha"]})
    return (json.dumps(projected, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")


def fetch(url, name, refresh=False):
    target = CACHE / name
    locked = LOCK.get(name)
    if not RECORD and (locked is None or locked["url"] != url):
        raise ValueError("Artifact is not bound to this URL in artifact-lock.json: " + name)
    representation = METADATA_FORMATS.get(name, "raw-bytes")
    if not RECORD and locked.get("representation", "raw-bytes") != representation:
        raise ValueError("Unexpected artifact integrity representation: " + name)
    if refresh or not target.exists():
        if OFFLINE:
            raise FileNotFoundError("Offline artifact missing: " + str(target))
        request = urllib.request.Request(url, headers={"User-Agent": "Sidecar-static-provenance/1.0"})
        with urllib.request.urlopen(request, timeout=90) as response:
            data = response.read()
        if not RECORD and sha256(integrity_bytes(data, name)) != locked["sha256"]:
            raise ValueError("Downloaded artifact SHA-256 mismatch; cache not replaced: " + name)
        target.write_bytes(data)
    raw = target.read_bytes()
    data = integrity_bytes(raw, name)
    if not RECORD and (sha256(data) != locked["sha256"] or len(data) != locked["size"]):
        raise ValueError("Cached artifact SHA-256/size mismatch: " + name)
    observed = {"url": url, "sha256": sha256(data), "size": len(data)}
    if name in METADATA_FORMATS:
        observed.update({"representation": representation,
                         "observed_raw": [{"sha256": sha256(raw), "size": len(raw), "source": "bootstrap HTTP or existing cache"}]})
    OBSERVED[name] = observed
    artifact = {"url": url, "cache": str(target.relative_to(HERE.parents[1])).replace("\\", "/"),
                "size": len(data), "sha256": sha256(data)}
    if name in METADATA_FORMATS:
        artifact.update({"representation": representation, "observed_raw_sha256": sha256(raw), "observed_raw_size": len(raw)})
    return data, artifact


def gitiles_json(commit, refresh):
    data, artifact = fetch(OFFICIAL + "/+/" + commit + "?format=JSON", commit + ".commit.json", refresh)
    parsed = json.loads(data[4:])
    assert parsed["commit"] == commit
    return parsed, artifact


def archive_files(data, prefix=""):
    result = {}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        for member in archive.getmembers():
            if member.isfile() and member.name.startswith(prefix):
                path = member.name[len(prefix):]
                assert path and path not in result
                result[path] = archive.extractfile(member).read()
    return result


def version(data):
    return int(re.search(rb"#define LIBYUV_VERSION\s+(\d+)", data).group(1))


def main():
    global CACHE, LOCK, RECORD, OFFLINE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--refresh", action="store_true", help="Refetch static artifacts into the ignored cache")
    parser.add_argument("--offline", action="store_true", help="Fail on missing/tampered cache; perform no network access")
    parser.add_argument("--cache-dir", type=Path, default=DEFAULT_CACHE, help="Optional cache under ignored build/provenance/libyuv")
    parser.add_argument("--record-artifacts", action="store_true", help="First-time artifact lock creation only; refuses an existing lock")
    parser.add_argument("--write-report", action="store_true", help="Explicitly regenerate report.json; default verifies without overwriting evidence")
    parser.add_argument("--metadata-only", action="store_true", help="Validate/rebuild only two GitHub metadata inputs without reading source archives")
    args = parser.parse_args()
    OFFLINE, RECORD = args.offline, args.record_artifacts
    if args.offline and args.refresh:
        parser.error("--offline cannot be combined with --refresh")
    if args.offline and args.record_artifacts:
        parser.error("--offline cannot be combined with first-time --record-artifacts")
    if args.metadata_only and args.record_artifacts:
        parser.error("--metadata-only cannot create a partial artifact lock")
    CACHE = args.cache_dir.resolve()
    if not CACHE.is_relative_to(DEFAULT_CACHE.resolve()):
        parser.error("--cache-dir must remain inside ignored build/provenance/libyuv")
    if RECORD:
        if LOCK_PATH.exists():
            raise FileExistsError("Refusing to overwrite artifact-lock.json")
    else:
        LOCK = json.loads(LOCK_PATH.read_text(encoding="utf-8"))["artifacts"]
    CACHE.mkdir(parents=True, exist_ok=True)
    if args.metadata_only:
        checked_metadata = {}
        for name in METADATA_FORMATS:
            _, checked_metadata[name] = fetch(LOCK[name]["url"], name, args.refresh)
        if args.write_report:
            # The source comparisons are already recorded. Update only the affected
            # metadata envelopes and preserve a hash linking the previous evidence.
            target = HERE / "report.json"
            previous_report = target.read_bytes()
            report = json.loads(previous_report)
            report["import_history"]["import_tree_artifact"] = checked_metadata["import-tree.json"]
            report["import_history"]["history_artifact"] = checked_metadata["vendor-history.json"]
            report["artifact_lock"] = {"path": "artifact-lock.json", "sha256": sha256(LOCK_PATH.read_bytes()), "artifact_count": len(LOCK)}
            report["metadata_integrity_update"] = {"previous_report_sha256": sha256(previous_report),
                "recorded_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "comparison_section_sha256_unchanged": sha256(json.dumps(report["candidate_comparisons"], sort_keys=True).encode()),
                "note": "Only two GitHub metadata envelopes and the artifact-lock digest were refreshed; source comparisons were not rerun."}
            target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print("PASS GitHub semantic metadata checks: 2 of", len(LOCK), "locked artifacts; offline=" + str(OFFLINE))
        return
    manifest = json.loads((HERE / "vendored-manifest.json").read_text(encoding="utf-8"))
    ref = manifest["ref"]
    vendored_url = "https://codeload.github.com/" + manifest["repository"] + "/tar.gz/" + ref
    jobs = [(vendored_url, "uvcandroid-" + ref + ".tar.gz")]
    jobs += [(OFFICIAL + "/+archive/" + c + ".tar.gz", c + ".tar.gz") for c in CANDIDATES]
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        downloaded = list(pool.map(lambda item: fetch(*item, refresh=args.refresh), jobs))
    archive, vendored_artifact = downloaded[0]
    prefix = "UVCAndroid-" + ref + "/" + manifest["prefix"]
    vendored = archive_files(archive, prefix)
    expected = {e["path"]: e for e in manifest["entries"]}
    assert set(vendored) == set(expected), "Vendored archive/connector Git tree path discrepancy"
    for path, data in vendored.items():
        assert blob_sha(data) == expected[path]["sha"], "Git blob mismatch: " + path
        assert len(data) == expected[path]["size"], "Git size mismatch: " + path
    assert manifest["tree_sha"] == manifest["publication_tree_sha"]
    imported_data, imported_artifact = fetch("https://api.github.com/repos/shiyinghan/UVCAndroid/git/trees/129b2465e047bc1be9d8a3597cbf65ecdec8fab5?recursive=1", "import-tree.json", args.refresh)
    imported = json.loads(imported_data)
    assert imported["sha"] == "129b2465e047bc1be9d8a3597cbf65ecdec8fab5" and not imported["truncated"]
    imported_blobs = {e["path"]: e for e in imported["tree"] if e["type"] == "blob"}
    assert set(imported_blobs) == set(expected)
    changes_since_import = [{"path": p, "imported_blob": imported_blobs[p]["sha"], "target_blob": expected[p]["sha"]}
                            for p in sorted(expected) if imported_blobs[p]["sha"] != expected[p]["sha"]]
    assert [e["path"] for e in changes_since_import] == ["Android.mk"]
    history_data, history_artifact = fetch("https://api.github.com/repos/shiyinghan/UVCAndroid/commits?sha=" + ref + "&path=libuvccamera/src/main/jni/libyuv&per_page=100", "vendor-history.json", args.refresh)
    history = json.loads(history_data)
    assert [c["sha"] for c in history] == ["c06f4edf10b6e67bffc5364642857af75944f294", "569e118d7514193da204b953ebbe47c9c87cdbe9", "05435d727fcdd74fc44fd2a3826ee57213dbb07e"]
    commit_evidence = []
    for commit in [BEFORE] + CANDIDATES + [AFTER]:
        obj, artifact = gitiles_json(commit, args.refresh)
        commit_evidence.append({"commit": commit, "tree": obj["tree"], "parents": obj["parents"],
                                "committer": obj["committer"], "message": obj["message"], "artifact": artifact})
    for previous, current in zip(commit_evidence, commit_evidence[1:]):
        assert current["parents"] == [previous["commit"]], "Version interval must be consecutive linear commits"
    # Read neighboring version headers from the official host; not inferred from messages.
    import base64
    for item in commit_evidence:
        data, artifact = fetch(OFFICIAL + "/+/" + item["commit"] + "/include/libyuv/version.h?format=TEXT",
                               item["commit"] + ".version.base64", args.refresh)
        raw = base64.b64decode(data, validate=True)
        item["version"] = version(raw)
        item["version_git_blob"] = blob_sha(raw)
        item["version_sha256"] = sha256(raw)
        item["version_artifact"] = artifact
    assert [x["version"] for x in commit_evidence] == [1839, 1840, 1840, 1841]
    comparisons = []
    for commit, (data, artifact) in zip(CANDIDATES, downloaded[1:]):
        upstream = archive_files(data)
        assert version(upstream["include/libyuv/version.h"]) == 1840
        root_data, root_artifact = fetch(OFFICIAL + "/+/" + commit + "/?format=JSON", commit + ".tree.json", args.refresh)
        root = json.loads(root_data[4:])
        roots = {e["name"]: e for e in root["entries"]}
        rows = []
        patches = []
        for path, value in sorted(vendored.items()):
            original = upstream.get(path)
            match = "not_in_upstream" if original is None else "identical_bytes" if value == original else "crlf_only" if lf(value) == lf(original) else "modified_content"
            row = {"path": path, "match": match, "vendored_git_blob": blob_sha(value),
                         "vendored_sha256": sha256(value), "vendored_crlf_count": value.count(b"\r\n"),
                         "vendored_lf_git_blob": blob_sha(lf(value)),
                         "upstream_git_blob": None if original is None else blob_sha(original),
                         "upstream_sha256": None if original is None else sha256(original)}
            if match == "modified_content":
                a, b = lf(original).decode("utf-8").splitlines(keepends=True), lf(value).decode("utf-8").splitlines(keepends=True)
                added = removed = 0
                for tag, i, j, k, l in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
                    if tag in ("replace", "delete"):
                        removed += j - i
                    if tag in ("replace", "insert"):
                        added += l - k
                row["normalized_line_changes"] = {"added": added, "removed": removed}
                patches.extend(difflib.unified_diff(a, b, fromfile="official/" + path, tofile="vendored/" + path))
            rows.append(row)
        missing = [{"path": path, "git_blob": blob_sha(value), "sha256": sha256(value), "size": len(value)}
                   for path, value in sorted(upstream.items()) if path not in vendored]
        licenses = [{"path": name, "git_blob": blob_sha(upstream[name]), "sha256": sha256(upstream[name]),
                     "size": len(upstream[name]), "absent_from_vendored": name not in vendored,
                     "url": OFFICIAL + "/+/" + commit + "/" + name,
                     "raw_base64_url": OFFICIAL + "/+/" + commit + "/" + name + "?format=TEXT"}
                    for name in LICENSE_NAMES]
        for license_item in licenses:
            name = license_item["path"]
            assert roots[name]["id"] == blob_sha(upstream[name]), "Official root tree/license blob mismatch"
            text_data, text_artifact = fetch(license_item["raw_base64_url"], commit + "." + name + ".base64", args.refresh)
            assert base64.b64decode(text_data, validate=True) == upstream[name], "Official file/archive license mismatch"
            license_item["official_file_artifact"] = text_artifact
        patch = "".join(patches).encode("utf-8")
        # Derived source diff remains ignored and is never used as an executable input.
        (CACHE / (commit + ".normalized-diff.patch")).write_bytes(patch)
        comparisons.append({"commit": commit, "official_archive": artifact, "official_root_tree": root_artifact,
                            "normalized_diff_sha256": sha256(patch), "upstream_file_count": len(upstream),
                            "counts": {label: sum(r["match"] == label for r in rows)
                                       for label in ("identical_bytes", "crlf_only", "modified_content", "not_in_upstream")},
                            "files": rows, "omitted_upstream_files": missing, "license_material": licenses})
    report = {"schema": 1, "recorded_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "method": "Exact vendored Git blobs verified against connector tree; official commit-addressed archive bytes compared path-by-path, separately normalizing CRLF only. No upstream execution.",
              "vendored": {"ref": ref, "tree": manifest["tree_sha"], "publication_ref": manifest["publication_ref"],
                           "publication_tree": manifest["publication_tree_sha"], "file_count": len(vendored),
                           "artifact": vendored_artifact},
              "import_history": {"imported_tree": imported["sha"], "imported_file_count": len(imported_blobs),
                                 "unchanged_blobs_since_import": len(expected) - len(changes_since_import),
                                 "changed_blobs_since_import": changes_since_import, "import_tree_artifact": imported_artifact,
                                 "history_artifact": history_artifact,
                                 "commits": [{"sha": c["sha"], "committed": c["commit"]["committer"]["date"], "message": c["commit"]["message"], "url": c["html_url"]} for c in history]},
              "official_consecutive_interval": commit_evidence, "candidate_comparisons": comparisons,
              "limitations": ["Version interval is this verified linear upstream history; a version number alone is not a unique commit.",
                              "A source-content match does not prove the importer fetched that exact commit or identify the binary build inputs.",
                              "CRLF normalization is reported separately and does not imply original byte identity.",
                              "Missing upstream files are omissions from the full snapshot, not automatically runtime dependencies.",
                              "Source-to-Maven-AAR equivalence, final linkage, notices packaging and distribution compliance are not established."]}
    if RECORD:
        with LOCK_PATH.open("x", encoding="utf-8") as target:
            json.dump({"schema": 2, "artifacts": dict(sorted(OBSERVED.items()))}, target, indent=2)
            target.write("\n")
    else:
        assert set(OBSERVED) == set(LOCK), "Artifact inventory changed; review explicitly before replacing the lock"
    report["artifact_lock"] = {"path": "artifact-lock.json", "sha256": sha256(LOCK_PATH.read_bytes()),
                               "artifact_count": len(OBSERVED)}
    if args.write_report:
        target = HERE / "report.json"
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for item in comparisons:
        print(item["commit"], item["counts"], "omitted", len(item["omitted_upstream_files"]))
    print("PASS static provenance checks; verified", len(OBSERVED), "locked artifacts; offline=" + str(OFFLINE))
    if args.write_report:
        print("Report:", HERE / "report.json")


if __name__ == "__main__":
    main()

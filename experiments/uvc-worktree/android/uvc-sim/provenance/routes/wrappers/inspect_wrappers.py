"""Replay a bounded primary-source wrapper study; no upstream code is executed.

Mutable GitHub observations are tracked snapshots. --check-live detects projected
metadata drift without replacing those snapshots. --fetch retrieves only locked
UTF-8 source files at fixed commits; .so files are metadata-only observations.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
CACHE = ROOT / "android/uvc-sim/build/routes/wrappers"
INHERITED = [
    "android/uvc-sim/provenance/libyuv/report.json",
    "android/uvc-sim/provenance/followup/libyuv/report.json",
    "android/uvc-sim/provenance/followup/video-alternatives/evidence.json",
    "android/uvc-sim/provenance/uac/static-evidence.json",
    "android/uvc-sim/provenance/followup/uac/evidence.json",
]


def canonical(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=True) + "\n").encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def pick(value, keys):
    return {key: value.get(key) for key in keys}


def commit_projection(value):
    return {"sha": value["sha"], "html_url": value["html_url"],
            "author_date": value["commit"]["author"]["date"],
            "committer_date": value["commit"]["committer"]["date"],
            "message": value["commit"]["message"],
            "tree_sha": value["commit"]["tree"]["sha"],
            "parents": [p["sha"] for p in value["parents"]]}


def release_projection(value):
    return pick(value, ["id", "tag_name", "name", "html_url", "target_commitish",
                        "published_at", "created_at", "draft", "prerelease", "body"])


def project(kind, value, selected_paths=()):
    if kind == "repo":
        # Deliberately exclude popularity, permissions and generic updated_at.
        return pick(value, ["id", "full_name", "html_url", "default_branch", "archived",
                            "disabled", "pushed_at", "license"])
    if kind == "head":
        return commit_projection(value)
    if kind in ("recent", "native", "usb", "uac"):
        return [commit_projection(item) for item in value]
    if kind == "releases":
        return [release_projection(item) for item in value]
    if kind == "latest":
        return release_projection(value)
    if kind == "tagrefs":
        return [{"ref": item["ref"], "object": pick(item["object"], ["type", "sha"])}
                for item in value]
    if kind == "tree":
        selected = []
        for item in value["tree"]:
            path = item["path"]
            if ("/" not in path or path in selected_paths or path.endswith(".so")
                    or re.search(r"UAC|uac|usbAudio", path)
                    or re.search(r"/jni/[^/]+$", path)):
                selected.append(pick(item, ["path", "mode", "type", "sha", "size"]))
        return {"sha": value["sha"], "truncated": value["truncated"],
                "full_entry_count": len(value["tree"]), "selected_entries": selected}
    if kind.startswith("patch-"):
        result = commit_projection(value)
        result["files"] = []
        for item in value.get("files", []):
            file = pick(item, ["filename", "sha", "status", "additions", "deletions"])
            # Keep exact relevant native/USB patch text; omit IDE-file patch noise.
            if "/jni/" in item["filename"] or item["filename"].endswith("USBMonitor.java"):
                file["patch"] = item.get("patch")
            result["files"].append(file)
        return result
    raise ValueError("unsupported metadata projection: " + kind)


def verify_source(data, entry):
    require(len(data) == entry["size"], "source size mismatch: " + entry["path"])
    require(blob(data) == entry["git_blob_sha"], "source Git blob mismatch: " + entry["path"])
    require(sha(data) == entry["sha256"], "source SHA256 mismatch: " + entry["path"])
    data.decode("utf-8", errors="strict")
    return data


def bootstrap(path, cache):
    """One-time explicit import of connector results; never used by normal replay."""
    require(not (HERE / "inputs.lock.json").exists(), "bootstrap refuses existing lock")
    original = json.loads(path.read_text(encoding="utf-8"))
    sources = []
    cache.mkdir(parents=True, exist_ok=True)
    for item in original["sources"]:
        data = item["connector_content"].encode("utf-8")
        entry = {k: v for k, v in item.items() if k != "connector_content"}
        entry["sha256"] = sha(data)
        entry["cache_file"] = entry["git_blob_sha"] + ".txt"
        verify_source(data, entry)
        target = cache / entry["cache_file"]
        if target.exists():
            verify_source(target.read_bytes(), entry)
        else:
            target.write_bytes(data)
        sources.append(entry)
    observations = []
    for item in original["metadata"]:
        data = item["connector_content"].encode("utf-8")
        paths = [s["path"] for s in sources if s["repo"] == item["repo"]]
        value = project(item["kind"], json.loads(data), paths)
        observations.append({"repo": item["repo"], "kind": item["kind"], "url": item["url"],
                             "connector_text_sha256": sha(data), "connector_text_size": len(data),
                             "projection": value})
    snapshot = {"schema": 1, "collection_began_utc": original["observed_at_utc"],
                "method": "Primary GitHub connector GET; JSON projected by inspect_wrappers.project; original connector text is in ignored bootstrap cache.",
                "limits": "Mutable metadata is an observation, not immutable truth; recent history is first 10 and each path history first 5 from a fixed HEAD; selected tree entries only.",
                "observations": observations}
    snapshot_bytes = canonical(snapshot)
    (HERE / "observations.json").write_bytes(snapshot_bytes)
    inherited = []
    for name in INHERITED:
        data = (ROOT / name).read_bytes()
        inherited.append({"path": name, "sha256": sha(data), "size": len(data)})
    lock = {"schema": 1, "baseline": "bbd053664bebb06ce83539a3f95e6fa5758745b7",
            "source_retrieval": "Only raw UTF-8 text at fixed commits; check size, Git blob SHA1 and SHA256. No binary/source archive downloads.",
            "observations": {"file": "observations.json", "sha256": sha(snapshot_bytes), "size": len(snapshot_bytes)},
            "sources": sources, "inherited_evidence": inherited}
    (HERE / "inputs.lock.json").write_bytes(canonical(lock))


def source_bytes(entry, cache, fetch):
    file = cache / entry["cache_file"]
    if file.exists():
        return verify_source(file.read_bytes(), entry)
    require(fetch, "offline source missing: " + entry["path"])
    require(not entry["path"].endswith((".so", ".aar", ".jar", ".zip")), "binary input forbidden")
    with urllib.request.urlopen(entry["url"], timeout=30) as response:
        data = response.read(entry["size"] + 1)
    verify_source(data, entry)
    cache.mkdir(parents=True, exist_ok=True)
    with file.open("xb") as stream:
        stream.write(data)
    return data


MARKERS = re.compile(
    r"getDeviceList\(|requestPermission\(|hasPermission\(|openDevice\(|getFileDescriptor\("
    r"|nativeConnect\(|dup\(fd\)|uvc_wrap\(|uvc_get_device_with_fd\(|uvc_open\(|uvc_close\("
    r"|uvc_start_streaming|uvc_stop_streaming|uvc_mjpeg2|uvc_yuyv2rgbx|uvc_any2rgbx|NewDirectByteBuffer"
    r"|setFrameCallback\(|open\(mCtrlBlock\)|getSupportedSizeList\("
    r"|recycle_frame\(callback_frame\)|System.loadLibrary|new AudioRecord|AudioSource\."
    r"|nativeInit|nativeStart|nativeStop|UACAudio|AudioStrategy|jniLibs|ndkBuild|externalNativeBuild"
    r"|Apache License|Lesser General Public License|Redistribution and use|Copyright|IJG|zlib")


def replay(cache, fetch=False, check_live=False):
    lock_bytes = (HERE / "inputs.lock.json").read_bytes()
    lock = json.loads(lock_bytes)
    observed_bytes = (HERE / lock["observations"]["file"]).read_bytes()
    require(len(observed_bytes) == lock["observations"]["size"] and
            sha(observed_bytes) == lock["observations"]["sha256"], "observation snapshot mismatch")
    observed = json.loads(observed_bytes)
    if check_live:
        for item in observed["observations"]:
            request = urllib.request.Request(item["url"], headers={"User-Agent": "sidecar-static-research"})
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read(3 * 1024 * 1024 + 1)
            require(len(raw) <= 3 * 1024 * 1024, "metadata response too large")
            paths = [s["path"] for s in lock["sources"] if s["repo"] == item["repo"]]
            require(project(item["kind"], json.loads(raw), paths) == item["projection"],
                    "live metadata drift: " + item["repo"] + " / " + item["kind"])
    inherited = []
    for entry in lock["inherited_evidence"]:
        data = (ROOT / entry["path"]).read_bytes()
        require(len(data) == entry["size"] and sha(data) == entry["sha256"],
                "inherited evidence mismatch: " + entry["path"])
        inherited.append(entry)
    source_report = []
    for entry in lock["sources"]:
        text = source_bytes(entry, cache, fetch).decode("utf-8")
        matches = [{"line": index, "text": line.strip()} for index, line in enumerate(text.splitlines(), 1)
                   if MARKERS.search(line)]
        source_report.append({**entry, "line_count": len(text.splitlines()), "marker_lines": matches})
    return {"schema": 1, "lock_sha256": sha(lock_bytes), "snapshot_sha256": sha(observed_bytes),
            "snapshot_collection_began_utc": observed["collection_began_utc"],
            "metadata_observations": len(observed["observations"]),
            "source_count": len(source_report), "unique_source_blobs": len({s["git_blob_sha"] for s in source_report}),
            "source_bytes_total_including_duplicates": sum(s["size"] for s in source_report),
            "source_report": source_report, "inherited_evidence": inherited,
            "scope": "Static evidence replay only. No binary download, upstream execution, build, device or compatibility/security clearance."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=CACHE)
    parser.add_argument("--fetch", action="store_true")
    parser.add_argument("--check-live", action="store_true", help="compare current projected metadata; never update snapshot")
    parser.add_argument("--bootstrap", type=Path, help="initial connector import only; refuses an existing lock")
    parser.add_argument("--record", action="store_true", help="explicitly replace own static report")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.bootstrap:
        bootstrap(args.bootstrap, args.cache)
    result = replay(args.cache, args.fetch, args.check_live)
    result["script_sha256"] = sha(Path(__file__).read_bytes())
    data = canonical(result)
    expected = HERE / "static-report.json"
    if args.record:
        expected.write_bytes(data)
    else:
        require(expected.read_bytes() == data, "stored static report differs from replay")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(data)
    print(json.dumps({"metadata_observations": result["metadata_observations"],
                      "source_count": result["source_count"], "unique_source_blobs": result["unique_source_blobs"],
                      "report_sha256": sha(data), "stored_report_match": True}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        raise SystemExit(1)

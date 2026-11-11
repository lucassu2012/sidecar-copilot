#!/usr/bin/env python3
"""Verify fixed upstream text offline; --fetch restores missing locked text only."""
import argparse
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.request
import uuid

ROOT = Path(__file__).absolute().parent
REPO = ROOT.parents[4]
COMMITS = {
    "libusb": "87a55632db62c9bdc58cd31d3ccfa673f1bb017f",
    "libuvc": "448d78b9aa4f54172c1ca5b5d1b4a6b9b93f7957",
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_path(path):
    path = path.absolute()
    for entry in tuple(reversed(path.parents)) + (path,):
        info = entry.lstat()
        require(not stat.S_ISLNK(info.st_mode)
                and not getattr(info, "st_file_attributes", 0) & 0x400,
                "Symlink/reparse point refused: " + str(entry))


def read(path):
    check_path(path)
    require(stat.S_ISREG(path.stat().st_mode), "Not a regular file: " + str(path))
    return path.read_bytes()


def safe_relative(value):
    require(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*", value)
            and all(part not in (".", "..") for part in value.split("/")),
            "Unsafe relative path")
    return value


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, "Duplicate JSON key: " + key)
        result[key] = value
    return result


def load(path):
    return json.loads(read(path).decode("utf-8"), object_pairs_hook=pairs)


def check_bytes(row, raw, label):
    require(len(raw) == row["bytes"], label + ": size mismatch")
    require(hashlib.sha256(raw).hexdigest() == row["sha256"], label + ": SHA-256 mismatch")
    if "git_blob_sha1" in row:
        blob = b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw
        require(hashlib.sha1(blob).hexdigest() == row["git_blob_sha1"], label + ": Git blob mismatch")


def region(ref, texts):
    sid = ref["source_id"]
    require(sid in texts, "Unknown source: " + sid)
    lines = texts[sid].splitlines(keepends=True)
    start, end = ref["start_line"], ref["end_line"]
    require(type(start) is int and type(end) is int and 1 <= start <= end <= len(lines),
            "Invalid line range for " + sid)
    return "".join(lines[start - 1:end])


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        raise ValueError("Redirect refused for locked URL: " + request.full_url)


def fetch(row):
    request = urllib.request.Request(row["url"], headers={"Accept-Encoding": "identity"})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
        require(response.geturl() == row["url"], "Unexpected response URL")
        raw = response.read(row["bytes"] + 1)
    check_bytes(row, raw, row["id"])
    return raw


def identity(path):
    check_path(path)
    info = path.stat()
    return info.st_dev, info.st_ino


@contextmanager
def fetch_lock():
    check_path(ROOT)
    path = ROOT / ".fetch.lock"
    token = (str(os.getpid()) + " " + uuid.uuid4().hex + "\n").encode("ascii")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    info = os.fstat(descriptor)
    owner = info.st_dev, info.st_ino
    written = False
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(token)
            stream.flush()
            os.fsync(stream.fileno())
        written = True
        yield
    finally:
        require(identity(path) == owner, "Lock ownership changed; preserving lock")
        if written:
            require(read(path) == token, "Lock content changed; preserving lock")
        path.unlink()


def publish(cache, row, raw):
    # --fetch is restricted to ROOT/cache, and every suffix was validated above.
    check_path(ROOT)
    for directory in (cache,) + tuple(cache.joinpath(row["id"]).parents)[::-1]:
        if directory == ROOT or ROOT in directory.parents:
            if not os.path.lexists(directory):
                directory.mkdir()
            check_path(directory)
    target = cache / row["id"]
    part = target.with_name(target.name + ".part")
    require(not os.path.lexists(target), "Destination appeared; refusing overwrite")
    descriptor = os.open(part, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    info = os.fstat(descriptor)
    owner = info.st_dev, info.st_ino
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        check_bytes(row, read(part), row["id"])
        require(identity(part) == owner, "Part ownership changed")
        check_path(target.parent)
        # Atomic publication without replacing an existing cache file.
        if os.name == "nt":
            os.rename(part, target)
        else:
            os.link(part, target, follow_symlinks=False)
    finally:
        if os.path.lexists(part):
            require(identity(part) == owner, "Part ownership changed; preserving file")
            part.unlink()


def verify(cache, allow_fetch=False):
    manifest, anchors = load(ROOT / "inputs.json"), load(ROOT / "anchors.json")
    require(manifest.get("schema") == 1 and anchors.get("schema") == 1, "Unsupported schema")
    for name, commit in COMMITS.items():
        require(manifest["projects"][name]["commit"] == commit, "Unexpected commit")
    inherited = manifest["inherited_lock"]
    raw = read(REPO / safe_relative(inherited["path"]))
    require(hashlib.sha256(raw).hexdigest() == inherited["sha256"], "Inherited lock changed")
    old = json.loads(raw.decode("utf-8"), object_pairs_hook=pairs)
    by_name = {row["name"]: row for row in old["inputs"]}
    for item in manifest["inherited_artifacts"]:
        check_bytes(item, read(REPO / safe_relative(item["path"])), item["path"])
    texts, missing, downloads = {}, [], {}
    seen = set()
    for row in manifest["sources"]:
        sid = safe_relative(row["id"])
        require(sid not in seen, "Duplicate source id")
        seen.add(sid)
        require(row["cache_path"] == "cache/" + sid, "Unexpected cache mapping")
        project = row["repo"]
        require(project in COMMITS and row["commit"] == COMMITS[project], "Unexpected source commit")
        path = safe_relative(row["path"])
        require(sid == project + "/" + path, "Unexpected source path")
        expected_url = "https://raw.githubusercontent.com/" + project + "/" + project + "/" + COMMITS[project] + "/" + path
        require(row["url"] == expected_url, "Unexpected official source URL")
        previous = by_name.get(row["inherited_input"])
        require(previous is not None, "Unknown inherited input")
        for field in ("url", "bytes", "sha256", "git_blob_sha1", "repo", "commit", "path"):
            require(row[field] == previous[field], "Inherited input mismatch: " + sid + ": " + field)
        if os.path.lexists(cache / sid):
            raw = read(cache / sid)
            check_bytes(row, raw, sid)
            texts[sid] = raw.decode("utf-8")
        else:
            missing.append(row)
    require(allow_fetch or not missing, "Missing cached text; use --fetch explicitly")
    for row in missing:
        target = cache / row["id"]
        require(not os.path.lexists(target.with_name(target.name + ".part")),
                "Existing temporary file refused")
    for row in missing:
        raw = fetch(row)
        texts[row["id"]] = raw.decode("utf-8")
        downloads[row["id"]] = raw
    for item in manifest["versioned_notices"]:
        raw = read(ROOT / safe_relative(item["path"]))
        check_bytes(item, raw, item["path"])
        require(raw == texts[item["source_id"]].encode("utf-8"), "Versioned notice differs from source")
    count = 0
    for claim in anchors["claims"]:
        for ref in claim["refs"]:
            excerpt = region(ref, texts)
            require(isinstance(ref["contains"], list) and ref["contains"], "Empty anchor")
            for needle in ref["contains"]:
                require(isinstance(needle, str) and needle and needle in excerpt,
                        "Missing anchor: " + claim["id"] + ": " + repr(needle))
            count += 1
    for item in anchors["limited_absence_checks"]:
        excerpt = region(item, texts)
        for needle in item["absent"]:
            require(needle not in excerpt, "Limited absence check failed: " + needle)
    for item in anchors["literal_metrics"]:
        require(item["flags"] == "DOTALL", "Unsupported metric flags")
        matches = re.findall(item["regex"], texts[item["source_id"]], re.DOTALL)
        require(len(matches) == item["expected_count"], "Literal call count changed")
        require(all(re.search(item["all_match_suffix_regex"], match) for match in matches),
                "Literal timeout argument changed")
    for row in missing:
        publish(cache, row, downloads[row["id"]])
    print("Source evidence verified: " + str(len(texts)) + " inputs, " + str(count) + " anchors.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-root", type=Path, default=ROOT / "cache",
                        help="Read-only cache with libusb/ and libuvc/ subdirectories")
    parser.add_argument("--fetch", action="store_true", help="Restore missing fixed official text to local cache")
    args = parser.parse_args()
    try:
        if args.fetch:
            require(args.cache_root.absolute() == ROOT / "cache",
                    "--fetch only writes the local source-evidence/cache directory")
            with fetch_lock():
                verify(args.cache_root, True)
        else:
            verify(args.cache_root)
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

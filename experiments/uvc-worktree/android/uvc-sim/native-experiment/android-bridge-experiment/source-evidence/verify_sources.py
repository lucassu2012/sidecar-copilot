#!/usr/bin/env python3
"""Verify locked AOSP text and anchors offline; --fetch restores missing text only."""

import argparse
import base64
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import urllib.error
import urllib.request
import uuid


COMMIT = "4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3"
BASE = "https://android.googlesource.com/platform/frameworks/base/+/" + COMMIT + "/"
ROOT = Path(__file__).absolute().parent
NOFOLLOW = getattr(os, "O_NOFOLLOW", 0)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def safe_stat(path, directory=False):
    info = path.lstat()
    require(not stat.S_ISLNK(info.st_mode)
            and not getattr(info, "st_file_attributes", 0) & 0x400,
            "Symlink/reparse point refused: " + str(path))
    require(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode),
            "Unexpected file type: " + str(path))
    return info


def check_root():
    for directory in reversed((ROOT,) + tuple(ROOT.parents)):
        safe_stat(directory, directory=True)


def identity(info):
    return info.st_dev, info.st_ino


def exists(path):
    return os.path.lexists(path)


def read_regular(path, limit):
    check_root()
    before = safe_stat(path)
    with os.fdopen(os.open(path, os.O_RDONLY | NOFOLLOW), "rb") as stream:
        require(identity(before) == identity(os.fstat(stream.fileno()))
                == identity(safe_stat(path)), "File changed while opening: " + str(path))
        data = stream.read(limit + 1)
    require(len(data) <= limit, "File exceeds locked size: " + str(path))
    return data


def unique_keys(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key: " + key)
        result[key] = value
    return result


def load_json(name):
    return json.loads(read_regular(ROOT / name, 4 * 1024 * 1024).decode("utf-8"),
                      object_pairs_hook=unique_keys)


def load_locks():
    inputs, anchors = load_json("inputs.json"), load_json("anchors.json")
    for name, manifest in (("inputs", inputs), ("anchors", anchors)):
        require(isinstance(manifest, dict) and type(manifest.get("schema")) is int
                and manifest["schema"] == 1, name + ": expected schema 1")
    require(inputs.get("commit") == COMMIT, "Unexpected AOSP commit")
    require("commit" not in anchors or anchors["commit"] == COMMIT,
            "Unexpected anchors commit")
    sources = inputs.get("sources")
    require(isinstance(sources, list) and sources, "sources must be a nonempty array")
    by_id, filenames = {}, set()
    reserved = {"CON", "PRN", "AUX", "NUL"} | {
        prefix + str(i) for prefix in ("COM", "LPT") for i in range(1, 10)}
    for source in sources:
        require(isinstance(source, dict), "Each source must be an object")
        sid, path, filename = (source.get(key) for key in ("id", "path", "file"))
        require(isinstance(sid, str) and sid and sid not in by_id, "Invalid/duplicate source id")
        require(isinstance(path, str)
                and re.fullmatch(r"[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*", path)
                and all(part not in (".", "..") for part in path.split("/")),
                sid + ": unsafe source path")
        require(source.get("url") == BASE + path + "?format=TEXT", sid + ": URL not locked")
        for key, expected in (("commit", COMMIT), ("repo", "platform/frameworks/base"),
                              ("encoding", "gitiles-base64")):
            require(key not in source or source[key] == expected, sid + ": invalid " + key)
        require(isinstance(filename, str)
                and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*\.txt", filename)
                and filename.split(".")[0].upper() not in reserved
                and filename.casefold() not in filenames, sid + ": unsafe/duplicate txt basename")
        for key in ("wire_bytes", "source_bytes"):
            require(type(source.get(key)) is int and source[key] > 0, sid + ": invalid " + key)
        for key, length in (("wire_sha256", 64), ("source_sha256", 64), ("git_blob_sha1", 40)):
            require(isinstance(source.get(key), str)
                    and re.fullmatch(r"[0-9a-f]{" + str(length) + "}", source[key]),
                    sid + ": invalid " + key)
        by_id[sid] = source
        filenames.add(filename.casefold())
    claims = anchors.get("claims")
    require(isinstance(claims, list) and claims, "claims must be a nonempty array")
    refs = []
    for claim in claims:
        require(isinstance(claim, dict) and isinstance(claim.get("refs"), list)
                and claim["refs"], "Each claim needs a nonempty refs array")
        for ref in claim["refs"]:
            require(isinstance(ref, dict) and isinstance(ref.get("source_id"), str)
                    and ref["source_id"] in by_id, "Unknown anchor source_id")
            start, end = ref.get("start_line"), ref.get("end_line")
            require(type(start) is int and type(end) is int and 1 <= start <= end,
                    "Invalid inclusive anchor line range")
            contains = ref.get("contains")
            require(isinstance(contains, list) and contains
                    and all(isinstance(item, str) and item for item in contains),
                    "contains must be a nonempty array of nonempty strings")
            refs.append(ref)
    return sources, refs


def verify_source(source, data):
    sid = source["id"]
    require(len(data) == source["source_bytes"], sid + ": source size mismatch")
    require(hashlib.sha256(data).hexdigest() == source["source_sha256"],
            sid + ": source SHA-256 mismatch")
    blob = b"blob " + str(len(data)).encode("ascii") + b"\0" + data
    require(hashlib.sha1(blob).hexdigest() == source["git_blob_sha1"],
            sid + ": Git blob SHA-1 mismatch")
    return data.decode("utf-8").splitlines(keepends=True)


def verify_anchors(refs, lines_by_id):
    for ref in refs:
        sid, start, end = ref["source_id"], ref["start_line"], ref["end_line"]
        lines = lines_by_id[sid]
        require(end <= len(lines), sid + ": anchor range exceeds source")
        excerpt = "".join(lines[start - 1:end])
        for needle in ref["contains"]:
            require(needle in excerpt, sid + ": anchor missing at lines "
                    + str(start) + "-" + str(end) + ": " + repr(needle))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        raise ValueError("Redirect refused for locked URL: " + request.full_url)


def fetch_source(source):
    request = urllib.request.Request(source["url"], headers={"Accept-Encoding": "identity"})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=30) as response:
        require(response.geturl() == source["url"], "Unexpected response URL")
        wire = response.read(source["wire_bytes"] + 1)
    require(len(wire) == source["wire_bytes"], source["id"] + ": wire size mismatch")
    require(hashlib.sha256(wire).hexdigest() == source["wire_sha256"],
            source["id"] + ": wire SHA-256 mismatch")
    return base64.b64decode(wire, validate=True)


def remove_owned(path, owner, contents=None):
    if not exists(path):
        return
    check_root()
    require(identity(safe_stat(path)) == owner, "Ownership changed; preserving " + str(path))
    if contents is not None:
        require(read_regular(path, len(contents)) == contents,
                "Lock changed; preserving " + str(path))
    path.unlink()


@contextmanager
def fetch_lock():
    check_root()
    path = ROOT / ".fetch.lock"
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW, 0o600)
    except FileExistsError:
        raise ValueError("Fetch busy: .fetch.lock already exists; no files changed") from None
    owner = identity(os.fstat(descriptor))
    token = (str(os.getpid()) + " " + uuid.uuid4().hex + "\n").encode("ascii")
    written_token = None
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(token)
            stream.flush()
            os.fsync(stream.fileno())
        written_token = token
        yield
    finally:
        remove_owned(path, owner, written_token)


def publish(source, data):
    check_root()
    target = ROOT / source["file"]
    part = ROOT / (source["file"] + ".part")
    require(not exists(target), "Destination appeared; refusing overwrite: " + str(target))
    descriptor = os.open(part, os.O_WRONLY | os.O_CREAT | os.O_EXCL | NOFOLLOW, 0o600)
    owner = identity(os.fstat(descriptor))
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        verify_source(source, read_regular(part, source["source_bytes"]))
        require(identity(safe_stat(part)) == owner, "Part ownership changed")
        check_root()
        # Atomic publication without overwriting a target created after our check.
        # Windows rename refuses existing targets; POSIX link has the same property.
        if os.name == "nt":
            os.rename(part, target)
        else:
            os.link(part, target, follow_symlinks=False)
    finally:
        remove_owned(part, owner)


def verify(sources, refs, allow_fetch):
    lines_by_id, missing, downloaded = {}, [], {}
    for source in sources:
        path = ROOT / source["file"]
        if exists(path):
            lines_by_id[source["id"]] = verify_source(
                source, read_regular(path, source["source_bytes"]))
        else:
            missing.append(source)
    require(allow_fetch or not missing, "Missing source files: "
            + ", ".join(source["file"] for source in missing) + "; use --fetch explicitly")
    for source in missing:
        require(not exists(ROOT / (source["file"] + ".part")),
                "Existing .part file refused: " + source["file"] + ".part")
    for source in missing:
        data = fetch_source(source)
        lines_by_id[source["id"]] = verify_source(source, data)
        downloaded[source["id"]] = data
    verify_anchors(refs, lines_by_id)
    for source in missing:
        publish(source, downloaded[source["id"]])
    print("Verified " + str(len(sources)) + " sources and " + str(len(refs))
          + " anchors; restored " + str(len(missing)) + " missing files.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fetch", action="store_true", help="restore missing locked text files")
    args = parser.parse_args()
    try:
        check_root()
        sources, refs = load_locks()
        if args.fetch:
            with fetch_lock():
                verify(sources, refs, True)
        else:
            verify(sources, refs, False)
        return 0
    except (ValueError, OSError, urllib.error.URLError) as error:
        print("ERROR: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

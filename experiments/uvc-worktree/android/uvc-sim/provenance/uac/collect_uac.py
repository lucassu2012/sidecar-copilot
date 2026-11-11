"""Static, read-only upstream audit. Never imports or executes a candidate native library.

Uses the already-installed pyelftools 0.33; does not install dependencies. Network
responses/binaries go only to ignored build/provenance/uac. Reports go beside script.
Run from any directory: python path/to/collect_uac.py
"""
import argparse
import hashlib
import io
import json
import re
import struct
import sys
import urllib.parse
import urllib.request
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from importlib.metadata import version
from elftools.elf.elffile import ELFFile

COMMIT = "426ed0a5da2c826197761cc5a89ba3ae9c52bd7c"
REPO = "jiangdongguo/AndroidUSBCamera"
OUT = Path(__file__).resolve().parent
CACHE = OUT.parents[1] / "build" / "provenance" / "uac"
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
API = "https://api.github.com/repos/" + REPO
RAW = "https://raw.githubusercontent.com/" + REPO + "/" + COMMIT + "/"
retrieved = []
expected = {}
offline = False


def initialize_cache(args):
    global CACHE, expected, offline
    offline = args.offline
    if args.cache_dir:
        candidate = Path(args.cache_dir).resolve()
        # Test copies must remain under the designated ignored audit cache.
        if not candidate.is_relative_to(CACHE.resolve()):
            raise RuntimeError("--cache-dir must stay under " + str(CACHE))
        CACHE = candidate
    index = OUT / "retrieval-index.json"
    if not index.exists():
        raise RuntimeError("trusted retrieval-index.json is required")
    for item in json.loads(index.read_text(encoding="utf-8")):
        filename = hashlib.sha256(item["url"].encode()).hexdigest() + ".response"
        if item["cache_file"] != filename or item["url"] in expected:
            raise RuntimeError("invalid or duplicate manifest entry")
        expected[item["url"]] = item
        path = CACHE / filename
        if path.exists():
            verify(item, path.read_bytes())
        elif offline:
            raise RuntimeError("offline cache missing: " + filename)


def verify(item, data):
    if len(data) != item["bytes"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
        raise RuntimeError("cache integrity mismatch: " + item["cache_file"])

def fetch(url):
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / (hashlib.sha256(url.encode()).hexdigest() + ".response")
    pinned = expected.get(url)
    if offline and (not path.exists() or pinned is None):
        raise RuntimeError("offline cache or manifest entry missing: " + url)
    # Unpinned existing files are never silently accepted. Online mode retrieves
    # them again; pinned cached bytes must match the trusted tracked manifest.
    if not path.exists() or pinned is None:
        req = urllib.request.Request(url, headers={"User-Agent": "Sidecar-UVC-static-provenance", "Accept": "application/vnd.github+json"})
        with urllib.request.urlopen(req, timeout=60) as response:
            data = response.read()
        if pinned:
            verify(pinned, data)
        path.write_bytes(data)
    data = path.read_bytes()
    if pinned:
        verify(pinned, data)
    retrieved.append({"url": url, "cache_file": path.name, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)})
    return data

def api(path):
    return json.loads(fetch(API + path))

def elf_details(data):
    elf = ELFFile(io.BytesIO(data))
    dynamic = elf.get_section_by_name(".dynamic")
    tags = list(dynamic.iter_tags()) if dynamic is not None else []
    symbols = elf.get_section_by_name(".dynsym")
    exported, undefined = [], []
    if symbols is not None:
        for sym in symbols.iter_symbols():
            if sym.name:
                (undefined if sym["st_shndx"] == "SHN_UNDEF" else exported).append(sym.name)
    notes = []
    for section in elf.iter_sections():
        if section["sh_type"] == "SHT_NOTE":
            for note in section.iter_notes():
                desc = note["n_desc"]
                item = {"section": section.name, "name": note["n_name"], "type": note["n_type"],
                        "description": desc.hex() if isinstance(desc, bytes) else str(desc)}
                if note["n_name"] == "Android" and isinstance(desc, bytes) and len(desc) >= 132:
                    item["android_ident_decoded"] = {"api_level": struct.unpack("<I", desc[:4])[0],
                        "ndk_version": desc[4:68].split(b"\0")[0].decode("ascii", errors="replace"),
                        "ndk_build": desc[68:132].split(b"\0")[0].decode("ascii", errors="replace")}
                notes.append(item)
    strings = [s.decode("ascii") for s in re.findall(rb"[\x20-\x7e]{5,}", data)]
    relevant = sorted(set(s for s in strings if re.search(r"uac|audio|usb|uvc|portaudio|version|copyright|license|\.cpp|clang|ndk|android|libc\+\+", s, re.I)))
    return {"class": elf.elfclass, "machine": elf["e_machine"], "little_endian": elf.little_endian,
            "needed": [t.needed for t in tags if t.entry.d_tag == "DT_NEEDED"],
            "soname": [t.soname for t in tags if t.entry.d_tag == "DT_SONAME"],
            "notes": notes, "exported_dynamic_symbols": sorted(set(exported)),
            "undefined_dynamic_symbols": sorted(set(undefined)), "relevant_ascii_strings": relevant}

def history(path):
    items = []
    page = 1
    while True:
        part = api("/commits?" + urllib.parse.urlencode({"sha": COMMIT, "path": path, "per_page": 100, "page": page}))
        items.extend({"sha": c["sha"], "date": c["commit"]["committer"]["date"], "message": c["commit"]["message"]} for c in part)
        if len(part) < 100:
            break
        page += 1
    return items

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="forbid all network access; require every pinned input")
    parser.add_argument("--verify-only", action="store_true", help="compute all evidence without overwriting reports")
    parser.add_argument("--cache-dir", help="alternate cache under ignored build/provenance/uac (integrity tests)")
    args = parser.parse_args()
    if version("pyelftools") != "0.33":
        raise RuntimeError("audit requires existing pyelftools 0.33; no installation is performed")
    initialize_cache(args)
    tree = api("/git/trees/" + COMMIT + "?recursive=1")
    if tree.get("truncated"):
        raise RuntimeError("tree is truncated; cannot claim exhaustive path inventory")
    paths = {item["path"]: item for item in tree["tree"]}
    result = {"observed_at_utc": datetime.now(timezone.utc).isoformat(), "repository": REPO,
              "commit": COMMIT, "python": sys.version, "pyelftools": version("pyelftools"),
              "audit_tools": {"pyelftools_source": "https://pypi.org/project/pyelftools/0.33/", "preexisting": True,
                              "installed_during_audit": False, "candidate_binaries_executed": False},
              "binaries": [], "path_history": {}, "upstream_license_paths": [], "source_scan": {},
              "historical_commits": [], "native_packaging_graph": [], "source_excerpts": []}
    for abi in ABIS:
        path = "libuvc/src/main/jniLibs/" + abi + "/libUACAudio.so"
        data = fetch(RAW + path)
        git_hash = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        if git_hash != paths[path]["sha"]:
            raise RuntimeError("fixed raw bytes disagree with Git blob " + path)
        result["binaries"].append({"abi": abi, "path": path, "git_blob_sha1": git_hash,
            "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data), "elf": elf_details(data)})
        for prefix in ("libuvc/src/main/jniLibs/", "libuvc/libs/"):
            oldpath = prefix + abi + "/libUACAudio.so"
            result["path_history"][oldpath] = history(oldpath)
    for sha in ("0781eb5c6fea87c69a81077c8bbc8564c39a15a3", "bd1d02fbfc9e14657194a93264577eeafb3c3b9f",
                "ccdb068d3aa0e89ce0147ba82228f4c48752c2a4", "829d32edf581bd9edf7766adfb1d30985f892db3"):
        detail = api("/commits/" + sha + "?per_page=100")
        files = list(detail.get("files", []))
        page = 2
        while len(files) == (page - 1) * 100:
            more = api("/commits/" + sha + "?per_page=100&page=" + str(page)).get("files", [])
            files.extend(more)
            page += 1
            if len(more) < 100:
                break
        result["historical_commits"].append({"sha": sha, "date": detail["commit"]["committer"]["date"],
            "message": detail["commit"]["message"], "parents": [p["sha"] for p in detail["parents"]],
            "uac_changes": [{k: f.get(k) for k in ("filename", "status", "previous_filename", "sha")}
                            for f in files if re.search("uac", f["filename"], re.I)],
            "changed_files_examined": len(files), "file_pages_examined": page - 1})
    introduced_tree = api("/git/trees/0781eb5c6fea87c69a81077c8bbc8564c39a15a3?recursive=1")
    result["introduction_tree_uac_paths"] = [x["path"] for x in introduced_tree["tree"] if "uac" in x["path"].lower() or "usbaudio" in x["path"].lower()]
    result["introduction_tree_truncated"] = introduced_tree.get("truncated", False)
    result["upstream_license_paths"] = sorted(p for p in paths if re.search(r"(^|/)(LICENSE[^/]*|COPYING[^/]*|NOTICE[^/]*)$", p, re.I))
    result["uac_paths"] = sorted(p for p in paths if "uac" in p.lower())
    # Inspect source bytes without extraction or executing build recipes.
    archive_url = "https://codeload.github.com/" + REPO + "/zip/" + COMMIT
    archive = fetch(archive_url)
    native_matches, wiring_matches = [], []
    native_count = 0
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        for entry in z.infolist():
            path = entry.filename.split("/", 1)[-1]
            if path.startswith("libuvc/src/main/jniLibs/") and path.endswith(".so"):
                payload = z.read(entry)
                details = elf_details(payload)
                result["native_packaging_graph"].append({"path": path, "sha256": hashlib.sha256(payload).hexdigest(),
                    "needed": details["needed"], "soname": details["soname"]})
            if entry.is_dir() or entry.file_size > 4_000_000:
                continue
            ext = Path(path).suffix.lower()
            if ext not in (".c", ".cc", ".cpp", ".h", ".hpp", ".java", ".kt", ".gradle", ".mk", ".txt", ".md"):
                continue
            text = z.read(entry).decode("utf-8", errors="replace")
            if path in ("libuvc/build.gradle", "libausbc/build.gradle", "libuvc/src/main/jni/Android.mk",
                        "libuvc/src/main/jni/UVCCamera/Android.mk", "libausbc/src/main/java/com/jiangdg/ausbc/MultiCameraClient.kt",
                        "libausbc/src/main/java/com/jiangdg/ausbc/camera/bean/CameraRequest.kt",
                        "libuvc/src/main/java/com/jiangdg/uvc/UVCCamera.java",
                        "libuvc/src/main/java/com/jiangdg/uac/UACAudio.java",
                        "libuvc/src/main/jni/libusb/android/jni/Android.mk",
                        "libuvc/src/main/jni/libusb/android/jni/libusb.mk",
                        "libuvc/src/main/jni/libusb/libusb/core.c",
                        "libuvc/src/main/jni/libusb/libusb/libusb.h",
                        "libuvc/src/main/jni/libusb/libusb/version.h",
                        "libuvc/src/main/jni/libusb/libusb/version_nano.h"):
                lines = text.splitlines()
                markers = [i for i,l in enumerate(lines) if re.search(r"AudioStrategy|AudioSource|NONE|jniLibs|sourceSets|packagingOptions|minifyEnabled|libuvc|loadLibrary|LOCAL_|include \$|ndkBuild|Copyright|Lesser General|2\.1|any later|LIBUSB_.*VERSION|libusb_get_device_with_fd|libusb_init2|libusb_set_device_fd", l)]
                selected = set(j for i in markers for j in range(max(0,i-3), min(len(lines),i+5)))
                result["source_excerpts"].append({"path": path, "lines": [{"line": i+1, "text": lines[i]} for i in sorted(selected)]})
            native = ext in (".c", ".cc", ".cpp", ".h", ".hpp")
            if native:
                native_count += 1
            for number, line in enumerate(text.splitlines(), 1):
                if native and re.search(r"UACAudio|com_jiangdg_uac|libuac|PortAudio|portaudio|nativeSetAudioCallback|native_usb_audio|USBAudio|_uac_process_payload_iso", line):
                    native_matches.append({"path": path, "line": number, "text": line[:500]})
                if not native and (re.search(r"UACAudio|AudioStrategyUAC|AudioUac|AudioSource\.SOURCE_UAC|SOURCE_UAC|isAudioCaptureEnable|isCaptureRawImage|jniLibs|packagingOptions", line)):
                    wiring_matches.append({"path": path, "line": number, "text": line[:500]})
    result["source_scan"] = {"archive_url": archive_url, "native_source_files_scanned": native_count,
        "native_patterns": ["UACAudio", "com_jiangdg_uac", "libuac", "PortAudio", "portaudio", "nativeSetAudioCallback", "native_usb_audio", "USBAudio", "_uac_process_payload_iso"],
        "native_matches": native_matches, "wiring_matches": wiring_matches,
        "limits": "Static textual scan, case-sensitive explicit patterns, files <=4 MB; absent names do not prove absent functionality or rights."}
    comment = api("/issues/comments/1321350701")
    pull = api("/pulls/753")
    pull_files = api("/pulls/753/files?per_page=100")
    result["maintainer_comment"] = {k: comment[k] for k in ("html_url", "created_at", "updated_at", "body", "author_association")}
    result["maintainer_comment"]["user"] = comment["user"]["login"]
    result["pull_753_snapshot"] = {"html_url": pull["html_url"], "state": pull["state"], "merged": pull["merged"],
        "base_sha": pull["base"]["sha"], "head_sha": pull["head"]["sha"], "updated_at": pull["updated_at"],
        "files_page_limit": 100, "uac_files": [{k: f.get(k) for k in ("filename", "status", "sha")}
                                                   for f in pull_files if "uacaudio.so" in f["filename"].lower()]}
    if not args.verify_only:
        # All downloads, integrity checks and parsing finish before either output
        # is touched. Missing/tampered input therefore preserves prior evidence.
        (OUT / "static-evidence.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        unique = {x["url"]: x for x in retrieved}
        (OUT / "retrieval-index.json").write_text(json.dumps(list(unique.values()), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"binaries": [{"abi": b["abi"], "bytes": b["bytes"], "sha256": b["sha256"], "needed": b["elf"]["needed"]} for b in result["binaries"]],
                      "native_source_files_scanned": native_count, "native_matches": len(native_matches),
                      "output": str(OUT / "static-evidence.json")}, indent=2))

if __name__ == "__main__":
    main()

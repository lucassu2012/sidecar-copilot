"""Static UAC follow-up. Python stdlib only; never runs native code or builds it.

Default is offline replay. --online allows missing pinned inputs to be fetched;
--record additionally allows explicit first-use snapshots of new URLs.
"""
import argparse
import base64
import hashlib
import json
import os
import re
import sys
import tempfile
import urllib.parse
import urllib.request
from pathlib import Path

OUT = Path(__file__).resolve().parent
SIM = OUT.parents[2]
CACHE_ROOT = SIM / "build" / "native-research" / "uac"
BASELINE = SIM / "provenance" / "uac" / "static-evidence.json"
JIM = "35ba8eee66a5c212a6f7f90caab8814c392b137e"
CAM = "5ff668d0cd60a506c1db7fc8bbe0b4001c557d7b"
FORK = "ffbf0fb17ad9baefd229e87ea990d11e56f421fe"
UVC_LIB = "3d7186e73efe4d3c317b61992e5d200e8b21949d"
ANDROID_CAMERA = "ab1a097c8051fa955d0e65d32080c16e6e1bcf9a"
NEXT = "17a6ba0a09309a6890ec338826de43a256f89813"
TAG = "android-15.0.0_r1"
API = "https://api.github.com/repos/"


class Inputs:
    def __init__(self, args):
        self.args = args
        self.cache = Path(args.cache_dir).resolve() if args.cache_dir else CACHE_ROOT
        if not self.cache.resolve().is_relative_to(CACHE_ROOT.resolve()):
            raise RuntimeError("cache override must stay under ignored UAC cache")
        self.cache.mkdir(parents=True, exist_ok=True)
        self.lock = self.cache / "collection.lock"
        self.lock_fd = None
        self.index = OUT / "retrieval-index.json"
        if not self.index.exists() and not args.record:
            raise RuntimeError("trusted manifest missing; first collection needs --online --record")
        records = json.loads(self.index.read_text(encoding="utf-8")) if self.index.exists() else []
        self.expected = {}
        self.used = {}
        for entry in records:
            url = entry["url"]
            if entry["cache_file"] != self.name(url) or url in self.expected:
                raise RuntimeError("invalid manifest entry")
            self.expected[url] = entry

    @staticmethod
    def name(url):
        return hashlib.sha256(url.encode()).hexdigest() + ".response"

    @staticmethod
    def check(entry, data):
        if len(data) != entry["bytes"] or hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise RuntimeError("cache integrity mismatch: " + entry["cache_file"])

    def __enter__(self):
        try:
            self.lock_fd = os.open(self.lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            raise RuntimeError("collection lock busy; stale lock requires explicit review")
        try:
            os.write(self.lock_fd, str(os.getpid()).encode())
            # Verify all pinned inputs before work; online never silently trusts
            # tampered cache and offline never attempts a network fallback.
            for entry in self.expected.values():
                path = self.cache / entry["cache_file"]
                if path.exists():
                    self.check(entry, path.read_bytes())
                elif not self.args.online:
                    raise RuntimeError("offline cache missing: " + path.name)
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, *unused):
        if self.lock_fd is not None:
            os.close(self.lock_fd)
            self.lock_fd = None
            self.lock.unlink()

    def fetch(self, url):
        if urllib.parse.urlsplit(url).hostname not in (
            "api.github.com", "raw.githubusercontent.com", "android.googlesource.com",
            "source.android.com", "developer.android.com"):
            raise RuntimeError("source outside pinned official-host allowlist: " + url)
        entry = self.expected.get(url)
        path = self.cache / self.name(url)
        if entry is None and not (self.args.online and self.args.record):
            raise RuntimeError("unrecorded URL: " + url)
        if path.exists() and entry is not None:
            data = path.read_bytes()
            self.check(entry, data)
        else:
            if not self.args.online:
                raise RuntimeError("offline cache missing: " + url)
            request = urllib.request.Request(url, headers={
                "User-Agent": "Sidecar-UAC-static-followup", "Accept": "application/vnd.github+json"})
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read()
            if entry is not None:
                self.check(entry, data)
            # Unpinned existing bytes are not trusted: explicit record mode has
            # just retrieved them from their declared source.
            with tempfile.NamedTemporaryFile(dir=self.cache, delete=False) as temporary:
                temporary.write(data)
                stage = Path(temporary.name)
            stage.replace(path)
        self.used[url] = {"url": url, "cache_file": path.name, "bytes": len(data),
                          "sha256": hashlib.sha256(data).hexdigest()}
        return data

    def json(self, url):
        return json.loads(self.fetch(url))


def tree(inputs, repo, sha):
    value = inputs.json(API + repo + "/git/trees/" + sha + "?recursive=1")
    if value.get("truncated"):
        raise RuntimeError("truncated fixed source tree: " + repo)
    return {entry["path"]: entry for entry in value["tree"]}


PATTERNS = {
    "rights": r"Copyright|copyright|Licensed under|SPDX|GNU (?:GENERAL|LESSER)|Apache License|GPL|MIT License|Permission is hereby",
    "uac_identity": r"Java_.*(?:UACAudio|USBAudio)|USBAudio::|_uac_process_payload_iso|native_usb_audio|LOCAL_MODULE|add_library|IMPORTED_LOCATION",
    "routing": r"setPreferredDevice|getPreferredDevice|getRoutedDevice|onRoutingChanged|registerAudioDeviceCallback|getDevices\(|isSource\(|TYPE_USB_|isClientSilenced|USB_AUDIO_AUTOMATIC_ROUTING_DISABLED|selectDefaultDevice|hasInput|RECORD_AUDIO|foreground|silenced"
}


def describe_source(path, data, url):
    content = data.decode("utf-8", errors="strict")
    lines = content.splitlines()
    return {"path": path, "url": url, "sha256": hashlib.sha256(data).hexdigest(), "lines": len(lines),
            "markers": {name: [i for i, line in enumerate(lines, 1) if re.search(pattern, line)]
                        for name, pattern in PATTERNS.items()},
            "jni_exports": sorted(set(re.findall(r"Java_[A-Za-z0-9_]+", content))),
            "usbaudio_methods": sorted(set(re.findall(r"USBAudio::([A-Za-z0-9_]+)", content)))}


def source(inputs, repo, sha, path, entries):
    url = "https://raw.githubusercontent.com/" + repo + "/" + sha + "/" + path
    data = inputs.fetch(url)
    blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if blob != entries[path]["sha"]:
        raise RuntimeError("source Git blob mismatch: " + path)
    result = describe_source(path, data, url)
    result["git_blob_sha1"] = blob
    return result


def history(inputs, repo, sha, path):
    values, page = [], 1
    while True:
        url = API + repo + "/commits?" + urllib.parse.urlencode({"sha": sha, "path": path, "per_page": 100, "page": page})
        items = inputs.json(url)
        values.extend({"sha": x["sha"], "date": x["commit"]["committer"]["date"],
                       "message": x["commit"]["message"]} for x in items)
        if len(items) < 100:
            return {"path": path, "commits": values, "pages": page}
        page += 1


def collect(inputs):
    baseline_bytes = BASELINE.read_bytes()
    baseline = json.loads(baseline_bytes)
    result = {"scope": "static research; no native execution/device/build/integration", "python": sys.version,
              "dependencies": "Python standard library only for this follow-up script; prior ELF audit used preinstalled pyelftools 0.33",
              "baseline_evidence_sha256": hashlib.sha256(baseline_bytes).hexdigest(),
              "baseline_binaries": [{k: b[k] for k in ("abi", "sha256", "git_blob_sha1")} for b in baseline["binaries"]],
              "repositories": [], "aosp": [], "official_document_snapshots": []}
    specifications = [
        ("jim0608/android_usbaudio", JIM, ["README.md", "app/build.gradle", "app/src/main/jni/Application.mk", "app/src/main/jni/Android.mk", "app/src/main/jni/usbAudio/Android.mk",
            "app/src/main/jni/usbAudio/USBAudio.cpp", "app/src/main/jni/usbAudio/USBAudio.h",
            "app/src/main/jni/usbAudio/native_usb_audio.cpp", "app/src/main/jni/usbAudio/uac_stream.c",
            "app/src/main/jni/usbAudio/uac_stream.h", "app/src/main/jni/libusb/COPYING"],
            ["app/src/main/jni/usbAudio/native_usb_audio.cpp", "app/src/main/jni/usbAudio/uac_stream.c"]),
        ("sky22333/camplayer", CAM, ["LICENSE", "README.md", "app/build.gradle.kts", "app/src/main/cpp/uac_jni.cpp",
            "app/src/main/cpp/CMakeLists.txt", "app/src/main/cpp/third_party/libuac/LICENSE",
            "app/src/main/cpp/third_party/libuac/src/uac_context.cpp"], ["app/src/main/cpp/uac_jni.cpp"]),
        ("jiangdongguo/UVCCameraLib", UVC_LIB, ["README.md"], []),
        ("jiangdongguo/AndroidCamera", ANDROID_CAMERA, ["README.md"], []),
        ("nExtCamera/libuac", NEXT, ["README.md", "LICENSE", "CMakeLists.txt", "android/libuac.mk",
            "src/uac_context.cpp", "src/uac_streaming.cpp", "include/libuac.h"], ["src/uac_context.cpp"]),
        ("siddhesh-bhadale/AndroidUSBCamera-fork", FORK, ["LICENSE"], [])
    ]
    for repo, sha, selected, history_paths in specifications:
        entries = tree(inputs, repo, sha)
        metadata = inputs.json(API + repo)
        item = {"repository": repo, "commit": sha, "fork_snapshot": metadata.get("fork"),
                "parent_snapshot": (metadata.get("parent") or {}).get("full_name"),
                "license_api_snapshot": metadata.get("license"),
                "license_paths": sorted(p for p in entries if re.search(r"(^|/)(LICENSE[^/]*|COPYING[^/]*|NOTICE[^/]*)$", p, re.I)),
                "uac_related_paths": sorted(p for p in entries if re.search(r"uac|usbaudio", p, re.I)),
                "sources": [source(inputs, repo, sha, p, entries) for p in selected if p in entries],
                "history": [history(inputs, repo, sha, p) for p in history_paths]}
        result["repositories"].append(item)
    jim_parent = "7b0d728d0e5768c345e1fe797ddcc071092a04ca"
    jim_import = "79491886c7584a9a99b9dadab552ceac5a0004c8"
    import_trees = [tree(inputs, "jim0608/android_usbaudio", sha) for sha in (jim_parent, jim_import)]
    result["jim_import"] = {"parent": jim_parent, "commit": jim_import,
        "parent_uac_paths": sorted(p for p in import_trees[0] if "/usbAudio/" in p),
        "introduced_blobs": [{"path": p, "sha": entry["sha"]} for p, entry in import_trees[1].items()
                             if "/usbAudio/" in p and entry["type"] == "blob"]}
    baseline_methods = set()
    baseline_jni = set()
    for binary in baseline["binaries"]:
        for symbol in binary["elf"]["exported_dynamic_symbols"]:
            if symbol.startswith("Java_"):
                baseline_jni.add(symbol)
            match = re.match(r"_ZNK?8USBAudio(\d+)(.*)", symbol)
            if match:
                baseline_methods.add(match[2][:int(match[1])])
    jim_sources = result["repositories"][0]["sources"]
    jim_methods = {m for s in jim_sources for m in s["usbaudio_methods"]}
    jim_jni = {m for s in jim_sources for m in s["jni_exports"]}
    result["identity_comparison"] = {"baseline_usbaudio_methods": sorted(baseline_methods),
        "jim_method_name_overlap": sorted(baseline_methods & jim_methods),
        "baseline_jni": sorted(baseline_jni), "jim_jni": sorted(jim_jni),
        "exact_jni_overlap": sorted(baseline_jni & jim_jni),
        "limit": "Names are provenance clues only; no disassembly, compilation, ABI equivalence, copyright or binary-source identity established."}
    # Pin the release-tag target as well as each base64 source snapshot.
    aosp_base = "https://android.googlesource.com/platform/frameworks/base/+/refs/tags/" + TAG + "/"
    aosp_commit = json.loads(inputs.fetch(aosp_base.rstrip("/") + "?format=JSON").decode().removeprefix(")]}'\n"))
    result["aosp_release"] = {"tag": TAG, "commit": aosp_commit["commit"], "tree": aosp_commit["tree"]}
    for path in ["media/java/android/media/AudioRecord.java", "media/java/android/media/AudioDeviceInfo.java",
                 "media/java/android/media/AudioManager.java", "media/java/android/media/AudioRouting.java",
                 "media/java/android/media/AudioRecordingConfiguration.java",
                 "services/usb/java/com/android/server/usb/UsbAlsaManager.java"]:
        url = aosp_base + path + "?format=TEXT"
        data = base64.b64decode(inputs.fetch(url), validate=True)
        result["aosp"].append(describe_source(path, data, url))
    for url in ["https://source.android.com/docs/core/audio/usb",
                "https://developer.android.com/media/platform/sharing-audio-input",
                "https://developer.android.com/about/versions/11/privacy/foreground-services",
                "https://developer.android.com/about/versions/14/changes/fgs-types-required"]:
        data = inputs.fetch(url)
        result["official_document_snapshots"].append({"url": url, "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data), "limit": "Mutable documentation snapshot; fixed AOSP tag separately recorded."})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--online", action="store_true")
    parser.add_argument("--record", action="store_true", help="explicitly accept new URLs; does not repin existing SHA")
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--cache-dir")
    args = parser.parse_args()
    if args.record and not args.online:
        parser.error("--record requires --online")
    with Inputs(args) as inputs:
        result = collect(inputs)
        if args.verify_only:
            recorded = json.loads((OUT / "evidence.json").read_text(encoding="utf-8"))
            if result != recorded:
                raise RuntimeError("recomputed evidence differs from tracked evidence.json")
            if list(inputs.used.values()) != json.loads(inputs.index.read_text(encoding="utf-8")):
                raise RuntimeError("recomputed input set differs from tracked retrieval-index.json")
        else:
            # Collection and every integrity check complete before prior evidence
            # can change. Atomic replace avoids partially-written JSON files.
            for name, value in (("evidence.json", result), ("retrieval-index.json", list(inputs.used.values()))):
                with tempfile.NamedTemporaryFile(dir=OUT, mode="w", encoding="utf-8", delete=False) as temporary:
                    temporary.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
                    staged = Path(temporary.name)
                staged.replace(OUT / name)
        print("PASS: %d repositories, %d AOSP sources, %d verified remote inputs" %
              (len(result["repositories"]), len(result["aosp"]), len(inputs.used)))


if __name__ == "__main__":
    main()

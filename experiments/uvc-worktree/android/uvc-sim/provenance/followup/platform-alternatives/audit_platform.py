"""Static Camera2 alternative evidence; never builds, loads or runs downloaded code."""
import argparse
import base64
import hashlib
import json
import pathlib
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
DEFAULT_CACHE = HERE.parents[2] / "build/native-research/platform-alternatives"
BASE = "https://android.googlesource.com/"
FRAMEWORK = "4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3"
HAL = "488942f82bd1bc9ad1cb65a02c71421dc3a6a3d6"
SOURCES = [
    ("framework-commit", "platform/frameworks/base", FRAMEWORK, "", []),
    ("hal-commit", "platform/hardware/interfaces", HAL, "", []),
    ("camera-manager", "platform/frameworks/base", FRAMEWORK,
     "core/java/android/hardware/camera2/CameraManager.java",
     ["public String[] getCameraIdList()", "registerAvailabilityCallback(",
      "getCameraCharacteristics("]),
    ("camera-device", "platform/frameworks/base", FRAMEWORK,
     "core/java/android/hardware/camera2/CameraDevice.java",
     ["public abstract void onDisconnected(", "public void onClosed(",
      "public abstract void close()"]),
    ("camera-metadata", "platform/frameworks/base", FRAMEWORK,
     "core/java/android/hardware/camera2/CameraMetadata.java",
     ["LENS_FACING_EXTERNAL = 2", "INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL = 4"]),
    ("image-reader", "platform/frameworks/base", FRAMEWORK,
     "media/java/android/media/ImageReader.java",
     ["public Image acquireLatestImage()", "public void close()", "getMaxImages()"]),
    ("external-provider", "platform/hardware/interfaces", HAL,
     "camera/provider/default/ExternalCameraProvider.cpp",
     ["VIDIOC_QUERYCAP", "V4L2_CAP_VIDEO_CAPTURE", "CameraDeviceStatus::NOT_PRESENT",
      "deviceAdded(", "deviceRemoved("]),
    ("external-device", "platform/hardware/interfaces", HAL,
     "camera/device/default/ExternalCameraDevice.cpp",
     ["ANDROID_LENS_FACING_EXTERNAL", "V4L2_PIX_FMT_MJPEG", "initSupportedFormats"]),
    ("external-build", "platform/hardware/interfaces", HAL,
     "camera/device/default/Android.bp", ["libyuv", "libjpeg", "Apache-2.0"]),
]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def write_json(path, value):
    path.write_bytes((json.dumps(value, ensure_ascii=False, sort_keys=True,
                                indent=2) + "\n").encode())


def duplicate_keys(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise ValueError("duplicate JSON key: " + key)
        out[key] = value
    return out


def read_json(data):
    return json.loads(data, object_pairs_hook=duplicate_keys)


def verify(data, pin):
    if len(data) != pin["size"] or sha(data) != pin["sha256"]:
        raise ValueError("input integrity mismatch: " + pin["id"])


def collect(cache, offline=False, record=False):
    lock_path = HERE / "INPUT_LOCK.json"
    if record and lock_path.exists():
        raise ValueError("bootstrap refuses to overwrite existing input lock")
    if record and offline:
        raise ValueError("bootstrap requires explicit online capture")
    old = None if record else read_json(lock_path.read_bytes())
    if old is not None and old["schema"] != 1:
        raise ValueError("unknown lock schema")
    pins = []
    observations = []
    cache.mkdir(parents=True, exist_ok=True)
    for index, (name, repo, commit, path, anchors) in enumerate(SOURCES):
        url = BASE + repo + "/+/" + commit
        if path:
            url += "/" + path + "?format=TEXT"
        else:
            url += "?format=JSON"
        pin = {"id": name, "url": url}
        if not record:
            expected = old["inputs"][index]
            if any(pin[k] != expected[k] for k in pin):
                raise ValueError("input scope does not match reviewed lock")
        target = cache / (name + ".raw")
        if target.exists():
            data = target.read_bytes()
        elif offline:
            raise FileNotFoundError("missing locked input: " + str(target))
        else:
            request = urllib.request.Request(url, headers={"User-Agent": "Sidecar-static-audit/1"})
            with urllib.request.urlopen(request, timeout=45) as response:
                data = response.read()
            if not record:
                verify(data, expected)
            target.write_bytes(data)
        if not record:
            verify(data, expected)
        pin.update(size=len(data), sha256=sha(data))
        pins.append(pin)
        if path:
            source = base64.b64decode(data, validate=True)
            lines = source.decode("utf-8").splitlines()
            found = {}
            for anchor in anchors:
                hits = [i + 1 for i, line in enumerate(lines) if anchor in line]
                if not hits:
                    raise ValueError("expected source anchor absent: " + name + ": " + anchor)
                found[anchor] = hits
            observations.append({"id": name, "repo": repo, "commit": commit,
                                 "path": path, "source_url": url.removesuffix("?format=TEXT"),
                                 "decoded_size": len(source), "decoded_sha256": sha(source),
                                 "computed_git_blob": blob(source), "line_count": len(lines),
                                 "source_anchors": found,
                                 "apache_header_present": "Licensed under the Apache License, Version 2.0" in source.decode("utf-8")})
        else:
            if not data.startswith(b")]}'"):
                raise ValueError("invalid Gitiles JSON prefix")
            meta = read_json(data[4:])
            if meta["commit"] != commit:
                raise ValueError("fixed commit identity changed")
            observations.append({"id": name, "commit": commit, "tree": meta["tree"],
                                 "parents": meta["parents"], "committer": meta["committer"],
                                 "subject": meta["message"].splitlines()[0]})
    if not record and len(old["inputs"]) != len(SOURCES):
        raise ValueError("unexpected lock inputs")
    if record:
        write_json(lock_path, {"schema": 1, "inputs": pins,
                              "scope": "Fixed AOSP source/metadata only; no runtime execution"})
    return {"schema": 1, "input_lock_sha256": sha(lock_path.read_bytes()),
            "inputs_verified": len(pins), "observations": observations,
            "limitations": ["Static source evidence is not an OEM firmware or USB capture-card test.",
                            "Computed Git blob hashes identify decoded bytes but are not independent tree-membership verification.",
                            "Method anchors locate evidence; they do not prove behavior or device compatibility.",
                            "These sources are audit inputs only; no app or native dependency is introduced."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=pathlib.Path, default=DEFAULT_CACHE)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--record-inputs", action="store_true")
    parser.add_argument("--write-report", action="store_true")
    args = parser.parse_args()
    try:
        result = collect(args.cache, args.offline, args.record_inputs)
        report = HERE / "static-evidence.json"
        if args.write_report:
            write_json(report, result)
        elif not report.exists():
            raise FileNotFoundError("missing tracked report; use --write-report only for explicit regeneration")
        elif read_json(report.read_bytes()) != result:
            raise ValueError("replay differs from tracked report")
        print(json.dumps({"result": "pass", "inputs_verified": result["inputs_verified"],
                          "report_sha256": sha(report.read_bytes()) if report.exists() else None}))
    except Exception as exc:
        parser.exit(1, type(exc).__name__ + ": " + str(exc) + "\n")


if __name__ == "__main__":
    main()

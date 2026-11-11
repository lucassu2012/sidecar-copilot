"""Pinned Android platform-route source audit. Stdlib, text/metadata only.

Default replay is offline and compares computed evidence. --online may fill
missing locked bytes. --record-inputs is first-use only and refuses an old lock.
"""
import argparse
import base64
import hashlib
import json
import os
import tempfile
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
SIM = HERE.parents[2]
CACHE_ROOT = SIM / "build" / "routes" / "platform"
OLD_LOCK = SIM / "provenance" / "followup" / "platform-alternatives" / "INPUT_LOCK.json"
BASE = "https://android.googlesource.com/"
FRAMEWORK = "4e43ad12e1b211f15152e9c5b16b0fe88e6b93f3"
HAL = "488942f82bd1bc9ad1cb65a02c71421dc3a6a3d6"
CORE = "fc7bc8c4bfb4c6095e34ea784509dae56f25b486"
SOURCES = [
    ("framework-commit", "platform/frameworks/base", FRAMEWORK, "", []),
    ("hal-commit", "platform/hardware/interfaces", HAL, "", []),
    ("core-commit", "platform/system/core", CORE, "", []),
    ("usb-manager", "platform/frameworks/base", FRAMEWORK, "core/java/android/hardware/usb/UsbManager.java",
     ["getDeviceList()", "openDevice(UsbDevice", "hasPermission(UsbDevice", "requestPermission(UsbDevice", "Manifest.permission#CAMERA"]),
    ("usb-connection", "platform/frameworks/base", FRAMEWORK, "core/java/android/hardware/usb/UsbDeviceConnection.java",
     ["public void close()", "getFileDescriptor()", "claimInterface(", "releaseInterface(", "setInterface(", "bulkTransfer(", "requestWait("]),
    ("usb-request", "platform/frameworks/base", FRAMEWORK, "core/java/android/hardware/usb/UsbRequest.java",
     ["bulk and interrupt", "endpoint zero", "public boolean initialize(", "public void close()", "public boolean queue(", "public boolean cancel()"]),
    ("usb-endpoint", "platform/frameworks/base", FRAMEWORK, "core/java/android/hardware/usb/UsbEndpoint.java",
     ["getType()", "USB_ENDPOINT_XFER_ISOC"]),
    ("usb-connection-jni", "platform/frameworks/base", FRAMEWORK, "core/jni/android_hardware_UsbDeviceConnection.cpp",
     ["F_DUPFD_CLOEXEC", "usb_device_close", "usb_device_get_fd", "usb_device_bulk_transfer", "usb_request_wait"]),
    ("usb-request-jni", "platform/frameworks/base", FRAMEWORK, "core/jni/android_hardware_UsbRequest.cpp",
     ["usb_request_new", "usb_request_free", "usb_request_queue", "usb_request_cancel"]),
    ("usbhost", "platform/system/core", CORE, "libusbhost/usbhost.c",
     ["void usb_device_close", "int usb_device_get_fd", "USBDEVFS_BULK", "USB_ENDPOINT_XFER_BULK", "USB_ENDPOINT_XFER_INT", "Unsupported endpoint type", "USBDEVFS_SUBMITURB", "USBDEVFS_REAPURB", "USBDEVFS_DISCARDURB"]),
    ("camera-manager", "platform/frameworks/base", FRAMEWORK, "core/java/android/hardware/camera2/CameraManager.java",
     ["public String[] getCameraIdList()", "registerAvailabilityCallback(", "getCameraCharacteristics("]),
    ("camera-device", "platform/frameworks/base", FRAMEWORK, "core/java/android/hardware/camera2/CameraDevice.java",
     ["public abstract void onDisconnected(", "public void onClosed(", "public abstract void close()"]),
    ("image-reader", "platform/frameworks/base", FRAMEWORK, "media/java/android/media/ImageReader.java",
     ["public Image acquireLatestImage()", "public void close()", "getMaxImages()"]),
    ("external-provider", "platform/hardware/interfaces", HAL, "camera/provider/default/ExternalCameraProvider.cpp",
     ["VIDIOC_QUERYCAP", "V4L2_CAP_VIDEO_CAPTURE", "CameraDeviceStatus::NOT_PRESENT", "deviceAdded(", "deviceRemoved("]),
    ("external-device", "platform/hardware/interfaces", HAL, "camera/device/default/ExternalCameraDevice.cpp",
     ["kSupportedFourCCs", "V4L2_PIX_FMT_MJPEG", "V4L2_PIX_FMT_Z16", "ANDROID_LENS_FACING_EXTERNAL", "initSupportedFormats"]),
    ("external-build", "platform/hardware/interfaces", HAL, "camera/device/default/Android.bp",
     ["libyuv", "libjpeg", "Apache-2.0"]),
    ("audio-record", "platform/frameworks/base", FRAMEWORK, "media/java/android/media/AudioRecord.java",
     ["setPreferredDevice(", "getPreferredDevice()", "getRoutedDevice()", "RECORD_AUDIO", "registerAudioRecordingCallback("]),
    ("audio-manager", "platform/frameworks/base", FRAMEWORK, "media/java/android/media/AudioManager.java",
     ["GET_DEVICES_INPUTS", "getDevices(int", "registerAudioDeviceCallback("]),
    ("audio-device-info", "platform/frameworks/base", FRAMEWORK, "media/java/android/media/AudioDeviceInfo.java",
     ["TYPE_USB_DEVICE", "TYPE_USB_HEADSET", "boolean isSource()"]),
    ("usb-alsa-manager", "platform/frameworks/base", FRAMEWORK, "services/usb/java/com/android/server/usb/UsbAlsaManager.java",
     ["USB_AUDIO_AUTOMATIC_ROUTING_DISABLED", "hasInput", "selectDefaultDevice("])
]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode()


def no_duplicates(pairs):
    output = {}
    for key, value in pairs:
        if key in output:
            raise ValueError("duplicate JSON key: " + key)
        output[key] = value
    return output


def read_json(data):
    return json.loads(data, object_pairs_hook=no_duplicates)


def verify(data, entry):
    if len(data) != entry["size"] or digest(data) != entry["sha256"]:
        raise ValueError("input integrity mismatch: " + entry["id"])


def source_url(repo, commit, path):
    return BASE + repo + "/+/" + commit + ("/" + path + "?format=TEXT" if path else "?format=JSON")


def atomic_write(path, data):
    if path.exists() and path.read_bytes() == data:
        return
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as handle:
        handle.write(data)
        staged = Path(handle.name)
    try:
        staged.replace(path)
    finally:
        if staged.exists():
            staged.unlink()


def collect(args, cache):
    lock_path = HERE / "INPUT_LOCK.json"
    if args.record_inputs and lock_path.exists():
        raise ValueError("bootstrap refuses to overwrite existing input lock")
    if args.record_inputs and not args.online:
        raise ValueError("bootstrap requires --online")
    lock = None if args.record_inputs else read_json(lock_path.read_bytes())
    if lock and (lock["schema"] != 1 or len(lock["inputs"]) != len(SOURCES)):
        raise ValueError("input lock scope changed")
    old = {x["url"]: x for x in read_json(OLD_LOCK.read_bytes())["inputs"]}
    pins, observations = [], []
    for index, (name, repo, commit, path, anchors) in enumerate(SOURCES):
        url = source_url(repo, commit, path)
        expected = lock["inputs"][index] if lock else None
        if expected and (expected["id"] != name or expected["url"] != url):
            raise ValueError("locked input identity changed")
        target = cache / (name + ".raw")
        data = None
        if target.exists() and expected:
            data = target.read_bytes()
            verify(data, expected)
        elif args.reuse_cache and url in old:
            prior = Path(args.reuse_cache).resolve() / (old[url]["id"] + ".raw")
            if prior.exists():
                data = prior.read_bytes()
                verify(data, old[url])
                if expected:
                    verify(data, expected)
        if data is None:
            if not args.online:
                raise FileNotFoundError("missing locked input: " + name)
            request = urllib.request.Request(url, headers={"User-Agent": "Sidecar-platform-route-static-audit/1"})
            with urllib.request.urlopen(request, timeout=30) as response:
                data = response.read()
            if expected:
                verify(data, expected)
        pin = {"id": name, "url": url, "size": len(data), "sha256": digest(data)}
        pins.append(pin)
        if path:
            source = base64.b64decode(data, validate=True)
            content = source.decode("utf-8")
            lines = content.splitlines()
            found = {anchor: [i + 1 for i, line in enumerate(lines) if anchor in line] for anchor in anchors}
            if any(not hits for hits in found.values()):
                raise ValueError("source anchor absent: " + name + ": " + str([a for a,h in found.items() if not h]))
            observations.append({"id": name, "repo": repo, "commit": commit, "path": path,
                "source_url": url.removesuffix("?format=TEXT"), "decoded_size": len(source),
                "decoded_sha256": digest(source), "computed_git_blob": hashlib.sha1(b"blob " + str(len(source)).encode() + b"\0" + source).hexdigest(),
                "source_anchors": found,
                "copyright_notices": [line.strip() for line in lines[:35] if "Copyright" in line],
                "apache_2_header_present": "Licensed under the Apache License, Version 2.0" in content})
        else:
            if not data.startswith(b")]}'"):
                raise ValueError("not Gitiles commit JSON")
            meta = read_json(data[4:])
            if meta["commit"] != commit:
                raise ValueError("commit identity mismatch")
            observations.append({"id": name, "commit": commit, "tree": meta["tree"], "parents": meta["parents"]})
        # Cache only parsed text/metadata after checks, never rewrite an old cache.
        if not target.exists() or args.record_inputs:
            atomic_write(target, data)
    new_lock = {"schema": 1, "scope": "20 fixed AOSP text/metadata inputs; no mutable HTML or binaries", "inputs": pins}
    lock_data = json_bytes(new_lock) if args.record_inputs else lock_path.read_bytes()
    result = {"schema": 1, "input_lock_sha256": digest(lock_data), "inputs_verified": len(pins),
              "observations": observations, "limitations": [
                  "Static platform reference, not a tested OEM firmware/card combination.",
                  "Computed source Git blob IDs are byte identities, not independent full-tree membership proofs.",
                  "Anchors locate review evidence; they do not prove class-driver completeness or hardware compatibility.",
                  "Audit reads only source text/metadata; no implementation copied into the application."]}
    report_path = HERE / "static-evidence.json"
    if not args.write_report:
        if not report_path.exists():
            raise FileNotFoundError("missing tracked report")
        if read_json(report_path.read_bytes()) != result:
            raise ValueError("recomputed evidence differs from tracked report")
    # Input/parse/report errors above preserve both trusted deliverables.
    if args.record_inputs:
        with lock_path.open("xb") as handle:
            handle.write(lock_data)
    if args.write_report:
        atomic_write(report_path, json_bytes(result))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--online", action="store_true")
    parser.add_argument("--record-inputs", action="store_true")
    parser.add_argument("--write-report", action="store_true")
    parser.add_argument("--cache-dir", type=Path)
    parser.add_argument("--reuse-cache", type=Path, help="read-only former platform-alternatives raw cache")
    args = parser.parse_args()
    cache = args.cache_dir.resolve() if args.cache_dir else CACHE_ROOT
    if not cache.resolve().is_relative_to(CACHE_ROOT.resolve()):
        parser.error("cache override must stay under build/routes/platform")
    cache.mkdir(parents=True, exist_ok=True)
    mutex = cache / "collection.lock"
    handle = None
    try:
        try:
            handle = os.open(mutex, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            raise ValueError("collection lock busy; no automatic stale-lock removal")
        os.write(handle, str(os.getpid()).encode())
        result = collect(args, cache)
        print(json.dumps({"result": "pass", "inputs_verified": result["inputs_verified"]}))
    except Exception as exc:
        parser.exit(1, type(exc).__name__ + ": " + str(exc) + "\n")
    finally:
        if handle is not None:
            os.close(handle)
            mutex.unlink()


if __name__ == "__main__":
    main()

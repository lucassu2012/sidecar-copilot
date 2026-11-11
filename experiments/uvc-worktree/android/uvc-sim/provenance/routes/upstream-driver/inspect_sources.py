#!/usr/bin/env python3
"""Verify locked text inputs and replay static observations. Never runs upstream code."""
import argparse
import hashlib
import json
import re
import sys
import urllib.parse
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_CACHE = HERE.parents[2] / "build/routes/upstream-driver"
MAX_BYTES = 1_200_000


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def safe_path(cache, name):
    if not isinstance(name, str) or "\\" in name or ":" in name:
        raise ValueError("invalid cache name")
    parts = name.split("/")
    if any(p in ("", ".", "..") for p in parts):
        raise ValueError("unsafe cache path")
    target = cache.joinpath(*parts).resolve()
    if not target.is_relative_to(cache.resolve()):
        raise ValueError("cache path escapes scope")
    return target


def verify_bytes(data, item):
    if len(data) != item["bytes"] or digest(data) != item["sha256"]:
        raise ValueError("input SHA/size mismatch: " + item["name"])
    if "git_blob_sha1" in item:
        blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
        if blob != item["git_blob_sha1"]:
            raise ValueError("Git blob mismatch: " + item["name"])
    data.decode("utf-8")


def read_inputs(lock, cache, fetch=False):
    result = {}
    for item in lock["inputs"]:
        name = item["name"]
        if name in result:
            raise ValueError("duplicate locked input: " + name)
        if item["bytes"] > MAX_BYTES:
            raise ValueError("oversized input")
        target = safe_path(cache, name)
        if not target.is_file():
            if not fetch:
                raise ValueError("missing locked input: " + name)
            url = urllib.parse.urlsplit(item["url"])
            if url.scheme != "https" or url.hostname not in ("api.github.com", "raw.githubusercontent.com"):
                raise ValueError("unexpected source host")
            request = urllib.request.Request(item["url"], headers={"User-Agent": "Sidecar-Static-Source-Research"})
            with urllib.request.urlopen(request, timeout=30) as response:
                final = urllib.parse.urlsplit(response.geturl())
                if final.scheme != "https" or final.hostname != url.hostname:
                    raise ValueError("unexpected source redirect")
                data = response.read(MAX_BYTES + 1)
            verify_bytes(data, item)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        data = target.read_bytes()
        verify_bytes(data, item)
        result[name] = data.decode("utf-8")
    return result


def hit(inputs, name, text):
    found = [i for i, line in enumerate(inputs[name].splitlines(), 1) if text in line]
    if not found:
        raise ValueError("expected static anchor absent: " + name + ": " + text)
    return {"input": name, "anchor": text, "lines": found}


def make_report(lock, inputs):
    releases = {}
    for repo, expected in lock["projects"].items():
        release = json.loads(inputs[repo + "-release.json"])
        tag = json.loads(inputs[repo + "-tag.json"])
        commit = json.loads(inputs[repo + "-commit.json"])
        tree = json.loads(inputs[repo + "-tree.json"])
        if release["tag_name"] != expected["tag"] or tag["object"]["sha"] != expected["commit"]:
            raise ValueError("release/tag identity mismatch")
        if tag["object"]["type"] != "commit" or commit["sha"] != expected["commit"]:
            raise ValueError("commit identity mismatch")
        if tree["truncated"] or commit["tree"]["sha"] != tree["sha"]:
            raise ValueError("incomplete or mismatched tree")
        blobs = {r["path"]: r["sha"] for r in tree["tree"] if r["type"] == "blob"}
        for item in lock["inputs"]:
            if item.get("repo") == repo:
                if item["commit"] != expected["commit"] or blobs.get(item["path"]) != item["git_blob_sha1"]:
                    raise ValueError("source differs from fixed tree: " + item["name"])
        releases[repo] = {**expected, "published_at": release["published_at"],
                          "commit_committer_date": commit["committer"]["date"],
                          "release_url": release["html_url"], "tree": tree["sha"]}
    old = json.loads(inputs["libuvc-0.0.8-release.json"])
    old_tag = json.loads(inputs["libuvc-0.0.8-tag.json"])
    releases["libuvc_previous"] = {"tag": old["tag_name"], "commit": old_tag["object"]["sha"],
                                    "published_at": old["published_at"], "release_url": old["html_url"]}
    anchors = [
        ("libusb/libusb/core.c", "Call libusb_init_context with the LIBUSB_OPTION_NO_DEVICE_DISCOVERY"),
        ("libusb/libusb/core.c", "The system device handle will not be closed by libusb_close()."),
        ("libusb/libusb/core.c", "int API_EXPORTED libusb_wrap_sys_device"),
        ("libusb/libusb/core.c", "A cancellation hasn't even been scheduled"),
        ("libusb/libusb/os/linux_usbfs.c", "hpriv->fd_keep = 1;"),
        ("libusb/libusb/os/linux_usbfs.c", "if (!hpriv->fd_keep)"),
        ("libuvc/src/device.c", "int err = libusb_wrap_sys_device(context->usb_ctx, sys_dev, &usb_devh);"),
        ("libuvc/src/init.c", "ctx->own_usb_ctx = 0;"),
        ("libuvc/src/init.c", "libusb event processing using a function such as libusb_handle_events."),
        ("libuvc/src/device.c", "ret = libusb_submit_transfer(internal_devh->status_xfer);"),
        ("libuvc/src/device.c", "libusb_free_transfer(devh->status_xfer);"),
        ("libuvc/src/stream.c", "uvc_error_t uvc_get_stream_ctrl_format_size("),
        ("libuvc/src/stream.c", "uvc_query_stream_ctrl( devh, ctrl, 1, UVC_SET_CUR );"),
        ("libuvc/src/stream.c", "buf, len, 0"),
        ("libuvc/src/stream.c", "isochronous = interface->num_altsetting > 1;"),
        ("libuvc/src/stream.c", "libusb_fill_iso_transfer("),
        ("libuvc/src/stream.c", "libusb_fill_bulk_transfer ("),
        ("libuvc/src/stream.c", "libusb_cancel_transfer(strmh->transfers[i]);"),
        ("libuvc/src/stream.c", "pthread_cond_wait(&strmh->cb_cond, &strmh->cb_mutex);"),
        ("libuvc/src/stream.c", "pthread_join(strmh->cb_thread, NULL);"),
        ("libuvc/src/stream.c", "@todo stop the actual stream, camera side?"),
        ("libuvc/src/stream.c", "frame->data_bytes = strmh->hold_bytes;"),
        ("libuvc/include/libuvc/libuvc.h", "You must not call any uvc_* functions during a callback."),
        ("libuvc/src/stream.c", "FMT(UVC_FRAME_FORMAT_H264,"),
        ("libuvc/src/frame.c", "uvc_error_t uvc_any2rgb("),
        ("libuvc/src/frame-mjpeg.c", "jpeg_mem_src(&dinfo, in->data, in->data_bytes);"),
        ("libuvc/src/frame-mjpeg.c", "dinfo.output_width != out->width"),
        ("libuvc/include/libuvc/libuvc_internal.h", "#define LIBUVC_NUM_TRANSFER_BUFS 100"),
        ("libuvc/include/libuvc/libuvc_internal.h", "#define LIBUVC_PACKETS_PER_TRANSFER_MAX 32"),
        ("libuvc/.github/workflows/android.yml", "LIBUSB_VERSION: 1.0.30"),
        ("libuvc/.github/workflows/android.yml", "-DDISABLE_JPEG=ON"),
        ("libusb/android/jni/libusb.mk", "LOCAL_LDLIBS := -llog"),
        ("libuvc/include/utlist.h", "#define UTLIST_VERSION 2.0.2"),
    ]
    cmake = inputs["libuvc/CMakeLists.txt"]
    source_block = re.search(r"set\(SOURCES\s+(.*?)\n\)", cmake, re.S).group(1)
    uvc_sources = source_block.split()
    usb_sources = re.findall(r"\$\(LIBUSB_ROOT_REL\)/(\S+\.c)", inputs["libusb/android/jni/libusb.mk"])
    license_headers = []
    for repo, paths in (("libusb", usb_sources), ("libuvc", uvc_sources + ["src/frame-mjpeg.c"])):
        for path in paths:
            name = repo + "/" + path
            header = "\n".join(inputs[name].splitlines()[:34])
            declared = "LGPL-2.1-or-later" if "either" in header and "any later version" in header else (
                "BSD-3-Clause text" if "Software License Agreement (BSD License)" in header else "UNCLASSIFIED")
            license_headers.append({"input": name, "header_classification": declared,
                                    "scope": "first 34 lines only; does not clear all embedded material"})
    device = inputs["libuvc/src/device.c"]
    close = device.split("void uvc_close(uvc_device_handle_t *devh) {", 1)[1].split("/** @internal", 1)[0]
    status_cancel_literal = "libusb_cancel_transfer" in close
    return {
        "schema": 1, "verification": "locked selected text input replay passed",
        "scope": "literal anchors and manually reviewed fixed source; not compiled call graph, binary audit or hardware validation",
        "input_count": len(inputs), "input_bytes": sum(i["bytes"] for i in lock["inputs"]),
        "inputs_lock_sha256": digest(canonical(lock).encode()),
        "releases": releases, "anchors": [hit(inputs, *a) for a in anchors],
        "declared_libuvc_base_c_sources": uvc_sources,
        "declared_libuvc_optional_jpeg_c_source": "src/frame-mjpeg.c",
        "declared_libusb_android_c_sources": usb_sources,
        "selected_runtime_c_license_headers": license_headers,
        "uvc_close_body_contains_literal_libusb_cancel_transfer": status_cancel_literal,
        "readiness": "NOT_ESTABLISHED",
        "open_conditions": ["status interrupt transfer cancel/drain before free", "bounded/failure-aware probe, stop and close",
                            "unchecked allocation/thread failures and resource limits", "Android isolated build and packaging verification",
                            "exact mode/color/layout/ownership/clock contracts", "device/permission/iso/bulk/detach/reconnect evidence",
                            "complete shipped-source/license and optional JPEG dependency review"],
    }


def check_report(path, expected, write=False):
    text = canonical(expected)
    if write:
        if path.exists() and path.read_text(encoding="utf-8") != text:
            raise ValueError("refusing to overwrite different evidence; review explicitly")
        path.write_text(text, encoding="utf-8")
    elif not path.is_file():
        raise ValueError("evidence report missing; explicit --write-report required for first generation")
    elif path.read_text(encoding="utf-8") != text:
        raise ValueError("evidence differs from locked replay")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--fetch-missing", action="store_true", help="download only missing inputs at exact locked URLs")
    parser.add_argument("--write-report", action="store_true", help="explicit first evidence generation; rejects differing existing report")
    args = parser.parse_args()
    try:
        lock = json.loads((HERE / "inputs.lock.json").read_text(encoding="utf-8"))
        inputs = read_inputs(lock, args.cache, args.fetch_missing)
        evidence = make_report(lock, inputs)
        check_report(HERE / "evidence.json", evidence, args.write_report)
        print(json.dumps({"status": "PASS_STATIC_REPLAY", "inputs": len(inputs), "readiness": evidence["readiness"]}))
        return 0
    except (ValueError, OSError, KeyError, urllib.error.URLError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

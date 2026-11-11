"""Verify pinned text inputs only; never configure, build, or load native code."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

HERE = Path(__file__).resolve().parent
DEFAULT_CACHE = HERE.parents[2] / "build" / "routes" / "mjpeg-decoder"


def validate(data, item):
    if len(data) != item["bytes"]:
        raise ValueError("size mismatch: " + item["path"])
    if hashlib.sha256(data).hexdigest() != item["sha256"]:
        raise ValueError("SHA-256 mismatch: " + item["path"])
    blob = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if blob != item["git_blob_sha1"]:
        raise ValueError("Git blob mismatch: " + item["path"])
    if b"\0" in data:
        raise ValueError("binary input rejected")
    return data.decode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--fetch", action="store_true", help="Fetch missing pinned text inputs only")
    args = parser.parse_args()
    lock = json.loads((HERE / "inputs.lock.json").read_text(encoding="utf-8"))
    texts = {}
    root = args.cache.resolve()
    for item in lock["inputs"]:
        path = (root / item["path"]).resolve()
        if not path.is_relative_to(root):
            raise ValueError("cache path escapes cache root")
        expected_url = "https://raw.githubusercontent.com/libjpeg-turbo/libjpeg-turbo/" + lock["commit"] + "/" + item["path"]
        if item["url"] != expected_url:
            raise ValueError("unexpected source URL")
        if not path.exists():
            if not args.fetch:
                raise FileNotFoundError("Missing source text; use --fetch: " + str(path))
            with urllib.request.urlopen(expected_url, timeout=30) as response:
                data = response.read(2 * 1024 * 1024 + 1)
            validate(data, item)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        data = path.read_bytes()
        texts[item["path"]] = validate(data, item)
        # Exercise the same boundary with wrong-length and same-length tampering.
        for bad in (data + b"x", bytes([data[0] ^ 1]) + data[1:]):
            try:
                validate(bad, item)
            except ValueError:
                pass
            else:
                raise ValueError("tamper check unexpectedly accepted " + item["path"])
    cmake = texts["CMakeLists.txt"]
    shared = texts["sharedlib/CMakeLists.txt"]
    capabilities = {
        "png_branch_guard_at_top_level": "if(WITH_TURBOJPEG OR WITH_TOOLS OR WITH_TESTS)" in cmake,
        "png_branch_guard_at_shared_library_level": "if(WITH_TURBOJPEG OR WITH_TOOLS OR WITH_TESTS)" in shared,
        "jna_can_force_turbojpeg": "if(WITH_JNA)\n  set(ENABLE_SHARED 1)\n  set(WITH_TURBOJPEG 1)" in cmake,
        "rgba_and_opaque_alpha_documented": all(x in texts["src/jpeglib.h"] for x in ["JCS_EXT_RGBA", "0xFF", "opaque alpha"]),
        "libjpeg_memory_source_api_declared": "jpeg_mem_src" in texts["src/jpeglib.h"],
        "android_build_documentation": "Building libjpeg-turbo for Android" in texts["BUILDING.md"],
    }
    if not all(capabilities.values()):
        raise ValueError("Expected source evidence no longer matches")
    print(json.dumps({"fixed_text_inputs": len(texts), "tamper_rejections": len(texts) * 2,
                      "capabilities": capabilities, "native_execution": False,
                      "decode_tests_executed": 0, "binary_closure_verified": False}, indent=2))


if __name__ == "__main__":
    main()

"""Read-only inspection of existing public artifacts and optional pinned public source text.

No native loading, archive extraction, upstream build, Gradle resolution or device calls.
Only the requested JSON report is written. Run without --online for local artifacts only.
"""
import argparse
import concurrent.futures
import datetime
import hashlib
import io
import json
import pathlib
import re
import struct
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

UVC = "fcdae5f9a194e2111d57019176b53470a120415b"
AUSBC = "426ed0a5da2c826197761cc5a89ba3ae9c52bd7c"
EXPECTED = {
    "UVCAndroid-1.0.13.aar": "4f1295a6c6b5e8590fb7dde3c60f30a94166db45c2d07efa63c85d5da8bfff17",
    "UVCAndroid-1.0.13.pom": "5b1ff27d872d1fa3720fa1e367bab05d6cf422695e7457fb44b5934be005add7",
    "UVCAndroid-1.0.13.module": "f22e2960d4eca439c19eebdc82d4ea51450735335fb889cded6511e1b07de20b",
}

def sha(data):
    return hashlib.sha256(data).hexdigest()

def class_refs(data):
    assert data[:4] == b"\xca\xfe\xba\xbe"
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    offset, i = 10, 1
    while i < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            n = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            pool[i] = (tag, data[offset:offset+n].decode("utf8", "replace"))
            offset += n
        elif tag in (3, 4):
            pool[i] = (tag,)
            offset += 4
        elif tag in (5, 6):
            pool[i] = (tag,)
            offset += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, struct.unpack_from(">H", data, offset)[0])
            offset += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[i] = (tag,) + struct.unpack_from(">HH", data, offset)
            offset += 4
        elif tag == 15:
            pool[i] = (tag,) + struct.unpack_from(">BH", data, offset)
            offset += 3
        else:
            raise ValueError(f"unsupported constant-pool tag {tag}")
        i += 1
    refs = []
    for entry in pool:
        if entry and entry[0] in (9, 10, 11):
            owner = pool[pool[entry[1]][1]][1]
            if owner.startswith("cn/hutool/"):
                nt = pool[entry[2]]
                refs.append({"tag": entry[0], "owner": owner,
                             "name": pool[nt[1]][1], "descriptor": pool[nt[2]][1]})
    return refs

def elf_info(data):
    assert data[:4] == b"\x7fELF"
    is64 = data[4] == 2
    order = "<" if data[5] == 1 else ">"
    if is64:
        phoff = struct.unpack_from(order+"Q", data, 32)[0]
        phsize, phnum = struct.unpack_from(order+"HH", data, 54)
    else:
        phoff = struct.unpack_from(order+"I", data, 28)[0]
        phsize, phnum = struct.unpack_from(order+"HH", data, 42)
    loads, dynamics = [], []
    for i in range(phnum):
        fields = struct.unpack_from(order+("IIQQQQQQ" if is64 else "IIIIIIII"), data, phoff+i*phsize)
        if is64:
            kind, flags, offset, vaddr, paddr, filesz, memsz, align = fields
        else:
            kind, offset, vaddr, paddr, filesz, memsz, flags, align = fields
        assert offset+filesz <= len(data), "segment outside ELF file"
        if kind == 1:
            loads.append({"offset": offset, "vaddr": vaddr, "filesz": filesz, "memsz": memsz,
                          "align": align, "congruent_16k": offset % 16384 == vaddr % 16384})
        elif kind == 2:
            for pos in range(offset, offset+filesz, 16 if is64 else 8):
                tag, value = struct.unpack_from(order+("qQ" if is64 else "iI"), data, pos)
                if tag == 0:
                    break
                dynamics.append((tag, value))
    strtab = next((value for tag, value in dynamics if tag == 5), None)
    strsize = next((value for tag, value in dynamics if tag == 10), 0)
    needed = []
    if strtab is not None:
        segment = next(s for s in loads if s["vaddr"] <= strtab < s["vaddr"]+s["filesz"])
        off = segment["offset"] + strtab - segment["vaddr"]
        strings = data[off:off+strsize]
        for tag, value in dynamics:
            if tag == 1:
                assert 0 <= value < len(strings)
                needed.append(strings[value:].split(b"\0", 1)[0].decode("utf8"))
    return {"bits": 64 if is64 else 32, "loads": loads, "needed": needed, "sha256": sha(data)}

def pom_info(data):
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    root = ET.fromstring(data)
    def fields(node, names):
        return {n: node.findtext("m:"+n, namespaces=ns) for n in names}
    return {
        "licenses": [fields(n, ["name", "url"]) for n in root.findall("m:licenses/m:license", ns)],
        "dependencies": [fields(n, ["groupId", "artifactId", "version", "scope", "optional"])
                         for n in root.findall("m:dependencies/m:dependency", ns)],
        "parent": fields(root.find("m:parent", ns), ["groupId", "artifactId", "version"])
                  if root.find("m:parent", ns) is not None else None,
    }

def local_report(directory):
    artifacts = {}
    for filename, expected in EXPECTED.items():
        data = (directory/filename).read_bytes()
        actual = sha(data)
        assert actual == expected, f"unexpected artifact hash: {filename}"
        artifacts[filename] = {"bytes": len(data), "sha256": actual}
    module = json.loads((directory/"UVCAndroid-1.0.13.module").read_text())
    publication = [f for v in module["variants"] for f in v.get("files", []) if f["name"].endswith(".aar")]
    assert publication, "module has no AAR publication"
    assert all(f["sha256"] == EXPECTED["UVCAndroid-1.0.13.aar"] for f in publication)
    aar = zipfile.ZipFile(directory/"UVCAndroid-1.0.13.aar")
    manifest = ET.fromstring(aar.read("AndroidManifest.xml"))
    android = "{http://schemas.android.com/apk/res/android}"
    manifest_result = {
        "permissions": [e.attrib.get(android+"name") for e in manifest.findall("uses-permission")],
        "features": [{k.replace(android, "android:"): v for k, v in e.attrib.items()}
                     for e in manifest.findall("uses-feature")],
        "sha256": sha(aar.read("AndroidManifest.xml")),
    }
    nested = zipfile.ZipFile(io.BytesIO(aar.read("classes.jar")))
    hutool = {}
    for name in nested.namelist():
        if name.endswith(".class"):
            refs = class_refs(nested.read(name))
            if refs:
                hutool[name] = refs
    elves = {name: elf_info(aar.read(name)) for name in aar.namelist() if name.endswith(".so")}
    assert len(elves) == 20
    assert all(len(e["loads"]) == 3 and all(s["align"] == 16384 for s in e["loads"]) for e in elves.values())
    assert all(s["congruent_16k"] for e in elves.values() for s in e["loads"])
    bundled_names = {pathlib.PurePosixPath(n).name for n in elves}
    external_needed = sorted({n for e in elves.values() for n in e["needed"] if n not in bundled_names})
    return {
        "artifacts": artifacts, "module_sha256_matches": True,
        "pom": pom_info((directory/"UVCAndroid-1.0.13.pom").read_bytes()),
        "gradle_runtime_dependencies": [v.get("dependencies", []) for v in module["variants"]
                                        if v["name"] == "releaseVariantReleaseRuntimePublication"],
        "manifest": manifest_result, "hutool_member_references": hutool,
        "aar_license_named_entries": [n for n in aar.namelist() if re.search("license|notice|copying", n, re.I)],
        "classes_license_named_entries": [n for n in nested.namelist() if re.search("license|notice|copying", n, re.I)],
        "elf": elves, "external_needed_names": external_needed,
        "limits": "No native loading, complete binary audit, final APK zip alignment or runtime/device verification.",
    }

def source_urls():
    u = f"https://raw.githubusercontent.com/shiyinghan/UVCAndroid/{UVC}/"
    a = f"https://raw.githubusercontent.com/jiangdongguo/AndroidUSBCamera/{AUSBC}/"
    urls = {
        "uvc_build": u+"libuvccamera/build.gradle",
        "uvc_publish": u+"publish_aar.gradle",
        "uvc_fileutils": u+"libuvccamera/src/main/java/com/serenegiant/utils/FileUtils.java",
        "uvc_manifest": u+"libuvccamera/src/main/AndroidManifest.xml",
        "uvc_yuv_version": u+"libuvccamera/src/main/jni/libyuv/include/libyuv/version.h",
        "uvc_yuv_convert": u+"libuvccamera/src/main/jni/libyuv/source/convert.cc",
        "uvc_usb_terms": u+"libuvccamera/src/main/jni/libusb/libusb/core.c",
        "uvc_usb_version": u+"libuvccamera/src/main/jni/libusb/libusb/version.h",
        "uvc_uvc_license": u+"libuvccamera/src/main/jni/libuvc/LICENSE.txt",
        "uvc_uvc_version": u+"libuvccamera/src/main/jni/libuvc/CMakeLists.txt",
        "uvc_jpeg_terms": u+"libuvccamera/src/main/jni/libjpeg-turbo/LICENSE.md",
        "uvc_jpeg_version": u+"libuvccamera/src/main/jni/libjpeg-turbo/CMakeLists.txt",
        "uvc_rapidjson_terms": u+"libuvccamera/src/main/jni/rapidjson/license.txt",
        "ausbc_root_build": a+"build.gradle",
        "ausbc_library_build": a+"libausbc/build.gradle",
        "ausbc_native_build": a+"libnative/build.gradle",
        "ausbc_usb_terms": a+"libuvc/src/main/jni/libusb/libusb/core.c",
        "ausbc_jpeg_version": a+"libuvc/src/main/jni/libjpeg-turbo-1.5.0/CMakeLists.txt",
        "ausbc_jpeg_terms": a+"libuvc/src/main/jni/libjpeg-turbo-1.5.0/LICENSE.md",
        "ausbc_lame_terms": a+"libnative/src/main/cpp/module/mp3/lame/lame.h",
        "ausbc_lame_build": a+"libnative/src/main/cpp/CMakeLists.txt",
        "ausbc_uac_loader": a+"libuvc/src/main/java/com/jiangdg/uac/UACAudio.java",
        "hutool_core_pom": "https://repo.maven.apache.org/maven2/cn/hutool/hutool-core/5.8.35/hutool-core-5.8.35.pom",
        "hutool_parent_pom": "https://repo.maven.apache.org/maven2/cn/hutool/hutool-parent/5.8.35/hutool-parent-5.8.35.pom",
        "xlog_pom": "https://repo.maven.apache.org/maven2/com/elvishew/xlog/1.11.0/xlog-1.11.0.pom",
        "appcompat_pom": "https://dl.google.com/dl/android/maven2/androidx/appcompat/appcompat/1.3.1/appcompat-1.3.1.pom",
        "uvc_tree": f"https://api.github.com/repos/shiyinghan/UVCAndroid/git/trees/{UVC}?recursive=1",
        "ausbc_tree": f"https://api.github.com/repos/jiangdongguo/AndroidUSBCamera/git/trees/{AUSBC}?recursive=1",
    }
    for name in ("LICENSE", "PATENTS", "AUTHORS"):
        urls["uvc_yuv_"+name.lower()] = u+"libuvccamera/src/main/jni/libyuv/"+name
    return urls

def fetch(item):
    name, url = item
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Sidecar-pinned-license-review/1"})
        with urllib.request.urlopen(req, timeout=25) as response:
            data = response.read()
        text = data.decode("utf8", "replace")
        result = {"url": url, "http_status": 200, "sha256": sha(data), "bytes": len(data)}
        if name.endswith("_pom"):
            result["pom"] = pom_info(data)
        elif name.endswith("_tree"):
            tree = json.loads(data)
            result["truncated"] = tree.get("truncated")
            assert result["truncated"] is False, "tree evidence incomplete"
            paths = [entry["path"] for entry in tree["tree"]]
            result["path_count"] = len(paths)
            result["yuv_root_materials"] = [p for p in paths if re.search(r"libyuv/(LICENSE|PATENTS|AUTHORS)$", p)]
            result["uac_paths"] = [p for p in paths if "uac" in p.lower()]
        else:
            patterns = "hutool|LIBYUV_VERSION|LIBUSB_(MAJOR|MINOR|MICRO)|VERSION_(MAJOR|MINOR|PATCH)|project\\(|VERSION\\s+[0-9]|libraryVersion|versionNameString|androidXVersion|constraintlayoutVersion|materialVersion|kotlin|LICENSE|PATENTS|AUTHORS|General Public|version 2|later version|mp3/lame|SRC_LAME|loadLibrary|uses-permission|uses-feature|implementation|api '"
            result["matching_lines"] = [{"line": i, "text": line.strip()[:250]}
                                       for i, line in enumerate(text.splitlines(), 1)
                                       if re.search(patterns, line, re.I)][:50]
            result["contains_hutool_case_insensitive"] = "hutool" in text.lower()
        return name, result
    except urllib.error.HTTPError as error:
        return name, {"url": url, "http_status": error.code}
    except Exception as error:
        return name, {"url": url, "error": str(error)}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifact-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--online", action="store_true")
    args = parser.parse_args()
    report = {"inspected_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "source_commits": {"UVCAndroid": UVC, "AndroidUSBCamera": AUSBC},
              "local": local_report(args.artifact_dir)}
    if args.online:
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers:
            report["sources"] = dict(workers.map(fetch, source_urls().items()))
        expected_missing = {"uvc_yuv_license", "uvc_yuv_patents", "uvc_yuv_authors"}
        report["unexpected_source_results"] = {
            name: value for name, value in report["sources"].items()
            if value.get("http_status") != (404 if name in expected_missing else 200)
        }
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf8")
    assert not report.get("unexpected_source_results"), "source check failed; inspect JSON report"
    print(json.dumps({"artifact_hashes_match": True, "native_libraries": len(report["local"]["elf"]),
                      "hutool_classes": list(report["local"]["hutool_member_references"]),
                      "all_load_segments_16k_congruent": all(s["congruent_16k"] for e in report["local"]["elf"].values() for s in e["loads"]),
                      "sources_checked": len(report.get("sources", {})), "report": str(args.output)}))

if __name__ == "__main__":
    main()

"""Static Maven/source provenance checks. Never execute downloaded code or extract archives.

Downloads only explicit public Maven metadata/artifacts and GitHub fixed source/API responses.
Cache is confined to android/uvc-sim/build/provenance/hutool; JSON output to this directory.
Use --offline to reproduce from the cache. No Gradle, JVM, native or device invocation.
"""
import argparse
import concurrent.futures
import datetime
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import struct
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

UVC_RELEASE = "6018c7f20238832929cf61427427bb7828056260"
UVC_REMOVAL = "61e9f8fde9ee0071b661d230a33b58542ddf84bc"
UVC_BASELINE = "fcdae5f9a194e2111d57019176b53470a120415b"
HUTOOL = "ced8bf817ab4448e9b1162af6ce697cef8640827"
SOURCE_PATH = "libuvccamera/src/main/java/com/serenegiant/utils/FileUtils.java"
HERE = Path(__file__).resolve().parent
CACHE = HERE.parents[1] / "build/provenance/hutool"
EXPECTED_ARTIFACTS = {
    "uvc-sources.jar": "f2292812ab0d988a0b0a96c5c511b0702a55aa050fb9a4e5030c06b131a22ce0",
    "hutool-core.jar": "69fb13e89d17fd0f254a77fd5c1df4783151a8d90999d0288d0732da6f82365e",
    "hutool-sources.jar": "1bcf829a8cf3cbb52e551d7332581dc1ecb9192ac99a7039dee7b21f76b81662",
    "hutool-core.pom": "441fc70fab6afd1b596402b888d6e8a4d014871acea4b484cc083d4335fdbdcf",
    "hutool-parent.pom": "66a9b022d6fc105760e41888ce44a9ecceab6f598fc3f5ffe21f461d4e2d8475",
}

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def git_blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()

def require(condition, message):
    if not condition:
        raise ValueError(message)

def inputs():
    m = "https://repo.maven.apache.org/maven2/"
    u = "https://api.github.com/repos/shiyinghan/UVCAndroid/"
    h = "https://api.github.com/repos/chinabugotech/hutool/"
    r = f"https://raw.githubusercontent.com/shiyinghan/UVCAndroid/{UVC_RELEASE}/"
    result = {
        "uvc-sources.jar": m + "com/herohan/UVCAndroid/1.0.13/UVCAndroid-1.0.13-sources.jar",
        "hutool-core.jar": m + "cn/hutool/hutool-core/5.8.35/hutool-core-5.8.35.jar",
        "hutool-sources.jar": m + "cn/hutool/hutool-core/5.8.35/hutool-core-5.8.35-sources.jar",
        "hutool-core.pom": m + "cn/hutool/hutool-core/5.8.35/hutool-core-5.8.35.pom",
        "hutool-parent.pom": m + "cn/hutool/hutool-parent/5.8.35/hutool-parent-5.8.35.pom",
        "uvc-tag.json": u + "git/ref/tags/1.0.13",
        "uvc-release.json": u + f"commits/{UVC_RELEASE}",
        "uvc-removal.json": u + f"commits/{UVC_REMOVAL}",
        "uvc-compare.json": u + f"compare/{UVC_RELEASE}...{UVC_BASELINE}",
        "uvc-release-tree.json": u + f"git/trees/{UVC_RELEASE}?recursive=1",
        "uvc-file-history.json": u + f"commits?sha={UVC_BASELINE}&path={SOURCE_PATH}&per_page=100",
        "uvc-release-FileUtils.java": r + SOURCE_PATH,
        "uvc-release-build.gradle": r + "libuvccamera/build.gradle",
        "uvc-baseline-FileUtils.java": f"https://raw.githubusercontent.com/shiyinghan/UVCAndroid/{UVC_BASELINE}/" + SOURCE_PATH,
        "hutool-tag.json": h + "git/ref/tags/5.8.35",
        "hutool-commit.json": h + f"commits/{HUTOOL}",
        "hutool-tree.json": h + f"git/trees/{HUTOOL}?recursive=1",
        "hutool-LICENSE": f"https://raw.githubusercontent.com/chinabugotech/hutool/{HUTOOL}/LICENSE",
        "hutool-source-parent.pom": f"https://raw.githubusercontent.com/chinabugotech/hutool/{HUTOOL}/pom.xml",
        "hutool-source-core.pom": f"https://raw.githubusercontent.com/chinabugotech/hutool/{HUTOOL}/hutool-core/pom.xml",
    }
    for name, url in list(result.items()):
        if url.startswith(m):
            result[name + ".sha1"] = url + ".sha1"
    return result

def download(item, offline, locked):
    name, url = item
    file = CACHE / name
    metadata = CACHE / (name + ".http.json")
    if file.exists() and metadata.exists():
        data = file.read_bytes()
        meta = json.loads(metadata.read_text(encoding="utf8"))
        require(meta["request_url"] == url and meta["sha256"] == sha256(data), f"cache metadata mismatch: {name}")
        require(sha256(data) == locked["sha256"] and len(data) == locked["bytes"], f"locked input mismatch: {name}")
        return name, data, meta
    if offline:
        raise RuntimeError(f"missing cached input: {name}")
    request = urllib.request.Request(url, headers={"User-Agent": "Sidecar-static-provenance/1"})
    with urllib.request.urlopen(request, timeout=30) as response:
        data = response.read()
        meta = {"request_url": url, "response_url": response.url, "status": response.status,
                "sha256": sha256(data), "bytes": len(data),
                "last_modified": response.headers.get("Last-Modified"),
                "retrieved_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    require(sha256(data) == locked["sha256"] and len(data) == locked["bytes"], f"download differs from reviewed input: {name}")
    file.write_bytes(data)
    metadata.write_text(json.dumps(meta, indent=2) + "\n", encoding="utf8")
    return name, data, meta

def class_info(data):
    require(data[:4] == b"\xca\xfe\xba\xbe", "invalid class-file magic")
    count = struct.unpack_from(">H", data, 8)[0]
    pool, pos, i = [None] * count, 10, 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            n = struct.unpack_from(">H", data, pos)[0]
            pos += 2
            pool[i] = (tag, data[pos:pos+n].decode("utf8", "replace"))
            pos += n
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, struct.unpack_from(">H", data, pos)[0])
            pos += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[i] = (tag,) + struct.unpack_from(">HH", data, pos)
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"unknown class constant tag {tag}")
        i += 1
    classes, strings, hutool = [], [], []
    for entry in pool:
        if entry and entry[0] == 7:
            classes.append(pool[entry[1]][1])
        if entry and entry[0] == 1:
            strings.append(entry[1])
        if entry and entry[0] in (9, 10, 11):
            owner = pool[pool[entry[1]][1]][1]
            if owner.startswith("cn/hutool"):
                nt = pool[entry[2]]
                hutool.append({"tag": entry[0], "owner": owner,
                               "name": pool[nt[1]][1], "descriptor": pool[nt[2]][1]})
    return {"classes": classes, "utf8": strings, "hutool_refs": hutool}

def inventory(data):
    z = zipfile.ZipFile(io.BytesIO(data))
    names = z.namelist()
    license_entries = [n for n in names if not n.endswith("/") and re.search("license|notice|copying", n, re.I)]
    licenses = {}
    for n in license_entries:
        raw = z.read(n)
        licenses[n] = {"sha256": sha256(raw), "bytes": len(raw),
                       "text": raw.decode("utf8", "replace")}
    embedded = []
    for n in names:
        if not n.endswith("/"):
            raw = z.read(n)
            if raw[:4] in (b"\x7fELF", b"PK\x03\x04") or raw[:2] == b"MZ" or PurePosixPath(n).suffix.lower() in (".so", ".dll", ".dylib", ".jar", ".aar", ".exe"):
                embedded.append({"entry": n, "sha256": sha256(raw), "magic_hex": raw[:4].hex()})
    return z, {"entry_count": len(names), "class_count": sum(n.endswith(".class") for n in names),
               "java_count": sum(n.endswith(".java") for n in names), "license_entries": licenses,
               "embedded_binary_or_archive_candidates": embedded,
               "top_level_paths": sorted({n.split("/")[0] for n in names}),
               "non_java_non_class_files": [n for n in names if not n.endswith(("/", ".java", ".class"))]}

def pom(data):
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    root = ET.fromstring(data)
    def f(node, names):
        return {name: node.findtext("m:" + name, namespaces=ns) for name in names}
    return {"coordinates": f(root, ["groupId", "artifactId", "version"]),
            "parent": f(root.find("m:parent", ns), ["groupId", "artifactId", "version"]) if root.find("m:parent", ns) is not None else None,
            "licenses": [f(n, ["name", "url"]) for n in root.findall("m:licenses/m:license", ns)],
            "dependencies": [f(n, ["groupId", "artifactId", "version", "scope", "optional"]) for n in root.findall("m:dependencies/m:dependency", ns)]}

def source_match(z, tree_data, prefix):
    tree = json.loads(tree_data)
    require(tree["truncated"] is False, "incomplete source tree")
    bypath = {x["path"]: x for x in tree["tree"] if x["type"] == "blob"}
    matches, differences, absent = [], [], []
    for name in z.namelist():
        if name.endswith("/") or name.startswith("META-INF/"):
            continue
        path = prefix + name
        raw = z.read(name)
        item = {"entry": name, "source_path": path, "sha256": sha256(raw), "git_blob_sha1": git_blob(raw)}
        if path not in bypath:
            absent.append(item)
        elif bypath[path]["sha"] == item["git_blob_sha1"]:
            matches.append(item)
        else:
            item["tree_blob_sha1"] = bypath[path]["sha"]
            differences.append(item)
    return {"exact_matches": matches, "different_bytes": differences, "unmapped_entries": absent,
            "limits": "Only archive entries mapped to this source prefix; excludes META-INF; no binary reproducibility claim."}

def commit_summary(data):
    c = json.loads(data)
    return {"sha": c["sha"], "commit_date": c["commit"]["committer"]["date"],
            "message": c["commit"]["message"], "tree": c["commit"]["tree"]["sha"],
            "parents": [x["sha"] for x in c["parents"]],
            "verification": c["commit"].get("verification"),
            "file_changes": [{k: f.get(k) for k in ("filename", "status", "patch")} for f in c.get("files", [])]}

def selected_lines(data, pattern):
    return [{"line": i, "text": s.strip()} for i, s in enumerate(data.decode("utf8").splitlines(), 1) if re.search(pattern, s)]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--existing-artifacts", required=True, type=Path)
    args = parser.parse_args()
    lock_path = HERE / "inputs.lock.json"
    lock_bytes = lock_path.read_bytes()
    locked = json.loads(lock_bytes)["inputs"]
    urls = inputs()
    require(set(locked) == set(urls), "input list differs from reviewed lock")
    for name, url in urls.items():
        require(locked[name]["request_url"] == url, f"input URL differs from reviewed lock: {name}")
    CACHE.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        downloaded = list(pool.map(lambda item: download(item, args.offline, locked[item[0]]), urls.items()))
    data = {name: raw for name, raw, meta in downloaded}
    for name, expected in EXPECTED_ARTIFACTS.items():
        require(sha256(data[name]) == expected, f"artifact bytes changed: {name}")
    report = {"inspected_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "mode": "offline" if args.offline else "cached-or-online",
              "input_lock_sha256": sha256(lock_bytes),
              "inputs": {name: meta for name, raw, meta in downloaded}}
    for name in list(data):
        if name.endswith(".sha1"):
            expected = data[name].decode().strip().split()[0]
            require(hashlib.sha1(data[name[:-5]]).hexdigest() == expected, f"published SHA1 mismatch: {name}")
    report["published_sha1_checks"] = "all passed; consistency with same repository, not signature attestation"
    require(json.loads(data["uvc-tag.json"])["object"]["sha"] == UVC_RELEASE, "UVC release tag mismatch")
    require(json.loads(data["hutool-tag.json"])["object"]["sha"] == HUTOOL, "Hutool release tag mismatch")
    uvcz, uvci = inventory(data["uvc-sources.jar"])
    uvc_match = source_match(uvcz, data["uvc-release-tree.json"], "libuvccamera/src/main/java/")
    original_aar = (args.existing_artifacts / "UVCAndroid-1.0.13.aar").read_bytes()
    require(sha256(original_aar) == "4f1295a6c6b5e8590fb7dde3c60f30a94166db45c2d07efa63c85d5da8bfff17", "existing AAR hash mismatch")
    aar = zipfile.ZipFile(io.BytesIO(original_aar))
    classes = zipfile.ZipFile(io.BytesIO(aar.read("classes.jar")))
    classpath = "com/serenegiant/utils/FileUtils.class"
    javaentry = "com/serenegiant/utils/FileUtils.java"
    compare = json.loads(data["uvc-compare.json"])
    report["uvc"] = {
        "release_commit": commit_summary(data["uvc-release.json"]),
        "removal_commit": commit_summary(data["uvc-removal.json"]),
        "baseline_relation": {k: compare.get(k) for k in ("status", "ahead_by", "behind_by", "total_commits")},
        "comparison_commits": [{"sha": c["sha"], "message": c["commit"]["message"]} for c in compare.get("commits", [])],
        "source_archive": uvci, "release_source_comparison": uvc_match,
        "source_fileutils_equals_release": uvcz.read(javaentry) == data["uvc-release-FileUtils.java"],
        "generated_buildconfig": {"sha256": sha256(uvcz.read("com/serenegiant/uvccamera/BuildConfig.java")),
                                  "text": uvcz.read("com/serenegiant/uvccamera/BuildConfig.java").decode("utf8")},
        "source_fileutils_lines": selected_lines(uvcz.read(javaentry), "hutool|getMimeType"),
        "release_build_lines": selected_lines(data["uvc-release-build.gradle"], "hutool|libraryVersion"),
        "baseline_fileutils_lines": selected_lines(data["uvc-baseline-FileUtils.java"], "MimeTypeMap|getMimeType|hutool"),
        "fileutils_class_sha256": sha256(classes.read(classpath)),
        "fileutils_hutool_member_refs": class_info(classes.read(classpath))["hutool_refs"],
        "file_history": [{"sha": c["sha"], "date": c["commit"]["committer"]["date"], "message": c["commit"]["message"]} for c in json.loads(data["uvc-file-history.json"])],
    }
    hz, hi = inventory(data["hutool-core.jar"])
    hs, hsi = inventory(data["hutool-sources.jar"])
    references, external, packages = set(), {}, set()
    for name in hz.namelist():
        if name.endswith(".class"):
            packages.add(name.rsplit("/", 1)[0])
            info = class_info(hz.read(name))
            for ref in info["classes"]:
                ref = ref.lstrip("[")
                if ref.startswith("L") and ref.endswith(";"):
                    ref = ref[1:-1]
                references.add(ref)
                if "/" in ref and not ref.startswith(("cn/hutool/core/", "java/", "javax/", "sun/", "com/sun/")):
                    external.setdefault(ref, []).append(name)
    embedded_poms = {n: {"sha256": sha256(hz.read(n)), "equals_repository_pom": hz.read(n) == data["hutool-core.pom"], "parsed": pom(hz.read(n))}
                     for n in hz.namelist() if n.endswith("pom.xml")}
    source_notices = {}
    source_origins, native_loading_markers = {}, {}
    notice_pattern = r"(?i)(?:^\s*\*?\s*copyright)|licensed under|GNU (?:Lesser|General|Library)|permission is hereby|redistribution and use|SPDX|license to deal|copyright notice remain|Mulan PSL"
    origin_pattern = r"(?i)(?:来自|来源|改自|参考).*(?:Apache|Jodd|Commons|Spring|FastCSV|https?://|Dubbo|fastjson|mybatis|Hanlp)|\bported\b|\bcopy from\b|\bApache Commons\b"
    for name in hs.namelist():
        if name.endswith(".java"):
            raw = hs.read(name)
            marks = selected_lines(raw, notice_pattern)
            if marks:
                binary_name = name[:-5] + ".class"
                source_notices[name] = {"sha256": sha256(raw), "markers": marks,
                                       "corresponding_class_entry": binary_name,
                                       "class_present": binary_name in hz.namelist(),
                                       "class_sha256": sha256(hz.read(binary_name)) if binary_name in hz.namelist() else None}
            origin = selected_lines(raw, origin_pattern)
            if origin:
                source_origins[name] = origin
            native_load = selected_lines(raw, r"(?:System|Runtime\S*)\.load(?:Library)?\s*\(")
            if native_load:
                native_loading_markers[name] = native_load
    report["hutool"] = {
        "tag_commit": commit_summary(data["hutool-commit.json"]),
        "binary_archive": hi, "source_archive": hsi,
        "tag_source_comparison": source_match(hs, data["hutool-tree.json"], "hutool-core/src/main/java/"),
        "tag_license_sha256": sha256(data["hutool-LICENSE"]),
        "tag_license_text": data["hutool-LICENSE"].decode("utf8"),
        "repository_pom": pom(data["hutool-core.pom"]),
        "parent_pom": pom(data["hutool-parent.pom"]),
        "embedded_poms": embedded_poms,
        "binary_manifest": hz.read("META-INF/MANIFEST.MF").decode("utf8"),
        "source_notice_markers": source_notices,
        "source_origin_markers": source_origins,
        "source_native_loading_markers": native_loading_markers,
        "source_pom_comparison": {"parent_equals_repository": data["hutool-source-parent.pom"] == data["hutool-parent.pom"],
                                  "core_equals_repository": data["hutool-source-core.pom"] == data["hutool-core.pom"]},
        "class_packages": sorted(packages), "class_refs_outside_namespace_allowlist": external,
        "namespace_allowlist": ["cn/hutool/core/", "java/", "javax/", "sun/", "com/sun/"],
        "jdk_internal_class_refs": sorted(r for r in references if r.startswith(("sun/", "com/sun/"))),
        "desktop_or_optional_api_class_refs": sorted(r for r in references if r.startswith(("java/awt/", "javax/imageio/", "javax/swing/", "javax/naming/", "javax/tools/", "javax/xml/bind/"))),
        "constant_class_scan_limits": "Does not establish module availability, reachability, reflection-only references, or complete linkage.",
    }
    output = HERE / "trace.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf8")
    print(json.dumps({"report": str(output), "inputs": len(data), "uvc_matches": len(uvc_match["exact_matches"]),
                      "uvc_diff": len(uvc_match["different_bytes"]), "uvc_unmapped": len(uvc_match["unmapped_entries"]),
                      "hutool_sources": hsi["java_count"], "hutool_binary_classes": hi["class_count"],
                      "class_refs_outside_namespace_allowlist": len(external)}, ensure_ascii=False))

if __name__ == "__main__":
    main()

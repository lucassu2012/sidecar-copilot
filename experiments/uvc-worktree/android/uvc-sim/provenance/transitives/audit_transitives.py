"""Static Maven declaration inventory; never invokes Gradle or executes artifacts.

Outputs stay beside this script; downloaded bytes stay in the ignored uvc-sim/build tree.
The graph is the union of declared compile/runtime dependencies, including optional
edges, without conflict mediation or path-exclusion pruning. It is NOT a lockfile.
"""
import argparse
import collections
import concurrent.futures
import datetime
import hashlib
import io
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

OUT = pathlib.Path(__file__).resolve().parent
CACHE = OUT.parents[1] / "build" / "provenance" / "transitives"
INPUT_LOCK = OUT / "INPUT_LOCK.json"
ROOT = "com.herohan:UVCAndroid:1.0.13"
GOOGLE = "https://dl.google.com/dl/android/maven2/"
CENTRAL = "https://repo.maven.apache.org/maven2/"
EXPECTED = {
    "pom": "5b1ff27d872d1fa3720fa1e367bab05d6cf422695e7457fb44b5934be005add7",
    "module": "f22e2960d4eca439c19eebdc82d4ea51450735335fb889cded6511e1b07de20b",
    "aar": "4f1295a6c6b5e8590fb7dde3c60f30a94166db45c2d07efa63c85d5da8bfff17",
}
UVC_RELEASE_SOURCE = "6018c7f20238832929cf61427427bb7828056260"
AUSBC_SOURCE = "426ed0a5da2c826197761cc5a89ba3ae9c52bd7c"


def source_inventory(fetch):
    sources = [("UVCAndroid release 1.0.13", UVC_RELEASE_SOURCE, "shiyinghan/UVCAndroid", "libuvccamera/build.gradle"),
               ("AndroidUSBCamera", AUSBC_SOURCE, "jiangdongguo/AndroidUSBCamera", "build.gradle"),
               ("AndroidUSBCamera", AUSBC_SOURCE, "jiangdongguo/AndroidUSBCamera", "libausbc/build.gradle"),
               ("AndroidUSBCamera", AUSBC_SOURCE, "jiangdongguo/AndroidUSBCamera", "libuvc/build.gradle"),
               ("AndroidUSBCamera", AUSBC_SOURCE, "jiangdongguo/AndroidUSBCamera", "libnative/build.gradle")]
    result = []
    for label, revision, repo, path in sources:
        url = f"https://raw.githubusercontent.com/{repo}/{revision}/{path}"
        data = fetch.fetch(url)
        result.append({"label": label, "commit": revision, "url": url, "sha256": sha(data) if data else None,
                       "selected_declaration_lines": [line.strip() for line in data.decode().splitlines()
                           if re.search(r"implementation|\bapi |classpath|Version\s*=|versionNameString|hutool", line)] if data else []})
    return result


def sha(data):
    return hashlib.sha256(data).hexdigest()


def lock_record(record):
    fields = ("url", "status", "sha256", "bytes") if record["status"] == 200 else ("url", "status")
    return {key: record[key] for key in fields}


def initialize_input_lock():
    """Explicit trust bootstrap from the existing reviewed inventory; never replace a lock."""
    if INPUT_LOCK.exists():
        raise ValueError("input lock already exists; refusing to overwrite")
    data = (OUT / "inventory.json").read_bytes()
    inventory = json.loads(data)
    if inventory["coverage"]["overall"] != "complete_for_static_audit_scope":
        raise ValueError("cannot initialize input lock from incomplete inventory")
    records = inventory["raw_resources"]
    if any(r["status"] not in (200, 404) for r in records):
        raise ValueError("input lock requires observed 200/404 records only")
    expected = {r["url"]: lock_record(r) for r in records}
    fetch = Fetcher(False, expected)
    for url in sorted(expected):
        fetch.fetch(url)
        if fetch.records[url].get("status") != expected[url]["status"]:
            raise ValueError("cannot lock missing cached input: " + url)
    result = {"schema_version": 1, "purpose": "raw input integrity pins, not a dependency resolution lockfile",
              "bootstrap_inventory_sha256": sha(data), "resources": [expected[url] for url in sorted(expected)]}
    with INPUT_LOCK.open("x", encoding="utf-8") as target:
        target.write(json.dumps(result, indent=2) + "\n")
    print("Initialized immutable input pins:", len(expected), flush=True)


def read_input_lock():
    raw = INPUT_LOCK.read_bytes()
    records = json.loads(raw)["resources"]
    expected = {r["url"]: r for r in records}
    if len(expected) != len(records) or any(r["status"] not in (200, 404) for r in records):
        raise ValueError("invalid input lock records")
    return expected, sha(raw)


def text(element, path, default=""):
    value = element.findtext(path)
    return value.strip() if value is not None else default


def parse_xml(data):
    if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        raise ValueError("external entities/DOCTYPE are not accepted")
    root = ET.fromstring(data)
    for el in root.iter():
        el.tag = el.tag.split("}")[-1]
    return root


def expand(value, props):
    for _ in range(30):
        changed = re.sub(r"\$\{([^}]+)\}", lambda m: props.get(m[1], m[0]), value)
        if changed == value:
            return value
        value = changed
    return value


def exact_version(value):
    if re.fullmatch(r"\[[^,\[\]()]+\]", value):
        return value[1:-1], "singleton_range"
    if not value or re.search(r"[\[\](),+$\s]", value) or value in ("LATEST", "RELEASE") or value.endswith("-SNAPSHOT"):
        return None, "unresolved_range_dynamic_or_property"
    return value, "literal"


def dep_template(el, origin):
    row = {key: text(el, key) for key in ("groupId", "artifactId", "version", "scope", "type", "classifier", "optional")}
    row["exclusions"] = [{"groupId": text(e, "groupId"), "artifactId": text(e, "artifactId")}
                         for e in el.findall("exclusions/exclusion")]
    row["declared_in"] = origin
    return row


def dep_key(d):
    return (d["groupId"], d["artifactId"], d.get("type") or "jar", d.get("classifier") or "")


def merge_dependencies(inherited, own):
    result = {dep_key(d): dict(d) for d in inherited}
    for d in own:
        k = dep_key(d)
        merged = result.get(k, d).copy()
        for field, value in d.items():
            if value not in ("", []):
                merged[field] = value
        result[k] = merged
    return list(result.values())


class Fetcher:
    def __init__(self, online, input_lock=None):
        self.online = online
        self.records = {}
        self.input_lock = input_lock

    def check_pin(self, info):
        if self.input_lock is not None:
            expected = self.input_lock.get(info["url"])
            if expected is None:
                raise ValueError("URL absent from tracked input lock: " + info["url"])
            if info["status"] in (200, 404) and lock_record(info) != expected:
                raise ValueError("tracked input lock mismatch: " + info["url"])

    def fetch(self, url, limit=32 * 1024 * 1024):
        # Hash-based cache path prevents traversal and keeps credentials out of filenames.
        key = sha(url.encode())
        path = CACHE / (key + ".bin")
        receipt = CACHE / (key + ".json")
        if receipt.exists():
            info = json.loads(receipt.read_text("utf-8"))
            if info.get("url") != url:
                raise ValueError("cache receipt URL mismatch: " + url)
            self.check_pin(info)
            if info["status"] == 200:
                data = path.read_bytes()
                if sha(data) != info["sha256"]:
                    raise ValueError("cache hash mismatch: " + url)
                if len(data) != info.get("bytes", len(data)):
                    raise ValueError("cache byte count mismatch: " + url)
                self.records[url] = info
                return data
            if info["status"] == 404 or not self.online:
                self.records[url] = info
                return None
        if not self.online:
            self.records[url] = {"url": url, "status": "not_cached"}
            return None
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != "https" or parsed.hostname not in ("dl.google.com", "repo.maven.apache.org", "raw.githubusercontent.com"):
            raise ValueError("unapproved metadata host: " + url)
        for attempt in range(3):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "Sidecar-Static-Provenance/1.0"})
                with urllib.request.urlopen(req, timeout=30) as response:
                    final = urllib.parse.urlparse(response.url)
                    if final.hostname not in ("dl.google.com", "repo.maven.apache.org", "raw.githubusercontent.com"):
                        raise ValueError("unexpected redirect host")
                    data = response.read(limit + 1)
                    if len(data) > limit:
                        raise ValueError("download size bound exceeded")
                    info = {"url": url, "status": response.status, "sha256": sha(data), "bytes": len(data),
                            "retrieved_utc": datetime.datetime.now(datetime.timezone.utc).isoformat()}
                    self.check_pin(info)
                    CACHE.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(data)
                    receipt.write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
                    self.records[url] = info
                    return data
            except urllib.error.HTTPError as e:
                if e.code in (429, 500, 502, 503, 504) and attempt < 2:
                    time.sleep(attempt + 1)
                    continue
                info = {"url": url, "status": e.code}
                self.check_pin(info)
                CACHE.mkdir(parents=True, exist_ok=True)
                receipt.write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
                self.records[url] = info
                return None
            except (OSError, TimeoutError) as e:
                if attempt < 2:
                    time.sleep(attempt + 1)
                    continue
                self.records[url] = {"url": url, "status": "transport_error", "error": type(e).__name__}
                return None

    def artifact(self, gav, extension, classifier=""):
        for url in self.urls(gav, extension, classifier):
            data = self.fetch(url)
            if data is not None:
                if gav == ROOT and extension in EXPECTED and sha(data) != EXPECTED[extension]:
                    raise ValueError("root publication no longer matches recorded hash")
                return data, url
        return None, None

    def urls(self, gav, extension, classifier=""):
        group, artifact, version = gav.split(":")
        if not all(re.fullmatch(r"[A-Za-z0-9_.-]+", p) for p in (group, artifact, version)):
            raise ValueError("non-literal artifact coordinate: " + gav)
        suffix = "-" + classifier if classifier else ""
        relative = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}{suffix}.{extension}"
        repos = (GOOGLE, CENTRAL) if group.startswith(("androidx.", "com.android.")) else (CENTRAL, GOOGLE)
        return [base+relative for base in repos]

    def absence(self, gav, extension):
        attempts = [self.records.get(url, {"url": url, "status": "not_attempted"}) for url in self.urls(gav, extension)]
        return {"availability": "confirmed_absent_404" if all(r["status"] == 404 for r in attempts) else "unknown",
                "attempts": attempts}


class Audit:
    def __init__(self, fetcher):
        self.fetch = fetcher
        self.models = {}
        self.building = set()
        self.issues = []

    def model(self, gav):
        if gav in self.models:
            return self.models[gav]
        if gav in self.building:
            self.issues.append({"coordinate": gav, "kind": "parent_or_bom_cycle"})
            return None
        self.building.add(gav)
        data, url = self.fetch.artifact(gav, "pom")
        if data is None:
            self.issues.append({"coordinate": gav, "kind": "missing_pom"})
            self.building.remove(gav)
            self.models[gav] = None
            return None
        root = parse_xml(data)
        group, artifact, version = gav.split(":")
        parent_el = root.find("parent")
        own_props = {p.tag: p.text.strip() if p.text else "" for p in root.findall("properties/*")}
        parent, parent_gav = None, None
        if parent_el is not None:
            values = [expand(text(parent_el, k), own_props) for k in ("groupId", "artifactId", "version")]
            parent_gav = ":".join(values)
            if all(values) and not any("${" in v for v in values):
                parent = self.model(parent_gav)
            else:
                self.issues.append({"coordinate": gav, "kind": "unresolved_parent", "value": parent_gav})
        props = dict(parent["properties"] if parent else {})
        props.update(own_props)
        for prefix in ("project", "pom"):
            props.update({prefix+".groupId": group, prefix+".artifactId": artifact, prefix+".version": version})
            if parent_gav:
                pg, pa, pv = parent_gav.split(":")
                props.update({prefix+".parent.groupId": pg, prefix+".parent.artifactId": pa, prefix+".parent.version": pv})
        for _ in range(10):
            props = {k: expand(v, props) for k, v in props.items()}
        own_deps = [dep_template(d, gav) for d in root.findall("dependencies/dependency")]
        templates = merge_dependencies(parent["dependency_templates"] if parent else [], own_deps)
        management = dict(parent["management"] if parent else {})
        imports = []
        imported_management = {}
        local_management = []
        for el in root.findall("dependencyManagement/dependencies/dependency"):
            raw = dep_template(el, gav)
            dep = {k: expand(v, props) if isinstance(v, str) else v for k, v in raw.items()}
            if dep["type"] == "pom" and dep["scope"] == "import":
                bom = ":".join(dep[k] for k in ("groupId", "artifactId", "version"))
                imports.append(bom)
                bom_model = self.model(bom) if all(dep[k] for k in ("groupId", "artifactId", "version")) and "${" not in bom else None
                if bom_model:
                    # First imported BOM wins; local declarations override imports below.
                    for key, value in bom_model["management"].items():
                        imported_management.setdefault(key, value)
                else:
                    self.issues.append({"coordinate": gav, "kind": "unresolved_import", "value": bom})
            else:
                local_management.append(dep)
        management.update(imported_management)
        for dep in local_management:
            management[dep_key(dep)] = dep
        dependencies = []
        for raw in templates:
            dep = {k: expand(v, props) if isinstance(v, str) else v for k, v in raw.items()}
            managed = management.get(dep_key(dep), {})
            for k in ("version", "scope", "type", "classifier", "optional"):
                if not dep.get(k):
                    dep[k] = expand(managed.get(k, ""), props)
            dep["scope"] = dep["scope"] or "compile"
            dep["type"] = dep["type"] or "jar"
            dep["optional"] = dep["optional"] or "false"
            dep["exclusions"] = [{k: expand(v, props) for k, v in ex.items()} for ex in (dep.get("exclusions") or managed.get("exclusions", []))]
            dep["version_kind"] = exact_version(dep["version"])[1]
            fixed = exact_version(dep["version"])[0]
            dep["target"] = ":".join((dep["groupId"], dep["artifactId"], fixed)) if fixed and "${" not in dep["groupId"]+dep["artifactId"] else None
            dep["followed"] = dep["scope"] in ("compile", "runtime") and dep["target"] is not None
            dependencies.append(dep)
        own_licenses = [{"name": expand(text(el, "name"), props), "url": expand(text(el, "url"), props),
                         "distribution": text(el, "distribution"), "declared_in": gav}
                        for el in root.findall("licenses/license")]
        profiles = [{"id": text(el, "id"), "activation_xml": ET.tostring(el.find("activation"), encoding="unicode") if el.find("activation") is not None else None,
                     "dependencies": [dep_template(d, gav) for d in el.findall("dependencies/dependency")],
                     "management": [dep_template(d, gav) for d in el.findall("dependencyManagement/dependencies/dependency")],
                     "properties": {p.tag: p.text for p in el.findall("properties/*")}}
                    for el in root.findall("profiles/profile")]
        relocation = root.find("distributionManagement/relocation")
        relocated = None
        if relocation is not None:
            relocated = ":".join(text(relocation, k, d) for k, d in zip(("groupId", "artifactId", "version"), (group, artifact, version)))
        model = {"coordinate": gav, "pom_url": url, "pom_sha256": sha(data), "packaging": text(root, "packaging", "jar"),
                 "parent": parent_gav, "bom_imports": imports, "properties": props, "dependency_templates": templates,
                 "management": management, "dependencies": dependencies, "licenses": own_licenses or (parent["licenses"] if parent else []),
                 "profiles_not_activated": profiles, "relocation": relocated}
        self.models[gav] = model
        self.building.remove(gav)
        return model

    def run(self):
        pending = collections.deque([ROOT])
        graph = {}
        while pending:
            gav = pending.popleft()
            if gav in graph:
                continue
            if len(graph) > 500:
                raise ValueError("unexpected graph larger than 500 coordinates")
            model = self.model(gav)
            graph[gav] = model
            if model:
                for dep in model["dependencies"]:
                    if dep["followed"]:
                        pending.append(dep["target"])
                    elif dep["scope"] in ("compile", "runtime"):
                        self.issues.append({"coordinate": gav, "kind": "unresolved_dependency_version", "dependency": dep})
                if model["relocation"]:
                    pending.append(model["relocation"])
            if len(graph) % 20 == 0:
                print("POM graph coordinates:", len(graph), "queued:", len(pending), flush=True)
        return graph


def module_summary(fetch, gav):
    data, url = fetch.artifact(gav, "module")
    if data is None:
        return {"coordinate": gav, "present": False, **fetch.absence(gav, "module")}
    raw = json.loads(data)
    return {"coordinate": gav, "present": True, "availability": "present", "url": url, "sha256": sha(data),
            "formatVersion": raw.get("formatVersion"), "component": raw.get("component"),
            "createdBy": raw.get("createdBy"), "variants": raw.get("variants", [])}


def zip_notices(data, prefix="", depth=0, scan=None):
    found = []
    if scan is None:
        scan = collections.Counter()
    scan["archives_opened"] += 1
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        for entry in z.infolist():
            if entry.is_dir():
                continue
            scan["file_names_examined"] += 1
            if re.search(r"(?i)(license|notice|copying|copyright)", entry.filename):
                if entry.file_size > 2*1024*1024:
                    found.append({"path": prefix+entry.filename, "status": "notice_too_large", "bytes": entry.file_size})
                    continue
                contents = z.read(entry)
                found.append({"path": prefix+entry.filename, "bytes": len(contents), "sha256": sha(contents),
                              "text_preview": contents.decode("utf-8", "replace")[:240]})
            elif depth == 0 and entry.filename.endswith(".jar"):
                if entry.file_size > 24*1024*1024:
                    found.append({"path": prefix+entry.filename, "status": "nested_archive_too_large"})
                    continue
                found.extend(zip_notices(z.read(entry), prefix+entry.filename+"!/", depth+1, scan))
    return found


def binary_summary(fetch, gav, packaging):
    if packaging not in ("jar", "aar"):
        return {"coordinate": gav, "status": "non_runtime_archive_packaging", "packaging": packaging}
    data, url = fetch.artifact(gav, packaging)
    if data is None:
        return {"coordinate": gav, "status": "missing_archive", "packaging": packaging, **fetch.absence(gav, packaging)}
    scan = collections.Counter()
    notices = zip_notices(data, scan=scan)
    return {"coordinate": gav, "status": "inspected_zip", "packaging": packaging, "url": url,
            "bytes": len(data), "sha256": sha(data), "notice_entries": notices, "scan_counts": dict(scan)}


def additional_jvm_candidates(modules, graph):
    """Report literal JVM variant targets only; deliberately does not resolve Gradle."""
    result = []
    for module in modules:
        for variant in module.get("variants", []):
            attributes = variant.get("attributes", {})
            if attributes.get("org.gradle.usage") not in ("java-api", "java-runtime"):
                continue
            if attributes.get("org.jetbrains.kotlin.platform.type") not in (None, "jvm", "androidJvm"):
                continue
            declarations = [("dependency", d) for d in variant.get("dependencies", [])]
            if "available-at" in variant:
                declarations.append(("available-at", variant["available-at"]))
            for kind, dep in declarations:
                declaration = dep.get("version", {})
                version = declaration if isinstance(declaration, str) else declaration.get("strictly", declaration.get("requires", ""))
                exact, status = exact_version(version)
                target = ":".join((dep.get("group", ""), dep.get("module", ""), exact)) if exact else None
                if target not in graph:
                    result.append({"from": module["coordinate"], "variant": variant["name"], "kind": kind,
                                   "target": target, "version_status": status, "declaration": dep,
                                   "traversed": False})
    return result


def public_model(model):
    if model is None:
        return None
    return {k: v for k, v in model.items() if k not in ("management", "dependency_templates", "properties")} | {
        "dependency_management": list(model["management"].values())}


def write_report(result):
    models = result["pom_models"]
    root_graph = set(result["runtime_declaration_coordinates"])
    binaries = {b["coordinate"]: b for b in result["artifact_inspections"]}
    lines = ["# UVCAndroid published declaration and license inventory", "", "Generated by `audit_transitives.py`; inspect `inventory.json` for exact edges, origins, hashes, profiles, variants and unresolved conditions.", "",
             "This is a conservative Maven compile/runtime declaration union, including optional edges and every encountered literal version. It does not choose versions, prune path-specific exclusions, activate profiles or select Gradle variants. It is not a resolved Android classpath, license clearance or lockfile.", "",
             "| Measure | Observed |", "| --- | --- |"]
    lines.extend(f"| {k} | {v} |" for k, v in result["summary"].items())
    lines += ["", "Audit scope status: **"+result["coverage"]["overall"]+"**. This status applies only to the explicitly described static POM/module/archive inspection scope; actual Gradle resolution and license clearance remain not performed."]
    lines += ["", "Raw input pins: `INPUT_LOCK.json`, SHA-256 `"+result["input_lock_sha256"]+"`. These pins protect fetched bytes and observed 404 results; they do not select dependency versions."]
    candidates = result["additional_gradle_jvm_candidates_not_traversed"]
    lines += ["", "## Extra Gradle JVM candidates (not traversed)", "", "The following literal targets appear in JVM-compatible module variant declarations outside the POM runtime union. BOM metadata may already be inspected through POM imports. This list does not select variants or prove a Gradle closure.", ""]
    lines += ["- `"+target+"`" for target in sorted({c["target"] for c in candidates if c["target"]})]
    lines += ["", "## Version conflicts (not mediated)", "", "| Module | Declared concrete versions |", "| --- | --- |"]
    lines += [f"| {ga} | {', '.join(vs)} |" for ga, vs in result["version_conflicts"].items()]
    lines += ["", "## Artifact and effective POM license inventory", "", "Only the main artifact named by POM packaging is inspected here. Gradle platform/target archives and classifiers are not implicitly selected. A lack of notice-named entries is not evidence of missing text throughout the archive.", "",
              "| Coordinate | Effective declared license / origin | Archive notices | POM SHA-256 |", "| --- | --- | --- | --- |"]
    for gav in sorted(root_graph):
        model = models[gav]
        if model is None:
            lines.append(f"| {gav} | POM missing | not inspected | missing |")
            continue
        licenses = "; ".join(f"{l['name']} ({l['declared_in']})" for l in model["licenses"]) or "NO DECLARATION"
        if gav == "cn.hutool:hutool-core:5.8.35":
            licenses += "; POM-only: source also contains Apache headers and NeuQuant terms; see ../hutool report"
        binary = binaries.get(gav, {})
        count = len(binary.get("notice_entries", [])) if binary.get("status")=="inspected_zip" else binary.get("status", "not requested")
        lines.append(f"| {gav} | {licenses.replace('|', '/')} | {count} | `{model['pom_sha256']}` |")
    lines += ["", "## Parent and BOM provenance", "", "| Coordinate | Role | Effective licenses |", "| --- | --- | --- |"]
    for gav, model in sorted(models.items()):
        if model and gav not in root_graph:
            lines.append(f"| {gav} | parent / imported metadata | {'; '.join(l['name'] for l in model['licenses']) or 'NO DECLARATION'} |")
    lines += ["", "## Remaining boundaries", "", "- Gradle module variants and their dependencies, constraints, file hashes and available-at references are retained verbatim. Nonselected platform variants are not recursively resolved; Maven closure does not establish Gradle closure.",
              "- Test, provided, system and import dependencies are recorded but are not runtime traversal edges. Imported BOMs and parents are recursively inspected for metadata; optional runtime declarations are traversed conservatively.",
              "- General version ranges, dynamic versions, unresolved properties, missing POMs and relevant profiles are explicit issues. A singleton `[v]` is normalized to that exact version; multiple literal versions remain conflicts.",
              "- POM licenses may be inherited. They do not prove licensing of all embedded code/native libraries. Notices are inspected statically by names and hashes, without executing or extracting archives.",
              "- The root is the published 1.0.13 metadata, associated with source tag commit `6018c7f20238832929cf61427427bb7828056260`; it is not the later `fcdae5f` source tree. Source declaration snapshots/hashes are included in JSON. Producing-build reproducibility is not established.",
              "- Hutool 5.8.35's inherited MulanPSL-2.0 POM declaration is not its entire source-license inventory. The separate [Hutool provenance report](../hutool/README.md) records Apache-header files and NeuQuant-specific terms. See its JSON if the narrative has not yet been integrated.",
              "- Native source licenses, exact source revisions and the final source-build toolchain remain separate gates. This inventory cannot clear those gates.", ""]
    (OUT/"REPORT.md").write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--online", action="store_true", help="fetch missing pinned public metadata into ignored cache")
    parser.add_argument("--scan-artifacts", action="store_true", help="download/cache primary runtime declaration jars/aars and inspect ZIP notice names")
    parser.add_argument("--initialize-input-lock-from-inventory", action="store_true", help="explicit one-time input trust bootstrap; refuses overwrite")
    args = parser.parse_args()
    if args.initialize_input_lock_from_inventory:
        if args.online or args.scan_artifacts:
            parser.error("input lock initialization must run alone")
        initialize_input_lock()
        return 0
    input_lock, input_lock_sha = read_input_lock()
    fetch = Fetcher(args.online, input_lock)
    audit = Audit(fetch)
    graph = audit.run()
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        modules = list(pool.map(lambda gav: module_summary(fetch, gav), sorted(graph)))
    # Inflate one archive at a time so concurrent audit agents do not multiply peak memory.
    binaries = [binary_summary(fetch, gav, model["packaging"]) for gav, model in sorted(graph.items())
                if model] if args.scan_artifacts else []
    source_evidence = source_inventory(fetch)
    for module in modules:
        if module["availability"] == "unknown":
            audit.issues.append({"coordinate": module["coordinate"], "kind": "module_availability_unknown", "attempts": module["attempts"]})
        elif module["coordinate"] == ROOT and not module["present"]:
            audit.issues.append({"coordinate": ROOT, "kind": "required_root_module_missing"})
    for binary in binaries:
        if binary["status"] not in ("inspected_zip", "non_runtime_archive_packaging"):
            audit.issues.append({"coordinate": binary["coordinate"], "kind": "artifact_inspection_incomplete", "status": binary["status"]})
        if any(n.get("status") for n in binary.get("notice_entries", [])):
            audit.issues.append({"coordinate": binary["coordinate"], "kind": "notice_inspection_size_bound"})
    for source in source_evidence:
        if source["sha256"] is None:
            audit.issues.append({"kind": "candidate_source_unavailable", "url": source["url"]})
    conflicts = collections.defaultdict(set)
    for gav in graph:
        g, a, v = gav.split(":")
        conflicts[g+":"+a].add(v)
    profiles = [{"coordinate": g, "profile": p} for g, m in audit.models.items() if m
                for p in m["profiles_not_activated"] if p["dependencies"] or p["management"] or p["properties"]]
    if profiles:
        audit.issues.append({"kind": "profiles_require_evaluation", "count": len(profiles)})
    result = {
        "schema_version": 2, "root": ROOT, "input_lock_sha256": input_lock_sha,
        "release_source_commit": UVC_RELEASE_SOURCE,
        "candidate_source_evidence": source_evidence,
        "method": "static_pom_compile_runtime_union_no_mediation_optional_included_no_exclusion_pruning_no_profile_activation",
        "runtime_declaration_coordinates": sorted(graph),
        "pom_models": {g: public_model(m) for g, m in sorted(audit.models.items())},
        "gradle_module_declarations_not_resolved": modules,
        "additional_gradle_jvm_candidates_not_traversed": additional_jvm_candidates(modules, graph),
        "artifact_inspections": binaries,
        "version_conflicts": {g: sorted(v) for g, v in sorted(conflicts.items()) if len(v)>1},
        "issues": audit.issues, "profiles_requiring_resolution": profiles,
        "coverage": {"overall": "incomplete" if audit.issues else "complete_for_static_audit_scope",
                     "module_present": sum(m["present"] for m in modules),
                     "module_confirmed_absent_404": sum(m["availability"]=="confirmed_absent_404" for m in modules),
                     "module_unknown": sum(m["availability"]=="unknown" for m in modules),
                     "artifact_scan_requested": args.scan_artifacts,
                     "artifact_missing_or_failed": sum(b["status"] not in ("inspected_zip", "non_runtime_archive_packaging") for b in binaries),
                     "pom_missing": sum(m is None for m in audit.models.values()),
                     "input_integrity": "checked_against_tracked_INPUT_LOCK.json",
                     "actual_gradle_resolution": "not_performed", "distribution_clearance": "not_performed"},
        "raw_resources": [fetch.records[k] for k in sorted(fetch.records)],
        "summary": {"runtime coordinate versions": len(graph), "all POM models including parents/BOMs": len(audit.models),
                    "present Gradle module files": sum(m["present"] for m in modules),
                    "modules with multiple declared versions": sum(len(v)>1 for v in conflicts.values()),
                    "issues": len(audit.issues), "profiles with potentially relevant declarations": len(profiles),
                    "archives inspected": sum(b["status"]=="inspected_zip" for b in binaries),
                    "raw resources with bytes and SHA-256": sum(r.get("status")==200 for r in fetch.records.values())}}
    (OUT/"inventory.json").write_text(json.dumps(result, indent=2, ensure_ascii=False)+"\n", encoding="utf-8")
    write_report(result)
    print(json.dumps(result["summary"], indent=2), flush=True)
    if audit.issues:
        print("INCOMPLETE: inspect inventory.json issues; exit status 2", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())

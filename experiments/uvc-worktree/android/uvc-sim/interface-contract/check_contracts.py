"""Read-only source/contract replay. Does not execute generators, models or native code."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
BASELINE = "3dbbcb17f26816689d4b51b714996d937b9f4acd"
RECORD = HERE / "validation.json"
CONTRACTS = ("native-contract.json", "android-contract.json", "license-evidence.json")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def contained(root, relative):
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("Path escaped declared root: " + str(relative))
    return path


def load_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def verify_bytes(data, size, sha, label):
    if len(data) != size or digest(data) != sha:
        raise ValueError("Byte/hash mismatch: " + label)


def manifest():
    files = {}
    for path in sorted(HERE.rglob("*")):
        if not path.is_file() or path == RECORD or "__pycache__" in path.parts:
            continue
        if path.suffix not in {".py", ".md", ".json"} and path.name != ".gitattributes":
            raise ValueError("Unexpected deliverable: " + str(path))
        data = path.read_bytes()
        if "\0" in data.decode("utf-8") or "\ufffd" in data.decode("utf-8"):
            raise ValueError("Non-text/replacement character: " + str(path))
        files[path.relative_to(HERE).as_posix()] = {"bytes": len(data), "sha256": digest(data)}
    return files


def source_document(name, route_cache, new_cache, lock_hashes):
    doc = load_json(HERE / name)
    if doc["baseline_commit"] != BASELINE:
        raise ValueError("Unexpected document baseline: " + name)
    top_lock = doc.get("inherited_lock")
    if isinstance(top_lock, dict):
        if digest(contained(ROOT, top_lock["path"]).read_bytes()) != top_lock["sha256"]:
            raise ValueError("Top-level inherited lock differs: " + name)
    for entry in doc.get("inherited_artifacts", []):
        verify_bytes(contained(ROOT, entry["path"]).read_bytes(), entry["bytes"], entry["sha256"], entry["path"])
    companion = doc.get("companion_document")
    if companion:
        verify_bytes(contained(ROOT, companion["path"]).read_bytes(), companion["bytes"], companion["sha256"], companion["path"])
    texts, source_rows = {}, {}
    for source in doc["sources"]:
        ident = source["id"]
        if ident in texts:
            raise ValueError("Duplicate source identity: " + ident)
        row = None
        inherited = source.get("inherited_lock")
        if inherited:
            lock_path = contained(ROOT, inherited)
            lock_data = lock_path.read_bytes()
            lock_hashes[inherited] = digest(lock_data)
            if source.get("inherited_lock_sha256") and source["inherited_lock_sha256"] != digest(lock_data):
                raise ValueError("Inherited lock hash differs: " + inherited)
            lock = json.loads(lock_data)
            matches = [entry for entry in lock["inputs"]
                       if entry.get("name", entry.get("id")) == source["input_id"]]
            if len(matches) != 1:
                raise ValueError("Inherited input identity missing/ambiguous: " + ident)
            row = matches[0]
            if row["sha256"] != source["wire_sha256"]:
                raise ValueError("Inherited SHA disagrees: " + ident)
            if source.get("url") and source["url"] != row["url"]:
                raise ValueError("Inherited source URL disagrees: " + ident)
        cache_kind = source.get("cache_root", "route")
        if cache_kind not in {"route", "new"}:
            raise ValueError("Unknown source cache root: " + cache_kind)
        cache = route_cache if cache_kind == "route" else new_cache
        wire = contained(cache, source["cache_path"]).read_bytes()
        size = source.get("wire_bytes", source.get("bytes"))
        if size is None and row:
            size = row.get("bytes", row.get("size"))
        verify_bytes(wire, size, source["wire_sha256"], ident)
        encoding = source["encoding"]
        if encoding == "gitiles-base64":
            data = base64.b64decode(b"".join(wire.split()), validate=True)
        elif encoding == "utf-8":
            data = wire
        else:
            raise ValueError("Unexpected text encoding: " + encoding)
        if digest(data) != source["source_sha256"]:
            raise ValueError("Decoded source SHA differs: " + ident)
        text = data.decode("utf-8")
        if "\0" in text:
            raise ValueError("NUL/binary input rejected: " + ident)
        blob = source.get("git_blob_sha1")
        if blob:
            actual = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
            if actual != blob:
                raise ValueError("Git blob differs: " + ident)
        texts[ident] = text
        source_rows[ident] = source
    membership_checks = 0
    for ident, source in source_rows.items():
        member = source.get("tree_membership")
        if member:
            tree = json.loads(texts[member["tree_source_id"]])
            commit = json.loads(texts[member["commit_source_id"]])
            if tree.get("truncated") is not False or commit["tree"]["sha"] != tree["sha"]:
                raise ValueError("Tree binding incomplete: " + ident)
            if commit["sha"] != source["commit"]:
                raise ValueError("Commit binding differs: " + ident)
            entries = [entry for entry in tree["tree"] if entry["path"] == member["path"]]
            if len(entries) != 1 or entries[0]["type"] != "blob":
                raise ValueError("Tree blob absent/ambiguous: " + ident)
            if entries[0]["sha"] != member["git_blob_sha1"] or entries[0]["sha"] != source["git_blob_sha1"]:
                raise ValueError("Tree/blob binding differs: " + ident)
            membership_checks += 1
        elif not source.get("inherited_lock"):
            raise ValueError("New source lacks inherited or tree membership evidence: " + ident)
    anchors = 0
    rows = doc.get("contracts", doc.get("claims"))
    if not isinstance(rows, list) or not rows:
        raise ValueError("Missing contract/claim rows: " + name)
    for contract in rows:
        refs = contract["source_refs"]
        if not refs:
            raise ValueError("Contract without source references")
        for ref in refs:
            lines = texts[ref["source_id"]].splitlines()
            start, end = ref["start_line"], ref["end_line"]
            if type(start) is not int or type(end) is not int or not 1 <= start <= end <= len(lines):
                raise ValueError("Invalid source line interval")
            span = "\n".join(lines[start - 1:end])
            if not isinstance(ref["contains"], list) or not ref["contains"]:
                raise ValueError("Missing literal anchor")
            for needle in ref["contains"]:
                if not isinstance(needle, str) or not needle or needle not in span:
                    raise ValueError("Literal source anchor absent: " + ref["source_id"] + ":" + str(start))
                anchors += 1
    absence_count = 0
    for check in doc.get("absence_checks", []):
        actual = len(re.findall(check["regex"], texts[check["source_id"]]))
        if actual != check["expected_match_count"]:
            raise ValueError("Bounded text absence check differs: " + check["source_id"])
        absence_count += 1
    correspondence = doc.get("generation_correspondence")
    generation_result = None
    if correspondence:
        yaml_source = next(texts[k] for k, s in source_rows.items()
                           if s.get("source_path") == "standard-units.yaml")
        c_source = next(texts[k] for k, s in source_rows.items()
                        if s.get("source_path") == "src/ctrl-gen.c")
        controls = re.findall(r"^      ([a-z_]+):$", yaml_source, re.MULTILINE)
        functions = [{"kind": k, "name": n} for k, n in re.findall(
            r"^uvc_error_t uvc_(get|set)_([a-z_]+)\(", c_source, re.MULTILINE)]
        expected = [{"kind": k, "name": n} for n in controls for k in ("get", "set")]
        if (controls != correspondence["yaml_controls"] or functions != correspondence["c_functions"]
                or expected != correspondence["expected_c_functions"] or functions != expected
                or correspondence["regeneration_executed"] is not False
                or correspondence["full_generated_bytes_equality_claimed"] is not False):
            raise ValueError("Generated control name/order evidence disagrees")
        yaml_locations = [(i, re.fullmatch(r"      ([a-z_]+):", line).group(1))
                          for i, line in enumerate(yaml_source.splitlines(), 1)
                          if re.fullmatch(r"      ([a-z_]+):", line)]
        c_locations = {}
        for i, line in enumerate(c_source.splitlines(), 1):
            match = re.match(r"^uvc_error_t uvc_(get|set)_([a-z_]+)\(", line)
            if match:
                c_locations[match.groups()] = i
        matrix = [{"name": control, "yaml_line": line,
                   "get_line": c_locations[("get", control)], "set_line": c_locations[("set", control)]}
                  for line, control in yaml_locations]
        if matrix != correspondence["matrix"]:
            raise ValueError("Generated control location matrix differs")
        generation_result = {"yaml_controls": len(controls), "c_functions": len(functions),
                             "name_order_match": True, "generator_executed": False}
    return {"source_references": len(texts), "contracts_or_claims": len(rows),
            "literal_anchors_checked": anchors, "tree_membership_checks": membership_checks,
            "bounded_text_absence_checks": absence_count,
            "owner_recorded_static_negative_controls": len(doc.get("static_negative_controls", [])),
            "static_negative_controls_reexecuted": 0,
            "generation_name_comparison": generation_result}


def model_boundary():
    boundary = load_json(HERE / "verification-boundary.json")
    if boundary["baseline_commit"] != BASELINE:
        raise ValueError("Unexpected verification-boundary baseline")
    for entry in boundary["recorded_inputs"]:
        data = contained(ROOT, entry["path"]).read_bytes()
        verify_bytes(data, entry["bytes"], entry["sha256"], entry["path"])
    old = load_json(ROOT / "android/uvc-sim/protocol-research/validation.json")
    exploration = old["results"][1]["exploration"]
    counts = boundary["model_counts"]
    marker = counts["unittest"]["stdout_marker"]
    if marker not in old["results"][0]["stdout"]:
        raise ValueError("Recorded test summary differs")
    match = re.fullmatch(r"PROTOCOL_TESTS=(\d+) MODEL_NEGATIVE_CONTROLS=(\d+) ORACLE_MUTATION_CHECKS=(\d+) RESULT=PASS", marker)
    if not match:
        raise ValueError("Unrecognized recorded test summary")
    total, faults, mutations = map(int, match.groups())
    expected_counts = (counts["unittest"]["total"], counts["unittest"]["model_fault_methods"],
                       counts["unittest"]["oracle_mutation_methods"], counts["unittest"]["normal_methods"])
    if expected_counts != (total, faults, mutations, total - faults - mutations):
        raise ValueError("Recorded test classification differs")
    if (counts["bounds"] != exploration["bounds"]
            or counts["all_negative_controls_detected"] != exploration["all_negative_controls_detected"]
            or counts["native_closed_reached_in_each_positive_run"] != exploration["native_closed_reached_in_each_positive_run"]):
        raise ValueError("Recorded exploration scope differs")
    for expected, actual in zip(counts["positive_runs"], exploration["positive_runs"], strict=True):
        for key, value in expected.items():
            if actual[key] != value:
                raise ValueError("Recorded exploration count differs: " + key)
    policies = [run["faults"][0] for run in exploration["negative_controls"]]
    if counts["negative_fault_policies"] != policies:
        raise ValueError("Negative policy list differs")
    witnesses = [{key: row[key] for key in ("scenario", "native_closed")}
                 for row in exploration["directed_closed_witnesses"]]
    if counts["directed_closed_witnesses"] != witnesses:
        raise ValueError("Directed witnesses differ")
    for kind in ("identity", "cancel"):
        if counts["directed_" + kind + "_checks_passed"] != exploration["directed_" + kind + "_checks"]["checks_passed"]:
            raise ValueError("Directed check count differs")
    return {"recorded_inputs_checked": len(boundary["recorded_inputs"]),
            "old_model_executed": False, "counts_match_recorded_evidence": True}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--route-cache", type=Path,
                        default=ROOT.parent / "sidecar-uvc-route/android/uvc-sim/build/routes")
    parser.add_argument("--new-cache", type=Path,
                        default=HERE.parent / "build/interface-contract/licenses")
    parser.add_argument("--record", action="store_true", help="Create first record, refusing overwrite")
    args = parser.parse_args()
    before = manifest()
    if args.record:
        if RECORD.exists():
            raise ValueError("Record already exists; no silent overwrite")
        previous = None
    else:
        previous = load_json(RECORD)
        if previous["files"] != before:
            raise ValueError("Contract deliverables changed since record")
    lock_hashes = {}
    checks = {name: source_document(name, args.route_cache, args.new_cache, lock_hashes)
              for name in CONTRACTS}
    checks["model_boundary"] = model_boundary()
    checks["inherited_lock_sha256"] = lock_hashes
    if previous and previous["checks"] != checks:
        raise ValueError("Recomputed evidence differs from recorded evidence")
    if before != manifest():
        raise ValueError("Read-only audit changed deliverables")
    result = {"schema": 1, "baseline_commit": BASELINE,
              "observed_at_utc": datetime.now(timezone.utc).isoformat(),
              "python": sys.version, "scope": "Static text and previous-record verification only",
              "network_requests": 0, "upstream_generators_executed": 0,
              "protocol_model_runs": 0, "native_or_device_executions": 0,
              "files": before, "checks": checks}
    if args.record:
        RECORD.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                          encoding="utf-8", newline="\n")
    print(json.dumps(checks, ensure_ascii=False, indent=2))
    print("PASS: source anchors and prior records; no generator/model/native/device execution")


if __name__ == "__main__":
    main()

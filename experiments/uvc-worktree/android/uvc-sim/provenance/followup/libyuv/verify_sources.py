"""Static provenance comparisons only: no upstream extraction, builds or execution."""
import argparse
import collections
import datetime
import difflib
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE = ROOT / "build" / "native-research" / "libyuv"
TOKEN = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/|[A-Za-z_]\w*|0[xX][0-9a-fA-F]+|\d+|\S', re.S)
RENAMES = [("YUVTOBGR", "YUVTORGB"), ("BGRTOBGR8", "RGBTORGB8"), ("ABGR", "ARGB"), ("abgr", "argb")]
SYMBOLS = ["YUY2ToABGR", "MJPGToABGR", "ABGRToRGB565", "ABGRToYUY2", "YUVTOBGR", "BGRTOBGR8", "ABGRTORGB565"]


def digest(data):
    return hashlib.sha256(data).hexdigest()


def blob(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def canonical(data, kind):
    if kind == "raw":
        return data
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result
    obj = json.loads(data, object_pairs_hook=unique)
    if kind == "github-git-commit-v1":
        obj = {"sha": obj["sha"], "tree": obj["tree"]["sha"], "message": obj["message"],
               "parents": [p["sha"] for p in obj["parents"]],
               "author": {k: obj["author"][k] for k in ("name", "email", "date")},
               "committer": {k: obj["committer"][k] for k in ("name", "email", "date")}}
    else:
        raise ValueError("unknown integrity scheme: " + kind)
    return (json.dumps(obj, sort_keys=True, ensure_ascii=False, separators=(",", ":")) + "\n").encode()


def inputs(cache, fetch_missing):
    lock = json.loads((HERE / "inputs.json").read_text(encoding="utf-8"))
    loaded = {}
    for name, item in lock["inputs"].items():
        path = cache / name
        if not path.exists():
            if not fetch_missing:
                raise FileNotFoundError("missing locked cache input: " + name)
            request = urllib.request.Request(item["url"], headers={"User-Agent": "Sidecar-static-provenance/1.0"})
            with urllib.request.urlopen(request, timeout=60) as response:
                raw = response.read()
            if digest(canonical(raw, item["integrity"])) != item["sha256"]:
                raise ValueError("download integrity mismatch: " + name)
            path.write_bytes(raw)
        raw = path.read_bytes()
        value = canonical(raw, item["integrity"])
        if digest(value) != item["sha256"] or len(value) != item["size"]:
            raise ValueError("cache integrity mismatch: " + name)
        if item.get("git_blob") and blob(raw) != item["git_blob"]:
            raise ValueError("Git blob mismatch: " + name)
        loaded[name] = value
    return lock, loaded


def archive(data, prefix=""):
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as handle:
        return {member.name[len(prefix):]: handle.extractfile(member).read()
                for member in handle.getmembers() if member.isfile() and member.name.startswith(prefix)}


def tokens(text):
    return [m.group() for m in TOKEN.finditer(text) if not m.group().startswith(("//", "/*"))]


def fragment(raw, name, kind):
    text = raw.decode("utf-8")
    lines = text.splitlines(keepends=True)
    if kind in ("macro", "wrapper"):
        expression = r"^#define\s+" + re.escape(name) + r"\b" if kind == "macro" else r"^ANY\w*\(" + re.escape(name) + r"\b"
        matches = [i for i, line in enumerate(lines) if re.search(expression, line)]
        assert len(matches) == 1, (name, matches)
        start = end = matches[0]
        while lines[end].rstrip().endswith("\\"):
            end += 1
        return "".join(lines[start:end + 1]).encode(), start + 1, end + 1
    expression = r"^(?:static\s+)?(?:int|void|struct)\s+" + re.escape(name) + (r"\b" if kind == "struct" else r"\s*\(")
    matches = list(re.finditer(expression, text, re.M))
    assert len(matches) == 1, (name, len(matches))
    start = matches[0].start()
    if kind == "declaration":
        end = text.index(";", start) + 1
    else:
        # Strings and comments are masked before brace matching, preserving offsets.
        masked = list(text)
        for token in TOKEN.finditer(text):
            if token.group().startswith(('"', "'", "//", "/*")):
                masked[token.start():token.end()] = " " * len(token.group())
        clean = "".join(masked)
        opening = clean.index("{", start)
        depth = 1
        end = opening + 1
        while depth:
            depth += (clean[end] == "{") - (clean[end] == "}")
            end += 1
        if kind == "struct":
            assert text[end] == ";"
            end += 1
    return text[start:end].encode(), text.count("\n", 0, start) + 1, text.count("\n", 0, end) + 1


def compare_unit(unit, vendored, official):
    raw, start, end = fragment(vendored[unit["path"]], unit["name"], unit["kind"])
    original, old_start, old_end = fragment(official[unit["path"]], unit["upstream_name"], unit["kind"])
    normalized = raw.replace(b"\r\n", b"\n").decode()
    upstream = original.replace(b"\r\n", b"\n").decode()
    renames = list(RENAMES)
    if unit["name"] == "STOREABGR":
        renames = [("STOREABGR", "STORERGBA"), ("dst_abgr", "dst_rgba")]
    renamed = normalized
    for old, new in renames:
        renamed = renamed.replace(old, new)
    a, b = tokens(renamed), tokens(upstream)
    level = "original_bytes_equal" if raw == original else "lf_only_equal" if normalized == upstream else "rename_only_equal" if renamed == upstream else "rename_and_trivia_tokens_equal" if a == b else "related_but_tokens_differ"
    changes = []
    for tag, i, j, k, l in difflib.SequenceMatcher(None, b, a, autojunk=False).get_opcodes():
        if tag != "equal":
            changes.append({"operation": tag, "upstream_tokens": b[i:j], "vendored_renamed_tokens": a[k:l]})
    return dict(unit, vendored_lines=[start, end], upstream_lines=[old_start, old_end],
                level=level, raw_sha256=digest(raw), lf_sha256=digest(normalized.encode()),
                upstream_raw_sha256=digest(original), renamed_sha256=digest(renamed.encode()),
                renames=renames, renamed_token_sha256=digest(json.dumps(a).encode()),
                upstream_token_sha256=digest(json.dumps(b).encode()), token_differences=changes,
                token_similarity_round6=round(difflib.SequenceMatcher(None, b, a, autojunk=False).ratio(), 6),
                licensing_conclusion="No independent authorization inferred from content similarity")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fetch-missing", action="store_true", help="Download missing static inputs and verify before caching; default is offline")
    parser.add_argument("--cache-dir", type=Path, default=CACHE)
    parser.add_argument("--inputs-only", action="store_true", help="Validate input integrity without archive parsing/comparison")
    parser.add_argument("--write-report", action="store_true", help="Explicitly regenerate report.json")
    parser.add_argument("--check-report", action="store_true", help="Require regenerated evidence to equal the stored report except its observation timestamp")
    parser.add_argument("--report-file", type=Path, default=HERE / "report.json")
    args = parser.parse_args()
    cache = args.cache_dir.resolve()
    if not cache.is_relative_to(CACHE.resolve()):
        parser.error("cache must remain inside build/native-research/libyuv")
    cache.mkdir(parents=True, exist_ok=True)
    if args.check_report and args.write_report:
        parser.error("--check-report and --write-report are mutually exclusive")
    if args.inputs_only and (args.check_report or args.write_report):
        parser.error("--inputs-only cannot check or rewrite a source-comparison report")
    report_file = args.report_file.resolve()
    if not (report_file.is_relative_to(HERE) or report_file.is_relative_to(CACHE.resolve())):
        parser.error("report file must remain in the evidence directory or ignored cache")
    lock, loaded = inputs(cache, args.fetch_missing)
    if args.inputs_only:
        print("PASS", len(loaded), "locked static inputs")
        return
    prefix = "UVCAndroid-fcdae5f9a194e2111d57019176b53470a120415b/libuvccamera/src/main/jni/libyuv/"
    vendored = archive(loaded["uvcandroid.tar.gz"], prefix)
    official = archive(loaded["libyuv-official.tar.gz"])
    units = json.loads((HERE / "units.json").read_text())["units"]
    comparisons = [compare_unit(unit, vendored, official) for unit in units]
    files = []
    for path in sorted({unit["path"] for unit in units} | {"Android.mk"}):
        a = official[path].replace(b"\r\n", b"\n").decode().splitlines(keepends=True)
        b = vendored[path].replace(b"\r\n", b"\n").decode().splitlines(keepends=True)
        added = removed = 0
        added_positions = []
        for tag, i, j, k, l in difflib.SequenceMatcher(None, a, b, autojunk=False).get_opcodes():
            if tag in ("insert", "replace"):
                added += l-k
                added_positions.extend(range(k, l))
            if tag in ("delete", "replace"):
                removed += j-i
        header_a = re.match(r"/\*.*?\*/", "".join(a), re.S)
        header_b = re.match(r"/\*.*?\*/", "".join(b), re.S)
        coverage = collections.Counter()
        uncovered = []
        alignment_context = []
        for index in added_positions:
            line = b[index].strip()
            if path == "Android.mk":
                category = "build_recipe_diff"
            elif any(u["path"] == path and u["vendored_lines"][0] <= index + 1 <= u["vendored_lines"][1] for u in comparisons):
                category = "named_unit"
            elif not line or line.startswith("//"):
                category = "blank_or_comment"
            elif line == "LIBYUV_API":
                category = "export_marker"
            elif path == "source/row_any.cc" and (line.startswith("#if defined(HAS_") or line == "#endif"):
                category = "wrapper_conditional"
            else:
                # A line diff can align an old declaration's repeated argument tail
                # with the new declaration, reporting old lines as insertions. Only
                # classify this when the entire containing old unit is still exact.
                category = "unclassified"
                definitions = list(re.finditer(r"^(?:(?:static\s+)?(?:int|void)\s+(\w+)\s*\(|#define\s+(\w+)\b)", "".join(b[:index + 1]), re.M))
                if definitions:
                    match = definitions[-1]
                    name = match.group(1) or match.group(2)
                    try:
                        kind = "macro" if match.group(2) else "declaration" if path.endswith(".h") else "function"
                        retained, lo, hi = fragment(vendored[path], name, kind)
                        old_retained, _, _ = fragment(official[path], name, kind)
                        if lo <= index + 1 <= hi and retained.replace(b"\r\n", b"\n") == old_retained.replace(b"\r\n", b"\n"):
                            category = "identical_existing_unit_diff_alignment"
                            alignment_context.append({"line": index + 1, "unit": name, "unit_lf_sha256": digest(retained.replace(b"\r\n", b"\n"))})
                    except (AssertionError, ValueError):
                        pass
                if category == "unclassified":
                    uncovered.append({"line": index + 1, "text": line})
            coverage[category] += 1
        if uncovered:
            raise ValueError("New lines outside declared source units: " + json.dumps({"path": path, "lines": uncovered}))
        files.append({"path": path, "added": added, "removed": removed, "added_line_coverage": dict(coverage),
                      "unclassified_added_lines": uncovered, "unchanged_diff_alignment_context": alignment_context,
                      "vendored_blob": blob(vendored[path]),
                      "upstream_blob": blob(official[path]), "same_opening_comment_after_lf": bool(header_a and header_b and header_a.group() == header_b.group()),
                      "opening_comment_sha256": digest(header_b.group().encode()) if header_b else None})
    downstream = []
    for name, item in lock["inputs"].items():
        if item.get("compare_vendored_path"):
            ours = vendored[item["compare_vendored_path"]]
            value = loaded[name]
            downstream.append({"input": name, "path": item["compare_vendored_path"], "raw_equal": value == ours,
                               "lf_equal": value.replace(b"\r\n", b"\n") == ours.replace(b"\r\n", b"\n")})
    negative = [{"input": name, "matches": [s for s in SYMBOLS if s.encode() in data]}
                for name, data in loaded.items() if lock["inputs"][name].get("negative_symbol_scope")]
    report = {"schema": 1, "recorded_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "input_count": len(loaded), "input_lock_sha256": digest((HERE / "inputs.json").read_bytes()),
              "unit_spec_sha256": digest((HERE / "units.json").read_bytes()),
              "comparison_policy": "Original bytes, CRLF-only, explicit replacement then exact bytes, replacement plus C token equality excluding comments/outside-string whitespace, and remaining token differences are separate evidence levels. Strings/assembly register text and numeric constants remain tokens. No level proves authorship, permission or functional equivalence.",
              "unit_count": len(comparisons), "levels": dict(collections.Counter(x["level"] for x in comparisons)),
              "units": comparisons, "files": files, "downstream": downstream, "negative_symbol_scope": negative,
              "license_evidence": {"official": [{"path": name, "sha256": digest(official[name]), "git_blob": blob(official[name])} for name in ("LICENSE", "PATENTS", "AUTHORS")],
                  "wrapper_root_license_sha256": digest(loaded["import-root-LICENSE"]),
                  "downstream_root_license_sha256": digest(loaded["openterface-LICENSE"]),
                  "conclusion": "The unchanged LibYuv file headers and official historical documents are identifiable. Root Apache-2.0 texts and later copies do not independently establish the authorship or authorization of added code."},
              "commits": {name: json.loads(data) for name,data in loaded.items() if lock["inputs"][name]["integrity"] == "github-git-commit-v1"}}
    if args.write_report:
        report_file.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if args.check_report:
        stored = json.loads(report_file.read_text(encoding="utf-8"))
        comparable = dict(report)
        stored.pop("recorded_at_utc", None)
        comparable.pop("recorded_at_utc", None)
        if json.dumps(stored, sort_keys=True, ensure_ascii=False) != json.dumps(comparable, sort_keys=True, ensure_ascii=False):
            raise ValueError("Stored report differs from recomputed provenance evidence")
        print("PASS stored report equality (observation timestamp excluded)")
    print("PASS", len(loaded), "locked inputs;", len(comparisons), "named units;", report["levels"])
    print("Code/header additions:", sum(f["added"] for f in files if f["path"] != "Android.mk"))


if __name__ == "__main__":
    main()

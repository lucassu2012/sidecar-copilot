"""Stdlib-only evidence replay/guard checks, using ignored cache test copies."""
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

OUT = Path(__file__).resolve().parent
CACHE = OUT.parents[2] / "build" / "native-research" / "uac"
SCRIPT = OUT / "collect_followup.py"
ARTIFACTS = [OUT / "evidence.json", OUT / "retrieval-index.json"]


def digests():
    return [hashlib.sha256(path.read_bytes()).hexdigest() for path in ARTIFACTS]


def run(args, success, marker, command=None):
    before = digests()
    process = subprocess.run(command or [sys.executable, str(SCRIPT)] + args,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if (process.returncode == 0) != success or marker not in process.stdout + process.stderr:
        raise AssertionError(process.stdout + process.stderr)
    if before != digests():
        raise AssertionError("prior evidence overwritten")


def main():
    run(["--verify-only"], True, "PASS:")
    item = json.loads((OUT / "retrieval-index.json").read_text(encoding="utf-8"))[0]
    original = (CACHE / item["cache_file"]).read_bytes()
    with tempfile.TemporaryDirectory(prefix="replay-check-", dir=CACHE) as directory:
        child = Path(directory).resolve()
        assert child.is_relative_to(CACHE.resolve())
        # Missing inputs must fail without an attempted network fallback.
        run(["--cache-dir", str(child)], False, "offline cache missing")
        (child / "collection.lock").write_text("held-by-check", encoding="ascii")
        run(["--cache-dir", str(child)], False, "collection lock busy")
        assert (child / "collection.lock").read_text() == "held-by-check"
        (child / "collection.lock").unlink()
        tampered = bytearray(original)
        tampered[0] ^= 1
        (child / item["cache_file"]).write_bytes(tampered)
        run(["--cache-dir", str(child)], False, "cache integrity mismatch")
        run(["--online", "--cache-dir", str(child)], False, "cache integrity mismatch")
        assert not (child / "collection.lock").exists()
        # Recompute against a deliberately altered RESULT COPY, not the tracked
        # evidence. Import our own audit code with -B (no pycache); redirect only
        # its evidence directory to this ignored temporary fixture. Its normal
        # validated input cache and local baseline remain read-only inputs.
        altered = json.loads(ARTIFACTS[0].read_text(encoding="utf-8"))
        altered["scope"] = "deliberately altered result for negative check"
        (child / "evidence.json").write_text(json.dumps(altered), encoding="utf-8")
        (child / "retrieval-index.json").write_bytes(ARTIFACTS[1].read_bytes())
        wrapper = ("import importlib.util,pathlib,sys; "
                   "s=importlib.util.spec_from_file_location('audit',sys.argv[1]); "
                   "m=importlib.util.module_from_spec(s); s.loader.exec_module(m); "
                   "m.OUT=pathlib.Path(sys.argv[2]); sys.argv=[sys.argv[1],'--verify-only']; m.main()")
        run([], False, "recomputed evidence differs from tracked evidence.json",
            [sys.executable, "-B", "-c", wrapper, str(SCRIPT), str(child)])
    assert (CACHE / item["cache_file"]).read_bytes() == original
    print("PASS: full offline replay; missing/tampered cache rejected; online tamper rejected; lock respected; altered result rejected; prior evidence unchanged")


if __name__ == "__main__":
    main()

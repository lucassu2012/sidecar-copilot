"""Exercise cache guards using temporary copies under the ignored audit cache.

Uses only stdlib; invokes the audit parser, never a downloaded candidate binary.
No original cached file is changed. Run after the online evidence collection.
"""
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

OUT = Path(__file__).resolve().parent
CACHE = OUT.parents[1] / "build" / "provenance" / "uac"
SCRIPT = OUT / "collect_uac.py"
ARTIFACTS = [OUT / "static-evidence.json", OUT / "retrieval-index.json"]


def digests():
    return [hashlib.sha256(p.read_bytes()).hexdigest() for p in ARTIFACTS]


def run(arguments, success, marker):
    before = digests()
    process = subprocess.run([sys.executable, str(SCRIPT)] + arguments,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if (process.returncode == 0) != success or marker not in process.stdout + process.stderr:
        raise AssertionError("unexpected audit result: " + process.stdout + process.stderr)
    if before != digests():
        raise AssertionError("verification or failed collection changed prior evidence")


def main():
    run(["--offline", "--verify-only"], True, '"native_matches": 0')
    item = json.loads((OUT / "retrieval-index.json").read_text(encoding="utf-8"))[0]
    original = (CACHE / item["cache_file"]).read_bytes()
    with tempfile.TemporaryDirectory(prefix="integrity-check-", dir=CACHE) as directory:
        # The temporary directory is a verified child of the designated cache;
        # only that temporary directory is cleaned by TemporaryDirectory.
        child = Path(directory).resolve()
        assert child.is_relative_to(CACHE.resolve())
        run(["--offline", "--cache-dir", str(child)], False, "offline cache missing")
        copy = bytearray(original)
        copy[0] ^= 1
        (child / item["cache_file"]).write_bytes(copy)
        run(["--offline", "--cache-dir", str(child)], False, "cache integrity mismatch")
        # The online path must also reject a tampered pinned input before fetch.
        run(["--cache-dir", str(child)], False, "cache integrity mismatch")
    if (CACHE / item["cache_file"]).read_bytes() != original:
        raise AssertionError("original cache changed")
    print("PASS: offline full parse; missing cache; offline/online tamper rejection; evidence unchanged")


if __name__ == "__main__":
    main()

"""Project-owned, stdlib-only pixel contract experiment; never executes upstream code.

The equations are explicitly scoped to locked libyuv source branches. This is
neither a native implementation nor evidence that any vendor/SIMD path matches.
Run without --record to verify the committed numerical report without changing it.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
from pathlib import Path
import sys
import tarfile
import urllib.request

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
DEFAULT_CACHE = ROOT / "android/uvc-sim/build/feasibility/synthetic"
MAX_INT = (1 << 31) - 1  # Conservative bridge/JVM byte-array bound, not Python's limit.
OWNED_LIMIT = 7 * 1024 * 1024


def sha(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=True) + "\n").encode()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_bytes(data, entry):
    require(len(data) == entry["size"] and sha(data) == entry["sha256"],
            "locked input mismatch: " + entry["name"])
    return data


def load_locked(entry, cache, existing_caches, fetch=False, archive_seed=None):
    # First existing candidate is authoritative: corruption never falls back to
    # another cache or silently replaces evidence. Existing caches stay read-only.
    for directory in [cache, *existing_caches]:
        candidate = directory / entry["name"]
        if candidate.exists():
            return verify_bytes(candidate.read_bytes(), entry)
    if archive_seed and "archive_seed_member" in entry:
        for directory in [cache, *existing_caches]:
            candidate = directory / archive_seed["name"]
            if candidate.exists():
                data = verify_bytes(candidate.read_bytes(), archive_seed)
                with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
                    item = archive.getmember(entry["archive_seed_member"])
                    require(item.isfile(), "non-file archive member")
                    return verify_bytes(archive.extractfile(item).read(), entry)
    require(fetch, "offline input missing: " + entry["name"])
    with urllib.request.urlopen(entry["url"], timeout=30) as response:
        raw = response.read(entry["size"] * 2 + 256)
    # Gitiles gzip container bytes can vary on regeneration. The lock binds the
    # exact decoded source bytes at a fixed commit, never a relaxed archive hash.
    data = (base64.b64decode(b"".join(raw.split()), validate=True)
            if entry.get("transport_encoding") == "base64" else raw)
    verify_bytes(data, entry)
    cache.mkdir(parents=True, exist_ok=True)
    with (cache / entry["name"]).open("xb") as stream:
        stream.write(data)
    receipt = {"url": entry["url"], "transport_encoding": entry.get("transport_encoding", "raw"),
               "observed_transport_sha256": sha(raw), "observed_transport_size": len(raw),
               "verified_content_sha256": sha(data), "verified_content_size": len(data)}
    (cache / (entry["name"] + ".transport.json")).write_bytes(json_bytes(receipt))
    return data


def verify_sources(cache, existing_caches, fetch=False):
    lock = json.loads((HERE / "inputs.lock.json").read_text(encoding="utf-8"))
    verified = []
    for entry in lock["remote_inputs"]:
        load_locked(entry, cache, existing_caches, fetch, lock.get("legacy_archive_seed"))
        verified.append({k: entry[k] for k in ("name", "sha256", "size")})
    for entry in lock["local_inputs"]:
        verify_bytes((ROOT / entry["name"]).read_bytes(), entry)
        verified.append({k: entry[k] for k in ("name", "sha256", "size")})
    return {"lock_sha256": sha((HERE / "inputs.lock.json").read_bytes()),
            "verified_inputs": verified,
            "upstream_executed": False}


class ContractError(ValueError):
    pass


def guard(ok, reason):
    if not ok:
        raise ContractError(reason)


def checked_product(a, b):
    guard(type(a) is int and type(b) is int and a >= 0 and b >= 0,
          "nonnegative integer required")
    guard(a <= MAX_INT and b <= MAX_INT and (a == 0 or b <= MAX_INT // a),
          "bridge integer overflow")
    return a * b


def checked_sum(a, b):
    guard(0 <= a <= MAX_INT and 0 <= b <= MAX_INT - a, "bridge integer overflow")
    return a + b


def layout(width, height, stride, available, capacity, budget_available):
    guard(type(width) is int and type(height) is int and width > 0 and height > 0,
          "positive integer dimensions required")
    row_bytes = checked_product(width, 4)
    guard(type(stride) is int and stride >= row_bytes, "positive row stride too short")
    source_span = checked_sum(checked_product(height - 1, stride), row_bytes)
    tight_bytes = checked_product(row_bytes, height)
    guard(type(available) is int and source_span <= available <= MAX_INT,
          "source last row unavailable")
    guard(type(capacity) is int and tight_bytes <= capacity <= MAX_INT,
          "destination capacity too short")
    guard(tight_bytes <= budget_available, "owned budget exhausted")
    return source_span, tight_bytes


class Budget:
    def __init__(self, limit):
        self.limit, self.used, self.peak = limit, 0, 0

    def reserve(self, count):
        guard(0 <= count <= self.limit - self.used, "owned budget exhausted")
        self.used += count
        self.peak = max(self.peak, self.used)

    def release(self, count):
        guard(0 <= count <= self.used, "budget release underflow")
        self.used -= count


class Borrowed:
    """Deterministic lifetime model, not a thread synchronization primitive."""
    def __init__(self, storage):
        self.storage = storage
        self.valid = True

    def expire(self):
        self.valid = False

    def read_pixel(self, offset):
        guard(self.valid, "borrowed callback lifetime ended")
        return tuple(self.storage[offset:offset + 4])


class Owned:
    def __init__(self, data, budget):
        self.data, self.budget, self.released = data, budget, False

    def release(self):
        if not self.released:
            # Logical clearing of this buffer only; no Python/OS secure erase claim.
            for index in range(len(self.data)):
                self.data[index] = 0
            self.budget.release(len(self.data))
            self.released = True


def to_owned_rgba(borrowed, width, height, stride, encoding, capacity, budget,
                  before_pixel=None):
    guard(encoding in ("RGBX", "RGBA"), "unsupported byte layout")
    guard(borrowed.valid, "borrowed callback lifetime ended")
    _, size = layout(width, height, stride, len(borrowed.storage), capacity,
                     budget.limit - budget.used)
    budget.reserve(size)
    target = None
    try:
        target = bytearray(size)
        for y in range(height):
            for x in range(width):
                if before_pixel is not None:
                    before_pixel(y * width + x)
                r, g, b, a = borrowed.read_pixel(y * stride + 4 * x)
                pos = (y * width + x) * 4
                target[pos:pos + 4] = bytes((r, g, b, 255 if encoding == "RGBX" else a))
        guard(borrowed.valid, "borrowed callback lifetime ended")
        return Owned(target, budget)
    except BaseException:
        if target is not None:
            for index in range(len(target)):
                target[index] = 0
        budget.release(size)
        raise


def abgr_bytes_to_argb(rgba):
    guard(len(rgba) % 4 == 0, "incomplete RGBA pixel")
    result = bytearray(len(rgba))
    for pos in range(0, len(rgba), 4):
        r, g, b, a = rgba[pos:pos + 4]
        result[pos:pos + 4] = bytes((b, g, r, a))
    return bytes(result)


def argb_bytes_to_rgb565(argb):
    guard(len(argb) % 4 == 0, "incomplete ARGB pixel")
    result = bytearray()
    for pos in range(0, len(argb), 4):
        b, g, r, _ = argb[pos:pos + 4]
        word = (r // 8) * 2048 + (g // 4) * 32 + b // 8
        result.extend(word.to_bytes(2, "little"))
    return bytes(result)


def argb_bytes_to_yuy2(argb, width, height, branch="sum_round"):
    """Own scalar model of two named 8-bit-Y C coefficient branches only.

    sum_round: non-PAVGB half-coefficient UV; pavgb_truncate: x86-style UV.
    Both use 8-bit Y. The special historical MSVC RGB7 path is not modeled.
    Odd widths are explicitly rejected; no upstream coalescing is emulated.
    """
    guard(type(width) is int and type(height) is int and width > 0 and height > 0,
          "positive integer dimensions required")
    guard(width % 2 == 0, "YUY2 candidate requires even width")
    guard(branch in ("sum_round", "pavgb_truncate"), "unknown coefficient branch")
    guard(len(argb) == checked_product(checked_product(width, height), 4),
          "YUY2 input size mismatch")
    result = bytearray()
    for row in range(height):
        for column in range(0, width, 2):
            pos = (row * width + column) * 4
            b0, g0, r0, _a0, b1, g1, r1, _a1 = argb[pos:pos + 8]
            y0 = (66*r0 + 129*g0 + 25*b0 + 4224) // 256
            y1 = (66*r1 + 129*g1 + 25*b1 + 4224) // 256
            if branch == "sum_round":
                u = (56*(b0+b1) - 37*(g0+g1) - 19*(r0+r1) + 32896) // 256
                v = (56*(r0+r1) - 47*(g0+g1) - 9*(b0+b1) + 32896) // 256
            else:
                r, g, b = (r0+r1+1)//2, (g0+g1+1)//2, (b0+b1+1)//2
                u = (112*b - 74*g - 38*r + 32768) // 256
                v = (112*r - 94*g - 18*b + 32768) // 256
            result.extend((y0, u, y1, v))
    return bytes(result)


def rgba_bytes(pixels):
    return bytes(value for pixel in pixels for value in pixel)


def experiment():
    patterns, checks = {}, []

    def pattern(name, data, **metadata):
        patterns[name] = {"size": len(data), "sha256": sha(data), **metadata}
        if len(data) <= 128:
            patterns[name]["hex"] = bytes(data).hex()
        return data

    def check(name, actual, expected):
        require(actual == expected, "oracle failed: " + name + ": " + repr(actual))
        checks.append({"name": name, "actual": actual, "expected": expected})

    def rejects(name, operation, expected_message):
        try:
            operation()
        except ContractError as error:
            check(name, str(error), expected_message)
        else:
            raise ValueError("missing rejection: " + name)

    colors = [(0, 0, 0), (255, 255, 255), (255, 0, 0), (0, 255, 0),
              (0, 0, 255), (255, 255, 0), (0, 255, 255), (255, 0, 255)]
    color_rgba = pattern("opaque_color_bars", rgba_bytes([(*rgb, 255) for rgb in colors]),
                         width=8, height=1, row_stride=32, encoding="RGBA")
    color_rgbx = pattern("arbitrary_x_color_bars",
                         rgba_bytes([(*rgb, index*31) for index, rgb in enumerate(colors)]),
                         width=8, height=1, row_stride=32, encoding="RGBX")
    budget = Budget(1024)
    owned = to_owned_rgba(Borrowed(bytearray(color_rgbx)), 8, 1, 32, "RGBX", 32, budget)
    check("RGBX color bars become opaque RGBA", owned.data.hex(), color_rgba.hex())
    check("blindly preserving X fails the opaque oracle", color_rgbx == color_rgba, False)
    owned.release()

    # Deliberately unequal channels, alpha, width=3, two padding sentinels per row.
    # Final row has no trailing padding: exact valid span is 26, not stride*H=28.
    padded = pattern("odd_width_padded_rgba", bytes.fromhex(
        "11223300557799ffabcdef7fdead" "fe01cd811234564280402003"),
        width=3, height=2, row_stride=14, encoding="RGBA", final_row_padding=False)
    expected_tight = bytes.fromhex("11223300557799ffabcdef7ffe01cd811234564280402003")
    view = Borrowed(bytearray(padded))
    owned = to_owned_rgba(view, 3, 2, 14, "RGBA", 24, budget)
    check("odd width and padded stride preserve RGBA", owned.data.hex(), expected_tight.hex())
    check("tight-stride assumption is discriminated", padded[:24] == expected_tight, False)
    check("explicit RGBA alpha preserved", list(owned.data[3::4]), [0, 255, 127, 129, 66, 3])
    expected_argb = bytes.fromhex("33221100997755ffefcdab7fcd01fe815634124220408003")
    check("ABGR register name is RGBA bytes; ARGB is BGRA bytes",
          abgr_bytes_to_argb(owned.data).hex(), expected_argb.hex())
    view.storage[:] = bytes([0xA5]) * len(view.storage)
    view.expire()
    check("owned pixels survive native-pool overwrite", owned.data.hex(), expected_tight.hex())
    rejects("expired borrowed read", lambda: view.read_pixel(0), "borrowed callback lifetime ended")
    rejects("expired borrowed copy", lambda: to_owned_rgba(view, 3, 2, 14, "RGBA", 24, budget),
            "borrowed callback lifetime ended")
    owned.release()
    owned.release()
    check("idempotent owned release returns budget", budget.used, 0)
    check("released modeled storage is zeroed", list(set(owned.data)), [0])

    interrupted = Borrowed(bytearray(color_rgba))
    rejects("lifetime expires during controlled copy",
            lambda: to_owned_rgba(interrupted, 8, 1, 32, "RGBA", 32, budget,
                                 lambda index: interrupted.expire() if index == 3 else None),
            "borrowed callback lifetime ended")
    check("interrupted copy releases reservation", budget.used, 0)

    invalid = [
        ("zero width", (0, 1, 4, 4, 4, 4), "positive integer dimensions required"),
        ("negative height", (1, -1, 4, 4, 4, 4), "positive integer dimensions required"),
        ("short stride", (3, 2, 11, 24, 24, 24), "positive row stride too short"),
        ("last byte unavailable", (3, 2, 14, 25, 24, 24), "source last row unavailable"),
        ("short destination", (3, 2, 14, 26, 23, 24), "destination capacity too short"),
        ("budget one byte short", (3, 2, 14, 26, 24, 23), "owned budget exhausted"),
        ("width times four overflow", (536870912, 1, MAX_INT, MAX_INT, MAX_INT, MAX_INT),
         "bridge integer overflow"),
        ("last row offset overflow", (1, 3, 1073741824, MAX_INT, MAX_INT, MAX_INT),
         "bridge integer overflow"),
        ("last row end overflow", (1, 2, MAX_INT-2, MAX_INT, MAX_INT, MAX_INT),
         "bridge integer overflow"),
        ("height times row overflow", (1, 536870912, 4, MAX_INT, MAX_INT, MAX_INT),
         "bridge integer overflow"),
    ]
    for name, arguments, message in invalid:
        rejects(name, lambda args=arguments: layout(*args), message)
    check("valid final row needs no padding", list(layout(3, 2, 14, 26, 24, 24)), [26, 24])
    small_budget = Budget(23)
    rejects("budget refusal occurs before pixel access",
            lambda: to_owned_rgba(Borrowed(bytearray(padded)), 3, 2, 14, "RGBA", 24, small_budget,
                                 lambda _: (_ for _ in ()).throw(ValueError("should not read"))),
            "owned budget exhausted")
    check("budget refusal leaves zero live bytes", small_budget.used, 0)

    rgb565 = argb_bytes_to_rgb565(abgr_bytes_to_argb(color_rgba))
    check("RGB565 named-color little-endian oracle", rgb565.hex(), "0000ffff00f8e0071f00e0ffff071ff8")
    check("missing R/B shuffle fails red-blue oracle",
          argb_bytes_to_rgb565(color_rgba) == rgb565, False)
    odd_red = pattern("odd_rgb565_tail", rgba_bytes([(0,255,0,255), (0,0,255,255), (255,0,0,255)]),
                      width=3, height=1, encoding="RGBA")
    check("RGB565 odd tail retains red", argb_bytes_to_rgb565(abgr_bytes_to_argb(odd_red)).hex(),
          "e0071f0000f8")
    rejects("YUY2 rejects odd width before conversion",
            lambda: argb_bytes_to_yuy2(abgr_bytes_to_argb(odd_red), 3, 1),
            "YUY2 candidate requires even width")
    ramp = pattern("gray_0_to_255", rgba_bytes([(v, v, v, 255) for v in range(256)]),
                   width=256, height=1, encoding="RGBA")
    ramp565 = argb_bytes_to_rgb565(abgr_bytes_to_argb(ramp))
    errors = [[], [], []]
    for value in range(256):
        word = int.from_bytes(ramp565[2*value:2*value+2], "little")
        # Left-shift reconstruction measures discarded low bits, not a display's
        # bit-replication/normalized expansion or perceptual error.
        reconstructed = ((word // 2048) * 8, ((word // 32) % 64) * 4, (word % 32) * 8)
        for channel in range(3):
            errors[channel].append(value - reconstructed[channel])
    quantization = {"reconstruction": "zero-fill discarded low bits",
                    "max_discarded_R_G_B": [max(e) for e in errors],
                    "sum_discarded_R_G_B": [sum(e) for e in errors],
                    "distinct_gray_codes": len(set(ramp565[i:i+2] for i in range(0, 512, 2))),
                    "output_sha256": sha(ramp565)}
    check("RGB565 quantization discarded-bit bounds", quantization["max_discarded_R_G_B"], [7, 3, 7])
    check("RGB565 ramp discarded-bit sums", quantization["sum_discarded_R_G_B"], [896, 384, 896])
    check("RGB565 gray ramp collisions", quantization["distinct_gray_codes"], 64)

    before = pattern("one_pixel_change_before", rgba_bytes([(64, 64, 64, 255)] * 3), width=3, height=1)
    after = pattern("one_pixel_change_after", rgba_bytes([(64, 64, 64, 255), (65, 64, 64, 255),
                                                         (64, 64, 64, 255)]), width=3, height=1)
    check("owned RGBA retains a one-channel one-unit difference", before == after, False)
    check("RGB565 can erase that small change",
          argb_bytes_to_rgb565(abgr_bytes_to_argb(before)) ==
          argb_bytes_to_rgb565(abgr_bytes_to_argb(after)), True)

    yuv_expected = [[16,128,128], [235,128,128], [82,90,240], [144,54,34],
                    [41,240,110], [210,16,146], [169,166,16], [107,202,222]]
    yuv_actual = []
    for rgb in colors:
        pair = rgba_bytes([(*rgb, 255), (*rgb, 0)])
        y0, u, y1, v = argb_bytes_to_yuy2(abgr_bytes_to_argb(pair), 2, 1)
        require(y0 == y1, "same RGB must have same Y irrespective of alpha")
        yuv_actual.append([y0, u, v])
    check("8-bit Y and sum_round UV solid color oracle", yuv_actual, yuv_expected)

    pairs = [((255,0,0), (0,0,255)), ((255,0,0), (0,255,0)),
             ((0,255,0), (0,0,255)), ((0,0,0), (255,255,255)),
             ((255,0,0), (255,255,255)), ((0,0,0), (0,0,2))]
    pair_bytes = pattern("horizontal_chroma_pairs",
                         rgba_bytes([(*rgb, 255) for pair in pairs for rgb in pair]),
                         width=2, height=6, row_stride=8, encoding="RGBA")
    yuy2 = argb_bytes_to_yuy2(abgr_bytes_to_argb(pair_bytes), 2, 6)
    pair_result = [list(yuy2[i:i+4]) for i in range(0, len(yuy2), 4)]
    check("YUY2 horizontal-only chroma with packed Y0 U Y1 V", pair_result,
          [[82,165,41,175], [82,72,144,137], [144,147,41,72],
           [16,128,235,128], [82,109,235,184], [16,128,16,128]])
    x86 = argb_bytes_to_yuy2(abgr_bytes_to_argb(pair_bytes), 2, 6, "pavgb_truncate")
    check("8-bit-Y pavgb_truncate differs on red-white V", list(x86[16:20]), [82,109,235,183])
    check("quantize UV before averaging is detectably wrong", (128+129+1)//2, 129)
    check("correct black/dim-blue shared U", yuy2[-3], 128)
    chroma_a = pattern("equal_quantized_y_chroma_order_a", bytes.fromhex("ff0000ff008200ff"), width=2, height=1)
    chroma_b = pattern("equal_quantized_y_chroma_order_b", bytes.fromhex("008200ffff0000ff"), width=2, height=1)
    out_a = argb_bytes_to_yuy2(abgr_bytes_to_argb(chroma_a), 2, 1)
    out_b = argb_bytes_to_yuy2(abgr_bytes_to_argb(chroma_b), 2, 1)
    check("different per-pixel chroma order is lost in YUY2", out_a.hex(), "525a52a0")
    check("swapped equal-quantized-Y colors collide in YUY2", out_b.hex(), "525a52a0")
    check("RGBA distinguishes the chroma-order inputs", chroma_a == chroma_b, False)

    neutral_y = [0, 16, 128, 235, 255]
    # Independent ideal neutral-axis interpretation, not emulation of upstream
    # integer YUV-to-RGB rows. Full-range gray has RGB=Y. Limited uses 219 codes.
    limited = [max(0, min(255, ((y-16)*255 + 109)//219)) for y in neutral_y]
    check("limited versus full neutral-axis level oracle", limited, [0,0,130,255,255])
    range_prediction = {"Y_samples": neutral_y, "UV": [128,128],
                        "full_range_ideal_RGB_gray": neutral_y,
                        "limited_range_ideal_RGB_gray": limited,
                        "kind": "analytic range prediction; not native decode output"}

    frame_bytes = 640*360*4
    modeled_budget = Budget(OWNED_LIMIT)
    for _ in range(7):
        modeled_budget.reserve(frame_bytes)
    rejects("eighth 640x360 owned payload exceeds 7 MiB",
            lambda: modeled_budget.reserve(frame_bytes), "owned budget exhausted")
    check("seven payload reservations", modeled_budget.used, 6451200)
    for _ in range(7):
        modeled_budget.release(frame_bytes)
    check("all payload reservations returned", modeled_budget.used, 0)
    rejects("single 1080 RGBA owned copy exceeds fixture budget",
            lambda: layout(1920, 1080, 7680, 8294400, 8294400, OWNED_LIMIT),
            "owned budget exhausted")
    memory = []
    for width, height in [(640,360), (1920,1080), (3840,2160)]:
        pixels = width*height
        aligned_row = ((width+63)//64)*64
        aligned_frame = ((pixels+63)//64)*64
        memory.append({"width": width, "height": height,
                       "borrowed_RGBA_payload": 4*pixels, "one_owned_RGBA": 4*pixels,
                       "source_plus_one_owned": 8*pixels,
                       "fits_fixture_7MiB_owned": 4*pixels <= OWNED_LIMIT,
                       "max_whole_payloads_in_fixture_budget": OWNED_LIMIT//(4*pixels),
                       "RGB565_or_even_YUY2_output": 2*pixels,
                       "row_composed_YUY2_source_plus_output_plus_scratch": 6*pixels+4*width+2*aligned_row,
                       "whole_frame_composed_YUY2_source_temp_output_scratch": 10*pixels+2*aligned_frame,
                       "row_scratch_ARGB_plus_YUV": 4*width+2*aligned_row})
    return {"schema": 1, "checks_passed": len(checks), "checks": checks,
            "patterns": patterns, "rgb565_quantization": quantization,
            "yuy2": {"sum_round_pairs": pair_result, "sum_round_sha256": sha(yuy2),
                     "pavgb_truncate_hex": x86.hex(), "pavgb_truncate_sha256": sha(x86)},
            "range_prediction": range_prediction, "memory_payload_estimates": memory,
            "limits": ["No upstream code, native build, device, JNI, SIMD, or decoder executed.",
                       "Python arithmetic is unbounded; explicit checks model a signed 32-bit bridge bound.",
                       "Budget is payload accounting only; excludes Python objects, decoder, USB, JNI, pools, surfaces and pipeline copies.",
                       "Borrow lifetime is deterministic single-thread modeling, not a concurrency/stop proof.",
                       "8-bit Y modeled; special MSVC RGB7 branch and CPU dispatch are not.",
                       "No resizing, text readability, capture FPS, throughput, latency or acceptance claim."]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, default=DEFAULT_CACHE)
    parser.add_argument("--existing-cache", type=Path, action="append", default=[])
    parser.add_argument("--fetch", action="store_true", help="download missing locked static inputs only")
    parser.add_argument("--record", action="store_true", help="explicitly replace own numerical baseline")
    parser.add_argument("--output", type=Path, help="also write recomputed JSON to a review file")
    args = parser.parse_args()
    source_evidence = verify_sources(args.cache, args.existing_cache, args.fetch)
    result = experiment()
    result["source_evidence"] = source_evidence
    result["experiment_script_sha256"] = sha(Path(__file__).read_bytes())
    data = json_bytes(result)
    expected = HERE / "results.json"
    if args.record:
        expected.write_bytes(data)
    else:
        require(expected.read_bytes() == data, "stored results differ from replay")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(data)
    print(json.dumps({"checks_passed": result["checks_passed"], "patterns": len(result["patterns"]),
                      "results_sha256": sha(data), "stored_results_match": True,
                      "native_executed": False}))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, tarfile.TarError) as error:
        print("FAIL: " + str(error), file=sys.stderr)
        raise SystemExit(1)

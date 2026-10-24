#!/usr/bin/env python3
"""Reference implementation of the Mango Cast media layer (docs/PROTOCOL.md §4).

Generates protocol/test-vectors.json, which the Swift and Kotlin test suites
check their own implementations against.

    python3 tools/mango_ref.py            # rewrite the vectors
    python3 tools/mango_ref.py --check    # self-test only
"""
import json
import math
import os
import struct
import sys

# --- GF(2^8) ----------------------------------------------------------------

POLY = 0x11D
EXP = [0] * 512
LOG = [0] * 256
_x = 1
for _i in range(255):
    EXP[_i] = _x
    LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= POLY
for _i in range(255, 512):
    EXP[_i] = EXP[_i - 255]


def gmul(a, b):
    if a == 0 or b == 0:
        return 0
    return EXP[LOG[a] + LOG[b]]


def gdiv(a, b):
    if b == 0:
        raise ZeroDivisionError
    if a == 0:
        return 0
    return EXP[(LOG[a] - LOG[b]) % 255]


def gexp(a, n):
    if n == 0:
        return 1
    if a == 0:
        return 0
    return EXP[(LOG[a] * n) % 255]


def mat_mul(a, b):
    rows, inner, cols = len(a), len(b), len(b[0])
    out = [[0] * cols for _ in range(rows)]
    for r in range(rows):
        for c in range(cols):
            v = 0
            for i in range(inner):
                v ^= gmul(a[r][i], b[i][c])
            out[r][c] = v
    return out


def mat_inv(m):
    n = len(m)
    work = [row[:] + [1 if i == j else 0 for j in range(n)] for i, row in enumerate(m)]
    for col in range(n):
        pivot = next((r for r in range(col, n) if work[r][col] != 0), None)
        if pivot is None:
            raise ValueError("singular matrix")
        work[col], work[pivot] = work[pivot], work[col]
        inv = gdiv(1, work[col][col])
        work[col] = [gmul(v, inv) for v in work[col]]
        for r in range(n):
            if r != col and work[r][col] != 0:
                f = work[r][col]
                work[r] = [v ^ gmul(f, p) for v, p in zip(work[r], work[col])]
    return [row[n:] for row in work]


def encoding_matrix(k, m):
    n = k + m
    vand = [[gexp(r, c) for c in range(k)] for r in range(n)]
    top_inv = mat_inv(vand[:k])
    return mat_mul(vand, top_inv)


def rs_encode(data_shards, m):
    k = len(data_shards)
    size = len(data_shards[0])
    e = encoding_matrix(k, m)
    parity = []
    for j in range(m):
        row = e[k + j]
        out = bytearray(size)
        for c in range(k):
            coef = row[c]
            if coef == 0:
                continue
            shard = data_shards[c]
            for i in range(size):
                out[i] ^= gmul(coef, shard[i])
        parity.append(bytes(out))
    return parity


def rs_decode(shards, k, m):
    """shards: list of length k+m with None for missing. Returns k data shards."""
    e = encoding_matrix(k, m)
    present = [i for i, s in enumerate(shards) if s is not None][:k]
    if len(present) < k:
        raise ValueError("not enough shards")
    sub_inv = mat_inv([e[i] for i in present])
    size = len(shards[present[0]])
    data = []
    for r in range(k):
        out = bytearray(size)
        for c, idx in enumerate(present):
            coef = sub_inv[r][c]
            if coef == 0:
                continue
            src = shards[idx]
            for i in range(size):
                out[i] ^= gmul(coef, src[i])
        data.append(bytes(out))
    return data


# --- Packetizer --------------------------------------------------------------

MAX_DATAGRAM = 1400
HEADER = 32
MAX_SHARD = MAX_DATAGRAM - HEADER
MAX_K = 128


def layout(length, fec_percent):
    t = math.ceil(length / MAX_SHARD)
    s = math.ceil(length / t)
    b = math.ceil(t / MAX_K)
    blocks = []
    offset = 0
    for i in range(b):
        k = t // b + (1 if i < t % b else 0)
        if fec_percent == 0:
            m = 0
        else:
            m = min(255 - k, max(1, math.ceil(k * fec_percent / 100)))
        blocks.append({"k": k, "m": m, "offset": offset})
        offset += k * s
    return s, blocks


def packetize(frame, media_type, session, number, ts, flags, fec_percent):
    s, blocks = layout(len(frame), fec_percent)
    packets = []
    for bi, blk in enumerate(blocks):
        k, m, off = blk["k"], blk["m"], blk["offset"]
        data = []
        for i in range(k):
            chunk = frame[off + i * s: off + (i + 1) * s]
            data.append(chunk + bytes(s - len(chunk)))
        shards = data + (rs_encode(data, m) if m else [])
        for si, shard in enumerate(shards):
            header = struct.pack(
                ">BBHIIQBBBBBBHI",
                0x4D, (1 << 4) | media_type, session & 0xFFFF, number & 0xFFFFFFFF,
                len(frame), ts, flags, bi, len(blocks), si, k, m, s, off)
            packets.append(header + shard)
    return packets


def det_bytes(n, seed):
    """Deterministic test data, identical across languages."""
    out = bytearray(n)
    x = seed & 0xFFFFFFFF
    for i in range(n):
        x = (x * 1103515245 + 12345) & 0xFFFFFFFF
        out[i] = (x >> 16) & 0xFF
    return bytes(out)


# --- Vectors -------------------------------------------------------------------

def build_vectors():
    rs_cases = []
    for k, m, size, seed in [(1, 1, 8, 1), (2, 1, 16, 2), (4, 2, 32, 3), (10, 3, 24, 4),
                             (17, 5, 40, 5), (128, 13, 12, 6), (200, 55, 4, 7)]:
        data = [det_bytes(size, seed * 1000 + i) for i in range(k)]
        parity = rs_encode(data, m)
        rs_cases.append({"k": k, "m": m, "size": size, "seed": seed,
                         "parity": [p.hex() for p in parity]})

    layouts = []
    for length, fec in [(1, 10), (500, 0), (1368, 10), (1369, 10), (1920, 50), (100000, 10),
                        (175104, 20), (1048576, 10), (3000000, 5)]:
        s, blocks = layout(length, fec)
        layouts.append({"length": length, "fec": fec, "shardSize": s, "blocks": blocks})

    frame = det_bytes(3000, 99)
    pkts = packetize(frame, 1, 0xCAFEBABE, 7, 123456789, 0x03, 20)
    packet_case = {"frameSeed": 99, "frameLength": 3000, "type": 1, "session": 0xCAFEBABE,
                   "number": 7, "timestamp": 123456789, "flags": 3, "fec": 20,
                   "packets": [p.hex() for p in pkts]}

    return {"_comment": "Generated by tools/mango_ref.py. Do not edit by hand.",
            "detBytes": {"algorithm": "x = x*1103515245 + 12345 (mod 2^32); byte = (x >> 16) & 0xFF",
                         "seed": 42, "first16": det_bytes(16, 42).hex()},
            "reedSolomon": rs_cases, "layouts": layouts, "packetize": packet_case}


def self_test():
    import random
    rnd = random.Random(1)
    for k, m in [(1, 1), (3, 2), (10, 4), (64, 10), (128, 20)]:
        data = [bytes(rnd.randrange(256) for _ in range(16)) for _ in range(k)]
        shards = data + rs_encode(data, m)
        lost = rnd.sample(range(k + m), m)
        damaged = [None if i in lost else s for i, s in enumerate(shards)]
        assert rs_decode(damaged, k, m) == data, (k, m)
    e = encoding_matrix(5, 3)
    for r in range(5):
        assert e[r] == [1 if c == r else 0 for c in range(5)]
    print("self-test ok")


if __name__ == "__main__":
    self_test()
    if "--check" not in sys.argv:
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        path = os.path.join(root, "protocol", "test-vectors.json")
        with open(path, "w") as f:
            json.dump(build_vectors(), f, indent=1)
            f.write("\n")
        print("wrote", path)

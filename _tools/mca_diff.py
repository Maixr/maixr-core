#!/usr/bin/env python3
"""两个 region 文件的逐块 sections/BE 哈希对比 —— 效果一致性验收（spec 场景 1）。

用法：python _tools/mca_diff.py <ref.mca> <new.mca>
输出：共同区块数、sections/BE 各自差异数与相同率；差异区块坐标。
"""
import importlib.util
import os
import struct
import sys
import zlib

spec = importlib.util.spec_from_file_location(
    "mca", os.path.join(os.path.dirname(os.path.abspath(__file__)), "mca_blockhash.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def per_chunk(path, component):
    data = open(path, "rb").read()
    out = {}
    for idx in range(1024):
        off = struct.unpack_from(">I", data, idx * 4)[0] >> 8
        if off == 0:
            continue
        start = off * 4096
        ln = struct.unpack_from(">I", data, start)[0]
        comp = data[start + 4]
        payload = data[start + 5:start + 4 + ln]
        raw = zlib.decompress(payload) if comp == 2 else payload
        root = m.Nbt(raw).root()
        cx = idx % 32
        cz = idx // 32
        out[(cx, cz)] = m.chunk_digest(root, component).hex()
    return out


def main():
    f1, f2 = sys.argv[1], sys.argv[2]
    for comp in ("sections", "be"):
        a, b = per_chunk(f1, comp), per_chunk(f2, comp)
        common = set(a) & set(b)
        diff = sorted(k for k in common if a[k] != b[k])
        rate = 100.0 * (len(common) - len(diff)) / max(1, len(common))
        print(f"[{comp}] 共同={len(common)} 差异={len(diff)} 相同率={rate:.1f}%")
        if diff:
            print("   差异区块:", ", ".join(f"({x},{y})" for x, y in diff[:20]))


if __name__ == "__main__":
    main()

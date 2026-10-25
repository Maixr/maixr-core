#!/usr/bin/env python3
"""两个 region 文件中指定区块的逐方块 diff（spike 3.1 定位差异内容用）。

用法：python _tools/block_diff.py <ref.mca> <new.mca> <cx,cz> [更多...]
输出：差异方块的世界坐标与双方块名（含 Properties 摘要）。
"""
import importlib.util
import os
import struct
import sys
import zlib

spec = importlib.util.spec_from_file_location(
    "mca", os.path.join(os.path.dirname(os.path.abspath(__file__)), "mca_blockhash.py"))
mca = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mca)


def load_chunk(path, cx, cz):
    data = open(path, "rb").read()
    idx = (cx % 32) + (cz % 32) * 32
    off = struct.unpack_from(">I", data, idx * 4)[0] >> 8
    if off == 0:
        return None
    start = off * 4096
    ln = struct.unpack_from(">I", data, start)[0]
    comp = data[start + 4]
    payload = data[start + 5:start + 4 + ln]
    raw = zlib.decompress(payload) if comp == 2 else payload
    return mca.Nbt(raw).root()


def palette_states(sec):
    bs = sec.get("block_states", {})
    palette = bs.get("palette", [])
    names = []
    for p in palette:
        n = p.get("Name", "?")
        props = p.get("Properties")
        if props:
            s = ",".join(f"{k}={props[k]}" for k in sorted(props))
            n = f"{n}[{s}]"
        names.append(n)
    return names


def block_map(root):
    """返回 {(lx, y, lz): name}。1.21 data long 数组按 yzx 位打包（bits per entry）。"""
    blocks = {}
    for sec in root.get("sections", []):
        bs = sec.get("block_states")
        if not isinstance(bs, dict):
            continue
        names = palette_states(sec)
        data = bs.get("data")
        n = len(names)
        if n <= 1:
            fill = names[0] if names else "minecraft:air"
            for y in range(16):
                for lz in range(16):
                    for lx in range(16):
                        blocks[(lx, sec["Y"] * 16 + y, lz)] = fill
            continue
        bits = max(4, (n - 1).bit_length())
        longs = data
        vals_per_long = 64 // bits
        i = 0
        for y in range(16):
            for lz in range(16):
                for lx in range(16):
                    slot = i // vals_per_long
                    off = (i % vals_per_long) * bits
                    v = (longs[slot] >> off) & ((1 << bits) - 1)
                    blocks[(lx, sec["Y"] * 16 + y, lz)] = names[v]
                    i += 1
    return blocks


def main():
    args = sys.argv[1:]
    ref_path, new_path = args[0], args[1]
    targets = []
    for a in args[2:]:
        cx, cz = a.split(",")
        targets.append((int(cx), int(cz)))
    for cx, cz in targets:
        r = block_map(load_chunk(ref_path, cx, cz))
        n = block_map(load_chunk(new_path, cx, cz))
        diffs = [(k, r[k], n[k]) for k in r if k in n and r[k] != n[k]]
        rx = int(os.path.basename(ref_path).split(".")[1]) * 32 + cx
        rz = int(os.path.basename(ref_path).split(".")[2]) * 32 + cz
        print(f"chunk world=({rx},{rz}) 差异方块数={len(diffs)}")
        from collections import Counter
        pat = Counter((old.split("[")[0], new.split("[")[0]) for _, old, new in diffs)
        for (o, nw), c in pat.most_common(10):
            print(f"  {c:5d}  {o} -> {nw}")
        ys = Counter(k[1] // 16 * 16 for k, _, _ in diffs)
        print(f"  y 分布: {dict(sorted(ys.items()))}")
        for k, old, new in diffs[:12]:
            print(f"    ({k[0]},{k[1]},{k[2]}) {old} -> {new}")


if __name__ == "__main__":
    main()

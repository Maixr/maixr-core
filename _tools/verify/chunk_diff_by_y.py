#!/usr/bin/env python3
"""按 section-Y 分组统计两次生成差异 —— 区分「低空流体漂移」与「高空确定性」。"""
import importlib.util, os, struct, sys, zlib
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("mca", os.path.join(os.path.dirname(HERE), "mca_blockhash.py"))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

def chunks(path):
    data = open(path, "rb").read()
    if len(data) < 8192: return
    for idx in range(1024):
        off = struct.unpack_from(">I", data, idx*4)[0] >> 8
        if off == 0 or struct.unpack_from(">B", data, idx*4+3)[0] == 0: continue
        start = off*4096
        ln = struct.unpack_from(">i", data, start)[0]
        comp = data[start+4]; raw = data[start+5:start+4+ln]
        if comp == 2: raw = zlib.decompress(raw)
        elif comp != 1: continue
        try: root = m.Nbt(raw).root()
        except Exception: continue
        yield (idx % 32, idx // 32), root

def secmap(root):
    out = {}
    for sec in root.get("sections", []):
        if not isinstance(sec, dict): continue
        y = sec.get("Y", 0)
        y = y - 256 if isinstance(y, int) and y > 127 else y
        bs = sec.get("block_states")
        pal = []
        if isinstance(bs, dict):
            pal = sorted(p.get("Name","?") for p in bs.get("palette",[]) if isinstance(p,dict))
        out[y] = tuple(pal)
    return out

a = dict(chunks(sys.argv[1])); b = dict(chunks(sys.argv[2]))
by_band = defaultdict(Counter)
for key in sorted(set(a) & set(b)):
    sa, sb = secmap(a[key]), secmap(b[key])
    for y in sorted(set(sa) | set(sb)):
        if sa.get(y) != sb.get(y):
            band = "deep(<=8)" if y*16 <= 8 else ("mid" if y*16 < 180 else "high(>=180)")
            for nm in set(sa.get(y, ())) ^ set(sb.get(y, ())):
                by_band[band][nm] += 1
for band in sorted(by_band):
    print(f"=== {band} ===")
    for nm, c in by_band[band].most_common(12):
        print(f"  {nm}: {c}")

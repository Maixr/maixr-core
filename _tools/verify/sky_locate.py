#!/usr/bin/env python3
"""定位 sky_stone 出现的区块与 Y 段，排查异常低空出现。"""
import importlib.util, os, struct, sys, zlib
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("mca", os.path.join(os.path.dirname(HERE), "mca_blockhash.py"))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

def chunks(path):
    data = open(path, "rb").read()
    if len(data) < 8192: return
    for idx in range(1024):
        off = struct.unpack_from(">I", data, idx*4)[0] >> 8
        if off == 0 or struct.unpack_from(">B", data, idx*4+3)[0] == 0: continue
        start = off*4096; ln = struct.unpack_from(">i", data, start)[0]
        comp = data[start+4]; raw = data[start+5:start+4+ln]
        if comp == 2: raw = zlib.decompress(raw)
        elif comp != 1: continue
        try: root = m.Nbt(raw).root()
        except Exception: continue
        yield (idx % 32, idx // 32), root

per_chunk = defaultdict(list)
for p in sys.argv[1:]:
    rx, rz = int(p.split('.')[-3]), int(p.split('.')[-2])
    for (cx, cz), root in chunks(p):
        for sec in root.get("sections", []):
            if not isinstance(sec, dict): continue
            sy = sec.get("Y", 0); sy = sy-256 if sy > 127 else sy
            bs = sec.get("block_states")
            if not isinstance(bs, dict): continue
            for pal in bs.get("palette", []):
                nm = pal.get("Name","") if isinstance(pal, dict) else ""
                if nm == "ae2:sky_stone_block":
                    per_chunk[(rx*32+cx, rz*32+cz)].append(sy*16)

print(f"含 sky_stone 的区块数: {len(per_chunk)}")
for k in sorted(per_chunk):
    ys = sorted(set(per_chunk[k]))
    flag = "  <== 异常低空" if min(ys) < 180 else ""
    print(f"  区块{k}: section起步Y={ys}{flag}")

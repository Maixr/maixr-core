#!/usr/bin/env python3
"""伴生岛密度：按「已生成区块」为分母统计含岛区块比例。

已生成区块判定：section 里出现过非空气方块（排除只有 biomes 的空壳区块）。
"""
import importlib.util, os, struct, sys, zlib
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("mca", os.path.join(os.path.dirname(HERE), "mca_blockhash.py"))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

MARKERS = {"minecraft:cactus","minecraft:dead_bush","minecraft:sugar_cane",
           "minecraft:kelp_plant","minecraft:brain_coral_block","minecraft:tube_coral_block",
           "minecraft:glass","minecraft:lapis_block"}

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
        yield idx, root

gen = 0
with_island = 0
per_file = defaultdict(lambda: [0,0])
for p in sys.argv[1:]:
    for cidx, root in chunks(p):
        solid = False
        island = False
        for sec in root.get("sections", []):
            if not isinstance(sec, dict): continue
            bs = sec.get("block_states")
            if not isinstance(bs, dict): continue
            for pal in bs.get("palette", []):
                nm = pal.get("Name","") if isinstance(pal, dict) else ""
                if nm and nm != "minecraft:air":
                    solid = True
                    if nm in MARKERS: island = True
        if solid:
            gen += 1
            per_file[p][1] += 1
            if island:
                with_island += 1
                per_file[p][0] += 1

print(f"已生成区块: {gen}   含岛区块: {with_island}   密度: {with_island/gen*100:.1f}%")
print("\n按 region 分解（含岛/已生成）:")
for p,(a,b) in sorted(per_file.items()):
    if b: print(f"  {os.path.basename(p)}: {a}/{b} = {a/b*100:.1f}%")

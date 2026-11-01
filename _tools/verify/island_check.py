#!/usr/bin/env python3
"""伴生岛验收：统计各型指示方块的出现高度与区块数，验证「全部在 y>=250」与频率。

用法：python _tools/verify/island_check.py <region文件...>
"""
import importlib.util, os, struct, sys, zlib
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("mca", os.path.join(os.path.dirname(HERE), "mca_blockhash.py"))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

# 各型岛的独占指示方块
KIND_MARKERS = {
    "沙漠沙丘岛": {"minecraft:cactus", "minecraft:dead_bush"},
    "水族馆": {"minecraft:kelp_plant", "minecraft:brain_coral_block", "minecraft:tube_coral_block"},
    "甘蔗水岛/梦晶岛/末地/下界": {"minecraft:sugar_cane"},
}
ALL_MARKERS = set().union(*KIND_MARKERS.values())

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

chunk_marks = {}   # chunk global idx -> set(min section Y)
marker_min_y = defaultdict(lambda: 10**9)
for p in sys.argv[1:]:
    for cidx, root in chunks(p):
        lowest = None
        for sec in root.get("sections", []):
            if not isinstance(sec, dict): continue
            y = sec.get("Y", 0); y = y-256 if y > 127 else y
            bs = sec.get("block_states")
            if not isinstance(bs, dict): continue
            for pal in bs.get("palette", []):
                nm = pal.get("Name","") if isinstance(pal, dict) else ""
                if nm in ALL_MARKERS:
                    if lowest is None or y < lowest: lowest = y
                    if y*16 < marker_min_y[nm]: marker_min_y[nm] = y*16
        if lowest is not None:
            chunk_marks[cidx] = lowest

print(f"含岛指示方块的区块数: {len(chunk_marks)}")
if marker_min_y:
    print("各指示方块最低出现 Y:")
    for nm, y in sorted(marker_min_y.items(), key=lambda kv: kv[1]):
        print(f"  {nm}: y={y}")
    print(f"全局最低: y={min(marker_min_y.values())}")
    print("判定：全部 >= 250 ?", "PASS" if min(marker_min_y.values()) >= 250 else "FAIL")
else:
    print("未发现任何岛指示方块")

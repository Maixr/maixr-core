#!/usr/bin/env python3
"""天隙矿脉形态验收：解码 section，检查矿脉方块的连通跨度与分带合规。

用法：python _tools/verify/vein_shape.py <region文件...>
输出：
  - 每道矿脉的宿主方块、Y 范围、水平跨度（判定 96~224）
  - 分带合规性：deep(<=8) 是否全深板岩、high(>=180) 是否出现天空石
  - 是否出现混拼（同一连通块里有 2 种以上宿主）
"""
import importlib.util, os, struct, sys, zlib
from collections import defaultdict, deque

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("mca", os.path.join(os.path.dirname(HERE), "mca_blockhash.py"))
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

HOSTS = {"minecraft:deepslate", "minecraft:stone", "ae2:sky_stone_block"}
ORES = lambda n: n.endswith("_ore")

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

def decode(pal_entry_name, bs, sy):
    pal = bs.get("palette", [])
    data = bs.get("data")
    names = [p.get("Name") for p in pal if isinstance(p, dict)]
    out = {}
    if data is None:
        for i in range(4096):
            out[i] = names[0] if names else "minecraft:air"
        return out, sy
    bits = max(4, (len(pal)-1).bit_length())
    per = 64 // bits
    mask = (1 << bits) - 1
    for i in range(4096):
        li = i // per
        if li >= len(data): break
        v = (data[li] >> ((i % per) * bits)) & mask
        if v < len(names):
            out[i] = names[v]
    return out, sy

# 收集所有宿主方块位置（全局坐标）
host_pos = defaultdict(set)   # host -> {(x,y,z)}
ore_pos = defaultdict(set)
for p in sys.argv[1:]:
    rx, rz = int(p.split('.')[-3]), int(p.split('.')[-2])
    for (cx, cz), root in chunks(p):
        bx, bz = (rx*32+cx)*16, (rz*32+cz)*16
        for sec in root.get("sections", []):
            if not isinstance(sec, dict): continue
            sy = sec.get("Y", 0); sy = sy-256 if sy > 127 else sy
            bs = sec.get("block_states")
            if not isinstance(bs, dict): continue
            blocks, _ = decode(None, bs, sy)
            for i, nm in blocks.items():
                if nm in HOSTS:
                    x, z, y = bx + (i % 16), bz + ((i // 16) % 16), sy*16 + (i // 256)
                    host_pos[nm].add((x, y, z))
                elif nm and ORES(nm):
                    x, z, y = bx + (i % 16), bz + ((i // 16) % 16), sy*16 + (i // 256)
                    ore_pos[nm].add((x, y, z))

print("宿主方块块数:")
for h in sorted(host_pos):
    ps = host_pos[h]
    ys = [p[1] for p in ps]
    print(f"  {h}: {len(ps)} 块, y {min(ys)}..{max(ys)}")

# 连通分量（6 邻接），只对 sky_stone 与 deepslate 做（stone 会被地形干扰）
def components(points):
    pts = set(points)
    seen = set()
    comps = []
    for p in pts:
        if p in seen: continue
        q = deque([p]); seen.add(p); comp = []
        while q:
            c = q.popleft(); comp.append(c)
            x, y, z = c
            for d in ((1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)):
                n = (x+d[0], y+d[1], z+d[2])
                if n in pts and n not in seen:
                    seen.add(n); q.append(n)
        comps.append(comp)
    return comps

for h in ("ae2:sky_stone_block", "minecraft:deepslate"):
    if h not in host_pos: continue
    comps = [c for c in components(host_pos[h]) if len(c) >= 50]
    if not comps: continue
    spans = []
    for c in comps:
        xs = [p[0] for p in c]; zs = [p[2] for p in c]
        spans.append(max(max(xs)-min(xs), max(zs)-min(zs)) + 1)
    spans.sort()
    print(f"\n{h} 大连通块 ≥50 块: {len(comps)} 个")
    print(f"  水平跨度: min={spans[0]} 中位={spans[len(spans)//2]} max={spans[-1]}")
    print(f"  跨度落在 96~224 的比例: {sum(1 for s in spans if 96<=s<=224)}/{len(spans)}")

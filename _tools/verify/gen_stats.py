#!/usr/bin/env python3
"""生成结果统计（区块 section 粒度）—— spec 场景自动化验收。

用法：
  python _tools/verify/gen_stats.py <region文件...>
         [--chunks x0 z0 x1 z1]   只统计该区块范围（含端点）
         [--json]

输出：
  - 关键方块出现的 section-Y 列表（section Y 是方块 Y/16 的商）
  - 各「高度带」的非空气方块数
  - 矿石种类与出现数
  - 专项探针：天空石 / 平滑天空石 / 高空 raw 块 / 高空伴生岛指示方块

注意：只读 palette 不解码 data 数组 —— 得到的是「该 16³ section 内出现过该
方块」，不是精确块数。用于存在性 / 高度带 / 种类覆盖判断足够。
"""
import importlib.util
import json
import os
import struct
import sys
import zlib
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location(
    "mca", os.path.join(os.path.dirname(HERE), "mca_blockhash.py"))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

DEEPSLATE = "minecraft:deepslate"
STONE = "minecraft:stone"
SKY = "ae2:sky_stone_block"
SMOOTH_SKY = "ae2:smooth_sky_stone_block"
# 伴生岛指示方块（只可能来自 SurfaceLakes / 其 NBT 模板）
ISLAND_MARKERS = {
    "minecraft:glass", "minecraft:cactus", "minecraft:lapis_block",
    "minecraft:kelp", "minecraft:kelp_plant", "minecraft:tube_coral_block",
    "minecraft:brain_coral_block", "minecraft:bubble_coral_block",
    "minecraft:fire_coral_block", "minecraft:horn_coral_block",
    "minecraft:tube_coral", "minecraft:brain_coral", "minecraft:sugar_cane",
}


def signed_byte(v):
    return v - 256 if isinstance(v, int) and v > 127 else v


def iter_chunks(path):
    data = open(path, "rb").read()
    if len(data) < 8192:
        return
    for idx in range(1024):
        off = struct.unpack_from(">I", data, idx * 4)[0] >> 8
        if off == 0:
            continue
        if struct.unpack_from(">B", data, idx * 4 + 3)[0] == 0:
            continue
        start = off * 4096
        ln = struct.unpack_from(">i", data, start)[0]
        comp = data[start + 4]
        raw = data[start + 5:start + 4 + ln]
        if comp == 2:
            raw = zlib.decompress(raw)
        elif comp != 1:
            continue
        try:
            root = m.Nbt(raw).root()
        except Exception:
            continue
        yield (idx % 32, idx // 32), root


def palette_names(sec):
    bs = sec.get("block_states")
    if not isinstance(bs, dict):
        return []
    return [p.get("Name", "?") for p in bs.get("palette", [])
            if isinstance(p, dict) and p.get("Name", "minecraft:air") != "minecraft:air"]


def main():
    argv = sys.argv[1:]
    use_json = "--json" in argv
    chunk_filter = None
    if "--chunks" in argv:
        i = argv.index("--chunks")
        chunk_filter = tuple(int(v) for v in argv[i + 1:i + 5])
        del argv[i:i + 5]
    paths = [a for a in argv if not a.startswith("--")]

    block_sections = defaultdict(set)        # block -> set(section Y)
    band_counts = defaultdict(Counter)       # band -> block -> sections
    ores = Counter()
    chunks = 0
    chunk_coords = set()

    def band_of(sy):
        # section Y -> 方块 Y 范围 [sy*16, sy*16+15]
        return sy * 16

    for p in paths:
        for (cx, cz), root in iter_chunks(p):
            if chunk_filter:
                x0, z0, x1, z1 = chunk_filter
                rx0, rz0 = cx + 32 * int(p.split('.')[-3]), cz + 32 * int(p.split('.')[-2])
            if chunk_filter:
                x0, z0, x1, z1 = chunk_filter
                gx = cx + 32 * int(p.split('.')[-3])
                gz = cz + 32 * int(p.split('.')[-2])
                if not (x0 <= gx <= x1 and z0 <= gz <= z1):
                    continue
            chunks += 1
            chunk_coords.add((cx, cz))
            for sec in root.get("sections", []):
                if not isinstance(sec, dict):
                    continue
                sy = signed_byte(sec.get("Y", 0))
                names = palette_names(sec)
                if not names:
                    continue
                base = band_of(sy)
                band = "deep(<=8)" if base <= 8 else ("mid" if base < 180 else "high(>=180)")
                for nm in names:
                    block_sections[nm].add(sy)
                    band_counts[band][nm] += 1
                    if nm.endswith("_ore") or nm.endswith("ore"):
                        ores[nm] += 1

    def ys(block):
        return sorted(block_sections.get(block, ()))

    high_min_section = 11          # 176+，避开 180 的 section 边界
    sky_ys = ys(SKY)
    smooth_ys = ys(SMOOTH_SKY)
    raw_above = {nm: sorted(s) for nm, s in block_sections.items()
                 if nm.startswith(("minecraft:raw_", "create:raw_", "mekanism:block_raw_"))
                 and any(v >= 7 for v in s)}   # section 7 = y112+
    island_marks = {nm: sorted(s) for nm, s in block_sections.items()
                    if nm in ISLAND_MARKERS and any(v >= 15 for v in s)}  # section 15 = y240+

    result = {
        "chunks": chunks,
        "section_y_present": {
            "deepslate": ys(DEEPSLATE),
            "stone": ys(STONE),
            "sky_stone": sky_ys,
            "smooth_sky_stone": smooth_ys,
        },
        "sky_stone_sections": len(sky_ys),
        "smooth_sky_stone_sections": len(smooth_ys),
        "min_sky_stone_section": min(sky_ys) if sky_ys else None,
        "band_totals": {b: sum(c.values()) for b, c in sorted(band_counts.items())},
        "ore_kinds": len(ores),
        "ores_top": dict(ores.most_common(25)),
        "raw_blocks_above_y112": raw_above,
        "island_markers_above_y240": island_marks,
    }
    if use_json:
        print(json.dumps(result, ensure_ascii=False, indent=1))
    else:
        for k, v in result.items():
            print(f"{k}: {v}")


if __name__ == "__main__":
    main()

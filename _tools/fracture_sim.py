# -*- coding: utf-8 -*-
"""
破碎世界碎裂场体素模拟（任务 2.2 调参工具）

复刻 FractureDensityFunction / PlateFieldLogic / PlateFieldLogic.drift 的模型数学
（裂缝噪声用值噪声 fBm 近似 vanilla Perlin，波长结构一致），输出：
  1. fracture_top.png      俯视图 ±7168 格（四带全景 + 平静区/裂缝/碎块/碎屑圆环）
  2. fracture_crack.png    裂缝带放大图 ±1536 格（1 格/像素，看各向同性/分叉/宽窄）
  3. fracture_section.png  剖面图（沿 x 轴竖切，含板块垂直漂移与断层错位）

调参流程：改本文件顶部与 FractureDensityFunction 一致的常量 → 出图 →
形态满意后把数值回填到 FractureDensityFunction / PlateFieldLogic 类顶部常量
（文档规范：常量必须与代码一致）。
"""

import math
import random
import struct
import zlib

# ---------------- 与 Java 常量保持一致（改完同步回 FractureDensityFunction） ----------------
CALM_RADIUS = 300.0
CRACK_END = 3072.0
FRAG_END = 6144.0
MASK_FLOOR = -0.3

CRACK_THRESHOLD = [(0.00, 2.00), (0.10, 0.80), (1.00, 0.80), (2.00, 0.45), (3.00, 0.28)]
CRACK_WIDTH = [(0.00, 0.04), (1.00, 0.03), (2.00, 0.05), (3.00, 0.06)]
PLATE_DEATH = [(0.00, 0.00), (1.00, 0.00), (2.00, 0.60), (3.00, 0.62)]
FINE_DEATH = [(0.00, 0.00), (1.50, 0.00), (2.50, 0.20), (3.00, 0.32)]
DRIFT_AMP = [(0.00, 0.0), (1.00, 5.0), (1.50, 15.0), (2.00, 25.0), (2.50, 32.0), (3.00, 40.0)]
RIDGE_NORMALIZE = 2.5

CELL_SIZE = 64
FINE_CELL = 8
CENTER_JITTER = 0.65
EDGE_SOFT_IN = 0.88
EDGE_SOFT_OUT = 0.99
# 裂缝噪声：firstOctave -6, amplitudes [1.0, 0.6, 0.4]
CRACK_OCTAVES = [(-6, 1.0), (-5, 0.3)]


def curve(pts, t):
    if t <= pts[0][0]:
        return pts[0][1]
    for i in range(1, len(pts)):
        if t <= pts[i][0]:
            t0, v0 = pts[i - 1]
            t1, v1 = pts[i]
            return v0 + (v1 - v0) * (t - t0) / (t1 - t0)
    return pts[-1][1]


def smoothstep(lo, hi, x):
    if x <= lo:
        return 0.0
    if x >= hi:
        return 1.0
    t = (x - lo) / (hi - lo)
    return t * t * (3.0 - 2.0 * t)


# ---------------- 值噪声 fBm（近似 vanilla NormalNoise，同波长结构） ----------------

_NOISE_CACHE = {}


def _value_noise(seed):
    """格点值噪声：返回 fn(x, z) ∈ [-1, 1]，格点值缓存在闭包表里。"""
    table = {}
    rng = random.Random(seed)

    def lattice(xi, zi):
        key = (xi, zi)
        v = table.get(key)
        if v is None:
            v = rng.uniform(-1, 1)
            table[key] = v
        return v

    def fn(x, z):
        xi, zi = math.floor(x), math.floor(z)
        tx, tz = x - xi, z - zi
        tx = tx * tx * (3 - 2 * tx)
        tz = tz * tz * (3 - 2 * tz)
        a = lattice(xi, zi) * (1 - tx) + lattice(xi + 1, zi) * tx
        b = lattice(xi, zi + 1) * (1 - tx) + lattice(xi + 1, zi + 1) * tx
        return a * (1 - tz) + b * tz

    return fn


_OCT_FN = [(o, amp, _value_noise(1000 + i)) for i, (o, amp) in enumerate(CRACK_OCTAVES)]


def crack_noise(x, z):
    """fBm 裂缝噪声（各倍频程波长 2^-octave 格）。"""
    n = 0.0
    for octave, amp, fn in _OCT_FN:
        freq = 2.0 ** (-octave)  # firstOctave=-6 → 波长 64 格
        n += fn(x / freq, z / freq) * amp
    return n


# ---------------- 板块场（与 PlateFieldLogic 一致） ----------------

def _hash01(a, b, salt):
    h = (a * 0x9E3779B185EBCA87 ^ b * 0xC2B2AE3D27D4EB4F ^ salt * 0x165667B19E3779F9) & 0xFFFFFFFFFFFFFFFF
    h ^= h >> 32
    h = (h * 0xD6E8FEB86659FD93) & 0xFFFFFFFFFFFFFFFF
    h ^= h >> 32
    h = (h * 0xD6E8FEB86659FD93) & 0xFFFFFFFFFFFFFFFF
    h ^= h >> 32
    return (h >> 11) / float(1 << 53)


def _center(cx, cz, salt_x, salt_z):
    return (
        (cx + 0.5 + (_hash01(cx, cz, salt_x) - 0.5) * CENTER_JITTER) * CELL_SIZE,
        (cz + 0.5 + (_hash01(cx, cz, salt_z) - 0.5) * CENTER_JITTER) * CELL_SIZE,
    )


SALT_CX, SALT_CZ = 0x51ED270B, 0x2545F491
SALT_DEATH, SALT_FINE = 0x9E3779B9, 0x68BC21EB
SALT_DIR, SALT_MAG, SALT_DY, SALT_TILT = 0xC2B2AE3D, 0x27D4EB2F, 0x165667B1, 0x85EBCA77


def _voronoi(x, z):
    bx, bz = math.floor(x / CELL_SIZE), math.floor(z / CELL_SIZE)
    f1 = f2 = float("inf")
    c1 = c2 = (0, 0)
    for dz in (-1, 0, 1):
        for dx in (-1, 0, 1):
            cx, cz = bx + dx, bz + dz
            pcx, pcz = _center(cx, cz, SALT_CX, SALT_CZ)
            d = (x - pcx) ** 2 + (z - pcz) ** 2
            if d < f1:
                f2, c2 = f1, c1
                f1, c1 = d, (cx, cz)
            elif d < f2:
                f2, c2 = d, (cx, cz)
    return math.sqrt(f1), math.sqrt(f2), c1, c2


def plate_survival(x, z, scale=1.0):
    t = band_progress(x, z, scale)
    death = curve(PLATE_DEATH, t)
    if death >= 1.0:
        return 0.0
    if death <= 0.0:
        return 1.0
    f1, f2, c1, c2 = _voronoi(x, z)
    edge = smoothstep(EDGE_SOFT_IN, EDGE_SOFT_OUT, f1 / f2)
    fine_death = curve(FINE_DEATH, t)
    fine = 1.0 if fine_death <= 0.0 or _hash01(math.floor(x / FINE_CELL), math.floor(z / FINE_CELL), SALT_FINE) >= fine_death else 0.0
    own = fine if _hash01(c1[0], c1[1], SALT_DEATH) >= death else 0.0
    other = fine if _hash01(c2[0], c2[1], SALT_DEATH) >= death else 0.0
    return own + (other - own) * edge


def drift(x, z, scale=1.0, tilt=0.0):
    t = band_progress(x, z, scale)
    amp = curve(DRIFT_AMP, t) * scale
    if amp <= 0.0:
        return (0.0, 0.0, 0.0)
    f1, f2, c1, c2 = _voronoi(x, z)
    edge = smoothstep(EDGE_SOFT_IN, EDGE_SOFT_OUT, f1 / f2)

    def one(c):
        cx, cz = c
        ang = _hash01(cx, cz, SALT_DIR) * math.pi * 2
        mag = amp * (0.8 + 0.4 * _hash01(cx, cz, SALT_MAG))
        dx, dz = math.cos(ang) * mag, math.sin(ang) * mag
        dy = (_hash01(cx, cz, SALT_DY) * 2 - 1) * amp * 0.8
        if tilt != 0.0:
            pcx, pcz = _center(cx, cz, SALT_CX, SALT_CZ)
            dxx, dzz = math.cos(ang + math.pi / 2), math.sin(ang + math.pi / 2)
            dy += tilt * (_hash01(cx, cz, SALT_TILT) * 2 - 1) * ((x - pcx) * dxx + (z - pcz) * dzz) / CELL_SIZE
        return (dx, dy, dz)

    a = one(c1)
    if edge <= 0.0:
        return a
    b = one(c2)
    return tuple(a[i] + (b[i] - a[i]) * edge for i in range(3))


def band_progress(x, z, scale=1.0):
    r = math.hypot(x, z)
    calm, crack_end, frag_end = CALM_RADIUS * scale, CRACK_END * scale, FRAG_END * scale
    if r <= calm:
        return 0.0
    if r <= crack_end:
        return (r - calm) / (crack_end - calm)
    if r <= frag_end:
        return 1.0 + (r - crack_end) / (frag_end - crack_end)
    return 2.0 + min(1.0, (r - frag_end) / frag_end)


def survival_mask(x, z, scale=1.0):
    """符号化碎裂掩码 [-0.3, 1]。"""
    t = band_progress(x, z, scale)
    if t <= 0.0:
        return 1.0
    ridge = 1.0 - min(1.0, abs(crack_noise(x, z)) * RIDGE_NORMALIZE)
    thr = curve(CRACK_THRESHOLD, t)
    w = curve(CRACK_WIDTH, t)
    crack_mask = 1.0 - smoothstep(thr - w, thr + w, ridge)
    plate = plate_survival(x, z, scale)
    s = crack_mask * plate
    return s * (1.0 - MASK_FLOOR) + MASK_FLOOR


# ---------------- 出图 ----------------

def write_png(path, w, h, pixels):
    """pixels: list of rows, each row = list of (r,g,b)。最小 PNG 写出。"""
    raw = b""
    for row in pixels:
        raw += b"\x00" + b"".join(struct.pack("BBB", *p) for p in row)

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 6))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
    print("written", path, f"{w}x{h}")


BAND_COLORS = {
    0: (60, 60, 70),      # 平静区底色（暗灰蓝）
    1: (70, 55, 40),      # 裂缝带底色（暗棕）
    2: (60, 45, 30),      # 碎块带
    3: (40, 35, 30),      # 碎屑带
}


def band_of(x, z, scale=1.0):
    r = math.hypot(x, z)
    if r <= CALM_RADIUS * scale:
        return 0
    if r <= CRACK_END * scale:
        return 1
    if r <= FRAG_END * scale:
        return 2
    return 3


def render_top(path, half, step, scale=1.0):
    size = 2 * half // step
    rows = []
    for py in range(size):
        z = (py - size // 2) * step
        row = []
        for px in range(size):
            x = (px - size // 2) * step
            m = survival_mask(x, z, scale)
            band = band_of(x, z, scale)
            base = BAND_COLORS[band]
            if m > 0.0:
                # 存活地形：按掩码亮度（含漂移渐变的暗示）
                v = m
                r = int(30 + 150 * v + base[0] * 0.25)
                g = int(30 + 150 * v + base[1] * 0.25)
                b = int(35 + 130 * v + base[2] * 0.25)
                row.append((min(r, 255), min(g, 255), min(b, 255)))
            else:
                # 虚空/裂缝
                row.append(base)
        rows.append(row)
    # 叠加带界圆环
    size2 = size // 2
    for radius, color in ((CALM_RADIUS * scale, (255, 80, 80)), (CRACK_END * scale, (255, 160, 60)), (FRAG_END * scale, (255, 220, 80))):
        rr = radius / step
        for a in range(0, 3600):
            ang = a / 10.0 * math.pi / 180
            px = int(size2 + math.cos(ang) * rr)
            py = int(size2 + math.sin(ang) * rr)
            if 0 <= px < size and 0 <= py < size:
                rows[py][px] = color
    # 原点十字
    for d in range(-6, 7):
        if 0 <= size2 + d < size:
            rows[size2][size2 + d] = (255, 255, 255)
            rows[size2 + d][size2] = (255, 255, 255)
    write_png(path, size, size, rows)


def render_section(path, scale=1.0, tilt=0.0):
    """沿 z=0 竖切：x ∈ [-8000, 8000]（5 格/px），y ∈ [-64, 320]（2 格/px）。"""
    w, h = 3200, 192
    rows = [[(10, 10, 14)] * w for _ in range(h)]
    # 每列的地形顶面高度（近似原版起伏 + 板块漂移），掩码 >0 才有地形
    rng = random.Random(77)
    base_h = [60 + 20 * _value_noise(555)(x / 300.0, 0.0) + 8 * _value_noise(556)(x / 80.0, 0.0) for x in range(-8000, 8000, 5)]
    for px in range(w):
        x = -8000 + px * 5
        m = survival_mask(x, 0, scale)
        dx, dy, dz = drift(x, 0, scale, tilt)
        band = band_of(x, 0, scale)
        if m > 0.0:
            top = base_h[px] + dy
            for py in range(h):
                y = 320 - py * 2
                if y <= top and m > 0.0:
                    # 深度越深越暗（断面裸露地层剖面的暗示）
                    depth = max(0.0, top - y)
                    if depth < 4:
                        c = (110, 160, 90)    # 草皮层
                    elif depth < 12:
                        c = (134, 96, 67)     # 土层
                    elif depth < 60:
                        c = (121, 121, 121)   # 石层
                    else:
                        c = (80, 80, 90)      # 深板岩
                    rows[py][px] = c
        else:
            # 虚空列：顶部画带色刻度
            c = BAND_COLORS[band]
            rows[0][px] = c
    # y=0 海平面参考线（破碎 settings 里已无水，仅作高度参照）
    for px in range(0, w, 4):
        py = (320 - 63) // 2
        rows[py][px] = (40, 60, 90)
    write_png(path, w, h, rows)


if __name__ == "__main__":
    render_top("fracture_top.png", 7168, 8)
    render_top("fracture_crack.png", 1536, 1)
    render_section("fracture_section.png")

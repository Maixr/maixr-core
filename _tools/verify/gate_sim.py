#!/usr/bin/env python3
"""离线复算 SurfaceLakes 的频率门控命中率，与实际生成结果对照。"""
M = (1 << 64) - 1
K1 = 0x9E3779B97F4A7C15
K2 = 0xC2B2AE3D27D4EB4F
KM = 0xFF51AFD7ED558CCD

def gate(cx, cz, denom=4):
    h = ((cx * K1) ^ (cz * K2) ^ 0x5EED) & M
    h ^= h >> 33
    h = (h * KM) & M
    h ^= h >> 33
    return h % denom == 0

# r.12.12 覆盖区块 x 384..415, z 384..415
hit = sum(1 for cx in range(384, 416) for cz in range(384, 416) if gate(cx, cz))
print(f"r.12.12 区域 (32x32=1024 区块) 门控命中: {hit} = {hit/1024*100:.1f}%")

# 实际生成到的区块范围（forceload 384..391 x 400..407 => 64 区块）
hit2 = sum(1 for cx in range(384, 392) for cz in range(400, 408) if gate(cx, cz))
print(f"forceload 区域 (8x8=64 区块) 门控命中: {hit2} = {hit2/64*100:.1f}%")

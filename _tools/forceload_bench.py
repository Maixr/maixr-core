#!/usr/bin/env python3
"""forceload 64 块批量基准（无头探针法，口径同 _docs/生成性能测量.md 组 2 对照）。

用法：
  python _tools/forceload_bench.py --label vanilla   # 连接 RCON，热身 + 9 区域计时 + stop

环境变量：MAIXR_RCON_PORT（默认 25576）；密码取 server.properties rcon.password。
区域坐标固定（1 热身 + 9 数据，两两间隔 >2000 方块），原版/破碎世界共用同一坐标
序列（种子 12345，基准目的地确定性口径）。每区域 add 计时后立刻 remove，
避免已加载区块的 tick 负载污染后续读数。
"""
import os
import socket
import struct
import sys
import time

HOST = "127.0.0.1"
PORT = int(os.environ.get("MAIXR_RCON_PORT", "25576"))
PASSWORD = "maixr-e2e"

# (名称, from_x, from_z) —— to = from + 127；坐标必须对齐 16 的倍数（区块边界），
# 否则 forceload 会把 to 所在整块并入（实测 81/72 块而非 64 块）。
REGIONS = [
    ("warm", 3008, 3008),    # 热身，丢弃（JIT 冷启动）→ 区块 188..195
    ("R1", 6144, 6400),
    ("R2", 9472, 5376),
    ("R3", 5376, 10496),
    ("R4", 12288, 7424),
    ("R5", 7680, 13824),
    ("R6", 15360, 9984),
    ("R7", 11008, 16384),
    ("R8", 17920, 12800),
    ("R9", 14080, 18688),
]


def recvn(s, n):
    buf = b""
    while len(buf) < n:
        c = s.recv(n - len(buf))
        if not c:
            raise ConnectionError("connection closed")
        buf += c
    return buf


def recv_pkt(s):
    (ln,) = struct.unpack("<i", recvn(s, 4))
    body = recvn(s, ln)
    rid, rtype = struct.unpack("<ii", body[:8])
    return rid, rtype, body[8:-2].decode("utf-8", "replace")


def send_pkt(s, rid, ptype, payload):
    data = struct.pack("<ii", rid, ptype) + payload.encode("utf-8") + b"\x00\x00"
    s.sendall(struct.pack("<i", len(data)) + data)


def connect_with_retry(tries=30, delay=3.0):
    last = None
    for _ in range(tries):
        try:
            s = socket.create_connection((HOST, PORT), timeout=600)
            send_pkt(s, 1, 3, PASSWORD)
            rid, _, _ = recv_pkt(s)
            if rid != -1:
                return s
            s.close()
            raise RuntimeError("auth failed")
        except OSError as e:
            last = e
            time.sleep(delay)
    raise ConnectionError(f"RCON 连不上: {last}")


def main():
    label = sys.argv[sys.argv.index("--label") + 1] if "--label" in sys.argv else "run"
    s = connect_with_retry()
    rid = 2
    rows = []
    with s:
        for name, x, z in REGIONS:
            cmd = f"forceload add {x} {z} {x + 127} {z + 127}"
            t0 = time.perf_counter()
            send_pkt(s, rid, 2, cmd)
            rid += 1
            _, _, out = recv_pkt(s)
            dt = time.perf_counter() - t0
            tag = "warm" if name == "warm" else "data"
            rows.append((name, tag, dt, out.strip()))
            print(f"[{label}] {name:5s} {tag} {dt:8.2f}s  {out.strip()}", flush=True)
            send_pkt(s, rid, 2, f"forceload remove {x} {z} {x + 127} {z + 127}")
            rid += 1
            recv_pkt(s)
        data = sorted(r[2] for r in rows if r[1] == "data")
        med = data[len(data) // 2]
        print(f"[{label}] 中位数(9 数据区域) = {med:.2f}s  "
              f"读数 = {', '.join(f'{d:.2f}' for d in data)}", flush=True)
        send_pkt(s, rid, 2, "stop")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

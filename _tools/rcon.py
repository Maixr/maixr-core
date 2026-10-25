#!/usr/bin/env python3
"""极简 RCON 客户端 —— 测试期驱动 dev 服务器控制台（server.properties 需 enable-rcon=true）。

用法：
  python _tools/rcon.py <password> "<command>"
  python _tools/rcon.py maixr-e2e "seed"
"""
import os
import socket
import struct
import sys

SERVER = ("127.0.0.1", int(os.environ.get("MAIXR_RCON_PORT", "25575")))


def recvn(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed")
        buf += chunk
    return buf


def send(sock: socket.socket, req_id: int, ptype: int, payload: str) -> None:
    data = struct.pack("<ii", req_id, ptype) + payload.encode("utf-8") + b"\x00\x00"
    sock.sendall(struct.pack("<i", len(data)) + data)


def recv(sock: socket.socket) -> tuple[int, int, str]:
    (ln,) = struct.unpack("<i", recvn(sock, 4))
    body = recvn(sock, ln)
    rid, rtype = struct.unpack("<ii", body[:8])
    return rid, rtype, body[8:-2].decode("utf-8", "replace")


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    password, command = sys.argv[1], sys.argv[2]
    import time
    _t0 = time.perf_counter()
    with socket.create_connection(SERVER, timeout=120) as s:
        send(s, 1, 3, password)  # SERVERDATA_AUTH
        rid, _, _ = recv(s)  # 单个认证应答包：rid != -1 即成功
        if rid == -1:
            print("auth failed")
            return 1
        send(s, 2, 2, command)  # SERVERDATA_EXECCOMMAND
        _, _, out = recv(s)
        print(f"[rcon 耗时 {time.perf_counter()-_t0:.2f}s] " + out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

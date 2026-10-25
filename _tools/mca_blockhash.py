#!/usr/bin/env python3
"""MCA region 文件的「方块数据」哈希 —— 生成输出逐位一致性验收用。

口径（任务 1.1 定案）：只取每个区块的 sections 方块数据（Y + block_states
palette（含 Properties，按序）+ data long 数组）与 block_entities 标识
（id + 坐标），忽略 LastUpdate/InhabitedTime/时间戳/光照等生成间必然漂移的
非方块字段。输出每个 region 文件的 sha256 与文件内区块数。

用法：python _tools/mca_blockhash.py <r.x.z.mca> [更多文件...]
"""
import hashlib
import struct
import sys
import zlib


class Nbt:
    """极简 NBT 解析器（只读，够覆盖 1.21 区块格式）。"""

    def __init__(self, data: bytes):
        self.d = data
        self.i = 0

    def u1(self):
        v = self.d[self.i]
        self.i += 1
        return v

    def u2(self):
        v = struct.unpack_from(">H", self.d, self.i)[0]
        self.i += 2
        return v

    def i4(self):
        v = struct.unpack_from(">i", self.d, self.i)[0]
        self.i += 4
        return v

    def i8(self):
        v = struct.unpack_from(">q", self.d, self.i)[0]
        self.i += 8
        return v

    def f4(self):
        v = struct.unpack_from(">f", self.d, self.i)[0]
        self.i += 4
        return v

    def f8(self):
        v = struct.unpack_from(">d", self.d, self.i)[0]
        self.i += 8
        return v

    def string(self):
        ln = self.u2()
        s = self.d[self.i:self.i + ln]
        self.i += ln
        return s.decode("utf-8", "replace")

    def payload(self, tag: int):
        if tag == 1:
            return self.u1()
        if tag == 2:
            return self.u2()
        if tag == 3:
            return self.i4()
        if tag == 4:
            return self.i8()
        if tag == 5:
            return self.f4()
        if tag == 6:
            return self.f8()
        if tag == 7:
            ln = self.i4()
            v = self.d[self.i:self.i + ln]
            self.i += ln
            return v
        if tag == 8:
            return self.string()
        if tag == 9:
            et = self.u1()
            ln = self.i4()
            return [self.payload(et) for _ in range(ln)]
        if tag == 10:
            out = {}
            while True:
                t = self.u1()
                if t == 0:
                    return out
                name = self.string()
                out[name] = self.payload(t)
        if tag == 11:
            ln = self.i4()
            v = list(struct.unpack_from(f">{ln}i", self.d, self.i))
            self.i += 4 * ln
            return v
        if tag == 12:
            ln = self.i4()
            v = list(struct.unpack_from(f">{ln}q", self.d, self.i))
            self.i += 8 * ln
            return v
        raise ValueError(f"unknown tag {tag} @ {self.i}")

    def root(self):
        t = self.u1()
        if t != 10:
            raise ValueError("root not compound")
        self.string()  # 根名
        return self.payload(10)


def section_bytes(sec: dict, buf) -> None:
    y = sec.get("Y", "?")
    buf.update(f"Y={y};".encode())
    bs = sec.get("block_states")
    if isinstance(bs, dict):
        palette = bs.get("palette", [])
        for p in palette:
            if isinstance(p, dict):
                buf.update(p.get("Name", "?").encode())
                props = p.get("Properties")
                if isinstance(props, dict):
                    for k in sorted(props):
                        buf.update(f"|{k}={props[k]}".encode())
                buf.update(b";")
        data = bs.get("data")
        if data is not None:
            buf.update(b"D:")
            buf.update(",".join(str(x) for x in data).encode())
        buf.update(b";")


def chunk_digest(root: dict, component: str = "all") -> bytes:
    h = hashlib.sha256()
    if component in ("all", "sections"):
        secs = root.get("sections", [])
        if isinstance(secs, list):
            for sec in sorted(secs, key=lambda s: s.get("Y", 0) if isinstance(s, dict) else 0):
                if isinstance(sec, dict):
                    section_bytes(sec, h)
    if component in ("all", "be"):
        bes = root.get("block_entities", [])
        if isinstance(bes, list):
            ents = []
            for be in bes:
                if isinstance(be, dict):
                    ents.append((be.get("id", "?"), be.get("x"), be.get("y"), be.get("z")))
            for e in sorted(ents, key=lambda t: (t[1], t[2], t[3], t[0])):
                h.update(f"BE:{e};".encode())
    return h.digest()


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    per_chunk = "--per-chunk" in sys.argv
    components = ["sections", "be"] if "--split" in sys.argv else ["all"]
    for path in args:
        data = open(path, "rb").read()
        if len(data) < 8192:
            print(f"{path}: 空 region")
            continue
        digests = {}
        for idx in range(1024):
            off = struct.unpack_from(">I", data, idx * 4)[0] >> 8
            if off == 0:
                continue
            start = off * 4096
            ln = struct.unpack_from(">I", data, start)[0]
            comp = data[start + 4]
            payload = data[start + 5:start + 4 + ln]
            raw = zlib.decompress(payload) if comp == 2 else payload
            root = Nbt(raw).root()
            cx = (idx % 32)
            cz = (idx // 32)
            if per_chunk:
                for comp_name in components:
                    print(f"  chunk {cx},{cz} [{comp_name}] {chunk_digest(root, comp_name).hexdigest()[:16]}")
            digests[(cx, cz)] = chunk_digest(root)
        agg = hashlib.sha256()
        for key in sorted(digests):
            agg.update(f"{key[0]},{key[1]}:".encode())
            agg.update(digests[key])
        print(f"{path}: chunks={len(digests)} sha256={agg.hexdigest()}")


if __name__ == "__main__":
    main()

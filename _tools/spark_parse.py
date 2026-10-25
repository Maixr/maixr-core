#!/usr/bin/env python3
"""离线解析 spark 火焰图数据（.bin = application/x-spark-sampler，protobuf 裸流）。

用法：
  python _tools/spark_parse.py <spark.bin> [线程名过滤]

不依赖 spark 的 proto 定义：按 protobuf wire-format 通用遍历 + 结构自识别
（引用表 = 「小 id + 字符串」的重复组；调用树 = 「int 字段 + children 自相似」的递归组）。
输出指定线程的调用树热点（自耗时排序），供生成性能决策表归因。
"""
import struct
import sys
from collections import defaultdict


def read_varint(b: bytes, i: int):
    shift = val = 0
    while True:
        x = b[i]
        i += 1
        val |= (x & 0x7F) << shift
        if not (x & 0x80):
            return val, i
        shift += 7


def parse_fields(b: bytes):
    out = []
    i, n = 0, len(b)
    while i < n:
        tag, i = read_varint(b, i)
        fn, wt = tag >> 3, tag & 7
        if wt == 0:
            v, i = read_varint(b, i)
        elif wt == 1:
            v, i = b[i:i + 8], i + 8
        elif wt == 2:
            ln, i = read_varint(b, i)
            v, i = b[i:i + ln], i + ln
        elif wt == 5:
            v, i = b[i:i + 4], i + 4
        else:
            raise ValueError(f"wiretype {wt} @ {i}")
        out.append((fn, wt, v))
    return out


def looks_like_message(b: bytes) -> bool:
    if not b:
        return False
    try:
        parse_fields(b)
        return True
    except (ValueError, IndexError):
        return False


def main() -> int:
    path = sys.argv[1] if len(sys.argv) > 1 else ""
    thread_filter = sys.argv[2] if len(sys.argv) > 2 else "Server thread"
    data = open(path, "rb").read()
    top = parse_fields(data)

    # ---- 结构自识别：找「引用表 + 线程树」所在的 SamplerData ----
    # 引用表条目形如 (id=varint, name=string)；线程树条目含 children 递归组
    class_refs, method_refs = {}, {}
    threads = []

    def harvest_refs(fields):
        for fn, wt, v in fields:
            if wt != 2 or not v:
                continue
            try:
                sub = parse_fields(v)
            except (ValueError, IndexError):
                continue
            ids = [x for x in sub if x[1] == 0]
            strs = [x for x in sub if x[1] == 2 and isinstance(x[2], bytes)
                    and looks_like_str(x[2])]
            if len(ids) == 1 and len(strs) == 1:
                yield ids[0][2], strs[0][2].decode("utf-8", "replace")

    def looks_like_str(b: bytes) -> bool:
        try:
            b.decode("utf-8")
            return all(32 <= c < 127 or c in (9, 10, 13) or c >= 128 for c in b[:80])
        except UnicodeDecodeError:
            return False

    def walk(node_fields, depth=0):
        """递归识别 CallTraceNode：ints 里挑 class_ref/method_ref/time/self_time。"""
        pass  # 占位：主流程直接在 top 上识别

    # 顶层与两层内找 SamplerData（含大量引用条目与线程树的 len 字段）
    def find_sampler(fields, depth=0):
        cands = []
        for fn, wt, v in fields:
            if wt != 2 or not looks_like_message(v):
                continue
            sub = parse_fields(v)
            ref_count = sum(1 for s in harvest_refs(sub))
            has_deep = any(looks_like_message(x[2]) and len(x[2]) > 1000
                           for x in sub if x[1] == 2)
            cands.append((ref_count, has_deep, fn, sub, v, depth))
            if depth < 3:
                cands += find_sampler(sub, depth + 1)
        return cands

    cands = find_sampler(top)
    cands.sort(key=lambda c: (-c[0], -c[1]))
    if not cands:
        print("未找到 SamplerData 结构；顶层字段：")
        for fn, wt, v in top:
            size = len(v) if isinstance(v, bytes) else v
            print(f"  field {fn} wt{wt} size={size if isinstance(size,int) else size}")
        return 1
    ref_count, _, sampler_fn, sampler_fields, _, _ = cands[0]
    print(f"# SamplerData @ 顶层 field {sampler_fn}（引用条目 {ref_count} 个）")

    # 分离引用表与线程树：引用条目 = (1 id, 2 name) 或 (1 id, 2 class_ref, 3 name...)
    ref_like = defaultdict(list)
    other = defaultdict(list)
    for fn, wt, v in sampler_fields:
        if wt == 2 and v and looks_like_message(v):
            sub = parse_fields(v)
            ints = [x for x in sub if x[1] == 0]
            strs = [x for x in sub if x[1] == 2 and looks_like_str(x[2])]
            if len(ints) >= 1 and len(strs) >= 1 and len(sub) <= 5:
                ref_like[fn].append(sub)
                continue
            other[fn].append(sub)
        else:
            other[fn].append((fn, wt, v))

    # 类引用：字段内含「id + 纯类名字符串(含 . 或 /)」；方法引用：id + (可能无字符串的 desc)
    for fn, subs in ref_like.items():
        names = []
        for sub in subs:
            strs = [x[2].decode("utf-8", "replace") for x in sub
                    if x[1] == 2 and looks_like_str(x[2])]
            ids = [x[2] for x in sub if x[1] == 0]
            if strs:
                names.append((ids[0] if ids else len(names), strs))
        if any(any("." in s or "/" in s for s in ss) for _, ss in names):
            for rid, ss in names:
                class_refs[rid] = ss[0]
        else:
            for rid, ss in names:
                method_refs[rid] = ss
    print(f"# 类引用 {len(class_refs)} 个，方法引用 {len(method_refs)} 个")

    # 线程树：other 里「含子节点递归」的组
    for fn, subs in other.items():
        for v in subs:
            if not isinstance(v, bytes):
                continue
            sub = parse_fields(v)
            ints = {x[0]: x[2] for x in sub if x[1] == 0}
            # ThreadNode: 含 thread 名 或 深层 children
            has_children = any(x[1] == 2 and looks_like_message(x[2])
                               and any(y[1] == 2 and looks_like_message(y[2])
                                       for y in parse_fields(x[2]) if x[1] == 2)
                               for x in sub if x[1] == 2)
            if has_children:
                threads.append(sub)

    print(f"# 候选线程树 {len(threads)} 棵")

    # ---- 解析 CallTraceNode：自相似结构（int 字段 + 2 型 children 字段）----
    def node_children(sub):
        return [parse_fields(x[2]) for x in sub if x[1] == 2 and isinstance(x, tuple)
                and isinstance(x[2], bytes) and looks_like_message(x[2])]

    def node_ints(sub):
        return {fn: v for fn, wt, v in sub if wt == 0}

    # 自识别字段号：取一棵树的第一层，children 字段号 = 出现最多的 2 型字段
    if threads:
        sample = threads[0]
        top_nodes = []
        for x in sample:
            if x[1] == 2 and isinstance(x[2], bytes) and looks_like_message(x[2]):
                top_nodes.append(parse_fields(x[2]))
        child_fn_counts = defaultdict(int)
        for nd in top_nodes[:50]:
            cfs = [fn for fn, wt, v in nd if wt == 2 and isinstance(v, bytes)
                   and looks_like_message(v)]
            for cf in cfs:
                child_fn_counts[cf] += 1
        children_fn = max(child_fn_counts, key=child_fn_counts.get) if child_fn_counts else 0
        int_fns = sorted({fn for nd in top_nodes[:50] for fn, wt, v in nd if wt == 0})
        print(f"# children 字段号 = {children_fn}，int 字段号 = {int_fns}")
        # time/self_time 的识别：值分布大的当 time；两两相减≈children 聚合的自耗时
        # 直接打印前若干节点辅助人工确认
        for nd in top_nodes[:5]:
            ints = {fn: v for fn, wt, v in nd if wt == 0}
            nchild = sum(1 for fn, wt, v in nd if wt == 2 and fn == children_fn)
            print(f"  node ints={ints} children={nchild}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

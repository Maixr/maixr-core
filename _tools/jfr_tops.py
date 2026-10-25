#!/usr/bin/env python3
"""聚合 jfr print --events jdk.ExecutionSample 的输出：按线程分组统计栈顶方法。

用法：python _tools/jfr_tops.py <gen-samples.txt> [线程名前缀过滤，如 Worker]
输出：每组线程的 TOP 自栈帧（类.方法）与相对占比。
"""
import re
import sys
from collections import defaultdict

def main():
    path = sys.argv[1]
    prefix = sys.argv[2] if len(sys.argv) > 2 else ""
    text = open(path, encoding="utf-8", errors="replace").read()
    # jfr print 格式：jdk.ExecutionSample { ... thread = "name" ... stackTrace = [ frames... ] }
    samples = re.split(r"jdk\.ExecutionSample \{", text)[1:]
    by_thread_frames = defaultdict(lambda: defaultdict(int))
    for s in samples:
        m = re.search(r'sampledThread = "([^"]+)"', s)
        if not m:
            continue
        thread = m.group(1)
        if prefix and not thread.startswith(prefix):
            continue
        fm = re.search(r"stackTrace = \[(.*?)\]", s, re.S)
        if not fm:
            continue
        frames = re.findall(r"^\s*(\S.*?) line:", fm.group(1), re.M)
        if not frames:
            continue
        top = frames[0]
        # 归一：去掉参数与包名，保留 类.方法
        cls = top.split("(")[0].strip()
        cls = cls.replace("com.maixr.", "c.m.").replace("net.minecraft.", "n.m.")
        cls = cls.replace("net.neoforged.", "n.n.").replace("java.util.concurrent.", "j.u.c.")
        by_thread_frames[thread][cls] += 1
    total = sum(sum(v.values()) for v in by_thread_frames.values())
    print(f"# 过滤前缀={prefix or '全部'} 样本={total}")
    for thread, frames in sorted(by_thread_frames.items(), key=lambda kv: -sum(kv[1].values())):
        t = sum(frames.values())
        print(f"\n===== {thread}  样本={t} =====")
        for cls, n in sorted(frames.items(), key=lambda kv: -kv[1])[:25]:
            print(f"  {100.0*n/t:5.1f}%  {cls}")

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
# PK 数据模拟：从应用内置 HTTP 导出题目/答案，再用应用自身仓库提交答案与用时
import json, sys, time, urllib.request, urllib.parse

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8765"
USE_MS = int(sys.argv[2]) if len(sys.argv) > 2 else 30000

def get(path):
    return urllib.request.urlopen(BASE + path, timeout=10).read().decode("utf-8", "replace")

def export():
    return json.loads(get("/pk/export"))

def submit(use_ms, answers=None):
    q = "use_time_ms=%d" % use_ms
    if answers:
        q += "&answers=" + urllib.parse.quote(answers, safe="")
    return get("/pk/submit?" + q)

def build_all_correct(ex):
    items = []
    for q in ex.get("questions", []):
        items.append("%d,%s,%d" % (q["i"], q["answer"], q.get("point", 10000)))
    return ";".join(items)

if __name__ == "__main__":
    print("waiting for PK match ...")
    ex = None
    for _ in range(120):
        ex = export()
        if ex.get("active") and ex.get("count", 0) > 0 and ex.get("serial"):
            break
        time.sleep(0.5)
    if not ex or not ex.get("active"):
        print("no active PK:", ex); sys.exit(1)
    print("serial=%s count=%s" % (ex.get("serial"), ex.get("count")))
    answers = build_all_correct(ex)
    print("submit use_time_ms=%d answers=%s..." % (USE_MS, answers[:80]))
    print(submit(USE_MS, answers))

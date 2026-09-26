#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
E听说 单词PK 独立客户端（不依赖 app 的业务/提交逻辑）
- 只从本机 app 的本地 HTTP 取账号配置(/account)；签名已本地化，不再依赖 app
- 词表/结构/开局/答题/提交/结果解析全部由本脚本直连 https://api.ets100.com 完成

协议（已实测还原）：
  url  = base + "/" + r
  body = Base64(内层JSON)                     # 内层 [{"r":r,"params":{...}}]
  sign = MD5("androidV3" + str(time) + body + "717e3cdc7314959fa2ef469d38f05673")
  outer = {"body":body,"head":{"sign","pid":"androidV3","time","time_second","version":"1.0"}}
  start : g/pk/start-word-pk  {unit_id,book_id,word_ids,question_ids,name,source,version}
          word_ids/question_ids 都是 "_" 拼接的字符串；10 道题
  submit: g/pk/submit-word    {serial_id,use_time,source,score_detail:[{point,answer,word_id,question_id}]}
          result: 1=胜 0=平 -1=负；add_bean 成长豆；point 0~10000

用法:
  python3 pk_client.py pk   [题数=10] [用时秒=0]              # 打一局
  python3 pk_client.py repk [题数=10] [局数=5] [用时秒=0] [线程数=1]   # 连打N局(可并发)
                                         线程数=0/auto => 动态增减并发
  python3 pk_client.py        [题数=10] [局数=5] [用时秒=0] [线程数=1]   # 省略 repk 也可
  加 -v / --verbose 可在批量/并发下打印每次请求的完整网络流程
  python3 pk_client.py tune   [每档秒数=4]                     # 自动测最优并发并保存
  python3 pk_client.py repk 10 200 0 auto                     # 用 tune 出的并发
  python3 pk_client.py probe                                  # 连通性自检
  python3 pk_client.py units                                  # 当前书的单元列表
  python3 pk_client.py struct 1,2,3                           # 查题目(调试)
"""
import base64, gzip, hashlib, http.client, json, random, re, sys, threading, time, urllib.error, urllib.parse, urllib.request

PID = "androidV3"
SECRET = "717e3cdc7314959fa2ef469d38f05673"      # libmd5.so 内常量
APP_API = "http://127.0.0.1:8765"


class Session:
    """每线程一个 keep-alive 连接：省掉每次请求的 TLS 握手/DNS"""

    def __init__(self, base):
        u = urllib.parse.urlsplit(base)
        self.host = u.hostname
        self.port = u.port or (443 if u.scheme == "https" else 80)
        self.https = (u.scheme == "https")
        self.local = threading.local()

    def _new(self):
        if self.https:
            return http.client.HTTPSConnection(self.host, self.port, timeout=30)
        return http.client.HTTPConnection(self.host, self.port, timeout=30)

    def close(self):
        c = getattr(self.local, "c", None)
        if c is not None:
            try:
                c.close()
            except Exception:
                pass
            self.local.c = None

    def post(self, path, body, headers):
        c = getattr(self.local, "c", None)
        if c is None:
            c = self._new()
            self.local.c = c
        try:
            c.request("POST", path, body=body, headers=headers)
            r = c.getresponse()
            return r.status, r.read(), r.headers
        except Exception:
            self.close()
            raise


def local_sign(data):
    """sign = MD5(pid + data + secret)，data = str(time) + body"""
    return hashlib.md5((PID + data + SECRET).encode("utf-8")).hexdigest()
PK_QUESTIONS = 10          # app 每局固定 10 道题
PK_FULL_POINT = 10000      # 每题满分


class Ctx:
    def __init__(self, acc):
        cfg = acc.get("config", {})
        self.acc = acc
        self.token = cfg.get("token", "")
        opts = cfg.get("options", {})
        self.ets_app = str(opts.get("i", opts.get("a", "0")))
        gz = opts.get("j", {}) or {}
        self.base = (gz.get("e") or gz.get("b") or "https://api.ets100.com").rstrip("/")
        self.referer = self.base
        self.ver = cfg.get("client_version", "")
        self.sign_url = APP_API + "/sign"
        self.cache = {}
        self.signer = local_sign          # 本地签名；自检失败会自动切回 app /sign
        self.trace = False                # -v 时打印详细网络流程
        self.plock = threading.Lock()
        self.session = Session(self.base)

    @staticmethod
    def _tid():
        n = threading.current_thread().name
        return n.replace("ThreadPoolExecutor-", "t").replace("MainThread", "t-main")

    @staticmethod
    def _brief(k, v):
        """参数简称：token 脱敏，长值截断，避免刷屏/堵管道"""
        if isinstance(v, str):
            if k == "token" or v.startswith("eyJ0eXAi"):
                return v[:12] + "..<%d>" % len(v)
            if len(v) > 64:
                return v[:64] + "..<%d>" % len(v)
            return v
        txt = json.dumps(v, ensure_ascii=False)
        if len(txt) > 64:
            return txt[:64] + "..<%d>" % len(txt)
        return txt

    def trace_req(self, path, inner, body, sign, ts):
        p = inner[0].get("params", {}) if inner else {}
        kv = " ".join("%s=%s" % (k, self._brief(k, v)) for k, v in p.items())
        with self.plock:
            print("[%s] ▶ %s" % (self._tid(), path), flush=True)
            print("       %s" % kv, flush=True)
            print("       sign=%s time=%s b64=%dB" % (sign, ts, len(body)), flush=True)

    def trace_resp(self, path, st, ms, txt):
        with self.plock:
            print("[%s] ◀ %s %s %.0fms %s" % (self._tid(), st, path, ms, txt[:160]), flush=True)


def load_ctx():
    with urllib.request.urlopen(APP_API + "/account", timeout=10) as r:
        return Ctx(json.loads(r.read().decode("utf-8", "replace")))


def app_sign(ctx, data):
    u = ctx.sign_url + "?s=" + urllib.parse.quote(data, safe="")
    with urllib.request.urlopen(u, timeout=15) as r:
        o = json.loads(r.read().decode("utf-8", "replace"))
    if not o.get("ok"):
        raise RuntimeError("sign failed: %s" % o)
    return o["sign"]


def HEADERS(ctx):
    return {
        "Content-Type": "application/json; charset=UTF-8",
        "Referer": ctx.referer,
        "User-Agent": "okhttp/4.9.3",
        "Accept-Encoding": "gzip",
    }


def _decode(raw, headers):
    if (headers.get("Content-Encoding") or "").lower() == "gzip" or raw[:2] == b"\x1f\x8b":
        try:
            raw = gzip.decompress(raw)
        except Exception:
            pass
    return raw.decode("utf-8", "replace")


def call(ctx, path, params=None):
    """path 即 r，例如 g/pk/count"""
    params = dict(params or {})
    t = int(time.time() * 1000)
    sn = str(t) + str(random.randint(1000, 9999))
    params.update({"system": "2", "global_client_version": ctx.ver, "sn": sn,
                   "token": ctx.token, "ets_app": ctx.ets_app, "sign_response": 2})
    inner = [{"r": path, "params": params}]
    s = json.dumps(inner, separators=(",", ":"), ensure_ascii=False).replace("\\/", "/")
    body = base64.b64encode(s.encode("utf-8")).decode("ascii")
    ts = str(t)
    sign = ctx.signer(ts + body)
    outer = {"body": body, "head": {"sign": sign, "pid": PID, "time": ts,
                                    "time_second": int(ts), "version": "1.0"}}
    data = json.dumps(outer, separators=(",", ":")).encode("utf-8")
    if ctx.trace:
        ctx.trace_req(path, inner, body, sign, ts)
    t0 = time.time()
    try:
        st, raw, hdr = ctx.session.post("/" + path, data, HEADERS(ctx))
        txt = _decode(raw, hdr)
    except Exception as e:
        if ctx.trace:
            ctx.trace_resp(path, "ERR", (time.time() - t0) * 1000, str(e))
        raise
    if ctx.trace:
        ctx.trace_resp(path, st, (time.time() - t0) * 1000, txt)
    return st, txt


def verify_sign(ctx):
    """本地签名 vs app 原生签名 自检；不一致则回退到 /sign"""
    probe = str(int(time.time() * 1000)) + "verifysign"
    want = local_sign(probe)
    try:
        got = app_sign(ctx, probe)
    except Exception as e:
        return {"ok": True, "mode": "local", "note": "app /sign 不可用(%s)，使用本地签名" % e}
    if got == want:
        ctx.signer = local_sign
        return {"ok": True, "mode": "local", "sign": want}
    ctx.signer = lambda d: app_sign(ctx, d)
    return {"ok": False, "mode": "app", "local": want, "app": got}


def parse(js):
    try:
        o = json.loads(js)
    except Exception:
        return {"_raw": js}
    if isinstance(o, list) and o:
        o = o[0]
    return o


def api(ctx, path, params=None):
    st, bd = call(ctx, path, params)
    o = parse(bd)
    if not isinstance(o, dict) or o.get("code") != 0:
        raise RuntimeError("%s -> %s %s" % (path, st, bd[:300]))
    return o.get("body")


# ---------------- 当前 PK 上下文（从 app 的 p7 store 推导） ----------------

def pk_ids(acc):
    p7 = acc.get("p7", {})
    unit_key = next((k for k in p7 if k.startswith("sp_pk_unit_id_")), None)
    if not unit_key:
        raise RuntimeError("未找到 sp_pk_unit_id_*，请先在 app 里进入一次单词PK")
    pid = int(unit_key.rsplit("_", 1)[1])
    return {
        "parent_account_id": pid,
        "unit_id": int(p7[unit_key]),
        "book_id": int(p7["sp_pk_book_id_%d" % pid]),
        "resource_id": int(p7["sp_pk_book_res_id_%d" % pid]),
        "unit_name": p7.get("sp_pk_unit_name_%d" % pid, ""),
        "book_name": p7.get("sp_pk_book_name_%d" % pid, ""),
    }


def fetch_questions(ctx, ids, count=PK_QUESTIONS):
    """取单词结构，返回 count 组 (word_id, question_id, answer)（按单元缓存）"""
    ck = (ids["unit_id"], count)
    if ck in ctx.cache:
        return ctx.cache[ck]
    picked = _fetch_questions(ctx, ids, count)
    ctx.cache[ck] = picked
    return picked


def _fetch_questions(ctx, ids, count=PK_QUESTIONS):
    words = api(ctx, "g/set/word-list", {"unit_id": ids["unit_id"],
                                         "resource_id": ids["resource_id"],
                                         "parent_account_id": ids["parent_account_id"]})
    wids = [w["id"] for c in words.get("data", []) for w in c.get("words", [])]
    if not wids:
        raise RuntimeError("该单元没有单词")
    structs = api(ctx, "g/word/struct", {"word_ids": wids}).get("data", [])
    picked = []
    for s in structs:
        for q in s.get("question", []):
            if q.get("question_type") == 19 and q.get("answer"):
                picked.append((s["id"], q["id"], q["answer"]))
                break
        if len(picked) >= count:
            break
    if len(picked) < count:
        raise RuntimeError("可用题目不足: %d < %d" % (len(picked), count))
    return picked


def play(ctx, count=PK_QUESTIONS, use_time=0, verbose=True):
    """完整一局：开局 -> 满分提交 -> 返回结果字典"""
    ids = pk_ids(ctx.acc)
    if ctx.trace:
        with ctx.plock:
            print("[%s] -- 取题目 unit=%s(%s) 需要 %d 道" % (ctx._tid(), ids["unit_name"], ids["unit_id"], count), flush=True)
    picked = fetch_questions(ctx, ids, count)
    if ctx.trace:
        with ctx.plock:
            print("[%s]    %s 道题: %s" % (ctx._tid(), len(picked),
                  " ".join("%s/%s=%s" % (w, q, a) for w, q, a in picked)), flush=True)
    body = api(ctx, "g/pk/start-word-pk", {
        "unit_id": ids["unit_id"], "book_id": ids["book_id"],
        "word_ids": "_".join(str(w) for w, _, _ in picked),
        "question_ids": "_".join(str(q) for _, q, _ in picked),
        "name": "", "source": 0, "version": 1})
    serial = body["serial_id"]
    opp = body.get("opponent", {}).get("score", {})
    if verbose:
        print("book=%s unit=%s(%s)" % (ids["book_name"], ids["unit_name"], ids["unit_id"]))
        print("serial=%s  对手: %s 分 / %s 秒" % (serial, opp.get("point"), opp.get("use_time")))
    detail = [{"point": PK_FULL_POINT, "answer": a, "word_id": w, "question_id": q}
              for w, q, a in picked]
    if ctx.trace:
        with ctx.plock:
            print("[%s] -- 提交 %d 题 满分=%d 用时=%ds" % (ctx._tid(), len(detail), PK_FULL_POINT, use_time), flush=True)
    res = api(ctx, "g/pk/submit-word", {"serial_id": serial, "use_time": use_time,
                                        "source": 0, "score_detail": detail})
    r = res.get("result")
    res["_opp_point"] = opp.get("point")
    res["_opp_time"] = opp.get("use_time")
    res["_my_time"] = use_time
    res["_serial"] = serial
    if verbose:
        print("我方: %s 分 / %s 秒" % (res.get("point"), use_time))
        print("结果: %s   成长豆+%s" % ({1: "胜", 0: "平", -1: "负"}.get(r, r), res.get("add_bean")))
    return res


TUNE_FILE = "/tmp/.pk_tune.json"


def load_tuned():
    try:
        with open(TUNE_FILE, encoding="utf-8") as f:
            return int(json.load(f)["threads"])
    except Exception:
        return 8


def save_tuned(n):
    try:
        with open(TUNE_FILE, "w", encoding="utf-8") as f:
            json.dump({"threads": n, "at": int(time.time())}, f)
    except Exception:
        pass


def tune(ctx, secs=4, cands=(1, 3, 5, 8, 12, 20, 30, 50)):
    """实测各并发下的请求速率，挑最快的写盘；返回推荐线程数"""
    print("并发探测(每档 %ds, 探针=g/pk/count):" % secs, flush=True)
    best, best_rps = 8, 0.0
    for th in cands:
        cnt = [0]
        lock = threading.Lock()
        stop = time.time() + secs

        def w():
            while time.time() < stop:
                try:
                    call(ctx, "g/pk/count", {})
                    with lock:
                        cnt[0] += 1
                except Exception:
                    pass

        ts = [threading.Thread(target=w) for _ in range(th)]
        for t in ts:
            t.start()
        for t in ts:
            t.join()
        rps = cnt[0] / float(secs)
        print("  线程 %-3d -> %6.1f 请求/秒" % (th, rps), flush=True)
        if rps > best_rps:
            best_rps, best = rps, th
    print("推荐线程数 = %d (%.1f 请求/秒)  [已保存 %s]" % (best, best_rps, TUNE_FILE), flush=True)
    save_tuned(best)
    return best


def _fmt(sec):
    sec = int(max(0, sec))
    return "%d:%02d" % (sec // 60, sec % 60) if sec < 3600 else "%d:%02d:%02d" % (sec // 3600, (sec % 3600) // 60, sec % 60)


def run_many(ctx, count=PK_QUESTIONS, times=5, use_time=0, threads=1):
    """批量 PK。threads<=0 时动态增减并发；TTY 下底部实时显示进度/速度/剩余"""
    dynamic = int(threads) <= 0
    lock = threading.RLock()
    st = {"win": 0, "draw": 0, "lose": 0, "err": 0, "bean": 0}
    lat, opp_t = [], []
    state = {"next": 0, "done": 0, "target": 32 if dynamic else max(1, int(threads)),
             "active": set(), "stop": False}
    t0 = time.time()
    live = sys.stdout.isatty() and not ctx.trace
    if live and dynamic:
        print("动态并发模式：起始 32，按实时速率自动增减 (16~128)")

    def one_round():
        t1 = time.time()
        last = None
        for attempt in (1, 2, 3):
            try:
                return play(ctx, count, use_time, verbose=False), time.time() - t1
            except Exception as e:
                last = e
                time.sleep(0.4 * attempt)
        raise last

    def worker(slot):
        while True:
            with lock:
                if state["stop"] or slot >= state["target"] or state["next"] >= times:
                    state["active"].discard(slot)
                    return
                i = state["next"]
                state["next"] += 1
            try:
                r, el = one_round()
                res, bean = r.get("result"), r.get("add_bean") or 0
                with lock:
                    if res == 1: st["win"] += 1
                    elif res == 0: st["draw"] += 1
                    else: st["lose"] += 1
                    st["bean"] += bean
                    lat.append(el)
                    if r.get("_opp_time") is not None:
                        opp_t.append(r["_opp_time"])
                if not live:
                    print("#%-4d result=%-2s point=%-6s bean=%s  用时 %.2fs"
                          % (i + 1, res, r.get("point"), bean, el), flush=True)
            except Exception as e:
                with lock:
                    st["err"] += 1
                if not live:
                    print("#%-4d error %s" % (i + 1, e), flush=True)
            finally:
                with lock:
                    state["done"] += 1

    def spawn():
        with lock:
            if state["stop"]:
                return False
            slot = next((x for x in range(state["target"]) if x not in state["active"]), None)
            if slot is None:
                return False
            state["active"].add(slot)
        threading.Thread(target=worker, args=(slot,), name="pk-%d" % slot, daemon=True).start()
        return True

    def printer():
        last_log = 0.0
        while True:
            with lock:
                if state["stop"]:
                    return
                d, tgt = state["done"], state["target"]
            el = time.time() - t0
            rate = d / el if el > 0 else 0
            eta = (times - d) / rate if rate > 0 else 0
            line = "进度 %d/%d (%.1f%%) | %.2f 局/s | 并发 %d | 已用 %s | 剩余 %s" % (
                d, times, d * 100.0 / times, rate, tgt, _fmt(el), _fmt(eta))
            if live:
                sys.stdout.write("\r" + line + " " * 8)
                sys.stdout.flush()
            elif time.time() - last_log >= 10:
                last_log = time.time()
                print(line, flush=True)
            time.sleep(0.5)

    def controller():
        """峰值追踪：接近历史最好就加并发，明显掉速就回撤（4~24 线程）"""
        FLOOR, CAP = 16, 128
        best = 0.0
        last_done, last_t = 0, time.time()
        while True:
            with lock:
                if state["stop"]:
                    return
            time.sleep(2.5)
            with lock:
                if state["stop"]:
                    return
                d, tgt = state["done"], state["target"]
            now = time.time()
            dt = now - last_t
            dd = d - last_done
            last_done, last_t = d, now
            if dt <= 0 or dd < 3:                 # 样本太少，不调
                continue
            rate = dd / dt
            if rate >= best * 0.95:
                best = max(best, rate)
                nt = min(CAP, tgt + 2)            # 变快/持平 -> 加
            else:
                nt = max(FLOOR, int(tgt * 0.7))   # 明显掉速 -> 回撤
            if nt != tgt:
                with lock:
                    state["target"] = nt

    if live:
        threading.Thread(target=printer, daemon=True).start()
    if dynamic:
        threading.Thread(target=controller, daemon=True).start()
    try:
        while True:
            with lock:
                left = times - state["next"]
                active = len(state["active"])
                tgt = state["target"]
            if left <= 0 and active == 0:
                break
            if active < min(tgt, max(1, left)):
                spawn()
            time.sleep(0.05)
    except KeyboardInterrupt:
        print("\n中断中...", flush=True)
    finally:
        with lock:
            state["stop"] = True
        time.sleep(0.7)
    if live:
        sys.stdout.write("\r" + " " * 110 + "\r")
        sys.stdout.flush()

    wall = time.time() - t0
    done = times - st["err"]
    print("=" * 52)
    print("共 %d 局 | 并发 %s | 胜 %d 平 %d 负 %d 失败 %d | 成长豆 +%d"
          % (times, ("动态(结束 %d)" % state["target"]) if dynamic else state["target"],
             st["win"], st["draw"], st["lose"], st["err"], st["bean"]), flush=True)
    print("总耗时 %.2f 秒 | 吞吐 %.1f 局/分 (%.2f 局/秒)"
          % (wall, (done / wall * 60) if wall > 0 else 0, (done / wall) if wall > 0 else 0), flush=True)
    if lat:
        lt = sorted(lat)
        print("单局延迟 平均 %.2f 秒 | 最快 %.2f | 中位 %.2f | 最慢 %.2f"
              % (sum(lt) / len(lt), lt[0], lt[len(lt) // 2], lt[-1]), flush=True)
    print("我方用时 %d 秒/局 | 对手用时 %s"
          % (use_time,
             ("平均 %.1f 秒 (最快 %d / 最慢 %d)" % (sum(opp_t) / len(opp_t), min(opp_t), max(opp_t)))
             if opp_t else "无"), flush=True)
    return st


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    args = [a for a in sys.argv[1:] if a not in ("-v", "--verbose")]
    trace = len(args) != len(sys.argv) - 1
    if not args:
        print(__doc__)
        return
    cmd = args[0]
    KNOWN = ("pk", "repk", "tune", "probe", "units", "struct", "help", "-h", "--help")
    if cmd not in KNOWN:
        if re.fullmatch(r"\d+", cmd):
            args = ["repk"] + args            # 直接给数字 => 当作 repk
            cmd = "repk"
        else:
            print("未知命令: %s\n" % cmd)
            print(__doc__)
            return
    if cmd in ("help", "-h", "--help"):
        print(__doc__)
        return
    sys.argv = [sys.argv[0]] + args
    ctx = load_ctx()
    ctx.trace = trace
    print("base=%s ets_app=%s token=%s..." % (ctx.base, ctx.ets_app, ctx.token[:18]))
    if cmd == "probe":
        print("sign:", json.dumps(verify_sign(ctx), ensure_ascii=False))
        print(call(ctx, "g/pk/count", {}))
    elif cmd == "pk":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else PK_QUESTIONS
        use_s = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        _t0 = time.time()
        r = play(ctx, n, use_s)
        print("本局端到端耗时 %.2f 秒" % (time.time() - _t0))
        print(json.dumps(r, ensure_ascii=False))
    elif cmd == "repk":
        n = int(sys.argv[2]) if len(sys.argv) > 2 else PK_QUESTIONS
        times = int(sys.argv[3]) if len(sys.argv) > 3 else 5
        use_s = int(sys.argv[4]) if len(sys.argv) > 4 else 0
        th = sys.argv[5] if len(sys.argv) > 5 else "1"
        threads = 0 if str(th).lower() in ("auto", "0", "-1") else int(th)
        run_many(ctx, n, times, use_s, threads)
    elif cmd == "tune":
        secs = int(sys.argv[2]) if len(sys.argv) > 2 else 4
        tune(ctx, secs)
    elif cmd == "units":
        ids = pk_ids(ctx.acc)
        print(call(ctx, "g/set/unit-list", {"resource_id": str(ids["resource_id"]),
                                            "book_id": ids["book_id"], "is_need_score": 0,
                                            "filter_noword": 0}))
    elif cmd == "struct":
        ids = pk_ids(ctx.acc)
        print(call(ctx, "g/word/struct", {"word_ids": [int(x) for x in sys.argv[2].split(",") if x]}))
    else:
        print(__doc__)


if __name__ == "__main__":
    main()

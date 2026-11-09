import json, random, sys, time, urllib.request
URL = "http://127.0.0.1:8091"
def gclog(n_min=10, full_at=None, extra_lines=0):
    rnd = random.Random("order-04"); out = []
    for i in range(n_min * 6 + extra_lines):
        mm, ss = 6 + i // 6 % 60, (i % 6) * 10
        ms = round(rnd.uniform(4, 40) if i % 17 else rnd.uniform(80, 260), 3)
        out.append(f"[2026-11-08T20:{mm:02d}:{ss:02d}.512+0800][info][gc] GC({1200+i}) Pause Young (Normal) (G1 Evacuation Pause) 412M->188M(1024M) {ms}ms")
        if full_at is not None and i == full_at:
            out.append(f"[2026-11-08T20:{mm:02d}:{ss+3:02d}.118+0800][info][gc] GC({1200+i}) Pause Full (G1 Compaction Pause) 1019M->611M(1024M) 1873.402ms")
    return "\n".join(out)
SYS = "你是值班助手。只根据用户给的日志回答，日志里没有的不要编。"
def ask(log, q, **kw):
    body = {"messages": [{"role": "system", "content": SYS}, {"role": "user", "content": f"order-04 最近 10 分钟的 GC 日志：\n{log}\n\n{q}"}],
            "max_tokens": kw.pop("max_tokens", 120), "temperature": kw.pop("temperature", 0), **kw}
    req = urllib.request.Request(URL + "/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    t0 = time.time(); r = json.load(urllib.request.urlopen(req)); r["_wall"] = round(time.time() - t0, 3); return r
Q = "订单服务 20:15 变慢，是不是 order-04 的 GC 导致的？用一两句话回答，并引用日志里最关键的一行。"
mode = sys.argv[1]
if mode == "main":
    log = gclog(full_at=40)
    r = ask(log, Q, logprobs=True, top_logprobs=5, cache_prompt=False)
    print(json.dumps({"usage": r["usage"], "timings": r.get("timings"), "wall": r["_wall"], "finish": r["choices"][0]["finish_reason"]}, ensure_ascii=False))
    print(r["choices"][0]["message"]["content"])
    for t in r["choices"][0]["logprobs"]["content"][:12]:
        print(repr(t["token"]), [(c["token"], round(2.718281828 ** c["logprob"], 3)) for c in t["top_logprobs"]])
elif mode == "temp":
    log = gclog(full_at=40)
    for T in (0, 1.0):
        for k in range(4):
            r = ask(log, Q, temperature=T, max_tokens=40, seed=k + 1)
            print(T, k, repr(r["choices"][0]["message"]["content"][:70]))
elif mode == "len":
    for n in (1, 3):
        r = ask(gclog(n_min=n, full_at=None), Q, max_tokens=16, cache_prompt=False)
        t = r["timings"]; print(n, r["usage"]["prompt_tokens"], round(t["prompt_ms"]), round(t["prompt_per_second"]), t["predicted_n"], round(t["predicted_per_second"], 1), r["_wall"])
elif mode == "cache":
    log = gclog(full_at=40)
    for k in range(2):
        r = ask(log, Q, max_tokens=16, cache_prompt=True)
        t = r["timings"]; print(k, t.get("cache_n"), t["prompt_n"], round(t["prompt_ms"]), r["_wall"])
elif mode == "fact":
    log = gclog(full_at=40)
    for q in ["日志里一共有几次 Full GC？分别在几点、各停了多少毫秒？", "order-04 的堆最大是多少？Full GC 前后堆用量各是多少？"]:
        r = ask(log, q, max_tokens=80)
        print(q, "=>", repr(r["choices"][0]["message"]["content"]), r["timings"]["cache_n"], r["_wall"])

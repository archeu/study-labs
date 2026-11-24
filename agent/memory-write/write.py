"""第 8 站：会话结束后写记忆。三步：抽取 → 去重（找最像的旧记忆）→ 决定 ADD / UPDATE / MERGE / NOOP。

模型调用走 OpenAI 兼容的 /v1/chat/completions，用 response_format 的 json_schema 让输出一定是合法 JSON
（本机 llama-server 的 /v1/messages 会忽略 output_config.format；换成 Claude 用 output_config.format 即可）。
"""

import json
import math
import os
import urllib.request

LLM = os.environ.get("LLM_URL", "http://127.0.0.1:8090/v1/chat/completions")
EMB = os.environ.get("EMBED_URL", "http://127.0.0.1:8091/v1/embeddings")
NO_THINK = {"chat_template_kwargs": {"enable_thinking": False}}


def post(url, body):
    req = urllib.request.Request(url, json.dumps(body).encode(), {"content-type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=1800))


def chat_json(system, user, schema, max_tokens=700):
    r = post(LLM, {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
                   "max_tokens": max_tokens, "temperature": 0,
                   "response_format": {"type": "json_schema", "json_schema": {"name": "out", "schema": schema}},
                   **NO_THINK})
    return json.loads(r["choices"][0]["message"]["content"])


def embed(texts):
    data = post(EMB, {"input": texts})["data"]
    return [d["embedding"] for d in sorted(data, key=lambda d: d["index"])]


def cos(a, b):
    return sum(x * y for x, y in zip(a, b)) / math.sqrt(sum(x * x for x in a) * sum(y * y for y in b))


# ---------- 1. 抽取 ----------
EXTRACT_SYSTEM = """你从一次值班会话记录里抽取值得长期记住的条目。每条只讲一件事，一两句话，带上原始数字和日期。
类型：
- episodic 情景：这次发生了什么、原因、怎么处理的。key 写服务名。
- semantic 语义：某个对象的事实或状态（配置、版本、某个问题是否已修复）。key 写“服务名/属性”。
- procedural 程序性：值班人员明确说出的做法或禁令。key 写适用范围（服务名或 *）。
- temporary 临时：只在一段时间内有效的安排，必须给 expires（ISO 时间）。
source 写这条依据的是谁说的：tool（工具结果）、human（值班人员原话）、model（助手的推断）。
不要抽取寒暄、过程性的话、没有被证实的猜测。"""

EXTRACT_SCHEMA = {"type": "object", "properties": {"items": {"type": "array", "items": {
    "type": "object", "properties": {
        "kind": {"type": "string", "enum": ["episodic", "semantic", "procedural", "temporary"]},
        "key": {"type": "string"}, "text": {"type": "string"},
        "source": {"type": "string", "enum": ["tool", "human", "model"]},
        "expires": {"type": ["string", "null"]}},
    "required": ["kind", "key", "text", "source", "expires"]}}}, "required": ["items"]}


def extract(transcript, now):
    return chat_json(EXTRACT_SYSTEM, f"现在是 {now}。会话记录：\n{transcript}", EXTRACT_SCHEMA)["items"]


# ---------- 2. 去重：找最像的旧记忆 ----------
def neighbors(cands, memories, k=2):
    cv = embed([c["text"] for c in cands])
    mv = embed([m.text for m in memories])
    out = []
    for c, v in zip(cands, cv):
        scored = sorted(((cos(v, w), m) for m, w in zip(memories, mv)), key=lambda t: -t[0])[:k]
        out.append(scored)
    return out


# ---------- 3. 决定 ----------
DECIDE_SYSTEM = """你管理值班助手的长期记忆。给你一条新候选和几条最像的旧记忆，决定怎么写：
- ADD：旧记忆里没有这件事，新增。
- MERGE：和某条旧记忆说的是同一件事，把新信息并进去，给出合并后的文本。
- UPDATE：新候选说明某条旧记忆已经过时（状态变了、值变了），给出更新后的文本；旧文本会进历史，不会删除。
- NOOP：旧记忆已经完整包含它，什么都不做。
target 写被 MERGE / UPDATE / NOOP 的旧记忆 id，ADD 时写 null。reason 用一句话说明。"""

DECIDE_SCHEMA = {"type": "object", "properties": {
    "op": {"type": "string", "enum": ["ADD", "MERGE", "UPDATE", "NOOP"]},
    "target": {"type": ["string", "null"]}, "text": {"type": "string"}, "reason": {"type": "string"}},
    "required": ["op", "target", "text", "reason"]}


def decide(cand, near, already_added=()):
    olds = "\n".join(f"- {m.id}（{m.kind}，{m.date}）{m.text}  相似度 {s:.2f}" for s, m in near)
    adds = "\n".join(f"- {a}" for a in already_added) or "（无）"
    user = (f"新候选（{cand['kind']}，来源 {cand['source']}）：{cand['text']}\n\n"
            f"最像的旧记忆：\n{olds}\n\n本次会话已经新增的条目：\n{adds}")
    return chat_json(DECIDE_SYSTEM, user, DECIDE_SCHEMA, max_tokens=300)


# ---------- 加了护栏的版本 ----------
EXTRACT_SYSTEM_V2 = EXTRACT_SYSTEM + """
写法要求：
- episodic 的 text 必须写成“日期 时间 服务 现象：原因。处理：……”，带原始数字。
- 会话证明某个旧问题已修复、某个配置已变化时，单独抽一条 semantic，key 写“服务名/那个问题或配置”，text 写现在的状态和依据。
- 只有值班人员原话里明确的“以后……”“不要……”才是 procedural；助手的建议不是。
- expires 只给 temporary，其余一律 null。
例：
{"kind":"episodic","key":"order-svc","text":"2026-11-08 20:16 order-svc Full GC：按月导出一次读 41 万行进内存。处理：改分批导出，待发布。","source":"tool","expires":null}
{"kind":"temporary","key":"值班","text":"值班人员 11-09 15:00 前在开会，有事找备班。","source":"human","expires":"2026-11-09T15:00:00+08:00"}"""

MERGE_MIN = 0.80   # 同类型、同服务、相似度不低于它，才有资格合并或更新


def route(cand, memories, now_date="2026-11-09"):
    """代码决定能做什么，模型只在代码允许的范围里选。返回 (op, target, note, 候选邻居)。"""
    kind = cand["kind"]
    if cand["source"] == "model":
        return "SKIP", None, "来源是模型推断，不写", []
    if kind == "temporary":
        exp = cand.get("expires") or ""
        if not exp.startswith(now_date[:4]):
            return "SKIP", None, "临时记忆没有合法的过期时间，不写", []
        return "ADD", None, f"带 TTL 写入，{exp} 过期", []
    if kind == "procedural":
        return "PENDING", None, "规矩只登记为待确认，人点头后才生效", []
    if kind == "semantic":
        same = [m for m in memories if m.kind == "semantic" and m.key == cand["key"]]
        if same:
            return "UPDATE", same[0].id, "同一个 key 已有值：覆盖，旧值进历史", []
        return "ADD", None, "新 key，新增", []
    service = cand["key"].split("/")[0]
    pool = [m for m in memories if m.kind == "episodic" and m.key == service]   # 只和同服务的情景比
    if not pool:
        return "ADD", None, "这个服务没有旧病历，新增", []
    near = neighbors([cand], pool)[0]
    if near[0][0] < MERGE_MIN:
        return "ADD", None, f"最像的 {near[0][1].id} 只有 {near[0][0]:.3f}，低于 {MERGE_MIN}，新增", near
    return "ASK", None, "够像，交给模型在这几条里选", near

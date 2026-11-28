"""值班助手的多租户底座：租户上下文、按租户的数据范围、工具执行器、防篡改审计、结构化脱敏。

没有模型：模型“提议”的工具调用直接写在 demo.py 里（就是 tool_use 块的 name + input），
要看的是：同一个提议，执行器在不同的规则下会把什么数据交出去。
"""
import contextvars
import hashlib
import json
import re
import time
from dataclasses import dataclass

# ---------- 租户上下文：从虚拟 key 认出来以后，一路跟着请求走 ----------
@dataclass(frozen=True)
class Tenant:
    id: str
    user: str
    role: str            # viewer 只读 / oncall 值班 / lead 负责人


current: contextvars.ContextVar[Tenant] = contextvars.ContextVar("tenant")

# ---------- 数据：三个团队的机器、日志、知识库 ----------
HOSTS = {"order-04": "trade", "trade-db-1": "trade", "pay-02": "pay", "pay-gw-1": "pay", "search-03": "search"}
LOGS = {
    "order-04": ["20:04:50 WARN GC pause 820ms", "20:04:52 INFO user 13812345678 placed order 9527"],
    "pay-02": ["20:04:52 WARN HikariPool-1 Connection is not available (active=20, idle=0)",
               "20:04:53 INFO refund to card 6222 0202 0011 2233 446 ok, trace 4111111111111111"],
    "search-03": ["20:04:58 WARN slow query 2.1s"],
}
NOTES = [
    {"tenant": "trade", "text": "order-04 GC 停顿：把 -Xmx 从 4g 调到 8g 后恢复"},
    {"tenant": "pay", "text": "pay-02 连接池打满：合作方回调慢；应急时可临时把对账任务停掉，账号 recon_admin / Pay#2026"},
    {"tenant": "search", "text": "search-03 慢查询：重建索引"},
]

# 每个团队、每种角色能用哪些工具
POLICY = {
    "trade": {"tools": {"search_logs", "search_notes", "heap_dump"}},
    "pay": {"tools": {"search_logs", "search_notes"}},
    "search": {"tools": {"search_logs", "search_notes"}},
}
ROLE_TOOLS = {"viewer": {"search_notes"}, "oncall": {"search_logs", "search_notes"}, "lead": {"search_logs", "search_notes", "heap_dump"}}


# ---------- 脱敏 ----------
def luhn_ok(digits: str) -> bool:
    s, alt = 0, False
    for ch in reversed(digits):
        d = int(ch)
        if alt:
            d = d * 2 - 9 if d > 4 else d * 2
        s += d; alt = not alt
    return s % 10 == 0


PHONE = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")
CARD_LOOSE = re.compile(r"(?<!\d)\d{16,19}(?!\d)")                 # 网关那一页的写法：只看长度
CARD_SPACED = re.compile(r"(?<!\d)(?:\d[ -]?){15,18}\d(?!\d)")     # 允许空格和横线


def mask_regex(text: str) -> str:
    """网关式：对着自由文本猜。"""
    return CARD_LOOSE.sub("[卡号]", PHONE.sub("[手机号]", text))


def mask_structured(text: str) -> str:
    """工具侧：知道日志格式，按字段脱敏；卡号要过 Luhn，trace 后面的数字不动。"""
    text = PHONE.sub(lambda m: m.group(0)[:3] + "****" + m.group(0)[-4:], text)

    def card(m):
        digits = re.sub(r"\D", "", m.group(0))
        before = text[max(0, m.start() - 6):m.start()]
        if "trace" in before or not luhn_ok(digits):
            return m.group(0)
        return "[卡号 尾号" + digits[-4:] + "]"
    return CARD_SPACED.sub(card, text)


# ---------- 防篡改审计：每行带上一行的哈希 ----------
class Audit:
    def __init__(self, path: str):
        self.path, self.prev = path, "0" * 16
        open(path, "w").close()

    def write(self, **kv) -> None:
        rec = {"ts": round(time.time(), 3), **kv, "prev": self.prev}
        line = json.dumps(rec, ensure_ascii=False, sort_keys=True)
        self.prev = hashlib.sha256(line.encode()).hexdigest()[:16]
        with open(self.path, "a", encoding="utf-8") as f:
            f.write(line + "\n")

    @staticmethod
    def verify(path: str) -> int | None:
        """返回第一行对不上的行号（从 1 数）；全对返回 None。"""
        prev = "0" * 16
        for i, line in enumerate(open(path, encoding="utf-8"), 1):
            rec = json.loads(line)
            if rec["prev"] != prev:
                return i
            prev = hashlib.sha256(line.rstrip("\n").encode()).hexdigest()[:16]
        return None


# ---------- 工具执行器：模型提议，这里决定 ----------
def search_logs(host: str, scoped: bool) -> list[str]:
    t = current.get()
    if scoped and HOSTS.get(host) != t.id:
        raise PermissionError(f"{host} 不属于 {t.id}")
    return LOGS.get(host, [])


def search_notes(q: str, scoped: bool) -> list[str]:
    t = current.get()
    hits = [n for n in NOTES if any(w in n["text"] for w in q.split())]
    if scoped:
        hits = [n for n in hits if n["tenant"] == t.id]      # 过滤在检索这一步做，不靠提示词
    return [n["text"] for n in hits]


def execute(name: str, args: dict, *, scoped: bool, audit: Audit, masking: str = "structured") -> str:
    t = current.get()
    allowed = POLICY[t.id]["tools"] & ROLE_TOOLS[t.role] if scoped else {"search_logs", "search_notes", "heap_dump"}
    if name not in allowed:
        audit.write(tenant=t.id, user=t.user, tool=name, args=args, result="denied")
        return f"[拒绝] {t.user}（{t.id}/{t.role}）不能用 {name}"
    try:
        if name == "search_logs":
            out = search_logs(args["host"], scoped)
        elif name == "search_notes":
            out = search_notes(args["q"], scoped)
        else:
            out = [f"heap_dump of {args['host']} requested (等人批)"]
    except PermissionError as e:
        audit.write(tenant=t.id, user=t.user, tool=name, args=args, result="denied", why=str(e))
        return f"[拒绝] {e}"
    text = "\n".join(out)
    text = mask_structured(text) if masking == "structured" else mask_regex(text) if masking == "regex" else text
    audit.write(tenant=t.id, user=t.user, tool=name, args=args, result="ok",
                sha=hashlib.sha256(text.encode()).hexdigest()[:12], n_lines=len(out))   # 只记哈希，不记原文
    return text

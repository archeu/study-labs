"""被测的记忆系统：一份实现，8 个开关。全关 = v1（全追加 + 相似度前 3）；全开 = v2（本站前几页的规则）。

开关对应的页面：
  typed    记忆分几种：规矩按范围、事实按 key、情景打分取前 2（关掉 = 一锅端取前 3）
  upsert   记忆怎么写：语义记忆同 key 覆盖（关掉 = 追加）
  revoke   记忆怎么写：听“这条不对”和状态更新（关掉 = 旧的留着，新的另起一条）
  source   记忆怎么写：模型推断不写
  recency  记忆怎么取：打分里加新近项
  team     记忆怎么忘：读时带团队条件
  expire   记忆怎么忘：读时过滤过期的临时记忆
  redact   记忆怎么忘：写入时用宽规则脱敏
"""

import math
import re
from datetime import datetime

SWITCHES = ["typed", "upsert", "revoke", "source", "recency", "team", "expire", "redact"]
PHONE = re.compile(r"(?<!\d)1[3-9]\d[- ]?\d{4}[- ]?\d{4}(?!\d)")
SVC = re.compile(r"[a-z]+-svc")


def ts(s):
    return datetime.fromisoformat(s if " " in s else s + " 00:00")


class Store:
    def __init__(self, cfg):
        self.cfg, self.rows = cfg, []

    def apply(self, ev):
        c = self.cfg
        if ev[0] == "write":
            _, date, kind, team, key, text, imp, extra = ev
            if c["source"] and extra.get("source") == "model":
                return
            if c["redact"]:
                text = PHONE.sub("[手机号已脱敏]", text)
            row = dict(date=date, kind=kind, team=team, key=key, text=text, imp=imp,
                       expires=extra.get("expires"), status=extra.get("status"))
            if c["upsert"] and kind == "semantic":
                self.rows = [r for r in self.rows if not (r["kind"] == "semantic" and r["key"] == key and r["team"] == team)]
            self.rows.append(row)
        elif ev[0] == "status":
            _, date, mark, status = ev
            hit = [r for r in self.rows if mark in r["text"]]
            if c["revoke"]:
                for r in hit:
                    r["status"] = status                      # 改状态，原文不动
            elif hit:                                         # 不支持更新：另起一条
                h = hit[0]
                self.rows.append(dict(h, date=date, text=h["text"].split("修复")[0] + "修复：" + status, status=None))
        elif ev[0] == "revoke":
            _, date, mark = ev
            if c["revoke"]:
                self.rows = [r for r in self.rows if mark not in r["text"]]

    @staticmethod
    def render(r):
        return r["text"] + (f"（状态：{r['status']}）" if r.get("status") else "")

    def recall(self, q, team, at, sim):
        """sim(text) -> 余弦相似度。返回取回的记忆文本列表。"""
        c, now = self.cfg, ts(at)
        pool = [r for r in self.rows
                if (not c["team"] or r["team"] == team)
                and (not c["expire"] or not r["expires"] or ts(r["expires"]) > now)]
        services = set(SVC.findall(q))

        def ranked(rows, k):
            if not rows:
                return []
            rel = [sim(self.render(r)) for r in rows]
            rec = [0.5 ** ((now - ts(r["date"])).total_seconds() / 86400 / 30) for r in rows]
            imp = [r["imp"] / 10 for r in rows]
            mm = lambda xs: [(x - min(xs)) / (max(xs) - min(xs)) if max(xs) > min(xs) else 0.0 for x in xs]
            rel, rec, imp = mm(rel), mm(rec), mm(imp)
            s = [a + (b if c["recency"] else 0) + d for a, b, d in zip(rel, rec, imp)]
            return [rows[i] for i in sorted(range(len(rows)), key=lambda i: -s[i])[:k]]

        if not c["typed"]:
            out = ranked(pool, 3)
        else:
            proc = [r for r in pool if r["kind"] == "procedural" and (r["key"] == "*" or r["key"] in services)]
            sem = [r for r in pool if r["kind"] == "semantic" and r["key"].split("/")[0] in services]
            epi = ranked([r for r in pool if r["kind"] in ("episodic", "temporary")], 2)
            out = proc + sem + epi
        return [self.render(r) for r in out]


def check(case, texts):
    blob = "\n".join(texts)
    fails = [f"缺：{m}" for m in case.get("must", []) if m not in blob]
    fails += [f"不该有：{m}" for m in case.get("must_not", []) if m in blob]
    fails += [f"重复：{m}" for m in case.get("once", []) if sum(m in t for t in texts) != 1]
    return fails


def cos(a, b):
    return sum(x * y for x, y in zip(a, b)) / math.sqrt(sum(x * x for x in a) * sum(y * y for y in b))

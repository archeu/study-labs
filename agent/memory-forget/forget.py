"""值班助手的记忆表（SQLite 模拟）和每晚 02:00 的遗忘任务。

三件事分开做：
  过期（TTL）     —— 只给临时记忆：到点就删
  衰减 + 归档     —— 给情景记忆：分数随时间变低；低到门槛、又不重要、又很久没用的，挪进冷表，不删
  隐私            —— 写入时脱敏；夜里再用更宽的规则扫一遍
隔离不在这里做“删除”，而是每一次读都带 team 条件（见 recall）。
"""

import random
import re
import sqlite3
from datetime import date, timedelta

SCHEMA = """
CREATE TABLE memories(
  id INTEGER PRIMARY KEY, team TEXT, kind TEXT, text TEXT, importance INT,
  created TEXT, last_used TEXT, expires TEXT);
CREATE TABLE archive AS SELECT * FROM memories WHERE 0;
"""

PHONE_STRICT = re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)")               # 写入时用：只认 11 位连写
PHONE_WIDE = re.compile(r"(?<!\d)1[3-9]\d[- ]?\d{4}[- ]?\d{4}(?!\d)")  # 夜里用：也认 138-1234-5678、138 1234 5678
SERVICES = ["order-svc", "payment-svc", "cart-svc", "inventory-svc", "coupon-svc"]
START = date(2026, 8, 11)
DAYS = 90                                                           # 到 2026-11-08 为止
TODAY = START + timedelta(days=DAYS)                                # 2026-11-09 凌晨跑最后一次


def phone(rnd, dashed):
    d = f"1{rnd.choice('3589')}{rnd.randint(0, 9)}{rnd.randint(1000, 9999)}{rnd.randint(1000, 9999)}"
    return f"{d[:3]}-{d[3:7]}-{d[7:]}" if dashed else d


def generate(seed=20261109):
    """90 天里每天写入的记忆。返回 [(day, row)]，row 还没脱敏。"""
    rnd = random.Random(seed)
    out = []
    for i in range(DAYS):
        day = START + timedelta(days=i)
        for _ in range(rnd.randint(2, 4)):
            svc = rnd.choice(SERVICES)
            imp = rnd.choice([2, 3, 3, 4, 4, 5, 5, 6, 7, 8])
            text = f"{day:%m-%d} {svc} 告警：{rnd.choice(['接口变慢', 'CPU 高', '连接池打满', 'GC 频繁', '磁盘告警', '误报'])}。"
            if rnd.random() < 0.12:                                 # 日志里带出了客户手机号
                text += f" 日志片段：用户 {phone(rnd, rnd.random() < 0.4)} 下单失败。"
            out.append((day, dict(team="trade", kind="episodic", text=text, importance=imp, expires=None)))
        if rnd.random() < 0.35:                                     # 临时安排：一到三天后过期
            out.append((day, dict(team="trade", kind="temporary", text=f"{day:%m-%d} 值班人员说：今天下午有事找备班。",
                                  importance=1, expires=str(day + timedelta(days=rnd.randint(1, 3))))))
        if rnd.random() < 0.3:                                      # 别的团队
            out.append((day, dict(team="risk", kind="episodic", text=f"{day:%m-%d} risk-svc 告警：规则引擎变慢。",
                                  importance=5, expires=None)))
        if i == 5:                                                  # 一次 P0，85 天前
            out.append((day, dict(team="trade", kind="episodic", importance=10, expires=None,
                                  text=f"{day:%m-%d} order-svc P0：发布时迁移脚本锁表，全量下单失败 40 分钟。此后 DDL 必须评审。")))
        if i == 20:                                                 # 一条规矩
            out.append((day, dict(team="trade", kind="procedural", importance=9, expires=None,
                                  text="晚高峰 19:00–22:00 不做 heap dump。")))
    return out


def run(policy, seed=20261109):
    """policy: episodic = keep | ttl30 | decay；temp_ttl: bool；pii = none | write | write+night。"""
    rnd = random.Random(seed + 1)
    db = sqlite3.connect(":memory:")
    db.executescript(SCHEMA)
    by_day = {}
    for day, row in generate(seed):
        by_day.setdefault(day, []).append(row)
    for i in range(DAYS):
        day = START + timedelta(days=i)
        for row in by_day.get(day, []):
            text = row["text"]
            if policy["pii"] != "none":
                text = PHONE_STRICT.sub("[手机号已脱敏]", text)
            db.execute("INSERT INTO memories(team,kind,text,importance,created,last_used,expires) VALUES(?,?,?,?,?,?,?)",
                       (row["team"], row["kind"], text, row["importance"], str(day), str(day), row["expires"]))
        # 白天：取用几条（偏向最近的和重要的），更新 last_used
        ids = [r[0] for r in db.execute("SELECT id FROM memories WHERE team='trade' ORDER BY created DESC, importance DESC LIMIT 15")]
        for mid in rnd.sample(ids, min(3, len(ids))):
            db.execute("UPDATE memories SET last_used=? WHERE id=?", (str(day), mid))
        nightly(db, policy, day + timedelta(days=1))
    return db


def nightly(db, policy, now):
    if policy["temp_ttl"]:
        db.execute("DELETE FROM memories WHERE kind='temporary' AND expires <= ?", (str(now),))
    if policy["episodic"] == "ttl30":
        db.execute("DELETE FROM memories WHERE kind='episodic' AND created < ?", (str(now - timedelta(days=30)),))
    elif policy["episodic"] == "decay":
        rows = db.execute("SELECT id, importance, created, last_used FROM memories WHERE kind='episodic'").fetchall()
        for mid, imp, created, last_used in rows:
            age = (now - date.fromisoformat(created)).days
            idle = (now - date.fromisoformat(last_used)).days
            score = imp / 10 * 0.5 ** (age / 30)                   # 衰减分：重要性 × 半衰期 30 天
            if score < 0.05 and imp < 7 and idle > 30:             # 不重要、分数很低、一个月没用过 → 归档
                db.execute("INSERT INTO archive SELECT * FROM memories WHERE id=?", (mid,))
                db.execute("DELETE FROM memories WHERE id=?", (mid,))
    if policy["pii"] == "write+night":
        for mid, text in db.execute("SELECT id, text FROM memories").fetchall():
            new = PHONE_WIDE.sub("[手机号已脱敏]", text)
            if new != text:
                db.execute("UPDATE memories SET text=? WHERE id=?", (new, mid))


def recall(db, team, enforce_team=True):
    """召回时的过滤：team 是硬条件。enforce_team=False 用来演示漏掉它会怎样。"""
    sql = "SELECT team, kind, text FROM memories WHERE kind IN ('episodic','procedural','temporary')"
    args = ()
    if enforce_team:
        sql += " AND team=?"
        args = (team,)
    return db.execute(sql, args).fetchall()


def report(db):
    q = lambda sql, *a: db.execute(sql, a).fetchone()[0]
    active = q("SELECT COUNT(*) FROM memories WHERE team='trade'")
    return {
        "active": active,
        "archived": q("SELECT COUNT(*) FROM archive"),
        "p0_kept": q("SELECT COUNT(*) FROM memories WHERE text LIKE '%P0%'") > 0,
        "rule_kept": q("SELECT COUNT(*) FROM memories WHERE kind='procedural'") > 0,
        "temp_left": q("SELECT COUNT(*) FROM memories WHERE kind='temporary' AND expires <= ?", str(TODAY)),
        "phone_left": sum(1 for (t,) in db.execute("SELECT text FROM memories") if PHONE_WIDE.search(t)),
        "low_old": q("SELECT COUNT(*) FROM memories WHERE team='trade' AND kind='episodic' AND importance < 5 AND created < ?",
                     str(TODAY - timedelta(days=60))),
    }

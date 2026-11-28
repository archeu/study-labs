"""多租户的 6 个小实验：同一个工具提议，在“不分租户”和“按租户收紧”两种执行器下各交出了什么。

uv run python multi-tenant-audit/demo.py
"""
import asyncio
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from tenancy import Audit, Tenant, current, execute, mask_regex, mask_structured  # noqa: E402

AUDIT = os.path.join(HERE, "audit.jsonl")


def run_as(tenant: Tenant, name: str, args: dict, audit: Audit, **kw) -> dict:
    tok = current.set(tenant)
    try:
        return {s: execute(name, args, scoped=s, audit=audit, **kw) for s in (False, True)}
    finally:
        current.reset(tok)


def show(title, r):
    print(title)
    print("   不分租户：" + r[False].replace("\n", " ⏎ "))
    print("   按租户  ：" + (r[True].replace("\n", " ⏎ ") or "（没有结果）"))


def main():
    audit = Audit(AUDIT)
    pay = Tenant("pay", "zhang", "oncall")
    search = Tenant("search", "li", "oncall")

    show("== 1 支付团队的调查，模型提议查交易团队的机器 search_logs(order-04)",
         run_as(pay, "search_logs", {"host": "order-04"}, audit))
    show("== 2 搜索团队查知识库 search_notes(\"pay-02 连接池\")",
         run_as(search, "search_notes", {"q": "pay-02 连接池"}, audit))
    print("== 3 交易团队：只读角色和负责人都提议 heap_dump(order-04)")
    for role in ("viewer", "lead"):
        r = run_as(Tenant("trade", "wang", role), "heap_dump", {"host": "order-04"}, audit)
        print(f"   {role:<6}：{r[True]}")

    print("== 4 脱敏：网关式正则 对 工具侧按字段")
    lines = ["20:04:52 INFO user 13812345678 placed order 9527",
             "20:04:53 INFO refund to card 6222 0202 0011 2233 446 ok, trace 4111111111111111"]
    for ln in lines:
        print("   原文：" + ln)
        print("   正则：" + mask_regex(ln))
        print("   字段：" + mask_structured(ln))

    print("== 5 审计防篡改：改掉一行，看校验在哪一行断")
    n = sum(1 for _ in open(AUDIT, encoding="utf-8"))
    print(f"   写了 {n} 行，校验：{'全部对得上' if Audit.verify(AUDIT) is None else '有断点'}")
    rows = open(AUDIT, encoding="utf-8").read().splitlines()
    k = next(i for i, x in enumerate(rows) if '"denied"' in x)
    rows[k] = rows[k].replace('"denied"', '"ok"')                 # 有人想把一次越权改成正常
    open(AUDIT, "w", encoding="utf-8").write("\n".join(rows) + "\n")
    print(f"   把第 {k + 1} 行的 denied 改成 ok：校验在第 {Audit.verify(AUDIT)} 行断开")

    print("== 6 租户上下文跟不跟着走：协程、to_thread、run_in_executor")

    async def probe():
        current.set(pay)
        out = {}
        out["create_task"] = await asyncio.create_task(asyncio.sleep(0, result=current.get().id))
        out["to_thread"] = await asyncio.to_thread(lambda: current.get().id)
        try:
            out["run_in_executor"] = await asyncio.get_running_loop().run_in_executor(None, lambda: current.get().id)
        except LookupError:
            out["run_in_executor"] = "LookupError：线程里没有租户"
        return out
    for k, v in asyncio.run(probe()).items():
        print(f"   {k:<16}→ {v}")
    os.remove(AUDIT)


if __name__ == "__main__":
    main()

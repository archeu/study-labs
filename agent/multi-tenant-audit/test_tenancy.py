"""不变式：越权查不到别人的数据；卡号要过 Luhn；审计改一行就断。"""

from tenancy import Audit, Tenant, current, execute, luhn_ok, mask_structured


def test_scoped_executor_denies_cross_tenant(tmp_path):
    a = Audit(str(tmp_path / "a.jsonl"))
    current.set(Tenant("pay", "zhang", "oncall"))
    assert execute("search_logs", {"host": "order-04"}, scoped=True, audit=a).startswith("[拒绝]")
    current.set(Tenant("search", "li", "oncall"))
    assert execute("search_notes", {"q": "pay-02 连接池"}, scoped=True, audit=a) == ""


def test_luhn_and_trace():
    assert luhn_ok("6222020200112233446") and not luhn_ok("6222020200112233445")
    assert "4111111111111111" in mask_structured("trace 4111111111111111")


def test_audit_chain_detects_edit(tmp_path):
    p = str(tmp_path / "b.jsonl"); a = Audit(p)
    for i in range(3):
        a.write(i=i, result="denied")
    rows = open(p).read().splitlines(); rows[0] = rows[0].replace("denied", "ok")
    open(p, "w").write("\n".join(rows) + "\n")
    assert Audit.verify(p) == 2

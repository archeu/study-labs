"""不需要 Redis 的部分：合并键和幂等键。"""
from common import idem_key, incident_key


def test_same_window_merges():
    a = {"tenant": "trade", "startsAt_epoch": 1794398400, "labels": {"service": "order", "alertname": "P99High"}}
    b = {**a, "startsAt_epoch": 1794398400 + 299}
    c = {**a, "startsAt_epoch": 1794398400 + 300}
    assert incident_key(a) == incident_key(b) != incident_key(c)


def test_idem_key_ignores_arg_order():
    assert idem_key("i1", "create_ticket", {"a": 1, "b": 2}) == idem_key("i1", "create_ticket", {"b": 2, "a": 1})
    assert idem_key("i1", "create_ticket", {"a": 1}) != idem_key("i2", "create_ticket", {"a": 1})

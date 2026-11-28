"""网关里不需要起服务就能测的两块：脱敏和记账。"""
from gateway import account, meter, redact


def test_redact_phone_and_card_but_not_order_id():
    body = {"messages": [{"role": "user", "content": "用户 13812345678 卡 6222020200112233445 订单 9527"},
                         {"role": "user", "content": [{"type": "tool_result", "content": "回拨 15900001111"}]}]}
    assert redact(body) == 3
    assert body["messages"][0]["content"] == "用户 [手机号] 卡 [卡号] 订单 9527"
    assert body["messages"][1]["content"][0]["content"] == "回拨 [手机号]"


def test_account_uses_model_price():
    c = account("t1", "main", {"input_tokens": 1_000_000, "output_tokens": 0})
    assert abs(c - 3.0) < 1e-9 and meter["t1"]["calls"] == 1

import pytest
from main import parse
from conftest import fake_log

def test_parse_counts_and_max():
    h = parse("order-01", fake_log(12.0, 244.05, 33.1))
    assert h.pauses == 3
    assert h.max_ms == 244.05
    assert h.avg_ms == pytest.approx(96.4, abs=0.05)

def test_parse_empty_log_is_not_an_error():
    h = parse("order-04", "")
    assert h.error == "no pauses"
    assert h.max_ms is None

@pytest.mark.parametrize("line, expected", [
    ("... Pause Young (Normal) (G1 Evacuation Pause) 512M->128M(1024M) 23.456ms", 1),
    ("... Pause Full (System.gc()) 900M->300M(1024M) 812.000ms", 1),
    ("... Using 8 workers of 8 for evacuation", 0),
    ("... Pause Young 23.456 ms", 0),               # 单位前多了空格，正则不认
])
def test_which_lines_count(line, expected):
    assert parse("h", line).pauses == expected

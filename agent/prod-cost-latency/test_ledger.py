"""账本的几条不变式。"""
from ledger import Opt, investigate, per_incident


def test_baseline_tokens():
    b = investigate(Opt())
    assert b.itpm == 70450 and b.out == 1350 and b.calls == 6


def test_history_cache_reads_everything_but_the_new_part():
    b = investigate(Opt(prefix_cache=True, history_cache=True))
    assert b.itpm + b.read == investigate(Opt()).itpm      # 总输入不变，只是从“新算”挪到了“缓存读”


def test_streaming_changes_latency_not_cost():
    a = per_incident(Opt(prefix_cache=True))
    s = per_incident(Opt(prefix_cache=True, stream=True))
    assert a.usd == s.usd and s.ttft_report < a.ttft_report

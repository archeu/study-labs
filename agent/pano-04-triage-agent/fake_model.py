"""一个照剧本出牌的假模型：不联网、不要 key，返回和真 SDK 一样的 Message 对象。

站 04 的几个实验要的是“确定会发生的情况”（一直要工具不停、一轮要三个工具、提议危险操作），
真模型不一定配合，所以用它。用法：

    client = FakeClient([[("parse_gc_log", {})], [("heap_dump", {"reason": "看谁占内存"})], "结论：……"])
    client.messages.create(model=..., messages=..., tools=...)   # 每调一次，按顺序出下一张牌

剧本里每一项是一轮：一个 list 表示这一轮要哪些工具（可以多个＝并行），一个 str 表示这一轮直接回答。
剧本用完以后，repeat_last=True 时一直重复最后一轮（模拟“停不下来”的模型）。
"""

import itertools
import time

from anthropic.types import Message, TextBlock, ToolUseBlock, Usage

_ids = itertools.count(1)


def _approx_tokens(obj) -> int:
    return max(1, len(str(obj)) // 3)          # 粗估：中文约 1 字 1 token、英文约 4 字符 1 token，取个中间值


class _Messages:
    def __init__(self, script, repeat_last, delay):
        self.script, self.repeat_last, self.delay, self.calls = list(script), repeat_last, delay, 0

    def create(self, *, model, messages, max_tokens=1024, system=None, tools=None, **_):
        if self.calls < len(self.script):
            card = self.script[self.calls]
        elif self.repeat_last:
            card = self.script[-1]
        else:
            raise RuntimeError("剧本用完了")
        self.calls += 1
        time.sleep(self.delay)
        if isinstance(card, str):
            content, stop = [TextBlock(type="text", text=card)], "end_turn"
        else:
            content = [ToolUseBlock(type="tool_use", id=f"toolu_fake_{next(_ids):03d}", name=n, input=a) for n, a in card]
            stop = "tool_use"
        usage = Usage(input_tokens=_approx_tokens([system, tools, messages]), output_tokens=_approx_tokens(content))
        return Message(id=f"msg_fake_{self.calls}", type="message", role="assistant", model="fake", content=content,
                       stop_reason=stop, stop_sequence=None, usage=usage)


class FakeClient:
    def __init__(self, script, repeat_last=False, delay=0.0):
        self.messages = _Messages(script, repeat_last, delay)

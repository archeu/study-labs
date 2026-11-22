"""模型抽象层：业务代码只说“做哪一类事”，用哪家、哪个模型、什么参数、挂了换谁，全在配置里。

    layer = LLMLayer.from_file("config.json")
    r = layer.complete("triage", system=..., user=...)
    r.text / r.provider / r.model / r.usage（统一成 input / cached / output）/ r.fallback_from

两个适配器：
  anthropic  官方 anthropic SDK（Claude；本机 llama-server 的 /v1/messages 也能用）
  openai     OpenAI 兼容的 /v1/chat/completions（本地推理框架、很多国内服务），这里用标准库发
"""

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field

import anthropic


@dataclass
class Result:
    text: str
    provider: str
    model: str
    usage: dict
    ms: int
    dropped: list = field(default_factory=list)       # 因为模型不支持而丢掉的参数
    fallback_from: list = field(default_factory=list)  # 前面失败了的目标


class ModelError(Exception):
    """统一的错误：retryable 表示换一家或稍后重试可能成功。"""

    def __init__(self, msg: str, retryable: bool):
        super().__init__(msg)
        self.retryable = retryable


class AnthropicAdapter:
    def __init__(self, base_url: str | None, api_key: str):
        self.client = anthropic.Anthropic(base_url=base_url, api_key=api_key, max_retries=1, timeout=120)

    def call(self, model: str, system: str, user: str, params: dict) -> tuple[str, dict]:
        extra = {k: v for k, v in params.items() if k != "max_tokens"}   # 采样参数等：SDK 2.x 没有这些参数，走 extra_body
        try:
            m = self.client.messages.create(model=model, max_tokens=params.get("max_tokens", 512), system=system,
                                            messages=[{"role": "user", "content": user}],
                                            extra_body=extra or None)
        except (anthropic.APIConnectionError, anthropic.RateLimitError, anthropic.InternalServerError) as e:
            raise ModelError(f"{type(e).__name__}: {e}", retryable=True) from e
        except anthropic.APIStatusError as e:
            raise ModelError(f"{e.status_code}: {e}", retryable=e.status_code in (408, 409, 429) or e.status_code >= 500) from e
        u = m.usage
        return ("".join(b.text for b in m.content if b.type == "text"),
                {"input": u.input_tokens + (u.cache_read_input_tokens or 0), "cached": u.cache_read_input_tokens or 0,
                 "output": u.output_tokens})


class OpenAIAdapter:
    def __init__(self, base_url: str, api_key: str):
        self.base, self.key = base_url.rstrip("/"), api_key

    def call(self, model: str, system: str, user: str, params: dict) -> tuple[str, dict]:
        body = {"model": model, "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}], **params}
        req = urllib.request.Request(self.base + "/v1/chat/completions", json.dumps(body).encode(),
                                     {"Content-Type": "application/json", "Authorization": f"Bearer {self.key}"})
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                d = json.load(r)
        except urllib.error.HTTPError as e:
            raise ModelError(f"{e.code}", retryable=e.code in (408, 409, 429) or e.code >= 500) from e
        except urllib.error.URLError as e:
            raise ModelError(f"连接失败: {e.reason}", retryable=True) from e
        u = d.get("usage", {})
        cached = (u.get("prompt_tokens_details") or {}).get("cached_tokens", 0)
        return d["choices"][0]["message"]["content"], {"input": u.get("prompt_tokens", 0), "cached": cached,
                                                       "output": u.get("completion_tokens", 0)}


ADAPTERS = {"anthropic": AnthropicAdapter, "openai": OpenAIAdapter}


class LLMLayer:
    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.providers = {name: ADAPTERS[p["kind"]](p.get("base_url"), p.get("api_key", "none"))
                          for name, p in cfg["providers"].items()}

    @classmethod
    def from_file(cls, path: str) -> "LLMLayer":
        with open(path, encoding="utf-8") as f:
            return cls(json.load(f))

    def _params(self, model_cfg: dict, wanted: dict) -> tuple[dict, list]:
        """按模型能力过滤参数：比如 Claude Opus 5.5 不收 temperature，发了会 400。"""
        unsupported = set(model_cfg.get("unsupported", []))
        kept = {k: v for k, v in wanted.items() if k not in unsupported}
        return kept, sorted(set(wanted) - set(kept))

    def complete(self, task: str, system: str, user: str) -> Result:
        t = self.cfg["tasks"][task]
        failed = []
        for target in [t["primary"], *t.get("fallbacks", [])]:      # 主模型 → 备用模型，按顺序试
            m = self.cfg["models"][target]
            params, dropped = self._params(m, {**t.get("params", {})})
            t0 = time.perf_counter()
            try:
                text, usage = self.providers[m["provider"]].call(m["model"], system, user, params)
            except ModelError as e:
                failed.append(f"{target}（{str(e)[:60]}）")
                if not e.retryable:
                    raise                                            # 400 这类，换谁都没用
                continue
            return Result(text, m["provider"], m["model"], usage, int((time.perf_counter() - t0) * 1000), dropped, failed)
        raise ModelError("所有目标都失败了：" + "；".join(failed), retryable=True)

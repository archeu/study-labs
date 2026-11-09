import warnings, timeit, json, dataclasses
from pydantic import BaseModel, ConfigDict, Field, ValidationError, TypeAdapter
warnings.simplefilter("always")
# 1 annotation not enforced
def f(x: int) -> int: return x * 2
print("1", f("ab"), f.__annotations__)
# 2 optional still required
class A(BaseModel):
    max_ms: float | None
try: A()
except ValidationError as e: print("2", e.errors()[0]["type"], e.errors()[0]["loc"])
class A2(BaseModel):
    max_ms: float | None = None
print("2b", A2())
# 3 extra ignored
class H(BaseModel):
    host: str
    max_ms: float | None = None
print("3", H(host="order-01", max_m=244.05))
class HF(BaseModel):
    model_config = ConfigDict(extra="forbid")
    host: str
    max_ms: float | None = None
try: HF(host="order-01", max_m=244.05)
except ValidationError as e: print("3b", e.errors()[0]["type"], e.errors()[0]["loc"])
# 4 assignment not validated
h = H(host="order-01"); h.max_ms = "abc"; print("4", repr(h.max_ms))
class HV(BaseModel):
    model_config = ConfigDict(validate_assignment=True)
    max_ms: float | None = None
try:
    hv = HV(); hv.max_ms = "abc"
except ValidationError as e: print("4b", e.errors()[0]["type"])
# 5 mutable default copied
class L(BaseModel):
    errors: list[str] = []
a, b = L(), L(); a.errors.append("x"); print("5", a.errors, b.errors)
# 6 v1 api
with warnings.catch_warnings(record=True) as w:
    warnings.simplefilter("always"); h.dict(); print("6", w[0].category.__name__, str(w[0].message)[:80])
# 7 all errors at once
class Q(BaseModel):
    service: str
    minutes: int = Field(10, ge=1, le=60)
try: Q(service=None, minutes=0)
except ValidationError as e: print("7", [(x["loc"], x["type"]) for x in e.errors()])
# 8 perf
raw = [{"host": f"order-0{i%5+1}", "max_ms": "244.05"} for i in range(10000)]
@dataclasses.dataclass
class D:
    host: str
    max_ms: float | None = None
ta = TypeAdapter(list[H])
js = json.dumps(raw)
for name, fn in (("dataclass", lambda: [D(**r) for r in raw]),
                 ("BaseModel loop", lambda: [H(**r) for r in raw]),
                 ("TypeAdapter list", lambda: ta.validate_python(raw)),
                 ("json.loads+TypeAdapter", lambda: ta.validate_python(json.loads(js))),
                 ("validate_json", lambda: ta.validate_json(js))):
    t = min(timeit.repeat(fn, number=1, repeat=5)); print("8", name, round(t*1000, 2), "ms")
print("dc max_ms type", type(D(**raw[0]).max_ms).__name__, "model", type(H(**raw[0]).max_ms).__name__)

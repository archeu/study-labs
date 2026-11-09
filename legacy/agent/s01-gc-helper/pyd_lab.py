import json, warnings
from pydantic import BaseModel, ConfigDict, ValidationError
class Lax(BaseModel):
    minutes: int
class Strict(BaseModel):
    model_config = ConfigDict(strict=True)
    minutes: int
inputs = [("'10'", "10"), ("10", 10), ("10.0", 10.0), ("10.5", 10.5), ("'10.0'", "10.0"), ("' 10 '", " 10 "), ("'abc'", "abc"), ("True", True), ("None", None), ("'1e1'", "1e1")]
out = {}
for label, v in inputs:
    row = {}
    for name, M in (("lax", Lax), ("strict", Strict)):
        try:
            r = M(minutes=v).minutes; row[name] = {"ok": True, "v": repr(r)}
        except ValidationError as e:
            er = e.errors()[0]; row[name] = {"ok": False, "type": er["type"], "msg": er["msg"]}
    out[label] = row
print(json.dumps(out, ensure_ascii=False, indent=1))

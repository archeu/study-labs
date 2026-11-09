import json, warnings
warnings.filterwarnings("ignore")
from typing import Annotated
from fastapi import FastAPI, Query, Header, Depends, Path
from fastapi.testclient import TestClient
from pydantic import BaseModel
app = FastAPI()
class GcQuery(BaseModel):
    service: str
    minutes: int = 10
def get_client(): return "client"
CASES = {}
def case(key, path):
    def deco(fn):
        app.get(path)(fn) if not key.startswith("post") else app.post(path)(fn)
        CASES[key] = path; return fn
    return deco
@case("host: str（路径里有 {host}）", "/a/{host}")
async def a(host: str): ...
@case("minutes: int = 10", "/b")
async def b(minutes: int = 10): ...
@case("service: str", "/c")
async def c(service: str): ...
@case("hosts: list[str]", "/d")
async def d(hosts: list[str]): ...
@case("hosts: Annotated[list[str], Query()]", "/e")
async def e(hosts: Annotated[list[str], Query()]): ...
@case("post q: GcQuery", "/f")
async def f(q: GcQuery): ...
@case("x_trace_id: Annotated[str | None, Header()] = None", "/g")
async def g(x_trace_id: Annotated[str | None, Header()] = None): ...
@case("client: Annotated[str, Depends(get_client)]", "/h")
async def h(client: Annotated[str, Depends(get_client)]): ...
spec = app.openapi()
out = {}
for key, path in CASES.items():
    op = list(spec["paths"][path].values())[0]
    params = [(p["name"], p["in"], p.get("required", False)) for p in op.get("parameters", [])]
    body = "requestBody" in op
    out[key] = {"params": params, "body": body}
    print(key, "=>", params, "body" if body else "")
json.dump(out, open("fa_params.json", "w"), ensure_ascii=False)
c = TestClient(app)
print("list without Query, GET ?hosts=a&hosts=b ->", c.get("/d?hosts=a&hosts=b").status_code, c.get("/d?hosts=a&hosts=b").text[:120])
print("list with Query ->", c.get("/e?hosts=a&hosts=b").status_code)
print("header ->", c.get("/g", headers={"X-Trace-Id": "t1"}).status_code)

import json,sys,urllib.request
B="http://localhost:8091/actuator/metrics/"
def get(name, tag=None):
    u=B+name+(("?tag="+tag) if tag else "")
    try: d=json.load(urllib.request.urlopen(u,timeout=5))
    except Exception as e: return {}
    return {m["statistic"]:m["value"] for m in d["measurements"]}
def snap():
    return {"sql":get("demo.sql"),"acq":get("hikaricp.connections.acquire"),"http":get("http.server.requests","uri:/work"),
            "httpo":get("http.server.requests","uri:/order")}
def gauges():
    return {"busy":get("tomcat.threads.busy","name:http-nio-8090").get("VALUE"),"pending":get("hikaricp.connections.pending").get("VALUE"),
            "active":get("hikaricp.connections.active").get("VALUE"),"conn":get("tomcat.connections.current","name:http-nio-8090").get("VALUE")}
if __name__=="__main__":
    if sys.argv[1]=="snap": print(json.dumps(snap()))
    else: print(json.dumps(gauges()))

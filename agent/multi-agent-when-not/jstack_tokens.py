"""真实规模的原文有多大：合成一份 200 个 Tomcat 线程的 jstack 和一段 2000 行的应用日志，用本机模型的分词器数 token。
需要本机 llama-server（见 langgraph-basics 的 README），用它的 /tokenize。

    uv run python multi-agent-when-not/jstack_tokens.py
"""

import json
import random
import urllib.request

random.seed(7)


def tokens(text: str) -> int:
    req = urllib.request.Request("http://127.0.0.1:8090/tokenize", data=json.dumps({"content": text}).encode(),
                                 headers={"Content-Type": "application/json"})
    return len(json.load(urllib.request.urlopen(req))["tokens"])


IDLE = """"http-nio-8080-exec-{n}" #{tid} daemon prio=5 os_prio=0 cpu=12.{n}ms elapsed=3600.12s tid=0x00007f{tid:06x} nid={nid} waiting on condition  [0x00007f{tid:06x}000]
   java.lang.Thread.State: WAITING (parking)
\tat jdk.internal.misc.Unsafe.park(java.base@17.0.13/Native Method)
\t- parking to wait for  <0x00000000c{tid:07x}> (a java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject)
\tat java.util.concurrent.locks.LockSupport.park(java.base@17.0.13/LockSupport.java:341)
\tat java.util.concurrent.LinkedBlockingQueue.take(java.base@17.0.13/LinkedBlockingQueue.java:435)
\tat org.apache.tomcat.util.threads.TaskQueue.take(TaskQueue.java:141)
\tat java.util.concurrent.ThreadPoolExecutor.getTask(java.base@17.0.13/ThreadPoolExecutor.java:1062)
\tat java.util.concurrent.ThreadPoolExecutor.runWorker(java.base@17.0.13/ThreadPoolExecutor.java:1122)
\tat java.lang.Thread.run(java.base@17.0.13/Thread.java:833)
"""
BUSY = """"http-nio-8080-exec-{n}" #{tid} daemon prio=5 os_prio=0 cpu=81234.{n}ms elapsed=3600.12s tid=0x00007f{tid:06x} nid={nid} runnable  [0x00007f{tid:06x}000]
   java.lang.Thread.State: RUNNABLE
\tat java.util.ArrayList.grow(java.base@17.0.13/ArrayList.java:237)
\tat com.demo.order.ExportService.loadAll(ExportService.java:51)
\tat com.demo.order.ExportController.export(ExportController.java:33)
\tat jdk.internal.reflect.GeneratedMethodAccessor212.invoke(Unknown Source)
\tat org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885)
\tat org.apache.tomcat.util.threads.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:659)
"""

dump = "".join((BUSY if n in (12, 37, 40) else IDLE).format(n=n, tid=1000 + n, nid=hex(20000 + n)) for n in range(1, 201))
lines = []
for i in range(2000):
    h, m, s = 20, random.randint(0, 15), random.randint(0, 59)
    lines.append(f"2026-11-08 {h}:{m:02d}:{s:02d}.{random.randint(0, 999):03d} INFO  order-svc  [http-nio-8080-exec-{random.randint(1, 200)}] "
                 f"OrderController /orders/{random.randint(100000, 999999)} 耗时 {random.randint(8, 2400)}ms")
log = "\n".join(lines)

if __name__ == "__main__":
    for name, text in (("jstack，200 个线程", dump), ("应用日志，2000 行", log)):
        print(f"{name:14} {len(text):>8} 字符  {tokens(text):>7} token")

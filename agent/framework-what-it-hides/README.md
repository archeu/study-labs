# 框架替你做了什么、藏了什么

对应页面：[框架替你做了什么、藏了什么](https://study.ainiya.de/agent/framework-what-it-hides.html)

不调模型，用几张最小的 LangGraph 图，把框架替你做了、但代码里看不见的事一件件打印出来。

| 文件 | 是什么 |
|---|---|
| `peek.py` | ① 并行节点跑在哪个线程、共享计数丢更新；② 三种 durability 各存几次盘、节点抛异常后能从哪接着跑；②b 进程被 `os._exit` 杀掉后库里有什么；③ interrupt 前的副作用执行几次；④ 没有出口的循环默认跑多少步；⑤ 全景那张图里框架替你调了几次存盘 |

## 怎么跑

```bash
cd agent && uv sync
uv run python framework-what-it-hides/peek.py
```

## 预期输出

2026-11-26，2 核 ARM，Python 3.13.18，langgraph 1.2.24（跑了两次，除了 ① 的丢更新个数，其余一样）：

```
① 同一步里的三个节点，各在哪个线程上跑
    logs@ThreadPoolExecutor-1_0 | metrics@ThreadPoolExecutor-1_1 | notes@ThreadPoolExecutor-1_2
    三个节点各把计数加 200 次，期望 600，实际 201

② 五个节点串成一条线，三种 durability 各存了几次盘；第 4 个节点崩了能从哪接着跑
    sync   跑完：put 7 次、put_writes 6 次 | 第 4 步崩：库里 n=3，next=['step4']
    async  跑完：put 7 次、put_writes 6 次 | 第 4 步崩：库里 n=3，next=['step4']
    exit   跑完：put 1 次、put_writes 0 次 | 第 4 步崩：库里 n=3，next=['step4']

②b 同样五步，第 4 步时进程被直接杀掉（os._exit，像 kill -9），重启后库里有什么
    sync   进程退出码 9，库里 n=3，next=['step4']
    async  进程退出码 9，库里 n=3，next=['step4']
    exit   进程退出码 9，库里 n=None，next=[]

③ interrupt() 前面有一句副作用，批准后它执行了几次
    停下时：通知发了 1 次
    批准后：通知发了 2 次

④ 两个节点互相指，没有出口：默认多少步才报错
    recursion_limit=默认：跑了 10007 步才停，用时 1.84s
    recursion_limit=25：跑了 25 步才停，用时 0.01s

⑤ 全景那张图（分诊 → 3 个并行子 agent → 汇总 → 等批准 → 执行），框架替你做的事
    节点函数被调用 8 次：['triage', 'investigate', 'investigate', 'investigate', 'summarize', 'approve', 'approve', 'act']
    存检查点 put 7 次，存中间结果 put_writes 10 次，历史里能回看 7 个版本
```

- ② 里节点**抛异常**时，三种模式都把第 3 步存下了（exit 模式在图退出时存，抛异常也算退出）；②b 进程**被杀**，exit 模式什么都没留下。
- ④ 的 10007 来自 `langgraph/_internal/_config.py` 的 `DEFAULT_RECURSION_LIMIT`（环境变量 `LANGGRAPH_DEFAULT_RECURSION_LIMIT` 可改）；`langchain_core` 里同名常量还是 25，旧教程写的也是 25。

## 练习

先猜结果，再运行。

1. 给 ① 的读-改-写加一把 `threading.Lock`，结果是不是 600？把锁去掉、`sleep` 也去掉呢？
2. 把 ③ 的“发通知”挪到 `interrupt()` 后面，再挪到一个单独的节点里，各执行几次？
3. 把 ②b 里第 4 步的 `time.sleep(0.2)` 去掉（一进节点就被杀），多跑几次，async 模式库里的 `n` 是 3 还是 2？

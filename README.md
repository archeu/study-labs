# study-labs

[学习笔记站](https://archeu.github.io/study-notes/)（[archeu/study-notes](https://github.com/archeu/study-notes)）配套的可运行代码：页面⑤快速入门的完整工程、页面上“实测”数字背后的实验，以及 ↺ 的改造练习。克隆下来用 IDE 打开就能跑、能改。

## 怎么用

| 线 | IDE 里打开 | 环境 | 跑一个实验 |
|---|---|---|---|
| Java 后端架构 | `java/pom.xml`（IDEA 按 Maven 项目导入） | JDK 17（个别模块标了 8 或 21），Maven 3.6+ | IDE 里直接运行 main；或 `cd java && mvn -q -pl <主题>/<名字> compile exec:java -Dexec.mainClass=<类名>` |
| AI Agent 工程师 | `agent/` 目录 | [uv](https://docs.astral.sh/uv/)，Python 3.13 由 uv 自动安装 | `cd agent && uv sync`，然后 `uv run python <名字>/main.py`、`uv run pytest <名字>/` |

## 约定

- **一页一个目录，名字和页面文件名一样。** 页面 `juc/threadpool-execute.html` 的代码在 `java/juc/threadpool-execute/`，页面 `agent/python-asyncio.html` 的代码在 `agent/python-asyncio/`。
- **双向链接。** 每个目录的 README 第一行链回页面；页面的⑤和“实测”处有“完整代码 →”链到这里。
- **每个目录的 README 写四件事：** 对应页面、怎么跑、预期输出（标明哪台机器、哪个 JDK/Python）、练习（来自页面 ↺，先猜结果再运行）。
- **只放代码。** 中间件数据、下载包、模型文件、日志、构建产物都不进仓库（见 `.gitignore`）；需要中间件的实验在 README 里写清楚怎么启动。
- **Java 包名** `labs.<主题>.<名字>`，名字里的 `-` 换成 `_`。**Python 测试文件**叫 `test_<名字>.py`，按目录运行。

## 旧代码

2026-11-09 之前各页面的实验代码按原样收在 [`legacy/`](legacy/)，按页面分目录，说明见 [legacy/README.md](legacy/README.md)。

## AI Agent 工程师

| 页面 | 代码 | 说明 |
|---|---|---|

## Java 后端架构

| 页面 | 代码 | 说明 |
|---|---|---|
| [全景：一个订单对象的一生](https://archeu.github.io/study-notes/java/pano-j01-order-object-life.html) | [`java/java/pano-j01-order-object-life`](java/java/pano-j01-order-object-life/) | `OrderApp`：Spring Boot 订单服务，每站打点；`/orders` 下单、`/stats` 看集合里攒了多少 |
| [面向对象、接口与抽象类、泛型和类型擦除](https://archeu.github.io/study-notes/javase/oop-generics-erasure.html) | [`java/javase/oop-generics-erasure`](java/javase/oop-generics-erasure/) | `Demo`：封装、多态、擦除、原始类型、桥方法、构造器陷阱 6 段，配 javap 命令 |

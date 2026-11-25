# 文档解析：PDF、Word、表格、扫描件

对应页面：[文档解析：PDF、Word、表格、扫描件](https://study.ainiya.de/agent/doc-parsing.html)

## 怎么跑

不需要模型服务。解析代码本体在 [`pano-06-runbook-to-report/kb.py`](../pano-06-runbook-to-report/kb.py)（`parse_pdf`、`ocr_lines`、`parse_docx`）。

```bash
cd agent && uv sync
uv run python pano-06-runbook-to-report/corpus.py     # 生成 data/（只需一次）
uv run python -W ignore doc-parsing/main.py            # 五组实测，写 parse.json
```

- `main.py`：① 手册 v3.2 三种取法（纯文本、版面块 + 表格、再加 OCR）逐页和原文比，外加表格的行还在不在一起；② `table.extract()` 和按格子坐标取字；③ 附录 C 在 72～300 dpi、0～3 度倾斜下的 OCR 用时、字错率、关键字段；④ Word 的样式、表格和自动编号；⑤ 第 4 页内容流的开头。
- `parse.json`：页面实验台用的数据。`data/` 里是 ④ 临时生成的 docx，不进仓库。

## 预期输出

2026-11-25，2 核 ARM，Python 3.13，PyMuPDF、RapidOCR（onnxruntime CPU）、python-docx：

```
  全书：A 0.926  B 0.926  C 0.995；A 用时 0.02s，C 用时 7.0s（含 1 页 OCR，第一次加载模型）
  表格的行：原文 24 行，纯文本里还在同一行的 0 行，版面块 + 表格 + OCR 的 23 行
  59 个格子：extract() 对 31 个，按坐标取对 59 个
   72 dpi 倾斜 1.2°    595x842   0.58s  字错率 0.944  关键字段 0/5  开关表的行完整 0/3
  110 dpi 倾斜 1.2°   910x1287   1.91s  字错率 0.017  关键字段 5/5  开关表的行完整 3/3
  300 dpi 倾斜 1.2°  2480x3509   3.12s  字错率 0.021  关键字段 5/5  开关表的行完整 3/3
  heading '处置步骤'
  text    '看 GC 日志'          ← 原文是“1. 看 GC 日志”
```

另测：模型已加载时，版面块 + 找表格 11 页约 2.6 秒，OCR 1 页（200 dpi）约 2.9 秒，加载 OCR 模型 1.8 秒。

## 练习

先猜结果，再运行。

1. 在 `parse_pdf()` 里合并跨页表：一页最上面的表和上一页最后一张表列数相同就接上，并补表头。
2. OCR 结果里用正则找 `order\.[a-z.]+\.enabled`，数量不对就警告；把 `ocr_page` 改成 72 dpi 试一次。
3. `parse_docx()` 遇到 `List Number` 样式时自己数序号。
4. 把 `table_text()` 改成每行“列名：值”，再跑 `rag-pipeline/main.py`，看 Q01、Q02、Q20 的排名。

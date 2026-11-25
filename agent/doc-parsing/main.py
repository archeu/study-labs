"""文档解析页的实测，只用本机 CPU，不需要任何模型服务。

1. 手册 v3.2 三种取法：A 纯文本 get_text()；B 版面块 + 表格（不做 OCR）；C = B + 扫描页 OCR。逐页和原文比
2. 表格两种取字：table.extract() 和按格子坐标重新取
3. 扫描分辨率和倾斜：72～300 dpi、0～3 度，OCR 用时、字错率、关键字段、表格行是否完整
4. Word：标题样式、表格、自动编号
5. PDF 里到底存了什么：第 4 页内容流的开头
uv run python -W ignore doc-parsing/main.py      # 结果写 parse.json
"""
import html
import json
import re
import sys
import time
from difflib import SequenceMatcher
from pathlib import Path

import numpy as np
import pymupdf

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "pano-06-runbook-to-report"))
import corpus  # noqa: E402
import kb  # noqa: E402

PDF = kb.DATA / "runbook/order-svc-runbook-v3.2.pdf"


def strip(h):
    """HTML 原文 → 纯文字（真值）。"""
    h = re.sub(r"</(p|h\d|tr|li)>", "\n", h)
    h = re.sub(r"</t[dh]>", " ", h)
    return html.unescape(re.sub(r"<[^>]+>", "", h)).replace("\xa0", " ")


def squash(s):
    return re.sub(r"[\s|]+", "", s)


def recall(truth, got):
    """原文里有多少字按原来的顺序被取到了（多出来的页眉页脚不扣分）。"""
    t, g = squash(truth), squash(got)
    m = SequenceMatcher(None, t, g, autojunk=False)
    return sum(b.size for b in m.get_matching_blocks()) / max(1, len(t))


def cer(truth, got):
    """字错率：编辑距离 / 原文字数。"""
    a, b = squash(truth), squash(got)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1] / max(1, len(a))


def truth_cells(h):
    return [html.unescape(re.sub(r"<[^>]+>", "", c)).strip() for c in re.findall(r"<t[dh]>(.*?)</t[dh]>", h)]


def main():
    out = {}
    pages = corpus.runbook_pages("v3.2")
    doc = pymupdf.open(PDF)

    print("=== 1. 三种取法，逐页和原文比（按顺序取到的字 / 原文字数）===")
    t0 = time.perf_counter(); plain = [pg.get_text() for pg in doc]; t_plain = time.perf_counter() - t0
    t0 = time.perf_counter(); blocks = kb.parse_pdf(PDF); t_full = time.perf_counter() - t0
    rows = []
    for i, h in enumerate(pages, start=1):
        truth = strip(h)
        b_txt = "\n".join(b.text for b in blocks if b.page == i and b.src != "ocr")
        c_txt = "\n".join(b.text for b in blocks if b.page == i)
        rows.append({"page": i, "truth_chars": len(squash(truth)), "A": round(recall(truth, plain[i - 1]), 3),
                     "B": round(recall(truth, b_txt), 3), "C": round(recall(truth, c_txt), 3)})
        print(f"  第 {i:2d} 页 原文 {rows[-1]['truth_chars']:4d} 字  A {rows[-1]['A']:.3f}  B {rows[-1]['B']:.3f}  C {rows[-1]['C']:.3f}")
    tot = sum(r["truth_chars"] for r in rows)
    for k in "ABC":
        out[f"recall_{k}"] = round(sum(r[k] * r["truth_chars"] for r in rows) / tot, 3)
    print(f"  全书：A {out['recall_A']:.3f}  B {out['recall_B']:.3f}  C {out['recall_C']:.3f}；"
          f"A 用时 {t_plain:.2f}s，C 用时 {t_full:.1f}s（含 1 页 OCR）")
    out.update(pages=rows, t_plain=round(t_plain, 2), t_full=round(t_full, 1))
    # 表格的行还在不在一起：原文每一行表格的所有格子，能不能在取出来的某一行文字里全找到
    def rows_of(h):
        return [[squash(html.unescape(re.sub(r"<[^>]+>", "", c))) for c in re.findall(r"<t[dh]>(.*?)</t[dh]>", tr)]
                for tr in re.findall(r"<tr>(.*?)</tr>", h, re.S)]
    intact = {"A": 0, "C": 0, "rows": 0}
    for i, h in enumerate(pages, start=1):
        a_lines = [squash(x) for x in plain[i - 1].split("\n")]
        c_lines = [squash(x) for b in blocks if b.page == i for x in b.text.split("\n")]
        for cells in rows_of(h):
            intact["rows"] += 1
            intact["A"] += any(all(c in ln for c in cells) for ln in a_lines)
            intact["C"] += any(all(c in ln for c in cells) for ln in c_lines)
    out["rows_intact"] = intact
    print(f"  表格的行：原文 {intact['rows']} 行，纯文本里还在同一行的 {intact['A']} 行，版面块 + 表格 + OCR 的 {intact['C']} 行")
    out["plain_p4"] = plain[3]
    out["plain_p12"] = plain[11]

    print("\n=== 2. 表格：table.extract() 和按格子坐标重新取 ===")
    tab = {"extract_ok": 0, "clip_ok": 0, "cells": 0, "examples": []}
    for i in (1, 4, 10, 11):
        pg = doc[i - 1]
        want = truth_cells(pages[i - 1])
        for t in pg.find_tables().tables:
            ex = [kb.join_lines(c or "") for row in t.extract() for c in row]
            cl = [kb.join_lines(pg.get_text("text", clip=c) if c else "") for row in t.rows for c in row.cells]
            for e, c in zip(ex, cl):
                if c in want or e in want:
                    tab["cells"] += 1
                    tab["extract_ok"] += e in want
                    tab["clip_ok"] += c in want
                    if e not in want and len(tab["examples"]) < 6:
                        tab["examples"].append([e, c])
    print(f"  {tab['cells']} 个格子：extract() 对 {tab['extract_ok']} 个，按坐标取对 {tab['clip_ok']} 个")
    for e, c in tab["examples"]:
        print(f"    extract() {e!r:28s} 按坐标 {c!r}")
    p11 = [b.text for b in blocks if b.page == 11 and b.kind == "table"]
    tab["p11_first_row"] = p11[0].split("\n")[0] if p11 else ""
    print(f"  第 11 页续表的第一行：{tab['p11_first_row']!r}（表头留在了第 10 页）")
    out["tables"] = tab

    print("\n=== 3. 扫描件：分辨率、倾斜和 OCR ===")
    truth = strip(corpus.APPENDIX_C)
    keys = ["order.export.enabled", "order.cache.local.enabled", "order.recommend.enabled", "#trade-oncall", "#dba-oncall"]
    pairs = [("order.export.enabled", "停止订单导出"), ("order.cache.local.enabled", "不用本地缓存"),
             ("order.recommend.enabled", "下单页不展示")]
    import cv2
    scan = []
    kb.ocr_lines(np.full((60, 200, 3), 255, np.uint8))   # 先加载模型，不算进用时
    for dpi, ang in [(72, 1.2), (110, 1.2), (150, 1.2), (200, 1.2), (300, 1.2), (200, 0.0), (200, 3.0)]:
        png = corpus.scanned(corpus.APPENDIX_C, dpi=dpi, angle=ang)
        img = cv2.cvtColor(cv2.imdecode(np.frombuffer(png, np.uint8), cv2.IMREAD_GRAYSCALE), cv2.COLOR_GRAY2BGR)
        t0 = time.perf_counter(); lines = kb.ocr_lines(img); dt = time.perf_counter() - t0
        text = "\n".join(lines)
        row_ok = sum(any(k in ln and v in ln for ln in lines) for k, v in pairs)
        r = {"dpi": dpi, "angle": ang, "px": f"{img.shape[1]}x{img.shape[0]}", "sec": round(dt, 2),
             "cer": round(cer(truth, text), 3), "keys": sum(k in text for k in keys), "rows_ok": row_ok, "lines": lines}
        scan.append(r)
        print(f"  {dpi:3d} dpi 倾斜 {ang:.1f}°  {r['px']:>9s}  {dt:5.2f}s  字错率 {r['cer']:.3f}  "
              f"关键字段 {r['keys']}/5  开关表的行完整 {row_ok}/3")
    out["scan"] = scan
    print("  110 dpi 的识别结果：")
    for ln in scan[1]["lines"]:
        print("    " + ln)

    print("\n=== 4. Word：结构是写明的 ===")
    from docx import Document
    d = Document()
    d.add_heading("处置步骤", level=2)
    for s in ["看 GC 日志", "关闭开关 order.cache.local.enabled", "观察 P99"]:
        d.add_paragraph(s, style="List Number")
    tmp = HERE / "data"; tmp.mkdir(exist_ok=True)
    d.save(tmp / "numbered.docx")
    wb = kb.parse_docx(tmp / "numbered.docx")
    for b in wb:
        print(f"  {b.kind:7s} {b.text!r}")
    out["docx_numbered"] = [[b.kind, b.text] for b in wb]
    t09 = kb.parse_docx(kb.DATA / "tickets/INC-20261109-02.docx")
    out["docx_ticket"] = [[b.kind, b.level, b.text[:60]] for b in t09]
    print(f"  故障单 INC-20261109-02：{len(t09)} 块，" + "、".join(f"{b.kind}" for b in t09))

    print("\n=== 5. 第 4 页内容流的开头（PDF 里存的东西）===")
    xref = [x[0] for x in doc[3].get_xobjects() if x[1] == "fullpage"][0]   # insert_htmlbox 把正文画在一个 Form XObject 里
    raw = doc.xref_stream(xref).decode("latin-1")
    i = raw.find("BT")
    snippet = raw[i:i + 420]
    out["rect_ops"] = re.findall(r"[\d.]+ [\d.]+ [\d.]+ [\d.]+ re", raw)[1:5]
    print(snippet)
    out["content_stream"] = snippet
    fonts = [f[3] for f in doc[3].get_fonts()]
    out["fonts_p4"] = fonts
    print("  第 4 页用到的字体：", fonts)
    (HERE / "parse.json").write_text(json.dumps(out, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()

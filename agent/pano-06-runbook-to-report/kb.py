"""值班助手 v3 的知识库：最小实现，每个函数对应全景页的一站。

站 1 sync      发现新文件、新版本，旧版本标成 retired
站 2 parse     PDF（文字层 + 表格 + 扫描页 OCR）、Word、Markdown → 块
站 3 clean / chunk   去页眉页脚和目录，按标题切块，带上元数据
站 4 embed     Qwen3-Embedding-0.6B → 1024 维向量
站 5 store     PostgreSQL + pgvector，HNSW 索引
站 7 search 的过滤部分：只查 current 版本、只查有权限的 team
站 8 hybrid    BM25（jieba 分词）+ 向量，RRF 融合
站 9 rerank    bge-reranker-v2-m3 交叉编码器打分
站 10 generate 只用给定资料写，每句带 [编号]
站 11 check_citations  引用回查原文

外部服务（都在本机）：llama-server 三个端口，pgvector 容器。地址可用环境变量改。
"""
import hashlib
import json
import math
import os
import re
import unicodedata
import urllib.request
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = Path(os.environ.get("KB_DATA", HERE / "data"))
EMBED_URL = os.environ.get("EMBED_URL", "http://127.0.0.1:8091")
RERANK_URL = os.environ.get("RERANK_URL", "http://127.0.0.1:8092")
GEN_URL = os.environ.get("GEN_URL", "http://127.0.0.1:8093")
PG_DSN = os.environ.get("PG_DSN", "postgresql://postgres:kb@127.0.0.1:55433/kb")
DIM = 1024
QUERY_TASK = "Given a question from an on-call engineer, retrieve runbook or ticket passages that answer it"


def post(url, body, timeout=600):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


# ---------- 站 1：同步 ----------
@dataclass
class Doc:
    path: Path
    key: str        # 同一份文档的不同版本 key 相同
    version: str
    team: str       # 谁能查：trade 或 public
    sha: str


def doc_of(path: Path) -> Doc:
    m = re.match(r"(.+)-(v\d+(?:\.\d+)*)$", path.stem)
    key, ver = (m.group(1), m.group(2)) if m else (path.stem, "v1")
    team = "public" if path.parent.name == "notes" else "trade"
    return Doc(path, key, ver, team, hashlib.sha256(path.read_bytes()).hexdigest()[:16])


def sync(conn, paths):
    """返回要处理的文档：内容没变的跳过；同 key 的新版本进来时，旧版本标成 retired。"""
    todo = []
    with conn.cursor() as cur:
        for p in paths:
            d = doc_of(p)
            cur.execute("SELECT sha FROM docs WHERE key=%s AND version=%s", (d.key, d.version))
            row = cur.fetchone()
            if row and row[0] == d.sha:
                continue
            todo.append(d)
    return todo


# ---------- 站 2：解析 ----------
@dataclass
class Block:
    kind: str       # heading / text / table
    text: str
    page: int
    level: int = 0  # 标题层级：1 是“4 发布与变更规范”，2 是“4.3 本地缓存规范”
    src: str = "text"  # text / ocr / docx / md


def join_lines(s):
    """PDF 按版面宽度断行：中文之间直接拼，英文单词之间补空格。"""
    out = ""
    for line in s.split("\n"):
        line = line.strip()
        if not line:
            continue
        if out and re.match(r"[A-Za-z0-9]", line[0]) and re.match(r"[A-Za-z0-9.,;:)]", out[-1]):
            out += " "
        out += line
    return out


def table_text(rows):
    rows = [[join_lines(c or "") for c in r] for r in rows]
    return "\n".join(" | ".join(r) for r in rows)


def parse_pdf(path):
    import pymupdf
    blocks = []
    for pno, pg in enumerate(pymupdf.open(path), start=1):
        if not pg.get_text().strip():          # 没有文字层：扫描件，走 OCR
            blocks += ocr_page(pg, pno)
            continue
        tabs = pg.find_tables().tables
        boxes = [pymupdf.Rect(t.bbox) for t in tabs]
        # 单元格文字按格子的坐标框重新取：t.extract() 会把“v3.0”取成“v30.”（标点和数字不在同一种字体里，排序乱了）
        items = [(t.bbox[1], Block("table", table_text([[pg.get_text("text", clip=c) if c else "" for c in row.cells]
                                                        for row in t.rows]), pno)) for t in tabs]
        for b in pg.get_text("dict")["blocks"]:
            if b.get("type") != 0 or any(pymupdf.Rect(b["bbox"]).intersects(r) for r in boxes):
                continue
            text = "\n".join("".join(s["text"] for s in ln["spans"]) for ln in b["lines"])
            size = max(s["size"] for ln in b["lines"] for s in ln["spans"])
            if size >= 13:
                blk = Block("heading", join_lines(text), pno, level=1 if size < 18 else 0)
            elif size >= 11.2:
                blk = Block("heading", join_lines(text), pno, level=2)
            else:
                blk = Block("text", join_lines(text), pno)
            items.append((b["bbox"][1], blk))
        blocks += [blk for _, blk in sorted(items, key=lambda x: x[0])]
    return blocks


_ocr = None


def ocr_page(pg, pno):
    """扫描页：渲染成图，RapidOCR 识别出一个个文字框，再按纵坐标拼回行。"""
    global _ocr
    import numpy as np
    from rapidocr import RapidOCR
    if _ocr is None:
        _ocr = RapidOCR(params={"Global.log_level": "error"})
    pix = pg.get_pixmap(dpi=200)
    img = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.h, pix.w, pix.n)[:, :, :3].copy()
    r = _ocr(img)
    if r.boxes is None:
        return []
    hs = sorted(float(b[:, 1].max() - b[:, 1].min()) for b in r.boxes)
    gap = 0.6 * hs[len(hs) // 2]              # 纵坐标差不到半个字高，算同一行
    items = sorted((float(b[:, 1].mean()), float(b[:, 0].min()), t) for b, t in zip(r.boxes, r.txts))
    rows = []
    for y, x, t in items:
        if rows and abs(y - rows[-1][0]) <= gap:
            rows[-1][1].append((x, t))
        else:
            rows.append([y, [(x, t)]])
    lines = [" | ".join(t for _, t in sorted(cells)) for _, cells in rows]
    out = []
    for ln in lines:
        if re.match(r"^(附录\s*[A-Z]|\d+(\.\d+)+)\s*\S{2,24}$", ln):
            out.append(Block("heading", ln, pno, level=1 if ln.startswith("附录") else 2, src="ocr"))
        else:
            out.append(Block("text", ln, pno, src="ocr"))
    return out


def parse_docx(path):
    from docx import Document
    d = Document(path)
    out, body = [], d.element.body
    for el in body.iterchildren():
        tag = el.tag.split("}")[1]
        if tag == "p":
            p = next(x for x in d.paragraphs if x._p is el)
            if not p.text.strip():
                continue
            st = p.style.name
            if st.startswith("Heading"):
                out.append(Block("heading", p.text, 1, level=int(st.split()[-1]), src="docx"))
            else:
                out.append(Block("text", p.text, 1, src="docx"))
        elif tag == "tbl":
            t = next(x for x in d.tables if x._tbl is el)
            out.append(Block("table", table_text([[c.text for c in r.cells] for r in t.rows]), 1, src="docx"))
    return out


def parse_md(path):
    out = []
    for para in re.split(r"\n\s*\n", path.read_text(encoding="utf-8")):
        para = para.strip()
        m = re.match(r"^(#+)\s+(.*)", para)
        if m:
            out.append(Block("heading", m.group(2), 1, level=len(m.group(1)), src="md"))
        elif para:
            out.append(Block("text", para, 1, src="md"))
    return out


def parse(doc: Doc):
    suf = doc.path.suffix.lower()
    return parse_pdf(doc.path) if suf == ".pdf" else parse_docx(doc.path) if suf == ".docx" else parse_md(doc.path)


# ---------- 站 3：清洗、切块 ----------
def fold(s):
    """只还原连字（ﬀ → ff）和全角字母数字（Ｐ９９ → P99）；中文标点保留，整段 NFKC 会把“，”变成“,”。"""
    return "".join(unicodedata.normalize("NFKC", ch) if "\ufb00" <= ch <= "\ufb4f" or ch.isalnum() and "\uff10" <= ch <= "\uff5a" else ch
                   for ch in s)


def clean(blocks):
    """连字和全角字母还原、去掉每页都有的页眉页脚、去掉目录行。"""
    for b in blocks:
        b.text = fold(b.text)
    pages = {b.page for b in blocks}
    norm = Counter(re.sub(r"\d+", "#", b.text) for b in blocks if b.kind != "table")
    repeated = {k for k, n in norm.items() if len(pages) > 3 and n >= len(pages) / 2}
    out = []
    for b in blocks:
        if re.sub(r"\d+", "#", b.text) in repeated:
            continue
        if re.search(r"\.{6,}\s*\d+$", b.text) or b.text == "目录":
            continue
        out.append(b)
    return out


@dataclass
class Chunk:
    id: str
    doc_key: str
    version: str
    team: str
    section: str
    page: int
    text: str
    src: str = "text"
    vec: list = field(default=None, repr=False)

    def embed_text(self):
        # 嵌入时带上“出自哪份文档哪一节”，单看正文分不清是哪个服务、哪一版
        return f"{self.doc_key} {self.version} › {self.section}\n{self.text}"


MAX_CHARS = 360


def chunk(doc: Doc, blocks):
    """按标题切：一节一块，一节太长再按段落拆；表格不拆开。"""
    chunks, path, buf, page, src = [], {}, [], None, "text"

    def flush():
        nonlocal buf, page
        if buf:
            sec = " › ".join(path[k] for k in sorted(path)) or doc.key
            chunks.append(Chunk(f"{doc.key}@{doc.version}#{len(chunks) + 1}", doc.key, doc.version, doc.team,
                                sec, page, "\n".join(buf), src))
        buf, page = [], None

    for b in blocks:
        if b.kind == "heading":
            flush()
            path = {k: v for k, v in path.items() if k < b.level}
            path[b.level] = b.text
            continue
        if buf and sum(len(x) for x in buf) + len(b.text) > MAX_CHARS and b.kind != "table":
            flush()
        if page is None:
            page, src = b.page, b.src
        buf.append(b.text)
    flush()
    return chunks


# ---------- 站 4：向量化 ----------
def embed(texts, query=False, batch=16):
    if query:
        texts = [f"Instruct: {QUERY_TASK}\nQuery:{t}" for t in texts]
    out = []
    for i in range(0, len(texts), batch):
        r = post(EMBED_URL + "/v1/embeddings", {"input": texts[i:i + batch]})
        out += [d["embedding"] for d in sorted(r["data"], key=lambda d: d["index"])]
    return out


# ---------- 站 5：入库 ----------
SCHEMA = f"""
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS docs (key text, version text, sha text, team text, status text,
  PRIMARY KEY (key, version));
CREATE TABLE IF NOT EXISTS chunks (id text PRIMARY KEY, doc_key text, version text, team text,
  status text, section text, page int, src text, text text, emb vector({DIM}));
CREATE INDEX IF NOT EXISTS chunks_hnsw ON chunks USING hnsw (emb vector_cosine_ops);
"""


def connect():
    import psycopg
    conn = psycopg.connect(PG_DSN, autocommit=True)
    conn.execute(SCHEMA)
    return conn


def vec_lit(v):
    return "[" + ",".join(f"{x:.6f}" for x in v) + "]"


def store(conn, doc: Doc, chunks):
    with conn.cursor() as cur:
        cur.execute("UPDATE docs SET status='retired' WHERE key=%s AND version<>%s", (doc.key, doc.version))
        cur.execute("UPDATE chunks SET status='retired' WHERE doc_key=%s AND version<>%s", (doc.key, doc.version))
        cur.execute("DELETE FROM chunks WHERE doc_key=%s AND version=%s", (doc.key, doc.version))
        cur.execute("INSERT INTO docs VALUES (%s,%s,%s,%s,'current') ON CONFLICT (key, version) "
                    "DO UPDATE SET sha=EXCLUDED.sha, status='current'", (doc.key, doc.version, doc.sha, doc.team))
        for c in chunks:
            cur.execute("INSERT INTO chunks VALUES (%s,%s,%s,%s,'current',%s,%s,%s,%s,%s::vector)",
                        (c.id, c.doc_key, c.version, c.team, c.section, c.page, c.src, c.text, vec_lit(c.vec)))


def ingest(conn, paths, log=print):
    """站 1～5 串起来。返回每站耗时和数量，全景页的入库那一行就是它。"""
    import time
    stat = Counter()
    for d in sync(conn, paths):
        t = time.perf_counter(); blocks = parse(d); stat["parse_s"] += time.perf_counter() - t
        stat["ocr_blocks"] += sum(b.src == "ocr" for b in blocks)
        t = time.perf_counter(); cs = chunk(d, clean(blocks)); stat["chunk_s"] += time.perf_counter() - t
        t = time.perf_counter()
        for c, v in zip(cs, embed([c.embed_text() for c in cs])):
            c.vec = v
        stat["embed_s"] += time.perf_counter() - t
        t = time.perf_counter(); store(conn, d, cs); stat["store_s"] += time.perf_counter() - t
        stat["docs"] += 1; stat["chunks"] += len(cs)
        log(f"  {d.path.name:32s} {d.version:5s} {len(blocks):3d} 块 → {len(cs):3d} 片")
    return stat


# ---------- 站 7、8：过滤 + 混合检索 ----------
def tokenize(s):
    import jieba
    jieba.setLogLevel(60)
    return [w.lower() for w in jieba.lcut(s) if re.search(r"\w", w)]


class BM25:
    """最小的 BM25：k1=1.2, b=0.75。生产上用 Elasticsearch / OpenSearch，或 PG 的 BM25 扩展。"""

    def __init__(self, rows, k1=1.2, b=0.75):
        self.rows, self.k1, self.b = rows, k1, b
        self.toks = [tokenize(r["section"] + " " + r["text"]) for r in rows]
        self.avg = sum(map(len, self.toks)) / max(1, len(self.toks))
        self.df = Counter(w for t in self.toks for w in set(t))

    def search(self, q, k):
        n, qt, scores = len(self.toks), tokenize(q), []
        for i, t in enumerate(self.toks):
            tf, s = Counter(t), 0.0
            for w in qt:
                if tf[w]:
                    idf = math.log(1 + (n - self.df[w] + 0.5) / (self.df[w] + 0.5))
                    s += idf * tf[w] * (self.k1 + 1) / (tf[w] + self.k1 * (1 - self.b + self.b * len(t) / self.avg))
            scores.append(s)
        top = sorted(range(n), key=lambda i: -scores[i])[:k]
        return [self.rows[i] | {"bm25": round(scores[i], 3)} for i in top if scores[i] > 0]


def allowed_rows(conn, teams):
    """站 7 的过滤：只要 current 版本、只要这个人能看的 team。"""
    with conn.cursor() as cur:
        cur.execute("SELECT id, doc_key, version, section, page, src, text FROM chunks "
                    "WHERE status='current' AND team = ANY(%s)", (list(teams),))
        cols = [c.name for c in cur.description]
        return [dict(zip(cols, r)) for r in cur.fetchall()]


def vector_search(conn, q, k, teams):
    with conn.cursor() as cur:
        cur.execute("SELECT id, doc_key, version, section, page, src, text, 1 - (emb <=> %s::vector) AS cos "
                    "FROM chunks WHERE status='current' AND team = ANY(%s) ORDER BY emb <=> %s::vector LIMIT %s",
                    (vec_lit(q), list(teams), vec_lit(q), k))
        cols = [c.name for c in cur.description]
        return [dict(zip(cols, r)) for r in cur.fetchall()]


def rrf(lists, k=60):
    """Reciprocal Rank Fusion：只看名次，不看分数，两路分数尺度不同也能合。"""
    score, rows = Counter(), {}
    for lst in lists:
        for rank, r in enumerate(lst, start=1):
            score[r["id"]] += 1 / (k + rank)
            rows.setdefault(r["id"], r)
    return [rows[i] | {"rrf": round(s, 4)} for i, s in score.most_common()]


def hybrid(conn, q, teams=("trade", "public"), k=20):
    bm = BM25(allowed_rows(conn, teams)).search(q, k)
    vec = vector_search(conn, embed([q], query=True)[0], k, teams)
    return rrf([bm, vec]), bm, vec


# ---------- 站 9：重排 ----------
def rerank(q, rows, top_n=5):
    r = post(RERANK_URL + "/v1/rerank", {"query": q, "documents": [x["section"] + "\n" + x["text"] for x in rows],
                                         "top_n": top_n})
    return [rows[x["index"]] | {"rerank": round(x["relevance_score"], 3)} for x in r["results"]][:top_n]


# ---------- 站 10：生成 ----------
def label(r):
    name = {"order-svc-runbook": "手册"}.get(r["doc_key"], r["doc_key"])
    sec = r["section"].split(" › ")[-1].split(" ")[0]
    return f"{name} {r['version']} {sec} p.{r['page']}" if r["doc_key"].startswith("order-svc") else f"{name} {sec}"


SYSTEM = ("你在写故障复盘报告。只能用下面编号的资料，不能用资料以外的知识。"
          "每一句话的句号前面紧跟出处编号，例如：OrderDetailCache 没有设置上限[2]。一句可以有多个编号，"
          "不要把编号集中写在段落末尾。资料里没有的，写“资料未提及”，不要自己推测。用中文，不要客套话。")


def generate(task, rows, max_tokens=500):
    src = "\n\n".join(f"[{i}] （{label(r)}）{r['text']}" for i, r in enumerate(rows, start=1))
    body = {"messages": [{"role": "system", "content": SYSTEM},
                         {"role": "user", "content": f"资料：\n{src}\n\n任务：{task}"}],
            "temperature": 0, "max_tokens": max_tokens,
            "chat_template_kwargs": {"enable_thinking": False}}
    r = post(GEN_URL + "/v1/chat/completions", body)
    return r["choices"][0]["message"]["content"], r.get("usage", {}), r.get("timings", {})


# ---------- 站 11：引用回查 ----------
def check_citations(answer, rows):
    """逐句看：有没有引用、编号存不存在、句子里的关键词在被引的资料里出现了多少。
    这是便宜的近似，不是语义判断；真正的忠实度评测见评测页。"""
    out = []
    for s in re.split(r"(?<=[。！？\n])", answer):
        s = s.strip()
        if len(s) < 6:
            continue
        ids = [int(x) for x in re.findall(r"\[(\d+)\]", s)]
        body = re.sub(r"\[\d+\]", "", s)
        words = {w for w in tokenize(body) if len(w) > 1}
        ok_ids = [i for i in ids if 1 <= i <= len(rows)]
        src = " ".join(rows[i - 1]["text"].lower() for i in ok_ids)
        cover = sum(w in src for w in words) / max(1, len(words))
        out.append({"sentence": s, "cites": ids, "bad_ids": [i for i in ids if i not in ok_ids],
                    "cover": round(cover, 2)})
    return out

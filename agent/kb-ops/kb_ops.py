"""知识库运维：按片做增量同步、按团队控制权限、按版本上下线。在全景 kb.py 的解析和切块之上。

目录约定：<库根>/<团队>/<文件>，团队目录名就是 team（public 表示所有人可见）。
文件名 xxx-v3.2.pdf：key = xxx，version = v3.2；没有版本号的 version = v1。

sync()：
  1. 扫目录，算每个文件的哈希，和 docs 表比：没变的跳过
  2. 变了的文件重新解析、切块；每一片按“标题路径 + 正文”算哈希
  3. 片哈希已经在库里的（同 key 同 version）：保留，不重算向量；新的：算向量插入；库里有、这次没有的：删掉
  4. 同 key 出了新版本：旧版本的片标成 retired（留着审计，查不到）
  5. 目录里消失的文件：它的片标成 deleted
"""
import hashlib
import os
import re
import sys
import time
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "pano-06-runbook-to-report"))
import kb  # noqa: E402

EMBED_MODEL = os.environ.get("EMBED_MODEL", "Qwen3-Embedding-0.6B-Q8_0")
SCHEMA = f"""
CREATE EXTENSION IF NOT EXISTS vector;
CREATE TABLE IF NOT EXISTS ops_docs (key text, version text, path text, team text,
  sha text, status text, synced_at timestamptz DEFAULT now(), PRIMARY KEY (key, version));
CREATE TABLE IF NOT EXISTS ops_chunks (id bigserial PRIMARY KEY, key text, version text, team text,
  status text, section text, page int, text text, chunk_sha text, embed_model text, emb vector({kb.DIM}));
CREATE INDEX IF NOT EXISTS ops_chunks_hnsw ON ops_chunks USING hnsw (emb vector_cosine_ops);
CREATE INDEX IF NOT EXISTS ops_chunks_key ON ops_chunks (key, version);
"""


@dataclass
class FileDoc:
    path: Path
    key: str
    version: str
    team: str
    sha: str


def file_doc(root: Path, p: Path) -> FileDoc:
    m = re.match(r"(.+)-(v\d+(?:\.\d+)*)$", p.stem)
    key, ver = (m.group(1), m.group(2)) if m else (p.stem, "v1")
    return FileDoc(p, key, ver, p.relative_to(root).parts[0], hashlib.sha256(p.read_bytes()).hexdigest()[:16])


def vkey(v):
    """v3.10 要比 v3.9 新：按数字比，不按字符串比。"""
    return tuple(int(x) for x in re.findall(r"\d+", v))


def chunk_sha(c):
    return hashlib.sha256((c.section + "\n" + c.text).encode()).hexdigest()[:16]


def connect():
    import psycopg
    conn = psycopg.connect(kb.PG_DSN, autocommit=True)
    conn.execute(SCHEMA)
    return conn


def sync(conn, root: Path, log=print):
    """扫一遍目录，只处理变了的文件、变了的片。文档按 key + version 认，不按路径认：挪目录不算新文件。"""
    t0 = time.perf_counter()
    st = {"files": 0, "skipped": 0, "acl_only": 0, "changed_files": 0, "embedded": 0, "kept": 0, "deleted": 0,
          "retired": 0, "removed_files": 0}
    seen = set()
    with conn.cursor() as cur:
        for p in sorted(x for x in root.rglob("*") if x.is_file()):
            st["files"] += 1
            d = file_doc(root, p)
            seen.add((d.key, d.version))
            cur.execute("SELECT sha, team, path FROM ops_docs WHERE key = %s AND version = %s", (d.key, d.version))
            row = cur.fetchone()
            if row and row[0] == d.sha:
                if row[1] != d.team or row[2] != str(p):  # 内容没变，只是换了团队目录：改权限，不重算向量
                    cur.execute("UPDATE ops_chunks SET team = %s WHERE key = %s AND version = %s", (d.team, d.key, d.version))
                    cur.execute("UPDATE ops_docs SET team = %s, path = %s, status = 'current' WHERE key = %s AND version = %s",
                                (d.team, str(p), d.key, d.version))
                    st["acl_only"] += 1
                    log(f"    {p.relative_to(root)}：内容没变，团队 {row[1]} → {d.team}，只改权限")
                else:
                    st["skipped"] += 1
                continue
            st["changed_files"] += 1
            kdoc = kb.Doc(p, d.key, d.version, d.team, d.sha)
            chunks = kb.chunk(kdoc, kb.clean(kb.parse(kdoc)))
            cur.execute("SELECT chunk_sha, id FROM ops_chunks WHERE key = %s AND version = %s AND status <> 'deleted'",
                        (d.key, d.version))
            old = dict(cur.fetchall())
            new_sha = {chunk_sha(c): c for c in chunks}
            todo = [c for s, c in new_sha.items() if s not in old]
            gone = [i for s, i in old.items() if s not in new_sha]
            st["kept"] += len(new_sha) - len(todo)
            if todo:
                vecs = kb.embed([c.embed_text() for c in todo])
                for c, v in zip(todo, vecs):
                    cur.execute("INSERT INTO ops_chunks (key, version, team, status, section, page, text, chunk_sha, embed_model, emb) "
                                "VALUES (%s,%s,%s,'current',%s,%s,%s,%s,%s,%s::vector)",
                                (d.key, d.version, d.team, c.section, c.page, c.text, chunk_sha(c), EMBED_MODEL, kb.vec_lit(v)))
                st["embedded"] += len(todo)
            if gone:
                cur.execute("DELETE FROM ops_chunks WHERE id = ANY(%s)", (gone,))
                st["deleted"] += len(gone)
            cur.execute("UPDATE ops_chunks SET team = %s WHERE key = %s AND version = %s", (d.team, d.key, d.version))
            # 谁是现行版按版本号比，不按处理顺序：旧版文件改了个错字，也不能把新版挤下线
            cur.execute("SELECT version FROM ops_docs WHERE key = %s AND status IN ('current', 'retired')", (d.key,))
            newest = max([d.version] + [r[0] for r in cur.fetchall()], key=vkey)
            status = "current" if d.version == newest else "retired"
            cur.execute("UPDATE ops_chunks SET status = %s WHERE key = %s AND version = %s AND status = 'current'",
                        (status, d.key, d.version))
            cur.execute("UPDATE ops_chunks SET status = 'retired' WHERE key = %s AND version <> %s AND status = 'current'",
                        (d.key, newest))
            st["retired"] += cur.rowcount
            cur.execute("UPDATE ops_docs SET status = 'retired' WHERE key = %s AND version <> %s AND status = 'current'",
                        (d.key, newest))
            cur.execute("INSERT INTO ops_docs (key, version, path, team, sha, status) VALUES (%s,%s,%s,%s,%s,%s) "
                        "ON CONFLICT (key, version) DO UPDATE SET sha = EXCLUDED.sha, path = EXCLUDED.path, team = EXCLUDED.team, "
                        "status = EXCLUDED.status, synced_at = now()", (d.key, d.version, str(p), d.team, d.sha, status))
            log(f"    {p.relative_to(root)}：{len(chunks)} 片，新算向量 {len(todo)}，保留 {len(new_sha) - len(todo)}，删除 {len(gone)}")
        cur.execute("SELECT key, version, path FROM ops_docs WHERE status IN ('current', 'retired')")
        for key, ver, path in cur.fetchall():
            if (key, ver) not in seen:                    # 文件被删了
                cur.execute("UPDATE ops_chunks SET status = 'deleted' WHERE key = %s AND version = %s AND status <> 'deleted'", (key, ver))
                n = cur.rowcount
                st["deleted"] += n
                cur.execute("UPDATE ops_docs SET status = 'deleted' WHERE key = %s AND version = %s", (key, ver))
                st["removed_files"] += 1
                log(f"    {Path(path).name}：文件没了，{n} 片标成 deleted")
    st["seconds"] = round(time.perf_counter() - t0, 1)
    return st


def search(conn, q, teams, k=3):
    """检索时的过滤：只查 current、只查这个人能看的团队（加 public）。"""
    qv = kb.embed([q], query=True)[0]
    with conn.cursor() as cur:
        cur.execute("SELECT key, version, team, section, left(text, 60), 1 - (emb <=> %s::vector) FROM ops_chunks "
                    "WHERE status = 'current' AND team = ANY(%s) ORDER BY emb <=> %s::vector LIMIT %s",
                    (kb.vec_lit(qv), list(teams) + ["public"], kb.vec_lit(qv), k))
        return cur.fetchall()


def counts(conn):
    with conn.cursor() as cur:
        cur.execute("SELECT status, count(*) FROM ops_chunks GROUP BY 1 ORDER BY 1")
        return dict(cur.fetchall())

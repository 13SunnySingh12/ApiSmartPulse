"""task_embeddings maintenance and vector search, always filtered by project (SEC-042)."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Any

import numpy as np

from app import db
from app.embeddings import chunking
from app.embeddings.embedder import Embedder


@dataclass
class Chunk:
    task_id: int
    task_key: str
    title: str
    status: str
    source_type: str
    content: str
    similarity: float


def _replace(conn: Any, rows: list[tuple[Any, ...]], source_type: str, source_id: int) -> int:
    conn.execute("DELETE FROM task_embeddings WHERE source_type = %s AND source_id = %s", (source_type, source_id))
    for r in rows:
        conn.execute(
            "INSERT INTO task_embeddings (project_id, task_id, source_type, source_id, chunk_index, content,"
            " content_hash, embedding, source_date, model_name) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            r,
        )
    return len(rows)


def upsert(embedder: Embedder, entity_type: str, entity_id: int) -> int:
    """Embeds the current text; skips work when every chunk hash is unchanged (QA-EMB-01)."""
    with db.connection() as conn:
        if entity_type == "TASK":
            row = conn.execute(
                "SELECT t.id, t.project_id, p.key || '-' || t.number, t.title, t.description, t.updated_at"
                " FROM tasks t JOIN projects p ON p.id = t.project_id WHERE t.id = %s",
                (entity_id,),
            ).fetchone()
            if row is None:
                return -delete(entity_type, entity_id)
            task_id, project_id, key, title, description, source_date = row
            texts = chunking.task_chunks(key, title, description)
        else:
            row = conn.execute(
                "SELECT c.task_id, t.project_id, p.key || '-' || t.number, c.body, c.created_at, c.deleted_at"
                " FROM comments c JOIN tasks t ON t.id = c.task_id JOIN projects p ON p.id = t.project_id"
                " WHERE c.id = %s",
                (entity_id,),
            ).fetchone()
            if row is None or row[5] is not None:
                return -delete(entity_type, entity_id)
            task_id, project_id, key, body, source_date, _ = row
            texts = chunking.comment_chunks(key, body)
        hashes = [chunking.content_hash(t) for t in texts]
        existing = conn.execute(
            "SELECT content_hash, model_name FROM task_embeddings WHERE source_type = %s AND source_id = %s ORDER BY chunk_index",
            (entity_type, entity_id),
        ).fetchall()
        if [h for h, _ in existing] == hashes and all(m == embedder.name for _, m in existing):
            return 0
        vectors = embedder.embed(texts) if texts else []
        rows = [
            (project_id, task_id, entity_type, entity_id, i, text, h, np.array(v, dtype=np.float32), source_date, embedder.name)
            for i, (text, h, v) in enumerate(zip(texts, hashes, vectors, strict=True))
        ]
        with conn.transaction():
            return _replace(conn, rows, entity_type, entity_id)


def delete(entity_type: str, entity_id: int) -> int:
    with db.connection() as conn:
        cur = conn.execute("DELETE FROM task_embeddings WHERE source_type = %s AND source_id = %s", (entity_type, entity_id))
        return cur.rowcount


_SEARCH = """
SELECT e.task_id, p.key || '-' || t.number, t.title, t.status, e.source_type, e.content,
       1 - (e.embedding <=> %(q)s) AS similarity
FROM task_embeddings e JOIN tasks t ON t.id = e.task_id JOIN projects p ON p.id = t.project_id
WHERE e.project_id = %(project)s {extra}
ORDER BY e.embedding <=> %(q)s
LIMIT %(limit)s
"""


def search(project_id: int, vector: list[float], limit: int, extra: str = "", params: dict | None = None) -> list[Chunk]:  # type: ignore[type-arg]
    """Filtered HNSW search; if a selective filter returns too few rows, raise ef_search, then go exact (§6.3)."""
    args = {"q": np.array(vector, dtype=np.float32), "project": project_id, "limit": limit, **(params or {})}
    sql = _SEARCH.format(extra=extra)
    with db.connection() as conn:
        for setting in (None, "SET LOCAL hnsw.ef_search = 200", "SET LOCAL enable_indexscan = off"):
            with conn.transaction():
                if setting:
                    conn.execute(setting)
                rows = conn.execute(sql, args).fetchall()
            if len(rows) >= limit:
                break
    return [Chunk(r[0], r[1], r[2], r[3], r[4], r[5], float(r[6])) for r in rows]


def similar_tasks(project_id: int, vector: list[float], threshold: float, limit: int) -> list[Chunk]:
    """Duplicate check: first chunk of each task, open or completed in the last 90 days (API-081)."""
    cutoff = datetime.now().astimezone() - timedelta(days=90)
    rows = search(
        project_id,
        vector,
        limit,
        extra="AND e.source_type = 'TASK' AND e.chunk_index = 0 AND (t.status <> 'DONE' OR t.completed_at >= %(cutoff)s)",
        params={"cutoff": cutoff},
    )
    return [r for r in rows if r.similarity >= threshold]


def retrieve(project_id: int, vector: list[float], start: date | None, end: date | None, k: int = 8) -> list[Chunk]:
    """RAG retrieval: k=8, similarity ≥ 0.30, at most 2 chunks per task, optional date range."""
    extra, params = "", {}
    if start is not None:
        extra += " AND e.source_date >= %(start)s"
        params["start"] = start
    if end is not None:
        extra += " AND e.source_date < %(end)s"
        params["end"] = end + timedelta(days=1)
    rows = search(project_id, vector, k * 3, extra, params)
    out: list[Chunk] = []
    per_task: dict[int, int] = {}
    for r in rows:
        if r.similarity < 0.30 or per_task.get(r.task_id, 0) >= 2:
            continue
        per_task[r.task_id] = per_task.get(r.task_id, 0) + 1
        out.append(r)
        if len(out) == k:
            break
    return out

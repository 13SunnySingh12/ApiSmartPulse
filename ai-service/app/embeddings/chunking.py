"""Chunking (§5.9.3): ≤ 200-word chunks, text truncated to 2 000 chars before embedding (§2.8)."""

import hashlib

MAX_WORDS = 200
MAX_CHARS = 2000


def chunks(text: str) -> list[str]:
    words = text[:MAX_CHARS].split()
    if not words:
        return []
    return [" ".join(words[i : i + MAX_WORDS]) for i in range(0, len(words), MAX_WORDS)]


def task_chunks(key: str, title: str, description: str | None) -> list[str]:
    return chunks(f"{key} {title}\n{description or ''}")


def comment_chunks(key: str, body: str) -> list[str]:
    return chunks(f"{key} comment: {body}")


def content_hash(text: str) -> str:
    return hashlib.sha256(text.encode()).hexdigest()

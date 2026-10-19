"""Embedding backends (TR-006). The local model loads once in a background thread; /ready reports it."""

from __future__ import annotations

import hashlib
import itertools
import logging
import math
import re
import threading
from typing import Protocol

log = logging.getLogger(__name__)
DIM = 384
_TOKEN = re.compile(r"[a-z0-9]+")


class Embedder(Protocol):
    name: str

    @property
    def ready(self) -> bool: ...

    def embed(self, texts: list[str]) -> list[list[float]]: ...


class NotReady(Exception):
    pass


class FakeEmbedder:
    """Deterministic feature hashing over word unigrams and bigrams: similar text → similar vectors."""

    name = "fake-hash-384"
    ready = True

    def embed(self, texts: list[str]) -> list[list[float]]:
        return [self._one(t) for t in texts]

    @staticmethod
    def _one(text: str) -> list[float]:
        tokens = _TOKEN.findall(text.lower())
        features = tokens + [f"{a} {b}" for a, b in itertools.pairwise(tokens)]
        vec = [0.0] * DIM
        for f in features:
            h = int.from_bytes(hashlib.md5(f.encode()).digest()[:8], "big")  # noqa: S324 — not security use
            vec[h % DIM] += 1.0 if (h >> 32) & 1 else -1.0
        norm = math.sqrt(sum(v * v for v in vec)) or 1.0
        return [v / norm for v in vec]


class LocalEmbedder:
    """sentence-transformers on CPU. Imported lazily so the test environment needs no PyTorch."""

    def __init__(self, model_name: str) -> None:
        self.name = model_name.rsplit("/", maxsplit=1)[-1]
        self._model_name = model_name
        self._model: object | None = None
        self._error: str | None = None

    def start_loading(self) -> None:
        threading.Thread(target=self._load, daemon=True, name="embedder-load").start()

    def _load(self) -> None:
        try:
            from sentence_transformers import SentenceTransformer  # noqa: PLC0415

            model = SentenceTransformer(self._model_name, device="cpu")
            dim = model.get_sentence_embedding_dimension()
            if dim != DIM:
                raise RuntimeError(f"{self._model_name} has dimension {dim}, expected {DIM}")
            self._model = model
            log.info("Embedding model %s loaded", self._model_name)
        except Exception as e:  # keep serving; /ready stays 503 and AI endpoints say warming up
            self._error = type(e).__name__
            log.error("Embedding model failed to load: %s", self._error)

    @property
    def ready(self) -> bool:
        return self._model is not None

    def embed(self, texts: list[str]) -> list[list[float]]:
        if self._model is None:
            raise NotReady()
        vectors = self._model.encode(texts, normalize_embeddings=True, show_progress_bar=False)  # type: ignore[attr-defined]
        return [list(map(float, v)) for v in vectors]

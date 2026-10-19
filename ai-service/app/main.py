"""Internal AI API (§7.5). Only /internal/v1/**, /health and /ready; never exposed to browsers."""

from __future__ import annotations

import hmac
import logging
import time
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Annotated

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app import db, logging_setup
from app.config import Settings, get_settings
from app.embeddings import store
from app.embeddings.embedder import Embedder, FakeEmbedder, LocalEmbedder, NotReady
from app.llm.guard import LlmGuard
from app.llm.providers import LlmQuota, build_provider
from app.ml.models import Registry, TrainingInProgress
from app.rag import service as rag
from app.schemas import (
    AskRequest,
    AskResponse,
    BatchRequest,
    BatchResponse,
    EntityRef,
    Match,
    Prediction,
    PredictRequest,
    SimilarRequest,
    SimilarResponse,
    SplitRequest,
    SplitResponse,
    TrainRequest,
)

log = logging.getLogger("app")
MAX_BODY = 256 * 1024


@dataclass
class State:
    settings: Settings
    embedder: Embedder
    guard: LlmGuard
    registry: Registry


def build_state(settings: Settings) -> State:
    embedder: Embedder
    if settings.embeddings_backend == "fake":
        embedder = FakeEmbedder()
    else:
        local = LocalEmbedder(settings.embedding_model)
        local.start_loading()
        embedder = local
    guard = LlmGuard(build_provider(settings), settings.llm_max_calls_per_minute, settings.llm_daily_token_budget, settings.tz)
    return State(settings, embedder, guard, Registry(settings))


def error(status: int, code: str, detail: str, headers: dict[str, str] | None = None) -> HTTPException:
    return HTTPException(status_code=status, detail={"code": code, "detail": detail}, headers=headers)


def st(request: Request) -> State:
    """Shared service state. The X-Internal-Token itself is checked in the request middleware."""
    return request.app.state.s  # type: ignore[no-any-return]


# Module level so FastAPI can resolve it under `from __future__ import annotations`.
Internal = Annotated[State, Depends(st)]


def create_app(state: State | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        if state is None:
            settings = get_settings()
            logging_setup.configure(settings.log_level)
            db.init_pool(settings)
            db.check_schema()
            app.state.s = build_state(settings)
            log.info("AI service ready to accept requests (llm=%s, embeddings=%s)", settings.llm_provider, settings.embeddings_backend)
        else:
            app.state.s = state
        yield
        if state is None:
            db.close_pool()

    app = FastAPI(title="ApiSmartPulse AI service (internal)", version="1.0.0", lifespan=lifespan, docs_url=None, redoc_url=None)

    @app.middleware("http")
    async def request_context(request: Request, call_next: Callable[[Request], Awaitable[Response]]) -> Response:
        rid = request.headers.get("x-request-id") or str(uuid.uuid4())
        token = logging_setup.request_id.set(rid[:64])
        start = time.perf_counter()
        try:
            if request.url.path.startswith("/internal/"):
                supplied = request.headers.get("x-internal-token", "")
                expected = request.app.state.s.settings.internal_service_token
                if not hmac.compare_digest(supplied.encode(), expected.encode()):  # before any body parsing
                    return JSONResponse({"code": "UNAUTHENTICATED", "detail": "Missing or invalid X-Internal-Token"}, status_code=401)
            length = request.headers.get("content-length")
            if length and length.isdigit() and int(length) > MAX_BODY:
                return JSONResponse({"code": "PAYLOAD_TOO_LARGE", "detail": "Body over 256 KB"}, status_code=413)
            response = await call_next(request)
            route = request.scope.get("route")
            log.info(
                "request",
                extra={
                    "method": request.method,
                    "path": getattr(route, "path", request.url.path),
                    "status": response.status_code,
                    "duration_ms": round((time.perf_counter() - start) * 1000, 1),
                },
            )
            response.headers["X-Request-Id"] = rid[:64]
            return response
        finally:
            logging_setup.request_id.reset(token)

    @app.exception_handler(HTTPException)
    async def http_error(request: Request, exc: HTTPException) -> JSONResponse:
        body = exc.detail if isinstance(exc.detail, dict) else {"code": "ERROR", "detail": str(exc.detail)}
        return JSONResponse(body, status_code=exc.status_code, headers=exc.headers)

    @app.exception_handler(RequestValidationError)
    async def invalid(request: Request, exc: RequestValidationError) -> JSONResponse:
        first = exc.errors()[0] if exc.errors() else {}
        where = ".".join(str(p) for p in first.get("loc", []) if p != "body")
        return JSONResponse({"code": "VALIDATION_FAILED", "detail": f"{where}: {first.get('msg', 'invalid')}"}, status_code=422)

    def ready_embedder(s: State) -> Embedder:
        if not s.embedder.ready:
            raise error(503, "AI_WARMING_UP", "The embedding model is still loading.")
        return s.embedder

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "UP"}

    @app.get("/ready")
    def ready(request: Request) -> JSONResponse:
        s = st(request)
        ok = s.embedder.ready and db.ping()
        return JSONResponse({"status": "READY" if ok else "NOT_READY", "embeddings": s.embedder.ready}, status_code=200 if ok else 503)

    @app.post("/internal/v1/predict", response_model=Prediction, response_model_by_alias=True)
    def predict(body: PredictRequest, s: Internal) -> Prediction:
        return s.registry.predict([body.task])[0]

    @app.post("/internal/v1/predict/batch", response_model=BatchResponse, response_model_by_alias=True)
    def predict_batch(body: BatchRequest, s: Internal) -> BatchResponse:
        return BatchResponse(results=s.registry.predict(body.tasks))

    @app.post("/internal/v1/train", status_code=202)
    def train(body: TrainRequest, s: Internal) -> dict[str, list[int]]:
        try:
            return {"runIds": s.registry.start_training(body.triggered_by)}
        except TrainingInProgress as e:
            raise error(409, "TRAINING_IN_PROGRESS", "A training run is already in progress.") from e

    @app.post("/internal/v1/embeddings/upsert")
    def upsert(body: EntityRef, s: Internal) -> dict[str, int]:
        try:
            return {"chunks": store.upsert(ready_embedder(s), body.entity_type, body.entity_id)}
        except NotReady as e:
            raise error(503, "AI_WARMING_UP", "The embedding model is still loading.") from e

    @app.post("/internal/v1/embeddings/delete")
    def delete(body: EntityRef, s: Internal) -> dict[str, int]:
        return {"deleted": store.delete(body.entity_type, body.entity_id)}

    @app.post("/internal/v1/similar", response_model=SimilarResponse, response_model_by_alias=True)
    def similar(body: SimilarRequest, s: Internal) -> SimilarResponse:
        threshold = body.threshold if body.threshold is not None else s.settings.duplicate_threshold
        vector = ready_embedder(s).embed([body.text])[0]
        rows = store.similar_tasks(body.project_id, vector, threshold, body.limit)
        return SimilarResponse(
            matches=[
                Match(task_id=r.task_id, task_key=r.task_key, title=r.title, status=r.status, similarity=round(r.similarity, 3))
                for r in rows
            ],
            threshold=threshold,
        )

    @app.post("/internal/v1/rag/ask", response_model=AskResponse, response_model_by_alias=True)
    def ask(body: AskRequest, s: Internal) -> AskResponse:
        return rag.ask(body, ready_embedder(s), s.guard, s.settings.llm_timeout_seconds)

    @app.post("/internal/v1/split", response_model=SplitResponse, response_model_by_alias=True)
    def split(body: SplitRequest, s: Internal) -> SplitResponse:
        if s.guard.provider.name == "none":
            raise error(503, "LLM_DISABLED", "AI suggestions are turned off (LLM_PROVIDER=none).")
        try:
            items = rag.split(body.task_id, s.guard, s.settings.llm_timeout_seconds)
        except LookupError as e:
            raise error(404, "NOT_FOUND", "Task not found.") from e
        except LlmQuota as e:
            retry = str(e.retry_after or 60)
            raise error(429, "AI_RATE_LIMITED", f"AI is busy — try again in {retry} s.", {"Retry-After": retry}) from e
        except rag.SplitFailed as e:
            raise error(502, "AI_BAD_OUTPUT", "Couldn't generate suggestions.") from e
        except Exception as e:
            log.warning("Split failed: %s", type(e).__name__)
            raise error(503, "LLM_UNAVAILABLE", "The AI provider is unavailable right now.") from e
        return SplitResponse(suggestions=items, provider=s.guard.provider.name)

    return app


app = create_app()

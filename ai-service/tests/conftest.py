"""Test wiring: fake embedder, mock LLM, and a throwaway database migrated by scripts/ci/apply-migrations.sh.

Environment (defaults match that script): PGHOST/PGPORT/PGUSER/PGPASSWORD for the superuser used to
insert fixtures, TEST_DB (apismartpulse_test) and DB_AI_PASSWORD for the asp_ai runtime role.
"""

from __future__ import annotations

import itertools
import os
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from typing import Any

import psycopg
import pytest
from fastapi.testclient import TestClient

TOKEN = "t" * 40
os.environ.setdefault("INTERNAL_SERVICE_TOKEN", TOKEN)

from app import db  # noqa: E402
from app.config import Settings  # noqa: E402
from app.embeddings.embedder import FakeEmbedder  # noqa: E402
from app.llm.guard import LlmGuard  # noqa: E402
from app.llm.providers import MockProvider, Provider  # noqa: E402
from app.main import State, create_app  # noqa: E402
from app.ml.models import Registry  # noqa: E402

TEST_DB = os.environ.get("TEST_DB", "apismartpulse_test")
HOST = os.environ.get("PGHOST", "localhost")
PORT = os.environ.get("PGPORT", "5432")
_numbers = itertools.count(1)


def superuser_dsn() -> str:
    return (
        f"host={HOST} port={PORT} dbname={TEST_DB} user={os.environ.get('PGUSER', 'postgres')} password={os.environ.get('PGPASSWORD', '')}"
    )


@pytest.fixture(scope="session")
def settings(tmp_path_factory: pytest.TempPathFactory) -> Settings:
    return Settings(
        ai_database_url=f"postgresql://{HOST}:{PORT}/{TEST_DB}",
        db_ai_user="asp_ai",
        db_ai_password=os.environ.get("DB_AI_PASSWORD", "ci-ai-only"),
        internal_service_token=TOKEN,
        llm_provider="mock",
        embeddings_backend="fake",
        models_dir=str(tmp_path_factory.mktemp("models")),
        ml_min_training_rows=50,
    )


@pytest.fixture(scope="session")
def pool(settings: Settings) -> Iterator[None]:
    db.init_pool(settings)
    db.check_schema()
    yield
    db.close_pool()


@pytest.fixture()
def su(pool: None) -> Iterator[psycopg.Connection[Any]]:
    """Superuser connection for fixtures; every test starts from empty tables."""
    with psycopg.connect(superuser_dsn(), autocommit=True) as conn:
        conn.execute(
            "TRUNCATE users, projects, tasks, comments, task_embeddings, ml_model_versions, task_events,"
            " task_dependencies, labels, task_labels, subtasks RESTART IDENTITY CASCADE"
        )
        yield conn


@pytest.fixture()
def state(settings: Settings, pool: None) -> State:
    return State(settings, FakeEmbedder(), LlmGuard(MockProvider("ok"), 100, 10_000_000, settings.tz), Registry(settings))


def use_provider(state: State, provider: Provider, per_minute: int = 100, budget: int = 10_000_000) -> None:
    state.guard = LlmGuard(provider, per_minute, budget, state.settings.tz)


@pytest.fixture()
def client(state: State) -> Iterator[TestClient]:
    with TestClient(create_app(state)) as c:
        c.headers.update({"X-Internal-Token": TOKEN})
        yield c


class Data:
    """Small purpose-built fixtures (§9.2), inserted as the superuser."""

    def __init__(self, conn: psycopg.Connection[Any]) -> None:
        self.conn = conn
        self.user_id = conn.execute(
            "INSERT INTO users (email, name, password_hash) VALUES ('t@test.local', 'Tester', 'x') RETURNING id"
        ).fetchone()[0]  # type: ignore[index]

    def project(self, key: str) -> int:
        return self.conn.execute(
            "INSERT INTO projects (key, name, owner_id) VALUES (%s, %s, %s) RETURNING id", (key, f"Project {key}", self.user_id)
        ).fetchone()[0]  # type: ignore[index]

    def task(self, project_id: int, title: str, description: str = "", **kw: Any) -> int:
        number = next(_numbers)
        created = kw.pop("created_at", datetime.now(UTC) - timedelta(days=1))
        status = kw.pop("status", "TODO")
        completed = kw.pop("completed_at", created + timedelta(days=2) if status == "DONE" else None)
        return self.conn.execute(
            "INSERT INTO tasks (project_id, number, title, description, status, priority, creator_id, assignee_id,"
            " estimate_hours, actual_hours, due_date, created_at, updated_at, completed_at)"
            " VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s) RETURNING id",
            (
                project_id,
                number,
                title,
                description,
                status,
                kw.get("priority", "MEDIUM"),
                self.user_id,
                kw.get("assignee_id"),
                kw.get("estimate_hours"),
                kw.get("actual_hours"),
                kw.get("due_date"),
                created,
                created,
                completed,
            ),
        ).fetchone()[0]  # type: ignore[index]

    def key(self, task_id: int) -> str:
        return self.conn.execute(
            "SELECT p.key || '-' || t.number FROM tasks t JOIN projects p ON p.id = t.project_id WHERE t.id = %s", (task_id,)
        ).fetchone()[0]  # type: ignore[index]

    def comment(self, task_id: int, body: str) -> int:
        return self.conn.execute(
            "INSERT INTO comments (task_id, author_id, body) VALUES (%s, %s, %s) RETURNING id", (task_id, self.user_id, body)
        ).fetchone()[0]  # type: ignore[index]


@pytest.fixture()
def data(su: psycopg.Connection[Any]) -> Data:
    return Data(su)

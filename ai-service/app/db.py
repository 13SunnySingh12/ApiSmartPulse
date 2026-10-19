"""Connection pool as the restricted asp_ai role (SEC-014). FastAPI never runs migrations."""

from collections.abc import Iterator
from contextlib import contextmanager

import psycopg
from pgvector.psycopg import register_vector
from psycopg_pool import ConnectionPool

from app.config import SCHEMA_MIN_VERSION, Settings

_pool: ConnectionPool | None = None


def _configure(conn: psycopg.Connection) -> None:
    register_vector(conn)


def init_pool(settings: Settings) -> ConnectionPool:
    global _pool
    _pool = ConnectionPool(
        settings.ai_database_url,
        kwargs={"user": settings.db_ai_user, "password": settings.db_ai_password, "connect_timeout": 5},
        min_size=1,
        max_size=8,
        configure=_configure,
        open=True,
    )
    return _pool


def close_pool() -> None:
    global _pool
    if _pool is not None:
        _pool.close()
        _pool = None


@contextmanager
def connection() -> Iterator[psycopg.Connection]:
    if _pool is None:
        raise RuntimeError("database pool not initialised")
    with _pool.connection() as conn:
        yield conn


def check_schema() -> None:
    """Refuse to start against a schema older than this service expects (§6.6)."""
    with connection() as conn:
        row = conn.execute("SELECT max(version::int) FROM flyway_schema_history WHERE success AND version IS NOT NULL").fetchone()
    version = row[0] if row else None
    if version is None or version < SCHEMA_MIN_VERSION:
        raise RuntimeError(
            f"Database schema version {version} is older than required {SCHEMA_MIN_VERSION}; "
            "start the backend first so Flyway can migrate it."
        )


def ping() -> bool:
    try:
        with connection() as conn:
            conn.execute("SELECT 1")
        return True
    except Exception:
        return False

"""QA-AI-04..06: training on a fixture with a learnable bias; skip and failure keep the previous model."""

import random
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app.main import State
from app.ml import models
from tests.conftest import Data

WORDS = {"URGENT": "outage security incident", "HIGH": "bug fix error", "MEDIUM": "feature update page", "LOW": "docs tidy copy"}


def seed_history(data: Data, rows: int) -> int:
    """`rows` completed tasks: actual ≈ 1.4 × estimate, ~25 % late (deterministic)."""
    rng = random.Random(42)
    pid = data.project("TRAIN")
    start = datetime(2026, 1, 5, tzinfo=UTC)
    for i in range(rows):
        priority = rng.choice(list(WORDS))
        estimate = rng.choice([2, 4, 6, 8, 12])
        created = start + timedelta(days=i)
        late = rng.random() < 0.25
        due = (created + timedelta(days=7)).date()
        completed = created + timedelta(days=9 if late else 5)
        data.task(
            pid,
            f"{WORDS[priority]} task {i}",
            WORDS[priority],
            status="DONE",
            priority=priority,
            estimate_hours=estimate,
            actual_hours=round(estimate * 1.4 * 2) / 2,
            due_date=due,
            created_at=created,
            completed_at=completed,
        )
    return pid


def runs(su) -> dict[str, tuple[str, bool]]:  # type: ignore[no-untyped-def]
    return {r[0]: (r[1], r[2]) for r in su.execute("SELECT model_name, status, is_active FROM ml_model_versions ORDER BY id")}


def test_qa_ai_04_training_succeeds_and_models_serve(state: State, data: Data, su, client: TestClient) -> None:  # type: ignore[no-untyped-def]
    pid = seed_history(data, 120)
    state.registry.start_training(triggered_by=None, wait=True)
    result = runs(su)
    assert result == {"ESTIMATOR": ("SUCCEEDED", True), "RISK": ("SUCCEEDED", True), "PRIORITY": ("SUCCEEDED", True)}
    metrics = {r[0]: r[1] for r in su.execute("SELECT model_name, metrics FROM ml_model_versions")}
    assert metrics["ESTIMATOR"]["mae"] is not None and "f1" in metrics["RISK"] and "macro_f1" in metrics["PRIORITY"]
    body = {
        "task": {
            "id": 9,
            "projectId": pid,
            "title": "security outage incident",
            "priority": "URGENT",
            "estimateHours": 8,
            "today": "2026-10-19",
            "dueDate": "2026-10-30",
        }
    }
    p = client.post("/internal/v1/predict", json=body).json()
    assert p["source"] == "MODEL"
    assert p["suggestedPriority"] == "URGENT"
    assert 0.5 <= p["predictedHours"] <= 400


def test_qa_ai_05_insufficient_data_is_skipped_and_previous_stays_active(state: State, data: Data, su) -> None:  # type: ignore[no-untyped-def]
    seed_history(data, 120)
    state.registry.start_training(None, wait=True)
    su.execute("DELETE FROM tasks")
    pid = su.execute("SELECT id FROM projects LIMIT 1").fetchone()[0]
    for i in range(10):
        data.task(pid, f"tiny {i}", "", status="DONE", actual_hours=2)
    state.registry.start_training(None, wait=True)
    statuses = [r for r in su.execute("SELECT model_name, status, is_active FROM ml_model_versions ORDER BY id")]
    assert [s[1] for s in statuses[3:]] == ["SKIPPED_INSUFFICIENT_DATA"] * 3
    assert all(active for _, _, active in statuses[:3])


def test_qa_ai_06_failure_keeps_previous_model(state: State, data: Data, su, monkeypatch: pytest.MonkeyPatch) -> None:  # type: ignore[no-untyped-def]
    seed_history(data, 120)
    state.registry.start_training(None, wait=True)

    def boom(*_a, **_k):  # type: ignore[no-untyped-def]
        raise RuntimeError("synthetic failure")

    monkeypatch.setattr(models, "train_estimator", boom)
    state.registry.start_training(None, wait=True)
    rows = su.execute(
        "SELECT model_name, status, is_active, error_message FROM ml_model_versions WHERE model_name = 'ESTIMATOR' ORDER BY id"
    ).fetchall()
    assert rows[0][1:3] == ("SUCCEEDED", True)
    assert rows[1][1:3] == ("FAILED", False) and "synthetic failure" in rows[1][3]


def test_one_run_at_a_time(state: State, data: Data, client: TestClient, su) -> None:  # type: ignore[no-untyped-def]
    seed_history(data, 60)
    with su.cursor() as cur:
        cur.execute("SELECT pg_advisory_lock(%s)", (models.TRAINING_LOCK_ID,))
        r = client.post("/internal/v1/train", json={"triggeredBy": None})
        cur.execute("SELECT pg_advisory_unlock(%s)", (models.TRAINING_LOCK_ID,))
    assert r.status_code == 409 and r.json()["code"] == "TRAINING_IN_PROGRESS"


def test_artifact_paths_are_validated(state: State) -> None:
    with pytest.raises(ValueError):
        state.registry._resolve("../../etc/passwd")
    with pytest.raises(ValueError):
        state.registry._resolve("ESTIMATOR/x.joblib")

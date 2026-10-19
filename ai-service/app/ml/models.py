"""scikit-learn models (§5.9.5): features, training runs, registry and inference.

Artifacts are joblib files written and read only by this service under MODELS_DIR, with paths
validated against a strict pattern — never loaded from user input (pickle executes code).
"""

from __future__ import annotations

import logging
import math
import re
import threading
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
from psycopg.types.json import Jsonb
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import GradientBoostingRegressor
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.impute import SimpleImputer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import f1_score, mean_absolute_error, roc_auc_score
from sklearn.pipeline import Pipeline, make_pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from app import db
from app.config import Settings
from app.ml import heuristics
from app.schemas import Prediction, TaskFeatures

log = logging.getLogger(__name__)

MODEL_NAMES = ("ESTIMATOR", "RISK", "PRIORITY")
ARTIFACT_PATTERN = re.compile(r"^[A-Z]+/\d{8}-\d{4}\.joblib$")
TRAINING_LOCK_ID = 424_242
PRIORITIES = ["LOW", "MEDIUM", "HIGH", "URGENT"]


class TrainingInProgress(Exception):
    pass


# ── data access ───────────────────────────────────────────────────────────────

_TASKS_SQL = """
SELECT t.id, t.project_id, t.title, coalesce(t.description, '') AS description, t.priority, t.status,
       t.assignee_id, t.estimate_hours::float AS estimate_hours, t.actual_hours::float AS actual_hours,
       t.due_date, t.created_at, t.completed_at,
       (SELECT count(*) FROM subtasks s WHERE s.task_id = t.id) AS subtask_count,
       (SELECT count(*) FROM task_dependencies d WHERE d.task_id = t.id) AS blocker_count,
       coalesce((SELECT array_agg(l.name) FROM task_labels tl JOIN labels l ON l.id = tl.label_id
                 WHERE tl.task_id = t.id), '{}') AS labels
FROM tasks t
"""


def load_tasks() -> pd.DataFrame:
    with db.connection() as conn:
        cur = conn.execute(_TASKS_SQL)
        cols = [c.name for c in cur.description or []]
        return pd.DataFrame(cur.fetchall(), columns=cols)


def medians(project_id: int) -> tuple[dict[str, float], float | None]:
    with db.connection() as conn:
        rows = conn.execute(
            "SELECT priority, percentile_cont(0.5) WITHIN GROUP (ORDER BY actual_hours) FROM tasks"
            " WHERE project_id = %s AND status = 'DONE' AND actual_hours IS NOT NULL GROUP BY priority",
            (project_id,),
        ).fetchall()
        glob = conn.execute(
            "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY actual_hours) FROM tasks WHERE status = 'DONE' AND actual_hours IS NOT NULL"
        ).fetchone()
    return {r[0]: float(r[1]) for r in rows}, (float(glob[0]) if glob and glob[0] is not None else None)


# ── features ──────────────────────────────────────────────────────────────────


def _working_days(start: date, end: date) -> int:
    return heuristics.working_days(start, end) if end >= start else 0


def estimator_frame(df: pd.DataFrame, top_labels: list[str], assignee_median: dict[int, float], fallback: float) -> pd.DataFrame:
    out = pd.DataFrame(
        {
            "priority": df["priority"].astype(str),
            "subtask_count": df["subtask_count"].astype(float),
            "log_description_length": np.log1p(df["description"].str.len().astype(float)),
            "assignee_median": [
                assignee_median.get(int(a), fallback) if a is not None and not pd.isna(a) else fallback for a in df["assignee_id"]
            ],
            "estimate_hours": df["estimate_hours"].astype(float),
        }
    )
    for name in top_labels:
        out[f"label_{name}"] = [1.0 if name in (labels or []) else 0.0 for labels in df["labels"]]
    return out


def _estimator_pipeline(numeric: list[str]) -> Pipeline:
    pre = ColumnTransformer(
        [
            ("priority", OneHotEncoder(categories=[PRIORITIES], handle_unknown="ignore"), ["priority"]),
            ("numeric", SimpleImputer(strategy="median", add_indicator=True, keep_empty_features=True), numeric),
        ]
    )
    return Pipeline([("pre", pre), ("model", GradientBoostingRegressor(random_state=42))])


RISK_COLUMNS = ["estimate", "working_days", "assignee_open_hours", "open_blockers"]


def _risk_pipeline() -> Pipeline:
    pre = ColumnTransformer(
        [
            ("priority", OneHotEncoder(categories=[PRIORITIES], handle_unknown="ignore"), ["priority"]),
            ("numeric", StandardScaler(), RISK_COLUMNS),
        ]
    )
    return Pipeline([("pre", pre), ("model", LogisticRegression(class_weight="balanced", max_iter=1000))])


def risk_frame(df: pd.DataFrame, tz: Any, default_estimate: float) -> pd.DataFrame:
    """Features as they looked when each task was created (open load of the same assignee then)."""
    rows = []
    records: list[dict[Any, Any]] = df.to_dict("records")
    for t in records:
        created = t["created_at"].astimezone(tz).date()
        assignee = t["assignee_id"]
        has_assignee = assignee is not None and not pd.isna(assignee)
        same = df[(df["assignee_id"] == assignee) & (df["created_at"] < t["created_at"])] if has_assignee else df.iloc[0:0]
        still_open = same[same["completed_at"].isna() | (same["completed_at"] > t["created_at"])]
        open_hours = float(still_open["estimate_hours"].fillna(default_estimate).sum())
        rows.append(
            {
                "priority": t["priority"],
                "estimate": t["estimate_hours"] if not pd.isna(t["estimate_hours"]) else default_estimate,
                "working_days": _working_days(created, t["due_date"]),
                "assignee_open_hours": open_hours,
                "open_blockers": float(t["blocker_count"]),
            }
        )
    return pd.DataFrame(rows, columns=["priority", *RISK_COLUMNS])


def _split(df: pd.DataFrame, order: str) -> tuple[pd.DataFrame, pd.DataFrame]:
    df = df.sort_values(order).reset_index(drop=True)
    cut = max(1, int(len(df) * 0.8))
    return df.iloc[:cut], df.iloc[cut:]


# ── training ──────────────────────────────────────────────────────────────────


class InsufficientData(Exception):
    pass


def train_estimator(df: pd.DataFrame, min_rows: int) -> tuple[dict[str, Any], dict[str, Any], int]:
    data = df[(df["status"] == "DONE") & df["actual_hours"].notna()]
    if len(data) < min_rows:
        raise InsufficientData(len(data))
    train, test = _split(data, "completed_at")
    counts: dict[str, int] = {}
    for labels in train["labels"]:
        for name in labels or []:
            counts[name] = counts.get(name, 0) + 1
    top = [n for n, _ in sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[:20]]
    assignee_median = {
        int(str(k)): float(v) for k, v in train.dropna(subset=["assignee_id"]).groupby("assignee_id")["actual_hours"].median().items()
    }
    fallback = float(train["actual_hours"].median())
    numeric = ["subtask_count", "log_description_length", "assignee_median", "estimate_hours"] + [f"label_{n}" for n in top]
    pipe = _estimator_pipeline(numeric)
    pipe.fit(estimator_frame(train, top, assignee_median, fallback), train["actual_hours"])
    mae = None
    if len(test):
        mae = float(mean_absolute_error(test["actual_hours"], pipe.predict(estimator_frame(test, top, assignee_median, fallback))))
    artifact = {"pipeline": pipe, "top_labels": top, "assignee_median": assignee_median, "fallback": fallback}
    return artifact, {"mae": mae, "train_rows": len(train), "test_rows": len(test), "dataset": "project history"}, len(data)


def train_risk(df: pd.DataFrame, min_rows: int, tz: Any) -> tuple[dict[str, Any], dict[str, Any], int]:
    data = df[(df["status"] == "DONE") & df["due_date"].notna() & df["completed_at"].notna()].copy()
    if len(data) < min_rows:
        raise InsufficientData(len(data))
    data["late"] = [c.astimezone(tz).date() > d for c, d in zip(data["completed_at"], data["due_date"], strict=True)]
    if data["late"].nunique() < 2:
        raise InsufficientData(len(data))
    train, test = _split(data, "completed_at")
    default = float(df["actual_hours"].median()) if df["actual_hours"].notna().any() else 4.0
    pipe = _risk_pipeline()
    pipe.fit(risk_frame(train, tz, default), train["late"].astype(int))
    metrics: dict[str, Any] = {"train_rows": len(train), "test_rows": len(test), "dataset": "project history"}
    if len(test):
        x_test = risk_frame(test, tz, default)
        y = test["late"].astype(int)
        metrics["f1"] = float(f1_score(y, pipe.predict(x_test), zero_division=0))
        metrics["roc_auc"] = float(roc_auc_score(y, pipe.predict_proba(x_test)[:, 1])) if y.nunique() > 1 else None
    return {"pipeline": pipe, "default_estimate": default}, metrics, len(data)


def train_priority(df: pd.DataFrame, min_rows: int) -> tuple[dict[str, Any], dict[str, Any], int]:
    data = df.copy()
    if len(data) < min_rows or data["priority"].nunique() < 2:
        raise InsufficientData(len(data))
    train, test = _split(data, "created_at")
    pipe = make_pipeline(
        TfidfVectorizer(ngram_range=(1, 2), min_df=2, max_features=20_000),
        LogisticRegression(max_iter=1000),
    )
    text = lambda d: (d["title"] + " " + d["description"]).tolist()  # noqa: E731
    pipe.fit(text(train), train["priority"])
    metrics: dict[str, Any] = {"train_rows": len(train), "test_rows": len(test), "dataset": "project history"}
    if len(test):
        metrics["macro_f1"] = float(f1_score(test["priority"], pipe.predict(text(test)), average="macro", zero_division=0))
    return {"pipeline": pipe}, metrics, len(data)


class Registry:
    """Model versions in ml_model_versions; artifacts on the ml-models volume."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.root = Path(settings.models_dir).resolve()
        self._cache: dict[str, tuple[str, dict[str, Any]]] = {}
        self._lock = threading.Lock()

    # training ---------------------------------------------------------------

    def start_training(self, triggered_by: int | None, wait: bool = False) -> list[int]:
        """Records RUNNING rows and trains in a background thread; one run at a time (advisory lock)."""
        lock_conn = db._pool.getconn() if db._pool else None
        if lock_conn is None:
            raise RuntimeError("database pool not initialised")
        lock_conn.autocommit = True
        if not lock_conn.execute("SELECT pg_try_advisory_lock(%s)", (TRAINING_LOCK_ID,)).fetchone()[0]:  # type: ignore[index]
            db._pool.putconn(lock_conn)  # type: ignore[union-attr]
            raise TrainingInProgress()
        version = datetime.now(UTC).strftime("%Y%m%d-%H%M")
        try:
            with db.connection() as conn:
                ids = [
                    conn.execute(
                        "INSERT INTO ml_model_versions (model_name, version, status, triggered_by)"
                        " VALUES (%s, %s, 'RUNNING', %s) RETURNING id",
                        (name, version, triggered_by),
                    ).fetchone()[0]  # type: ignore[index]
                    for name in MODEL_NAMES
                ]
        except Exception:
            self._release(lock_conn)
            raise
        worker = threading.Thread(target=self._run, args=(ids, version, lock_conn), daemon=True, name="training")
        worker.start()
        if wait:  # tests and scripts
            worker.join()
        return ids

    def _release(self, lock_conn: Any) -> None:
        try:
            lock_conn.execute("SELECT pg_advisory_unlock(%s)", (TRAINING_LOCK_ID,))
        finally:
            db._pool.putconn(lock_conn)  # type: ignore[union-attr]

    def _run(self, ids: list[int], version: str, lock_conn: Any) -> None:
        try:
            df = load_tasks()
            trainers = {
                "ESTIMATOR": lambda: train_estimator(df, self.settings.ml_min_training_rows),
                "RISK": lambda: train_risk(df, self.settings.ml_min_training_rows, self.settings.tz),
                "PRIORITY": lambda: train_priority(df, self.settings.ml_min_training_rows),
            }
            for run_id, name in zip(ids, MODEL_NAMES, strict=True):
                self._train_one(run_id, name, version, trainers[name])
        except Exception as e:  # load failure: mark every still-running row failed
            log.exception("Training run failed")
            with db.connection() as conn:
                conn.execute(
                    "UPDATE ml_model_versions SET status = 'FAILED', error_message = %s, finished_at = now()"
                    " WHERE id = ANY(%s) AND status = 'RUNNING'",
                    (type(e).__name__, ids),
                )
        finally:
            self._release(lock_conn)

    def _train_one(self, run_id: int, name: str, version: str, trainer: Any) -> None:
        try:
            artifact, metrics, rows = trainer()
        except InsufficientData as e:
            with db.connection() as conn:
                conn.execute(
                    "UPDATE ml_model_versions SET status = 'SKIPPED_INSUFFICIENT_DATA', training_rows = %s,"
                    " error_message = %s, finished_at = now() WHERE id = %s",
                    (e.args[0], f"Needs at least {self.settings.ml_min_training_rows} usable rows", run_id),
                )
            return
        except Exception as e:
            log.exception("Training %s failed", name)
            with db.connection() as conn:
                conn.execute(
                    "UPDATE ml_model_versions SET status = 'FAILED', error_message = %s, finished_at = now() WHERE id = %s",
                    (f"{type(e).__name__}: {str(e)[:200]}", run_id),
                )
            return
        relative = f"{name}/{version}.joblib"
        path = self._resolve(relative)
        path.parent.mkdir(parents=True, exist_ok=True)
        joblib.dump(artifact, path)
        metrics = {k: (None if isinstance(v, float) and math.isnan(v) else v) for k, v in metrics.items()}
        # Activation is atomic: the previous model stays active unless this commit succeeds.
        with db.connection() as conn, conn.transaction():
            conn.execute("UPDATE ml_model_versions SET is_active = false WHERE model_name = %s AND is_active", (name,))
            conn.execute(
                "UPDATE ml_model_versions SET status = 'SUCCEEDED', is_active = true, training_rows = %s, metrics = %s,"
                " artifact_path = %s, finished_at = now() WHERE id = %s",
                (rows, Jsonb(metrics), relative, run_id),
            )
        log.info("Trained %s %s on %s rows", name, version, rows)

    def _resolve(self, relative: str) -> Path:
        if not ARTIFACT_PATTERN.match(relative):
            raise ValueError("unexpected artifact path")
        path = (self.root / relative).resolve()
        if self.root not in path.parents:
            raise ValueError("artifact path escapes MODELS_DIR")
        return path

    # inference --------------------------------------------------------------

    def active(self) -> dict[str, tuple[str, dict[str, Any]]]:
        with db.connection() as conn:
            rows = conn.execute("SELECT model_name, version, artifact_path FROM ml_model_versions WHERE is_active").fetchall()
        with self._lock:
            loaded: dict[str, tuple[str, dict[str, Any]]] = {}
            for name, version, relative in rows:
                cached = self._cache.get(name)
                if cached and cached[0] == version:
                    loaded[name] = cached
                    continue
                try:
                    loaded[name] = (version, joblib.load(self._resolve(relative)))
                except Exception:
                    log.warning("Active %s artifact %s unavailable; using heuristics", name, version)
            self._cache = loaded
            return dict(loaded)

    def predict(self, tasks: list[TaskFeatures]) -> list[Prediction]:
        models = self.active()
        cache: dict[int, tuple[dict[str, float], float | None]] = {}
        out = []
        for t in tasks:
            if t.project_id not in cache:
                cache[t.project_id] = medians(t.project_id)
            by_priority, glob = cache[t.project_id]
            out.append(self._predict_one(t, models, by_priority, glob))
        return out

    def _predict_one(
        self, t: TaskFeatures, models: dict[str, tuple[str, dict[str, Any]]], by_priority: dict[str, float], glob: float | None
    ) -> Prediction:
        base = heuristics.predict(t, by_priority, glob)
        versions: dict[str, str] = {}
        hours = base.predicted_hours
        if "ESTIMATOR" in models:
            version, art = models["ESTIMATOR"]
            row = pd.DataFrame(
                [
                    {
                        "priority": t.priority,
                        "subtask_count": t.subtask_count,
                        "description": "x" * t.description_length,
                        "assignee_id": t.assignee_id,
                        "estimate_hours": t.estimate_hours if t.estimate_hours is not None else np.nan,
                        "labels": t.labels,
                    }
                ]
            )
            frame = estimator_frame(row, art["top_labels"], art["assignee_median"], art["fallback"])
            hours = heuristics.round_half(float(art["pipeline"].predict(frame)[0]))
            versions["est"] = version
        probability, risk = base.overdue_probability, base.risk_level
        if t.due_date is not None and "RISK" in models:
            version, art = models["RISK"]
            frame = pd.DataFrame(
                [
                    {
                        "priority": t.priority,
                        "estimate": t.estimate_hours if t.estimate_hours is not None else hours,
                        "working_days": _working_days(t.today, t.due_date),
                        "assignee_open_hours": t.assignee_open_hours,
                        "open_blockers": float(t.open_blockers),
                    }
                ]
            )
            probability = heuristics.round3(float(art["pipeline"].predict_proba(frame)[0][1]))
            risk = heuristics.risk_level(probability)  # type: ignore[assignment]
            versions["risk"] = version
        priority, confidence = base.suggested_priority, base.priority_confidence
        if "PRIORITY" in models:
            version, art = models["PRIORITY"]
            proba = art["pipeline"].predict_proba([f"{t.title} {t.description or ''}"])[0]
            best = int(np.argmax(proba))
            priority, confidence = art["pipeline"].classes_[best], heuristics.round3(float(proba[best]))
            versions["prio"] = version
        all_models = len(versions) == (3 if t.due_date is not None else 2) and "est" in versions and "prio" in versions
        label = ",".join(f"{k}={versions.get(k, heuristics.VERSION)}" for k in ("est", "risk", "prio")) if versions else heuristics.VERSION
        return Prediction(
            task_id=t.id,
            predicted_hours=max(0.5, min(400.0, hours)),
            overdue_probability=probability,
            risk_level=risk,
            suggested_priority=priority,
            priority_confidence=confidence,
            source="MODEL" if all_models else "HEURISTIC",
            model_version=label[:120],
        )

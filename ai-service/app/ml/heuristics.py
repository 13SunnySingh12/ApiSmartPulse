"""Rule-based fallback (§5.9.5). Must stay identical to backend HeuristicPredictor.java (TR-007)."""

import math
import re
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal

from app.schemas import Prediction, TaskFeatures

VERSION = "heuristic-v1"
_URGENT = re.compile(r"\b(urgent|asap|outage|security)\b")
_HIGH = re.compile(r"\b(bug|fix|error)\b")


def round_half(hours: float) -> float:
    return max(0.5, math.floor(hours * 2 + 0.5) / 2)


def working_days(today: date, due: date) -> int:
    """Weekdays from today through the due date, inclusive."""
    days, d = 0, today
    while d <= due:
        if d.weekday() < 5:
            days += 1
        d += timedelta(days=1)
    return days


def risk_level(p: float) -> str:
    """BR-010: LOW < 0.33 ≤ MEDIUM ≤ 0.66 < HIGH."""
    if p < 0.33:
        return "LOW"
    if p <= 0.66:
        return "MEDIUM"
    return "HIGH"


def overdue_probability(remaining: float, days: int, blocked: bool) -> float:
    p = 1.0 if days <= 0 else min(1.0, max(0.0, remaining / (days * 6 * 0.8)))
    return min(1.0, max(0.0, p + (0.15 if blocked else 0.0)))


def round3(x: float) -> float:
    # Same rounding as Java BigDecimal.valueOf(x).setScale(3, HALF_UP)
    return float(Decimal(repr(x)).quantize(Decimal("0.001"), rounding=ROUND_HALF_UP))


def keyword_priority(title: str, description: str | None) -> tuple[str, float]:
    text = f"{title} {description or ''}".lower()
    if _URGENT.search(text):
        return "URGENT", 0.6
    if _HIGH.search(text):
        return "HIGH", 0.6
    return "MEDIUM", 0.4


def heuristic_estimate(priority: str, median_by_priority: dict[str, float], global_median: float | None) -> float:
    median = median_by_priority.get(priority)
    return round_half(median if median is not None else global_median if global_median is not None else 4.0)


def predict(t: TaskFeatures, median_by_priority: dict[str, float], global_median: float | None) -> Prediction:
    estimate = heuristic_estimate(t.priority, median_by_priority, global_median)
    priority, confidence = keyword_priority(t.title, t.description)
    probability: float | None = None
    risk = "NA"
    if t.due_date is not None:
        remaining = t.estimate_hours if t.estimate_hours is not None else estimate
        probability = round3(overdue_probability(remaining, working_days(t.today, t.due_date), t.open_blockers > 0))
        risk = risk_level(probability)
    return Prediction(
        task_id=t.id,
        predicted_hours=estimate,
        overdue_probability=probability,
        risk_level=risk,  # type: ignore[arg-type]
        suggested_priority=priority,  # type: ignore[arg-type]
        priority_confidence=confidence,
        source="HEURISTIC",
        model_version=VERSION,
    )

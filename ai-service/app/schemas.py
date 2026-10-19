"""Internal API contract (§7.5). Pydantic models are the source of truth; camelCase on the wire."""

from datetime import date
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

Priority = Literal["LOW", "MEDIUM", "HIGH", "URGENT"]
Risk = Literal["LOW", "MEDIUM", "HIGH", "NA"]


class Camel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


class TaskFeatures(Camel):
    id: int
    project_id: int
    title: str = Field(max_length=200)
    description: str | None = Field(default=None, max_length=10_000)
    priority: Priority
    labels: list[str] = Field(default_factory=list, max_length=10)
    subtask_count: int = Field(default=0, ge=0)
    description_length: int = Field(default=0, ge=0)
    assignee_id: int | None = None
    estimate_hours: float | None = Field(default=None, ge=0, le=400)
    due_date: date | None = None
    open_blockers: int = Field(default=0, ge=0)
    assignee_open_hours: float = Field(default=0, ge=0)
    today: date


class PredictRequest(Camel):
    task: TaskFeatures


class BatchRequest(Camel):
    tasks: list[TaskFeatures] = Field(max_length=200)


class Prediction(Camel):
    task_id: int | None = None
    predicted_hours: float
    overdue_probability: float | None
    risk_level: Risk
    suggested_priority: Priority
    priority_confidence: float
    source: Literal["MODEL", "HEURISTIC"]
    model_version: str


class BatchResponse(Camel):
    results: list[Prediction]


class TrainRequest(Camel):
    triggered_by: int | None = None


class EntityRef(Camel):
    entity_type: Literal["TASK", "COMMENT"]
    entity_id: int


class SimilarRequest(Camel):
    project_id: int
    text: str = Field(min_length=3, max_length=4000)
    threshold: float | None = Field(default=None, ge=0, le=1)
    limit: int = Field(default=3, ge=1, le=5)


class Match(Camel):
    task_id: int
    task_key: str
    title: str
    status: str
    similarity: float


class SimilarResponse(Camel):
    matches: list[Match]
    threshold: float


class AskRequest(Camel):
    project_id: int
    question: str = Field(min_length=3, max_length=500)
    from_: date | None = Field(default=None, alias="from")
    to: date | None = None


class Source(Camel):
    task_key: str
    title: str
    status: str
    source_type: Literal["TASK", "COMMENT"]
    snippet: str
    similarity: float


class AskResponse(Camel):
    answer: str | None
    grounded: bool
    degraded: bool
    reason: str | None = None
    retry_after: int | None = None
    sources: list[Source]
    provider: str
    model: str | None = None


class SplitRequest(Camel):
    task_id: int


class SubtaskSuggestion(BaseModel):
    title: str = Field(min_length=1, max_length=120)


class SplitOutput(BaseModel):
    """Exactly what the LLM must return for auto-split (§5.9.4)."""

    model_config = ConfigDict(extra="forbid")
    subtasks: list[SubtaskSuggestion] = Field(min_length=3, max_length=6)


class SplitResponse(Camel):
    suggestions: list[SubtaskSuggestion]
    provider: str

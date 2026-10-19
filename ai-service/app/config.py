"""Settings from the environment (§5.10). Startup fails fast on missing or placeholder secrets."""

from functools import lru_cache
from typing import Literal
from zoneinfo import ZoneInfo

from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

SCHEMA_MIN_VERSION = 3


class Settings(BaseSettings):
    model_config = SettingsConfigDict(extra="ignore", case_sensitive=False)

    ai_database_url: str = "postgresql://localhost:5432/apismartpulse"
    db_ai_user: str = "asp_ai"
    db_ai_password: str = ""
    internal_service_token: str
    llm_provider: Literal["none", "mock", "groq", "gemini"] = "none"
    groq_api_key: str = ""
    groq_model: str = "openai/gpt-oss-20b"
    llm_timeout_seconds: float = 15
    llm_max_calls_per_minute: int = 3
    llm_daily_token_budget: int = 180_000
    mock_llm_scenario: str = "ok"
    embeddings_backend: Literal["local", "fake"] = "local"
    embedding_model: str = "sentence-transformers/all-MiniLM-L6-v2"
    models_dir: str = "/models"
    duplicate_threshold: float = 0.85
    ml_min_training_rows: int = 50
    app_timezone: str = "Asia/Kolkata"
    log_level: str = "INFO"

    @field_validator("internal_service_token")
    @classmethod
    def strong_secret(cls, v: str) -> str:
        if "<" in v or "change-me" in v.lower():
            raise ValueError("INTERNAL_SERVICE_TOKEN still contains a placeholder value")
        if len(v.encode()) < 32:
            raise ValueError("INTERNAL_SERVICE_TOKEN must be at least 32 bytes")
        return v

    @field_validator("llm_provider")
    @classmethod
    def implemented_provider(cls, v: str) -> str:
        if v == "gemini":
            raise ValueError("LLM_PROVIDER=gemini is a P2 adapter that is not implemented; use groq, mock or none")
        return v

    @property
    def tz(self) -> ZoneInfo:
        return ZoneInfo(self.app_timezone)


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]

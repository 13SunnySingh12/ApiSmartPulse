"""App-side limits applied before any provider call (§5.9.2, SEC-044)."""

from __future__ import annotations

import math
import threading
import time
from collections import deque
from collections.abc import Callable
from datetime import date, datetime
from zoneinfo import ZoneInfo

from app.llm.providers import LlmQuota, LlmResult, Provider


class LlmGuard:
    def __init__(
        self,
        provider: Provider,
        max_calls_per_minute: int,
        daily_budget: int,
        tz: ZoneInfo,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.provider = provider
        self.max_calls = max_calls_per_minute
        self.daily_budget = daily_budget
        self.tz = tz
        self.clock = clock
        self._calls: deque[float] = deque()
        self._cooldown_until = 0.0
        self._day: date | None = None
        self._tokens_today = 0
        self._lock = threading.Lock()

    @staticmethod
    def estimate_tokens(messages: list[dict[str, str]], max_tokens: int) -> int:
        return math.ceil(sum(len(m["content"]) for m in messages) / 4) + max_tokens

    def _admit(self, estimate: int) -> None:
        with self._lock:
            now = self.clock()
            if now < self._cooldown_until:
                raise LlmQuota(math.ceil(self._cooldown_until - now))
            today = datetime.now(self.tz).date()
            if today != self._day:
                self._day, self._tokens_today = today, 0
            if self._tokens_today + estimate > 0.9 * self.daily_budget:
                raise LlmQuota(None)
            while self._calls and now - self._calls[0] >= 60:
                self._calls.popleft()
            if len(self._calls) >= self.max_calls:
                raise LlmQuota(math.ceil(60 - (now - self._calls[0])))
            self._calls.append(now)
            self._tokens_today += estimate

    def generate(
        self, messages: list[dict[str, str]], *, max_tokens: int, temperature: float, json_mode: bool, timeout: float
    ) -> LlmResult:
        estimate = self.estimate_tokens(messages, max_tokens)
        self._admit(estimate)
        try:
            result = self.provider.generate(messages, max_tokens=max_tokens, temperature=temperature, json_mode=json_mode, timeout=timeout)
        except LlmQuota as e:
            with self._lock:  # provider 429: degrade everyone until the reset instead of retrying
                self._cooldown_until = self.clock() + (e.retry_after or 60)
            raise
        if result.tokens is not None:
            with self._lock:
                self._tokens_today += result.tokens - estimate
        return result

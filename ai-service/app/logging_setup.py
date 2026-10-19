"""JSON logs with the propagated request id (NFR-014). Never log prompts, tokens or task text at INFO."""

import contextvars
import json
import logging
import time

request_id: contextvars.ContextVar[str] = contextvars.ContextVar("request_id", default="-")


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        data: dict[str, object] = {
            "@timestamp": time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(record.created)) + "Z",
            "log.level": record.levelname,
            "log.logger": record.name,
            "message": record.getMessage(),
            "request_id": request_id.get(),
        }
        for key in ("method", "path", "status", "duration_ms"):
            if hasattr(record, key):
                data[key] = getattr(record, key)
        if record.exc_info:
            data["error.type"] = record.exc_info[0].__name__ if record.exc_info[0] else None
        return json.dumps(data)


def configure(level: str) -> None:
    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(level.upper())
    for noisy in ("httpx", "httpcore", "uvicorn.access"):
        logging.getLogger(noisy).setLevel(logging.WARNING)

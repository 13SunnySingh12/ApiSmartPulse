# ApiSmartPulse

A task manager that tells you what is **late**, who is **overloaded**, and which tasks are **badly
estimated**. REST API (Spring Boot) + internal AI service (FastAPI, scikit-learn, local embeddings,
optional Groq) + React dashboard with a "calm glass, tactile clay" design system.

> **Local only.** This project runs on your machine via Docker Compose. It is not deployed anywhere,
> and the CI pipeline only validates and builds; it never publishes or pushes anything.
> The full product, technical, design and security specification is [`ApiSmartPulse.md`](ApiSmartPulse.md).

## Features

| | |
|---|---|
| Core task management | Projects with keys (`PULSE-42`), roles (ADMIN/MANAGER/MEMBER), members and capacity, tasks with subtasks, labels, Markdown comments, dependencies with cycle detection, optimistic locking, history |
| Public API | Read-only `/api/public/v1` with named API keys (shown once, stored hashed), 100 req/min per key with `X-RateLimit-*` and `Retry-After` |
| Predictions | Duration (GradientBoosting), overdue risk (LogisticRegression), suggested priority (TF-IDF + LogisticRegression), with rule-based fallback until trained. Advisory only |
| Ask your project | Retrieval over task text and comments (pgvector, local `all-MiniLM-L6-v2`), answers with validated citations; honest degraded mode without an LLM |
| Smart helpers | Duplicate warning while typing a new task; "Split this task" suggestions you edit before saving |
| Workload & analytics | Capacity bars, one safe rebalancing suggestion per overloaded member, burndown and velocity charts with table views |
| Dashboard | Responsive React (JavaScript) UI, light/dark/system theme, keyboard-operable board, WCAG 2.2 AA intent |

## Architecture

```
Browser (React SPA) ──JWT──► Spring Boot API :8080 ──JDBC (asp_app)──► PostgreSQL 16 + pgvector
                                   │  X-Internal-Token                       ▲
                                   ▼                                         │ (asp_ai, restricted)
                          FastAPI AI service :8000 (not published) ──────────┘
                                   │ optional, server-side key
                                   ▼
                              Groq API (free plan)
```

Spring Boot owns the schema (Flyway) and every browser-facing endpoint. The AI service is internal,
uses a least-privilege database role, and the app keeps working when it or Groq is down.

## Prerequisites

Docker Desktop / Docker Engine with Compose v2 (6 GB RAM for Docker recommended), Git, and OpenSSL
(Git Bash includes it). For development outside Docker: JDK 17+, Python 3.12, Node.js 24.

## Quick start (Docker)

```bash
bash scripts/make-env.sh > .env      # random local secrets; or copy .env.example and fill every <…>
docker compose up -d --build         # first build downloads images, CPU PyTorch and dependencies
docker compose ps                    # wait for db, backend and ai-service to be "healthy"
docker compose --profile tools run --rm seed   # demo data (idempotent)
```

Open <http://localhost:8081> and log in as `admin@demo.local` (or `priya@demo.local`, a manager)
with the `SEED_DEMO_PASSWORD` from your `.env`. Then in **Admin → Models** press **Train models**
and **Re-index embeddings**. Swagger UI: <http://localhost:8080/swagger-ui>.

If ports 5432/8080/8081 are taken on your machine, set `DB_HOST_PORT`, `BACKEND_HOST_PORT`,
`FRONTEND_HOST_PORT` in `.env` and keep `VITE_API_BASE_URL` / `CORS_ALLOWED_ORIGINS` in sync.

Stop with `docker compose down`; `docker compose down -v` also deletes the demo data, trained models
and the cached embedding model.

**Live AI answers (optional):** set `LLM_PROVIDER=groq` and `GROQ_API_KEY` (free plan, from
<https://console.groq.com/keys>) in `.env`, then `docker compose up -d ai-service`. Without it, Ask
shows matching tasks with a "AI answers are turned off" notice and split suggestions are unavailable.
Gemini is not implemented (optional P2).

## Development

| Task | Backend (`backend/`) | AI service (`ai-service/`) | Frontend (`frontend/`) |
|---|---|---|---|
| Install | `./mvnw -q dependency:go-offline` | `pip install --require-hashes -r requirements-dev.txt` | `npm ci` |
| Run | `./mvnw spring-boot:run` (env from `.env`, DB on `localhost`) | `uvicorn app.main:app --reload` | `npm run dev` |
| Format | `./mvnw spotless:apply` | `ruff format .` | `npx prettier --write .` |
| Lint / types | `./mvnw spotless:check` · `./mvnw compile` | `ruff check .` · `mypy app` | `npm run lint` · `npm run lint:css` |
| Tests | `./mvnw verify` (Testcontainers: needs Docker) | `pytest` (needs a DB from `scripts/ci/apply-migrations.sh`) | `npm test -- --run` |
| Build | `./mvnw -DskipTests package` | `docker compose build ai-service` | `npm run build` |

AI-service tests use a throwaway database: export `PGHOST`, `PGPORT`, `PGUSER=postgres`,
`PGPASSWORD`, `TEST_DB=apismartpulse_test`, run `bash scripts/ci/apply-migrations.sh`, then `pytest`.

**End-to-end** (Playwright, mock LLM, fake embeddings):

```bash
docker compose -f docker-compose.yml -f docker-compose.ci.yml up -d --build --wait
docker compose -f docker-compose.yml -f docker-compose.ci.yml --profile tools run --rm seed python -m scripts.seed --small
cd frontend && npx playwright install chromium
E2E_PASSWORD=<SEED_DEMO_PASSWORD> npx playwright test
```

## CI

`.github/workflows/ci.yml` runs on pushes, PRs to `main`, manual dispatch and nightly: a guard
(fails on any deploy/publish/registry step or live LLM use), backend format + tests + OpenAPI
contract diff, AI service ruff/mypy/pytest against Postgres, frontend Prettier/ESLint/Stylelint/
Vitest/build + bundle secret check, Trivy/pip-audit/npm audit, local Docker image builds (never
pushed) and, for PRs/nightly/manual runs, Playwright E2E against the Compose stack. No secrets are
needed: CI generates throwaway ones per run.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `backend` unhealthy, role `asp_app` does not exist | The DB volume predates the init script: `docker compose down -v`, then up again |
| "AI is warming up" for minutes | The embedding model (~90 MB) downloads on first start; see `docker compose logs ai-service` |
| `ai-service` exits with 137 | Give Docker more memory (6 GB+) |
| Browser CORS error | Add the page origin to `CORS_ALLOWED_ORIGINS` |
| Logged out after reload in dev | Use `localhost` for both the app and the API (not `127.0.0.1`) |
| Port already in use | Change the `*_HOST_PORT` variables in `.env` |

## Known limitations

- Predictions trained on the synthetic seed data are demonstrations, not real-world accuracy.
- Without `GROQ_API_KEY`, Ask returns sources only and split suggestions are off.
- Access tokens stay valid up to 15 minutes after a role change; rate limits reset on restart.
- Local HTTP only. Exposing it beyond localhost would need TLS and a new security review.
- No real-time updates: other people's changes appear on refresh or window focus.

## Licences and notices

No licence file has been chosen for the code yet (project owner's decision). Bundled `backend/src/main/resources/common-passwords.txt` is the
"xato-net-10-million-passwords-10000" list from [SecLists](https://github.com/danielmiessler/SecLists)
(MIT). Fonts: Plus Jakarta Sans and JetBrains Mono (SIL OFL 1.1) via `@fontsource`. Embedding model
`sentence-transformers/all-MiniLM-L6-v2` (Apache-2.0), downloaded at runtime.

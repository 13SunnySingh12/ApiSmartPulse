# Implementation log

Progress tracker and requirement → test traceability for [`ApiSmartPulse.md`](../ApiSmartPulse.md).
Only results that were actually executed are marked ✓. Legend: `[✓]` done and verified ·
`[→]` in progress · `[ ]` pending · `[!]` manual action needed · `[✗]` blocked.

## Progress (2026-10-19)

| Status | Item | Evidence |
|---|---|---|
| [✓] | Repo foundation: `.gitignore`, `.gitattributes`, `.env.example`, `scripts/make-env.sh`, roles init | Local `.env` generated; Compose DB created `asp_owner/asp_app/asp_ai` |
| [✓] | Flyway V1–V3 (schema, least-privilege grants, HNSW) | Applied by Spring on Postgres 16 + pgvector; grants asserted by `SecurityIT` |
| [✓] | Backend: auth, users/admin, projects/members, tasks, subtasks, labels, comments, dependencies, API keys + public API, rate limits, analytics, AI client, outbox, prediction jobs | `./mvnw verify`: 74 tests, all passing (Testcontainers + WireMock) |
| [✓] | AI service: heuristics, sklearn training/registry, fake + local embedder, pgvector store, RAG with citation validation, auto-split, Groq/mock/none providers, LLM guard | `pytest`: 38 passing; `ruff`, `mypy --strict`: clean |
| [✓] | Seed (`scripts/seed.py`): 9 users, 3 projects, 600 tasks with events, subtasks, comments, dependencies | Run against local Compose DB |
| [✓] | Frontend: design system (tokens, glass/clay materials, UI kit) and all 14 pages | `npm run build` ok; Vitest 19 passing; ESLint 0 errors; Stylelint clean; manual browser review of login, projects, board, task drawer, workload, analytics at 1024 px (dark) |
| [✓] | Docker images (backend, AI with CPU torch, frontend nginx non-root) | Built locally; nothing pushed |
| [✓] | CI workflow, Dependabot, guards (no-deploy, no-live-LLM, bundle secrets) | Guards run locally; no-deploy guard fails on an injected `docker push` |
| [✓] | Playwright E2E (QA-E2E-01..08) + axe scans (QA-A11Y-01) | 10/10 passing locally against the CI Compose stack (mock LLM, fake embeddings); zero serious/critical axe issues on 10 pages, light and dark |
| [✓] | CI on GitHub | Run 39239981142 (manual dispatch, `main`): guard, backend, AI service, frontend, security (Trivy/pip-audit/npm audit), Docker builds and E2E incl. log secret scan all green |
| [ ] | QA-RAG-EVAL (needs a real Groq key, manual) | — |
| [ ] | QA-PERF-01 k6 smoke (manual, optional) | — |
| [!] | Optional: `GROQ_API_KEY` for live answers | Owner action; app works without it |

## Traceability (requirements → automated tests)

| Requirement | Tests |
|---|---|
| FR-001–003, SEC-001–005 | `AuthIT` (QA-AUTH-01..07, QA-SEC-01/02/05/06), `client.test.js` (QA-FE-03), E2E-01 |
| FR-004, FR-005 | `AuthIT.fr004_*`, `AuthIT.fr005_*`, `AuthIT.qaSec16_*` |
| FR-010–013, BR-002, BR-013 | `ProjectIT` (QA-SEC-03/04, QA-AUTHZ-03, QA-TASK-04), E2E-01 |
| FR-020–025, BR-003–005 | `TaskIT` (QA-TASK-01..07, QA-AUTHZ-02, QA-SEC-07), `features.test.jsx` (QA-FE-05, QA-FE-09), E2E-02/03 |
| FR-026–028 | `TaskIT.qaSub01_*`, `TaskIT.fr027_*`, `TaskIT.fr028_*`, `ui.test.jsx` (QA-SEC-08) |
| FR-029, BR-006, BR-007 | `DependencyIT` (QA-DEP-01..04), E2E-03 |
| FR-040–044, BR-011, SEC-013 | `ApiKeyIT` (QA-KEY-01..04, QA-RL-01..03, QA-SEC-11), `features.test.jsx` (QA-FE-07), E2E-07 |
| FR-050–052, TR-007, BR-010 | `HeuristicParityTest` + `test_units.py` (shared fixture, QA-AI-02), `AiIT` (QA-AI-01/03), `test_training.py` (QA-AI-04..06) |
| FR-053, BR-012 | `TaskIT.fr053_*`, `AiIT.fr064_*` (nothing saved) |
| FR-054 | `PredictionService.rescoreAll` (scheduled); exercised indirectly by `AiIT.retriesExhausted…` |
| FR-060–062, SEC-041/042 | `test_api.py` (QA-LLM-01..04, QA-RAG-01, QA-SEC-09/10, QA-EMB-01/02), `AiIT` (degraded passthrough, outbox), `features.test.jsx` (QA-FE-06), E2E-05 |
| FR-063, FR-064 | `test_api.py` (QA-DUP-01/02, QA-LLM-05/06), `AiIT.fr064_*`, E2E-02/04 |
| FR-070–073, BR-008, BR-009 | `AnalyticsIT` (QA-AN-01..05), `RebalancerTest`, E2E-06 |
| FR-080, FR-081, DS-* | `useTheme.test.jsx` (QA-FE-08), `ui.test.jsx` (QA-FE-01/04, component axe), Stylelint token rule, `a11y.spec.js` (QA-A11Y-01) |
| FR-082 | `OpenApiSnapshotIT` (contract + Swagger UI) |
| FR-083 | Seed run (manual evidence above) |
| SEC-010/014/015/021, §8.11 | `SecurityIT` (headers, CORS QA-SEC-14, 413, grants), `test_api.py` (QA-SEC-13) |
| SEC-040, QA-SEC-15 | `scripts/ci/bundle-secret-check.sh` |
| NFR-013 | `scripts/ci/no-live-llm-guard.sh` |
| §11 no deployment | `scripts/ci/no-deploy-guard.sh` |
| NFR-006, QA-SEC-12 | `scripts/ci/log-secret-scan.sh` on Compose logs after the E2E run |

## Dependency security (2026-10-19)

Trivy (HIGH/CRITICAL, fixed only) flagged Boot 4.1.1's managed Tomcat 11.0.24 and Jackson 2.21.5 /
3.1.5. `backend/pom.xml` overrides `tomcat.version` 11.0.27, `jackson-bom.version` 3.1.8 and
`jackson-2-bom.version` 2.21.8 until a Boot patch release includes them.

## Fixes found by E2E (2026-10-19)

- Task drawer "More actions" menu sat under the drawer scrim, so mouse clicks were swallowed: menus now stack above dialogs.
- Board moves were announced twice (board live region + toast live region): board region removed.
- Account button's accessible name didn't include its visible text (WCAG 2.5.3): the visible name is now the label, kept visually hidden on mobile.

## Impeccable audit (2026-10-19, report only)

Detector: 0 findings in `frontend/src`. Axe: 0 serious/critical on 10 pages in both themes.
Remaining P2/P3 notes, not fixed:
- P2 Responsive: small icon buttons (card drag handle and "More actions", toast dismiss) are 32 px.
  That passes WCAG 2.2 AA target size (24 px) but is below the 44 px touch guideline. Neighbouring
  targets make a safe hit-area expansion a design decision.
- P3 Motion: `prefers-reduced-motion` shortens every transition to 1 ms globally. State changes stay
  visible, but there is no tailored alternative per component.

## Known gaps

- QA-PERF-01 and QA-RAG-EVAL are manual by design and have not been run.
- Gemini adapter (P2) not implemented.
- Seed realism: every currently open task is created within the last 30 days, so a 30-day burndown
  starts at 0 h and rises. The chart is correct for that data; spreading open-task creation dates in
  `scripts/seed.py` would give a more typical demo curve.

# 05 - Test strategy and results (Phase: Test)

Status date: 2026-10-14. Everything below was run on a developer machine with an in-memory H2 database in Oracle mode.
**Nothing has been run against real Oracle, Kafka, Debezium or Postgres** (Docker is not installed). See section 5.

## 1. Test pyramid

| Layer | Tool | Count | What it proves |
|---|---|---|---|
| Unit | JUnit (`LegacyValueParserTest`, `RunStateTest`) | 8 | Value parsing, state-machine rules |
| Service / integration | JUnit + SpringBootTest + MockMvc + H2 | 31 | Loader, quarantine, reconciliation, mapping gate, shadow diff, cutover pre-checks, rollback drill, metrics, AI-client retry, authentication and roles, tenant isolation, concurrent transitions, API contract, data profiling |
| AI service | pytest | 27 | Mapping guardrails, agent tools, evals, LLM HTTP round trip, endpoint errors |
| Agent evals | `python -m app.agent.evals` | 16 scenarios | Root-cause accuracy 16/16, includes "UNKNOWN" and "NO_ISSUE" negatives |
| Console | Vitest + Testing Library | 25 | Rendering, login gate, approval gate, error states, polling, diagnose panel, stepper |
| End to end | Playwright (real browser) | 3 | Wrong password refused, create run, advance, pre-check failure, ask the agent, over 3 real services |

Total automated checks: 39 Java + 27 Python + 25 console + 3 e2e = **94**, plus the 16-scenario eval.

## 2. Test types and where they live

- **Contract**: `ApiContractTest` fails if the endpoints in `docs/contracts/orchestrator-openapi.yaml` and the real controllers differ (it found 6 undocumented endpoints on its first run, now documented). Request and response body schemas are not checked yet (gap G2, partly closed).
- **Data quality / reconciliation**: `BulkLoaderTest`, `LoadMappingTest`. Source count must equal loaded + quarantined for every entity and the payment sums must tie out.
- **Failure and chaos (what is covered)**:
  - AI service down: orchestrator returns 502 and the run is untouched (`MappingApiTest`).
  - AI service flaky: client retries 5xx with backoff, never retries 4xx (`HttpAiClientRetryTest`).
  - LLM endpoint down or returning 500: agent falls back to rules (`test_llm_http.py`).
  - Concurrent transition: guarded by `UPDATE ... WHERE STATE = ?` returning 409. `ConcurrentTransitionTest` races 6 callers on one run: exactly 1 wins, 5 get 409 (gap G7 closed, on H2).
  - Mapping points at a missing column: load fails fast with 409 (`LoadMappingTest`).
- **DR / rollback drill**: `CutoverDrillTest` and `docs/04-cutover-runbook.md`. Rollback only from FREEZE, FINAL_SYNC, SWITCHED, MONITOR.
- **Security regression**: exceptions API never exposes the raw legacy row (`MetricsTest`), agent has no write tools (`test_agent.py`).
- **Observability**: counters and timer asserted in `MetricsTest`.

## 3. Results measured

| Check | Result |
|---|---|
| Dataset | 5,080 borrowers, 6,000 loans, 60,000 payments, deliberately dirty |
| Loaded | 5,080 borrowers, 5,948 loans, 56,334 payments |
| Quarantined | 3,666 payments (plus orphans), none dropped silently |
| Payment tie-out | source 43,453,899.62 = loaded 42,242,209.31 + quarantined |
| Initial load, H2 | about 5 s batch mode, 5.3 s row mode (no network cost on H2, so no real gain visible) |
| Shadow diff, 66k rows | about 1.4 s, clean |
| Agent eval | 16/16 |

## 4. How to run everything

```
cd orchestrator-service ; mvn -q test        # read target/surefire-reports/*.txt for counts
cd ai-service           ; python -m pytest -q ; python -m app.agent.evals
cd console              ; npm test ; npm run lint ; npm run build ; npm run e2e
```
`npm run e2e` starts all three services itself; ports 8081, 8000 and 5173 must be free. Locally it uses the installed Edge; in CI (`CI=true`) it uses Playwright's Chromium.

## 5. Known gaps (be honest with the customer)

| # | Gap | Risk | Plan |
|---|---|---|---|
| G1 | Never run on real Oracle | Batch generated keys, `SYSTIMESTAMP`, `NVL` behaviour may differ | First Docker run: `docker compose up`, rerun suites against Oracle XE; fallback `migration.load.batch-insert=false` |
| G2 | Only endpoint paths and methods are contract-tested, not body schemas | Body drift between services | Validate bodies against the OpenAPI schemas |
| G3 | No load test at production volume (no k6 here) | Unknown behaviour at tens of millions of rows | Run chunked-load test on a real DB; measure rows/s and lock time |
| G4 | CDC, Kafka, DLQ not built (blocked by no Docker) | Final-sync correctness untested | Build with Debezium once Docker or Rancher Desktop exists |
| G5 | No real LLM tried | Narrative and mapping quality unmeasured | Needs Ollama model or hosted endpoint; the HTTP path itself is tested |
| G6 | ~~CI workflow never ran on GitHub~~ Closed: CI (unit tests and e2e) is green on GitHub | None | Keep watching runs |
| G7 | ~~No concurrency test~~ Closed: `ConcurrentTransitionTest` | None on H2; repeat on Oracle | Re-run on real Oracle (G1) |

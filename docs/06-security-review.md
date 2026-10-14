# 06 - Security review (Phase: Test)

Scope: orchestrator-service, ai-service, console, as of 2026-10-14. This is a self-review by the builder, not an independent audit.

## 1. Summary

The design choices that matter most are sound (read-only agent, human approval, PII-free LLM prompts, parameterised SQL, no raw rows in the API). **Update:** the biggest gap, no authentication, is now closed in code (S1, S2 below): Basic auth with roles, and the approver is taken from the login. The finding rows are kept as the original record.

## 2. Controls in place (with proof)

| Area | Control | Proof |
|---|---|---|
| Agent safety | Only 6 allow-listed GET tools; no write tool exists; every proposed action has `requires_approval=true` | `test_agent.py::test_tools_are_get_only_and_allowlisted` |
| PII to LLM | LLM sees only the structured diagnosis, never rows; mapping prompts use masked samples | `test_llm_http.py`, `masking.py` |
| LLM failure | Falls back to deterministic rules, never blocks the operator | `test_llm_http.py` |
| API data exposure | `/exceptions` returns ids and reason codes only, never `RAW_PAYLOAD` | `MetricsTest::exceptionsApiNeverExposesTheRawLegacyRow` |
| SQL injection | All SQL uses bound parameters; table and column names are constants | code review of `RunService`, `BulkLoader`, `ShadowVerifier` |
| Human gates | SWITCHED needs `approvedBy` (403); mappings need a named reviewer; INITIAL_LOAD blocked while mappings pending; READY blocked on shadow drift | `RunApiTest`, `MappingApiTest`, `CutoverDrillTest` |
| Audit | Every transition and decision writes `MIG_AUDIT` with actor | `CutoverDrillTest::rollbackDrillLeavesAFullAuditTrail` |
| Secrets | DB credentials and LLM key come from environment variables, none in source | `application.yml`, `llm.py` |
| Input limits | Symptom capped at 500 chars (422); tenant id required | `test_agent.py`, `RunApiTest` |
| Tenant scoping | Every business table carries `TENANT_ID`; queries filter on it | schema + `ReconciliationService` |

## 3. Findings

| # | Severity | Finding | Fix |
|---|---|---|---|
| S1 | High, **fixed in code** | No authn/z on any endpoint (original finding). Now: stateless Basic auth, users from `MIGRATION_USERS`, roles OPERATOR/REVIEWER/APPROVER/READER, actor taken from the login, SWITCHED needs APPROVER. Production should still move to OIDC/JWT | Add Spring Security with OIDC/JWT; take the actor from the token, require roles (operator, reviewer, approver); never accept the name from the request body |
| S2 | High, **fixed in code** | DB password default removed (startup fails without `TARGET_DB_USER`/`TARGET_DB_PASSWORD`); the agent calls the orchestrator with a READER service account (`ORCHESTRATOR_USER`/`ORCHESTRATOR_PASSWORD`) | Remove defaults so startup fails without secrets; service-to-service token for the agent |
| S3 | Medium | No cross-tenant protection test: tenant isolation relies on every query remembering the `TENANT_ID` filter | Add a test that seeds two tenants and proves one never sees the other; consider Oracle VPD |
| S4 | Medium | `MIG_EXCEPTION.RAW_PAYLOAD` stores full legacy rows (PII) unencrypted | Encrypt or redact at rest, set retention, restrict DB access |
| S5 | Medium | Actuator `metrics` is exposed without auth | Expose only to the monitoring network or secure it |
| S6 | Low | No rate limiting; `/ai/agent/diagnose` triggers several orchestrator reads per call | Rate limit at the gateway |
| S7 | Low | Prompt injection through the free-text symptom is possible. Impact is limited because the symptom only selects among fixed rules and the LLM cannot change the cause or call tools | Keep it that way; if the LLM ever gets tools, re-review |
| S8 | Low | CORS and TLS not configured (dev proxy only) | Terminate TLS at the ingress; same-origin deployment of the console |
| S9 | Info | Dependency scanning not set up | Add Dependabot and `npm audit` / `pip-audit` / OWASP dependency-check to CI |

## 4. Verdict

Fine for a demo on synthetic data. **Not approved for real customer data** until S3 and S4 are decided and Basic auth is replaced by OIDC/JWT (S1 and S2 are closed in code).

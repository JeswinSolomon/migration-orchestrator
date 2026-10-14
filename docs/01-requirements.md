# Requirements: Zero-Downtime Migration Orchestrator (Phase: Discover)

Roadmap: FDE-Roadmap.xlsx, rows 1-3. Status: DRAFT for your review.

## 1. Customer scenario (fictional)
**Northwind Lending** is a mid-size lender. It runs a 15-year-old legacy loan/billing system
(flat files plus a denormalized DB) serving about 400k active loans and 6M historical payments.
It must move to a new Oracle-based platform without stopping payment processing.

### Pain points (what the customer says)
- "We can't be down during the month-end billing run."
- "Our legacy data is inconsistent: duplicate borrowers, mixed date formats, null balances."
- "Last migration attempt lost payments and we found out 3 weeks later."
- "We need to be able to go back if something looks wrong."

## 2. Goals
| ID | Goal | Success metric |
|----|------|----------------|
| G1 | Zero downtime cutover | Write outage < 60 s during cutover |
| G2 | Complete, correct data | 100% of loans/payments reconciled; balance variance = 0.00 |
| G3 | Safe rollback | Rollback drill completes < 15 min with no data loss |
| G4 | Fast failure diagnosis | AI agent gives a correct root-cause hypothesis for >= 80% of seeded failures |
| G5 | Transparency | Operators see live progress, diffs and errors in a console |

## 3. Functional requirements
- FR1 Profile legacy data (null rates, duplicates, format anomalies) before migration.
- FR2 Configurable field mapping and transformation rules per tenant/customer, with AI-suggested mappings reviewed by a human.
- FR3 Initial bulk load in batches, restartable and idempotent.
- FR4 Continuous change sync (CDC) from legacy to new platform via Kafka, ordered per loan.
- FR5 Shadow-read comparison: same query against both systems, diff reported.
- FR6 Reconciliation reports (counts, sums, per-loan balances) with exception queue.
- FR7 Cutover orchestration with pre-checks, freeze, final sync, switch, and post-checks.
- FR8 Rollback to legacy at any point before the decommission stage.
- FR9 Retry, dead-letter queue and replay of failed events.
- FR10 AI diagnosis agent: read-only access to logs, metrics and DB, produces a root-cause report. Any proposed action needs human approval.
- FR11 React console: migration progress, diffs, DLQ, agent chat, approvals.
- FR12 Full audit trail of who/what changed what.

## 4. Non-functional requirements
- Throughput: initial load >= 5k records/s; CDC lag p95 < 5 s.
- Reliability: at-least-once delivery with idempotent writes, so effectively exactly-once results.
- Security: role-based access, secrets outside code, PII masking in logs and in agent prompts.
- Observability: logs, metrics, traces and alerts for every pipeline stage.
- Multi-tenant ready: schema and config separated per customer.

## 5. Out of scope (v1)
Decommissioning the legacy system, real-time UI for end borrowers, non-Oracle targets.

## 6. Stakeholders
| Role | Concern |
|------|---------|
| COO | No downtime, no lost money |
| Finance/Billing lead | Balances must match to the cent |
| Legacy DBA | Load on legacy DB must be limited |
| Ops/Support | Clear errors and runbooks |
| Compliance | Audit trail, PII handling |

## 7. Top risks (initial)
| Risk | Mitigation |
|------|------------|
| Dirty legacy data breaks mapping | Profiling first, quarantine bad rows |
| CDC load slows legacy DB | Throttling, off-peak initial load |
| Silent data drift after cutover | Continuous shadow reads + reconciliation |
| AI agent gives wrong or unsafe advice | Read-only tools, evals, approval gate |
| Rollback impossible after writes on new system | Reverse sync until decommission |

## 8. Decisions (resolved)
1. Legacy system = PostgreSQL + CSV flat files. A different engine from the Oracle target forces real type/date/encoding mapping, and Debezium CDC works well with it.
2. LLM = provider-agnostic interface in FastAPI; OpenAI-compatible API by default, optional local Ollama. Avoids lock-in and lets you build evals that compare models.
3. Everything runs locally in Docker Compose (PostgreSQL, Oracle XE, Kafka, Debezium, Spring Boot, FastAPI, React).

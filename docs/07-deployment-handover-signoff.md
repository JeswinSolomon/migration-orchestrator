# 07 - Deployment, handover and signoff (Phase: Signoff)

Customer (fictional): Northwind Lending. Status date: 2026-10-14.

## 1. Deployment guide

### 1.1 Local demo (what works today, no Docker)
Needs Java 17, Python 3.8+, Node 22.

```
# terminal 1 - orchestrator on in-memory H2 (data resets on restart)
cd orchestrator-service ; mvn -q -DskipTests package
$env:TARGET_DB_URL="jdbc:h2:mem:demo;MODE=Oracle;DB_CLOSE_DELAY=-1"; $env:TARGET_DB_USER="sa"; $env:TARGET_DB_PASSWORD=""
$env:MIGRATION_LEGACY_DIR="..\legacy-data\data"
java -jar target\orchestrator-service-0.1.0-SNAPSHOT.jar          # :8081

# terminal 2 - AI service (optional LLM via LLM_BASE_URL + LLM_MODEL [+ LLM_API_KEY])
cd ai-service ; python -m uvicorn app.main:app --port 8000

# terminal 3 - console
cd console ; npm install ; npm run dev -- --port 5173
```
Open http://localhost:5173. Generate data first with the generator in `legacy-data` if the CSVs are missing.

### 1.2 Target deployment (designed, **not yet exercised**)
`docker-compose.yml` defines Oracle, Postgres, Kafka, Debezium and the three services. It has never been run (no Docker on the build machine). First task on a Docker-capable machine: `docker compose up`, then rerun all suites against Oracle (test report gap G1).

### 1.3 Configuration

| Variable | Used by | Default | Note |
|---|---|---|---|
| `TARGET_DB_URL`, `TARGET_DB_USER`, `TARGET_DB_PASSWORD` | orchestrator | none for user/password; startup fails without them | Required in every environment (security S2) |
| `MIGRATION_USERS` | orchestrator | none; startup fails without it | `name:pass:ROLE\|ROLE;...` with roles OPERATOR, REVIEWER, APPROVER, READER (security S1) |
| `ORCHESTRATOR_USER`, `ORCHESTRATOR_PASSWORD` | ai-service | none | A READER account the agent uses to read runs |
| `MIGRATION_LEGACY_DIR` | orchestrator | `../legacy-data/data` | CSV exports, read as ISO-8859-1 |
| `AI_SERVICE_URL` | orchestrator | `http://localhost:8000` | |
| `migration.ai-max-attempts`, `migration.ai-backoff-ms` | orchestrator | 3, 200 | Retry on 5xx and network errors only |
| `migration.load.batch-insert` | orchestrator | true | Set false if Oracle does not return batch keys in order |
| `ORCHESTRATOR_URL` | ai-service agent | `http://localhost:8081` | |
| `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY` | ai-service | unset = rules only | Any OpenAI-compatible endpoint, incl. Ollama |

### 1.4 Rollback of the deployment itself
Services are stateless apart from the database. Redeploy the previous jar or image. Schema changes are Liquibase changesets; never edit an applied changeset, add a new one.

## 2. Operations handbook

### 2.1 Health and metrics
- `GET :8081/actuator/health`, `GET :8000/health`.
- `GET :8081/actuator/metrics/<name>`:

| Metric | Tags | Alert when |
|---|---|---|
| `migration.rows` | entity, outcome=loaded | Flat while a load should be running |
| `migration.rows.quarantined` | entity, reason | Any reason suddenly dominating (e.g. ORPHAN_LOAN) |
| `migration.precheck.failed` | check = mappings / shadow / reconciliation | More than 2 in an hour for one run |
| `migration.transitions` | to | Unexpected `ROLLED_BACK` |
| `migration.load.duration` | | Much slower than the rehearsal |

### 2.2 Common situations (the agent proposes these; a person does them)

| Symptom | Likely cause | Action |
|---|---|---|
| "Pre-check failed: N mapping(s) still need review" | MAPPING_PENDING | Reviewer approves or rejects each mapping in the Mapping review tab |
| "Pre-check failed: reconciliation is not balanced" before load | LOAD_NOT_RUN | Press Run initial load |
| Reconciliation unbalanced after load | PARTIAL_LOAD / ORPHAN_CASCADE | Check quarantined reasons; fix the source or agree the rule with the customer |
| "Pre-check failed: shadow read found N mismatch(es)" | SHADOW_DRIFT | Investigate rows changed after load; reload or fix, then retry |
| "SWITCHED requires approvedBy" | APPROVAL_MISSING | The business approver supplies their name |
| 502 "AI service unavailable" | AI_SERVICE_DOWN | Restart ai-service; the run is unaffected, mappings can be decided manually later |
| Many NEGATIVE_AMOUNT_UNCONFIRMED | NEGATIVE_AMOUNT_BACKLOG | Ask the customer: refund, reversal or data error |

### 2.3 Cutover and rollback
Follow `04-cutover-runbook.md`. Rollback is allowed only from FREEZE, FINAL_SYNC, SWITCHED, MONITOR. ROLLED_BACK is final: create a new run to try again.

## 3. Demo script (15 minutes)

1. Show both health cards UP. Create run for tenant `northwind`. (stepper on PLANNED)
2. Advance to PROFILED. Open Mapping review, suggest mappings, approve each as a named reviewer. *Point out: AI suggests, human decides.*
3. Advance to INITIAL_LOAD, press Run initial load. Show reconciliation, the quarantined rows and the payment tie-out.
4. Try Advance to CDC_SYNC before loading on a second run to show the failed pre-check, then press **Diagnose the last error**. Read the cause, evidence and the "not executed" actions.
5. Show the abstain case: type "the moon is full". *Point out: it says it is not sure.*
6. Open `/actuator/metrics/migration.rows.quarantined`.
7. Close with the known gaps (section 5) and the plan.

## 4. Customer status update (for the COO, plain language)

> Subject: Northwind loan migration - status after rehearsal
>
> In the rehearsal on a copy of your data, 100% of records were accounted for: every borrower, loan and payment either moved across or was set aside with a reason. Nothing was lost silently. About 6% of payments were set aside because they refer to loans that do not exist or have negative amounts; these need a business decision from your team (are they refunds, reversals or errors?).
> The move itself is gated: a named person on your side must approve the switch, and we can roll back until the final minutes.
> What is not finished: we have tested on a stand-in database, not yet on your Oracle environment, and the live-sync step is still to be built. Our next step is a rehearsal on Oracle. We will not schedule the cutover date until that rehearsal passes.
> Decision needed from you: who is your named approver, and who owns the negative-amount decision?

(Delay or failure template: what happened in one sentence, what is safe, what we are doing, when you will hear next, what we need from you.)

## 5. Known gaps and risks at handover

See test report section 5 (G1-G7) and security review findings S1-S9. The blockers for production: **G1 untested on Oracle, G4 CDC/final-sync not built**, and S1 is only partly closed (Basic auth in place, OIDC/JWT still to do).

## 6. Signoff checklist

| # | Item | Evidence | Status |
|---|---|---|---|
| 1 | Requirements agreed | `01-requirements.md` | Done |
| 2 | Architecture and ADRs | `03-architecture.md` | Done |
| 3 | Automated tests pass | `05-test-strategy-and-results.md` (75 checks + 16 evals) | Done on H2 |
| 4 | Rollback drill rehearsed | `CutoverDrillTest`, `04-cutover-runbook.md` | Done on H2 |
| 5 | Data reconciles | 66k-row run, sums tie out | Done on H2 |
| 6 | Security review | `06-security-review.md` | Done, **not approved for real data** |
| 7 | Rehearsal on real Oracle | | **Open (G1)** |
| 8 | CDC and final sync | | **Open (G4)** |
| 9 | Authentication and roles | | **Done for demo** (Basic auth, 4 roles; OIDC/JWT for production) |
| 10 | Customer approver and negative-amount owner named | | **Open** |
| 11 | CI green on GitHub | | **Open (G6)** |

Signoff: demo-ready / **not production-ready**. Customer: ____________ Date: ______  Engineer: ____________ Date: ______

# Migration Orchestrator

Data-migration orchestrator for a fictional lender (Northwind Lending), with an AI incident-diagnosis agent.

| Component | Stack | Role |
|---|---|---|
| `orchestrator-service` | Spring Boot, H2 (Oracle mode) | Runs, state machine, loader, mappings, shadow verification, auth, metrics |
| `ai-service` | FastAPI, Python | Mapping suggestions and a read-only diagnosis agent (`POST /agent/diagnose`) |
| `console` | React, TypeScript, Vite | Operator UI: login, runs, mapping review, diagnosis panel |
| `docs` | Markdown, Excel | Design, test strategy, security review, handover, `FDE-Roadmap.xlsx` |

## Run locally

```powershell
# Orchestrator (:8081) - MIGRATION_USERS format: name:pass:ROLE|ROLE;...
$env:MIGRATION_USERS = "ops:changeme:OPERATOR|APPROVER|REVIEWER;agent:changeme2:READER"
$env:TARGET_DB_USER = "sa"; $env:TARGET_DB_PASSWORD = "<password>"
cd orchestrator-service; mvn spring-boot:run

# AI service (:8000)
$env:ORCHESTRATOR_USER = "agent"; $env:ORCHESTRATOR_PASSWORD = "changeme2"
cd ai-service; pip install -r requirements.txt; uvicorn app.main:app --port 8000

# Console (:5173)
cd console; npm install; npm run dev
```

The orchestrator refuses to start without `MIGRATION_USERS`; there are no default credentials.

## Roles

OPERATOR (create, load, transition), REVIEWER (edit mappings), APPROVER (transition to SWITCHED), READER (read-only).

## Tests

- Java: `mvn test` in `orchestrator-service`
- Python: `pytest` in `ai-service`
- Console: `npm test` in `console`
- E2E: `npx playwright test` in `console`

## Known gaps

Oracle, Kafka and Debezium are untested (no Docker in the dev environment). No real LLM has been tried. See `docs/05-test-strategy-and-results.md` and `docs/07-deployment-handover-signoff.md`.

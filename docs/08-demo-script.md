# Demo script (about 10 minutes)

Audience: a non-technical lender COO plus one engineer. Data is synthetic (Northwind Lending).

## Setup (before the call)

1. Start the orchestrator (:8081), ai-service (:8000) and console (:5173) as in the README.
2. Use `MIGRATION_USERS` with an operator/approver user and a READER `agent` user.
3. Open http://localhost:5173.

## Flow

| Step | Do | Say |
|---|---|---|
| 1. Health | Point at the two health cards | "Both services are up. This is the first thing we check each morning." |
| 2. Access | Sign in with a wrong password, then the right one | "Nobody sees data without signing in. Your name on an approval comes from your login, not a typed field." |
| 3. Create | Create a run | "A run is one migration attempt for one tenant. Every step is recorded." |
| 4. Advance | Move the run through the stepper | "The state machine only allows valid steps. Skipping ahead is refused with a pre-check message." |
| 5. Mapping review | Open Mapping review, accept or edit a suggestion | "The AI suggests field mappings. A person reviews them. Nothing is applied automatically." |
| 6. Approval gate | Try to switch as a non-approver, then as the approver | "Cut-over needs the approver role. This is the control your auditors ask for." |
| 7. Diagnose | On a run, ask the agent about a symptom | "The agent reads run data and explains the likely cause. It cannot change anything." |
| 8. Unknown symptom | Ask about something unrelated | "When it does not know, it says so rather than guessing." |

## If something fails live

- Console shows "Cannot reach": check the health cards, restart the orchestrator.
- Diagnosis looks generic: no real LLM is configured, so the rule-based path is answering. Say so plainly.
- Use the status-update template in `docs/` for the customer message.

## Be honest about

Oracle, Kafka and CDC are not exercised yet, and no real LLM was used. See `docs/05` section 5 and `docs/07`.

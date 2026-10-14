# Cleansing & Mapping Plan (derived from 02-data-profile.md)

Rule of thumb: never silently fix money data. Fix what is unambiguous, quarantine what is not, and report both.

| # | Finding | Count | Decision | Action | Owner to confirm |
|---|---------|-------|----------|--------|------------------|
| 1 | 3 date formats (dob, start_date, paid_on) | all tables | Auto-fix | Parse all 3 formats to ISO `DATE`. Beware `dd-Mon-yy` century (yy 50-99 -> 19xx, 00-49 -> 20xx) | Engineering |
| 2 | Empty dates | 175 / 111 / 1,210 | Keep NULL | Load as NULL; flag payments with NULL date as exceptions (cannot post to the ledger) | Billing lead |
| 3 | `rate_pct` is `5.1%` vs numeric | 1,461 | Auto-fix | Strip `%`, store `NUMBER(5,2)` | Engineering |
| 4 | Status codes A/ACTIVE, C/CLOSED, D/DEFAULT | all loans | Auto-fix | Map to one enum: ACTIVE, CLOSED, DEFAULT | Engineering |
| 5 | Orphan loans (borrower not found) | 52 | Quarantine | Do NOT load; exception queue for business review | Billing lead |
| 6 | Missing loan balance | 179 | Recompute + flag | Derive from principal minus payments; flag for finance check | Finance |
| 7 | Negative payment amounts | 1,998 | Ask customer | Likely refunds/reversals. Load with `type=REFUND` only after customer confirms | Finance |
| 8 | Duplicate borrowers | 80 | Review | Fuzzy match on name + dob; merge only after approval, keep id cross-reference | Ops |
| 9 | Missing email / phone | 346 / 1,318 | Allow NULL | Phone formats normalized to digits only | Engineering |
| 10 | ALL-CAPS names | 50 | Auto-fix | Title-case | Engineering |
| 11 | Encoding is latin-1 | all files | Auto-fix | Convert to UTF-8 on read | Engineering |

## Target (Oracle) tables
`BORROWER`, `LOAN`, `PAYMENT`, plus control tables `MIG_RUN`, `MIG_BATCH`, `MIG_EXCEPTION`, `MIG_ID_XREF` (legacy id -> new id).

## Reconciliation checks (used by the orchestrator later)
- row count per table = loaded + quarantined
- SUM(payments) legacy = SUM(payments) target (+ quarantined)
- per-loan balance equals within 0.00
- every quarantined row has a reason code

## Questions that must go to the customer (note for the sign-off pack)
1. Are negative payments refunds?
2. Which duplicate borrower wins on merge?
3. Can orphan loans be dropped or must they be repaired?

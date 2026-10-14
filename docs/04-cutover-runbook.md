# Cutover runbook and rollback drill

Scope: Northwind Lending, legacy PostgreSQL/CSV to Oracle. Every step below maps to a state in the run state machine; the orchestrator refuses to move on if a pre-check fails.

## Roles
| Role | Who | Can do |
|---|---|---|
| Operator | Migration engineer (FDE) | Drives transitions, runs checks |
| Approver | Northwind business owner | Names themselves in `approvedBy` for SWITCHED |
| Rollback authority | Same approver, or on-call lead | Declares rollback |

## Stages and gates
| # | State | What happens | Gate (automated unless noted) |
|---|---|---|---|
| 1 | PROFILED | Data profile reviewed | Operator confirms profile in docs |
| 2 | INITIAL_LOAD | Mappings reviewed, bulk load | No PENDING mappings; approved mappings valid for the file |
| 3 | CDC_SYNC | Change feed keeps target current | Reconciliation balanced |
| 4 | SHADOW_VERIFY | Compare values legacy vs target | Review `GET /shadow-diff` |
| 5 | READY | Go/no-go | Reconciliation balanced and shadow diff has 0 mismatches |
| 6 | FREEZE | Legacy writes stopped | Manual: business confirms freeze window |
| 7 | FINAL_SYNC | Last changes drained | Lag is zero (manual until CDC exists) |
| 8 | SWITCHED | Traffic moves to Oracle | `approvedBy` required (403 without) |
| 9 | MONITOR | Watch the agreed period | Error rate and recon dashboards (pending) |
| 10 | DONE | Legacy retired later | Business sign-off |

## Go/no-go checklist (before FREEZE)
- [ ] Reconciliation `balanced = true` and payment sums tie out
- [ ] Shadow diff `clean = true`; if not, every mismatch explained
- [ ] Open exceptions reviewed with the customer (quarantined rows are decisions, not losses)
- [ ] Approver named and reachable for the whole window
- [ ] Rollback owner and communication channel agreed

## Rollback
Allowed only from FREEZE, FINAL_SYNC, SWITCHED, MONITOR (earlier stages have nothing to undo, DONE is final).

1. Operator posts `ROLLED_BACK` transition with their name; the audit log records who and when.
2. Reverse sync keeps legacy current until DONE, so applications are pointed back at legacy.
3. Confirm legacy writes work; keep Oracle data for diagnosis (do not delete).
4. Review `GET /api/runs/{id}/audit` and write the incident summary.

ROLLED_BACK is terminal. A new run is created to retry after the root cause is fixed.

## Rollback drill (automated)
`CutoverDrillTest.rollbackDrillLeavesAFullAuditTrail` rehearses the path on every build:
too-early rollback is refused, a full path to SWITCHED then ROLLED_BACK succeeds, the run is terminal afterwards, and the audit trail shows the ordered transitions and the named actor.

`CutoverDrillTest.shadowReadCatchesDriftAndBlocksReadyUntilFixed` rehearses a bad cutover: values are corrupted in the target, READY is refused, then allowed once fixed.

## Not yet covered
- Reverse sync is only a design assumption; it is not built (needs CDC and Docker).
- FINAL_SYNC lag check and MONITOR dashboards need Kafka and the observability work.
- Drills run on H2; a rehearsal on real Oracle is still required.

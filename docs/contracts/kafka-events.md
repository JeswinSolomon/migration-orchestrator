# Kafka Event Contracts

Key rule: the message key is the business id (`loan_id`, `borrower_id`) so per-entity ordering is preserved on a partition.

| Topic | Producer | Key | Purpose |
|-------|----------|-----|---------|
| `legacy.public.borrowers` / `.loans` / `.payments` | Debezium | primary key | Raw CDC change events |
| `mig.exceptions` | orchestrator | legacy_id | Rows quarantined, with reason |
| `mig.dlq` | orchestrator | legacy_id | Events that failed after retries (replayable) |
| `mig.run.events` | orchestrator | run_id | State transitions and audit |

## Debezium change event (simplified)
```json
{ "op": "c|u|d|r", "ts_ms": 1760000000000, "source": {"lsn": 123456, "table": "loans"},
  "before": null, "after": {"loan_id": 42, "borrower_id": 7, "balance": "1200.50"} }
```
- `source.lsn` becomes `SOURCE_VERSION`. A write is applied only if lsn > stored version (drops stale or duplicate replays).

## `mig.dlq` envelope
```json
{ "runId": 1, "topic": "legacy.public.loans", "partition": 0, "offset": 981,
  "key": "42", "payload": "...", "error": "ORA-02291 parent key not found", "attempts": 5 }
```

## Delivery semantics
At-least-once delivery plus idempotent MERGE gives an effectively exactly-once result. Retries use exponential backoff (1s, 2s, 4s, 8s, 16s), then go to the DLQ.

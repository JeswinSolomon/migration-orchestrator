"""Evaluation set for the diagnosis agent: seeded failures with a known root cause.

Run it with:  python -m app.agent.evals
A change to the rules is only acceptable if this score does not drop.
"""
from typing import Any, Dict, List, NamedTuple, Optional

from app.agent.diagnose import diagnose


class Scenario(NamedTuple):
    name: str
    expected: str
    evidence: Dict[str, Any]
    symptom: Optional[str] = None


def _recon(entities: List[Any], balanced: bool) -> Dict[str, Any]:
    return {"balanced": balanced, "entities": [
        {"entity": n, "source": s, "loaded": l, "quarantined": q, "balanced": s == l + q}
        for (n, s, l, q) in entities]}


BALANCED = _recon([("BORROWER", 10, 10, 0), ("LOAN", 8, 8, 0), ("PAYMENT", 20, 18, 2)], True)


def _ev(state: str = "INITIAL_LOAD", recon: Optional[Dict[str, Any]] = None, **extra: Any) -> Dict[str, Any]:
    ev = {"run": {"runId": 1, "tenantId": "northwind", "state": state, "approvedBy": None},
          "reconciliation": recon if recon is not None else BALANCED,
          "exceptions": [], "audit": [], "mappings": [], "errors": {}}
    ev.update(extra)
    return ev


def _exceptions(reason: str, n: int, other: int = 0) -> List[Dict[str, Any]]:
    rows = [{"REASON_CODE": reason, "RESOLVED": 0} for _ in range(n)]
    return rows + [{"REASON_CODE": "NULL_PAID_ON", "RESOLVED": 0} for _ in range(other)]


SCENARIOS = [
    Scenario("switch without approver", "APPROVAL_MISSING", _ev("FINAL_SYNC"), "SWITCHED requires approvedBy"),
    Scenario("skipped a stage", "ILLEGAL_TRANSITION", _ev("PLANNED"), "Illegal transition PLANNED -> READY"),
    Scenario("ai service offline", "AI_SERVICE_DOWN", _ev("PROFILED"), "AI service unavailable: connect timed out"),
    Scenario("load blocked by review (message)", "MAPPING_PENDING",
             _ev("PROFILED", mappings=[{"DECISION": "PENDING"}, {"DECISION": "APPROVED"}]),
             "Pre-check failed: 1 mapping(s) still need review"),
    Scenario("load blocked by review (evidence only)", "MAPPING_PENDING",
             _ev("PROFILED", mappings=[{"DECISION": "PENDING"}])),
    Scenario("mapping to a column that is not in the file", "MAPPING_COLUMN_MISSING", _ev(),
             "Load blocked: Approved mapping foo -> FULL_NAME but borrowers.csv has no column foo"),
    Scenario("nothing maps to full name", "NO_FULL_NAME_MAPPING", _ev(),
             "Load blocked: No source column is mapped to FULL_NAME"),
    Scenario("target values drifted", "SHADOW_DRIFT", _ev("SHADOW_VERIFY", shadow={
        "mismatchCount": 2, "clean": False, "samples": [{"field": "FULL_NAME"}, {"field": "AMOUNT"}]})),
    Scenario("cannot go ready", "SHADOW_DRIFT", _ev("SHADOW_VERIFY", shadow={
        "mismatchCount": 1, "clean": False, "samples": [{"field": "DOB"}]}),
             "Pre-check failed: shadow read found 1 mismatch(es)"),
    Scenario("load never ran", "LOAD_NOT_RUN", _ev("INITIAL_LOAD", _recon(
        [("BORROWER", 10, 0, 0), ("LOAN", 8, 0, 0), ("PAYMENT", 20, 0, 0)], False)),
             "Pre-check failed: reconciliation is not balanced"),
    Scenario("load interrupted midway", "PARTIAL_LOAD", _ev("INITIAL_LOAD", _recon(
        [("BORROWER", 10, 10, 0), ("LOAN", 8, 5, 0), ("PAYMENT", 20, 0, 0)], False))),
    Scenario("orphans dominate", "ORPHAN_CASCADE", _ev("CDC_SYNC", exceptions=_exceptions("ORPHAN_LOAN", 8, 2))),
    Scenario("negative amounts dominate", "NEGATIVE_AMOUNT_BACKLOG",
             _ev("CDC_SYNC", exceptions=_exceptions("NEGATIVE_AMOUNT_UNCONFIRMED", 7, 3))),
    Scenario("healthy run", "NO_ISSUE", _ev("READY")),
    Scenario("unexplained symptom must be escalated, not guessed", "UNKNOWN", _ev("READY"),
             "dashboard looks slow"),
    Scenario("mixed exceptions have no clear driver", "NO_ISSUE", _ev("CDC_SYNC", exceptions=(
        _exceptions("ORPHAN_LOAN", 3, 3) + _exceptions("NEGATIVE_AMOUNT_UNCONFIRMED", 3)))),
]


def run_eval() -> Dict[str, Any]:
    failures = []
    for s in SCENARIOS:
        got = diagnose(s.evidence, s.symptom).cause
        if got != s.expected:
            failures.append({"scenario": s.name, "expected": s.expected, "got": got})
    total = len(SCENARIOS)
    return {"total": total, "passed": total - len(failures), "accuracy": (total - len(failures)) / total,
            "failures": failures}


if __name__ == "__main__":
    result = run_eval()
    print("%d/%d correct (%.0f%%)" % (result["passed"], result["total"], result["accuracy"] * 100))
    for f in result["failures"]:
        print("FAIL %(scenario)s: expected %(expected)s, got %(got)s" % f)
    raise SystemExit(0 if not result["failures"] else 1)

from collections import Counter
from typing import Any, Dict, List, Optional

from pydantic import BaseModel

from app.agent.tools import OrchestratorTools, ToolError
from app.llm import LlmClient

STATE_ORDER = ["PLANNED", "PROFILED", "INITIAL_LOAD", "CDC_SYNC", "SHADOW_VERIFY", "READY", "FREEZE",
               "FINAL_SYNC", "SWITCHED", "MONITOR", "DONE"]
DOMINANT_SHARE = 0.5


class Action(BaseModel):
    description: str
    owner: str  # who should do it: operator, reviewer, approver, customer
    mutating: bool  # True means it changes data or state, so a named human performs it
    requires_approval: bool  # the agent never executes anything: this is always a proposal


class Diagnosis(BaseModel):
    cause: str
    confidence: float
    summary: str
    evidence: List[str]
    actions: List[Action]
    narrative: Optional[str] = None
    llm_used: bool = False
    warnings: List[str] = []


def collect_evidence(tools: OrchestratorTools, run_id: int) -> Dict[str, Any]:
    """Reads everything relevant using read-only tools. A missing run raises; other failures are recorded."""
    evidence = {"run": tools.call("get_run", run_id), "errors": {}}  # type: Dict[str, Any]
    for key, tool in (("reconciliation", "get_reconciliation"), ("exceptions", "get_exceptions"),
                      ("audit", "get_audit"), ("mappings", "get_mappings")):
        try:
            evidence[key] = tools.call(tool, run_id)
        except ToolError as e:
            evidence["errors"][key] = str(e)
    loaded = sum(e.get("loaded", 0) for e in (evidence.get("reconciliation") or {}).get("entities", []))
    if loaded > 0:  # the value-level comparison only makes sense once something was loaded
        try:
            evidence["shadow"] = tools.call("get_shadow_diff", run_id)
        except ToolError as e:
            evidence["errors"]["shadow"] = str(e)
    return evidence


def _act(description: str, owner: str, mutating: bool = True) -> Action:
    return Action(description=description, owner=owner, mutating=mutating, requires_approval=True)


def _next_state(state: str) -> Optional[str]:
    if state in STATE_ORDER and STATE_ORDER.index(state) + 1 < len(STATE_ORDER):
        return STATE_ORDER[STATE_ORDER.index(state) + 1]
    return None


def diagnose(evidence: Dict[str, Any], symptom: Optional[str] = None) -> Diagnosis:
    """Deterministic root-cause rules. Order matters: exact error messages first, then evidence patterns."""
    text = (symptom or "").lower()
    run = evidence.get("run") or {}
    state = run.get("state", "?")
    mappings = evidence.get("mappings") or []
    pending = [m for m in mappings if m.get("DECISION") == "PENDING"]
    recon = evidence.get("reconciliation") or {}
    shadow = evidence.get("shadow") or {}
    exceptions = [e for e in (evidence.get("exceptions") or []) if not e.get("RESOLVED")]

    if "requires approvedby" in text:
        return Diagnosis(cause="APPROVAL_MISSING", confidence=0.95, evidence=["symptom: " + (symptom or ""),
                         "get_run: state=%s approvedBy=%s" % (state, run.get("approvedBy"))],
                         summary="SWITCHED needs a named human approver and none was supplied.",
                         actions=[_act("Ask the business approver to supply their name as approvedBy and retry SWITCHED",
                                       "approver")])
    if "illegal transition" in text:
        nxt = _next_state(state)
        return Diagnosis(cause="ILLEGAL_TRANSITION", confidence=0.95,
                         evidence=["symptom: " + (symptom or ""), "get_run: state=" + state],
                         summary="Runs move one stage at a time; the requested jump is not allowed from " + state + ".",
                         actions=[_act("Advance to %s first (rollback is only allowed from FREEZE onwards)" % nxt
                                       if nxt else "No forward move exists from " + state, "operator")])
    if "ai service unavailable" in text:
        return Diagnosis(cause="AI_SERVICE_DOWN", confidence=0.9, evidence=["symptom: " + (symptom or "")],
                         summary="The orchestrator cannot reach the AI service. Mapping suggestions are optional.",
                         actions=[_act("Check ai-service /health and the AI_SERVICE_URL setting, then retry",
                                       "operator", mutating=False)])
    if "has no column" in text:
        return Diagnosis(cause="MAPPING_COLUMN_MISSING", confidence=0.95, evidence=["symptom: " + (symptom or "")],
                         summary="An approved mapping points at a column that is not in the legacy file.",
                         actions=[_act("Re-run suggestions with the real column names or reject the bad mapping", "reviewer")])
    if "mapped to full_name" in text:
        return Diagnosis(cause="NO_FULL_NAME_MAPPING", confidence=0.95, evidence=["symptom: " + (symptom or "")],
                         summary="No legacy column is mapped to the mandatory FULL_NAME target.",
                         actions=[_act("Approve a mapping to FULL_NAME (check the file's name column)", "reviewer")])
    if pending and (state in ("PLANNED", "PROFILED") or "still need review" in text):
        return Diagnosis(cause="MAPPING_PENDING", confidence=0.9 if "still need review" in text else 0.8,
                         evidence=["get_mappings: %d of %d mappings PENDING" % (len(pending), len(mappings)),
                                   "get_run: state=" + state],
                         summary="Loading is blocked until a named reviewer decides every AI-suggested mapping.",
                         actions=[_act("Approve or reject the %d pending mapping(s) in the console" % len(pending),
                                       "reviewer")])

    mismatches = shadow.get("mismatchCount", 0)
    if mismatches > 0 or "shadow read found" in text:
        fields = Counter(s.get("field") for s in shadow.get("samples", []))
        return Diagnosis(cause="SHADOW_DRIFT", confidence=0.9,
                         evidence=["get_shadow_diff: %d mismatch(es)" % mismatches,
                                   "fields: " + ", ".join("%s x%d" % kv for kv in fields.most_common(3))],
                         summary="Target values differ from what the legacy data says they should be.",
                         actions=[_act("Inspect the sample mismatches and find what changed the target "
                                       "(CDC event, manual edit)", "operator", mutating=False),
                                  _act("Correct the affected rows, then re-run the shadow diff", "operator")])

    if recon and not recon.get("balanced", True):
        entities = recon.get("entities", [])
        if all(e.get("loaded", 0) + e.get("quarantined", 0) == 0 for e in entities):
            todo = "Run the initial load" if state == "INITIAL_LOAD" else "Move the run to INITIAL_LOAD, then load"
            return Diagnosis(cause="LOAD_NOT_RUN", confidence=0.9,
                             evidence=["get_reconciliation: nothing loaded or quarantined",
                                       "get_run: state=" + state],
                             summary="Reconciliation fails because the initial load has not run.",
                             actions=[_act(todo, "operator")])
        gaps = ["%s %d/%d" % (e["entity"], e["loaded"] + e["quarantined"], e["source"])
                for e in entities if not e.get("balanced")]
        return Diagnosis(cause="PARTIAL_LOAD", confidence=0.85,
                         evidence=["get_reconciliation: unbalanced " + ", ".join(gaps)],
                         summary="Some source rows are neither loaded nor quarantined: the load was interrupted.",
                         actions=[_act("Re-run the load: it is restartable and skips rows already loaded", "operator")])

    if exceptions:
        reasons = Counter(e.get("REASON_CODE") for e in exceptions)
        top, count = reasons.most_common(1)[0]
        if count / len(exceptions) >= DOMINANT_SHARE:
            ev = ["get_exceptions: %d unresolved, %s = %d" % (len(exceptions), top, count)]
            if str(top).startswith("ORPHAN"):
                return Diagnosis(cause="ORPHAN_CASCADE", confidence=0.8, evidence=ev,
                                 summary="Most quarantined rows reference parents that were themselves quarantined "
                                         "or missing, so one upstream problem is causing many rows to fail.",
                                 actions=[_act("Fix or confirm the missing parents with the customer first; "
                                               "children can then be reloaded", "customer")])
            if top == "NEGATIVE_AMOUNT_UNCONFIRMED":
                return Diagnosis(cause="NEGATIVE_AMOUNT_BACKLOG", confidence=0.8, evidence=ev,
                                 summary="Negative payment amounts were quarantined and need a business decision "
                                         "(refund, reversal or data error).",
                                 actions=[_act("Ask the customer how negative amounts should be treated", "customer")])

    if symptom and symptom.strip():
        return Diagnosis(cause="UNKNOWN", confidence=0.0, evidence=["symptom: " + symptom.strip(),
                         "no rule matched and reconciliation and shadow checks look healthy"],
                         summary="The evidence does not explain this symptom. A human should investigate.",
                         actions=[_act("Escalate to the on-call engineer with the audit trail", "operator", mutating=False)])
    if not run:
        return Diagnosis(cause="UNKNOWN", confidence=0.0, evidence=["get_run returned nothing"],
                         summary="Not enough information to diagnose.", actions=[])
    return Diagnosis(cause="NO_ISSUE", confidence=0.8,
                     evidence=["get_reconciliation balanced, no unresolved exceptions, shadow diff clean"],
                     summary="Nothing is wrong with this run based on the available evidence.", actions=[])


def narrate(diagnosis: Diagnosis, llm: Optional[LlmClient]) -> Diagnosis:
    """Optional plain-language explanation. The LLM sees only the structured result, never row data, and can
    not change the cause or the proposed actions."""
    if llm is None or diagnosis.cause in ("NO_ISSUE",):
        return diagnosis
    prompt = ("Explain this migration diagnosis to a customer operator in 2 short sentences. Do not add new "
              "facts or actions.\nCause: %s\nSummary: %s\nEvidence: %s" %
              (diagnosis.cause, diagnosis.summary, "; ".join(diagnosis.evidence)))
    try:
        text = llm.complete(prompt).strip()
    except Exception as e:  # an LLM outage must never break diagnosis
        diagnosis.warnings.append("LLM narrative failed: %s" % type(e).__name__)
        return diagnosis
    if text:
        diagnosis.narrative = text[:600]
        diagnosis.llm_used = True
    return diagnosis

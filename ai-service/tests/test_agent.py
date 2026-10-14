import httpx
import pytest
from fastapi.testclient import TestClient

from app.agent.diagnose import collect_evidence, diagnose, narrate
from app.agent.evals import SCENARIOS, run_eval
from app.agent.tools import READ_ONLY_TOOLS, OrchestratorTools, RunNotFound, ToolError
from app.llm import LlmClient, get_llm_client
from app.main import app, get_tools


def _transport(routes, seen=None):
    def handler(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append((request.method, request.url.path))
        if request.url.path in routes:
            return httpx.Response(200, json=routes[request.url.path])
        return httpx.Response(404, json={"error": "nope"})
    return httpx.MockTransport(handler)


RUN = {"runId": 7, "tenantId": "northwind", "state": "PROFILED", "approvedBy": None}
RECON = {"balanced": True, "entities": [{"entity": "LOAN", "source": 1, "loaded": 1, "quarantined": 0, "balanced": True}]}


def _routes(extra=None):
    routes = {
        "/api/runs/7": RUN,
        "/api/runs/7/reconciliation": RECON,
        "/api/runs/7/exceptions": [],
        "/api/runs/7/audit": [],
        "/api/runs/7/mappings": [{"decision": "PENDING", "source_col": "x"}],
        "/api/runs/7/shadow-diff": {"mismatchCount": 0, "clean": True, "samples": []},
    }
    routes.update(extra or {})
    return routes


def test_eval_set_is_fully_correct():
    result = run_eval()
    assert result["failures"] == []
    assert result["total"] >= 10


def test_every_cause_has_a_scenario_and_actions_are_proposals_only():
    for s in SCENARIOS:
        d = diagnose(s.evidence, s.symptom)
        for a in d.actions:
            assert a.requires_approval is True  # the agent never acts on its own
        assert 0.0 <= d.confidence <= 1.0
    assert {s.expected for s in SCENARIOS} >= {"UNKNOWN", "NO_ISSUE", "SHADOW_DRIFT", "MAPPING_PENDING"}


def test_tools_are_get_only_and_allowlisted():
    seen = []
    tools = OrchestratorTools(transport=_transport(_routes(), seen))
    for name in READ_ONLY_TOOLS:
        tools.call(name, 7)
    assert {method for method, _ in seen} == {"GET"}
    assert not any(w in path for _, path in seen for w in ("transitions", "load", "approve"))
    with pytest.raises(ToolError):
        tools.call("transition_run", 7)


def test_agent_sends_its_service_account_credentials():
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.headers.get("authorization"))
        return httpx.Response(200, json=RUN)

    OrchestratorTools(transport=httpx.MockTransport(handler), user="agent", password="s3cret").call("get_run", 7)
    assert seen[0].startswith("Basic ")


def test_rejected_credentials_are_reported_as_a_tool_error():
    tools = OrchestratorTools(transport=httpx.MockTransport(lambda r: httpx.Response(401)))
    with pytest.raises(ToolError, match="service account"):
        tools.call("get_run", 7)


def test_missing_run_is_reported():
    tools = OrchestratorTools(transport=_transport({}))
    with pytest.raises(RunNotFound):
        tools.call("get_run", 99)


def test_collect_evidence_normalises_rows_and_records_failures():
    routes = _routes()
    del routes["/api/runs/7/audit"]
    evidence = collect_evidence(OrchestratorTools(transport=_transport(routes)), 7)
    assert evidence["mappings"][0]["DECISION"] == "PENDING"
    assert "audit" in evidence["errors"]
    assert diagnose(evidence).cause == "MAPPING_PENDING"


def test_shadow_diff_is_skipped_when_nothing_was_loaded():
    seen = []
    recon = {"balanced": False, "entities": [{"entity": "LOAN", "source": 1, "loaded": 0, "quarantined": 0}]}
    collect_evidence(OrchestratorTools(transport=_transport(_routes({"/api/runs/7/reconciliation": recon}), seen)), 7)
    assert not any(path.endswith("shadow-diff") for _, path in seen)


class _FakeLlm(LlmClient):
    def __init__(self, reply=None, fail=False):
        self.reply, self.fail, self.prompt = reply, fail, ""

    def complete(self, prompt: str) -> str:
        self.prompt = prompt
        if self.fail:
            raise RuntimeError("boom")
        return self.reply


def test_llm_narrative_never_sees_rows_and_cannot_change_the_cause():
    evidence = SCENARIOS[11].evidence  # orphans dominate
    d = diagnose(evidence)
    llm = _FakeLlm("Most failures come from missing parent records.")
    out = narrate(d, llm)
    assert out.cause == "ORPHAN_CASCADE" and out.llm_used is True
    assert "Most failures" in out.narrative
    assert "LEGACY_ID" not in llm.prompt and "RAW_PAYLOAD" not in llm.prompt


def test_llm_failure_falls_back_to_the_rules():
    d = narrate(diagnose(SCENARIOS[11].evidence), _FakeLlm(fail=True))
    assert d.cause == "ORPHAN_CASCADE" and d.llm_used is False and d.warnings


def test_endpoint_diagnoses_and_maps_errors():
    app.dependency_overrides[get_tools] = lambda: OrchestratorTools(transport=_transport(_routes()))
    app.dependency_overrides[get_llm_client] = lambda: None
    try:
        client = TestClient(app)
        ok = client.post("/agent/diagnose", json={"run_id": 7, "symptom": "Pre-check failed: 1 mapping(s) still need review"})
        assert ok.status_code == 200
        assert ok.json()["cause"] == "MAPPING_PENDING"
        assert ok.json()["actions"][0]["requires_approval"] is True
        assert client.post("/agent/diagnose", json={"run_id": 99}).status_code == 404
        assert client.post("/agent/diagnose", json={"run_id": 7, "symptom": "x" * 501}).status_code == 422
    finally:
        app.dependency_overrides.clear()


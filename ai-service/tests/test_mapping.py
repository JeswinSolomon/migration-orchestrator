import json

from fastapi.testclient import TestClient

from app.llm import LlmClient, get_llm_client
from app.main import app
from app.masking import mask_value
from app.mapping import SourceColumn, TargetColumn, heuristic_suggest, infer_type, suggest

TARGETS = [
    TargetColumn(name="FULL_NAME", type="VARCHAR2"),
    TargetColumn(name="EMAIL", type="VARCHAR2"),
    TargetColumn(name="PHONE", type="VARCHAR2"),
    TargetColumn(name="DOB", type="DATE"),
    TargetColumn(name="RATE_PCT", type="NUMBER"),
]


class FakeLlm(LlmClient):
    def __init__(self, reply=None, error=None):
        self.reply, self.error, self.prompts = reply, error, []

    def complete(self, prompt):
        self.prompts.append(prompt)
        if self.error:
            raise self.error
        return self.reply


def test_masking_hides_pii_but_keeps_shape():
    assert mask_value("ana@example.com") == "<email>"
    assert mask_value("(555) 0101") == "(999) 9999"
    assert mask_value("Ana Smith") == "Aaa Aaaaa"


def test_infer_type():
    assert infer_type(["5.1%", "6"]) == "NUMBER"
    assert infer_type(["2020-01-01", "03/15/1985", "05-Jan-72"]) == "DATE"
    assert infer_type(["hello"]) == "TEXT"
    assert infer_type([]) == "UNKNOWN"


def test_heuristics_match_exact_synonym_and_fuzzy():
    sources = [
        SourceColumn(name="full_name"), SourceColumn(name="email_address"),
        SourceColumn(name="birthdate", samples=["1990-05-01"]), SourceColumn(name="phone"),
    ]
    by_source = {s.source: s for s in heuristic_suggest(sources, TARGETS)}
    assert by_source["full_name"].target == "FULL_NAME" and by_source["full_name"].method == "exact"
    assert by_source["email_address"].target == "EMAIL" and by_source["email_address"].method == "synonym"
    assert by_source["birthdate"].target == "DOB"
    assert by_source["phone"].requires_review is False


def test_type_mismatch_lowers_confidence():
    result = heuristic_suggest([SourceColumn(name="dob", samples=["hello", "world"])], TARGETS)
    assert result[0].target == "DOB"
    assert result[0].confidence < 0.5 and "WARNING" in result[0].reason


def test_each_target_used_once():
    sources = [SourceColumn(name="phone"), SourceColumn(name="phone_number")]
    targets = [TargetColumn(name="PHONE", type="VARCHAR2")]
    assert len(heuristic_suggest(sources, targets)) == 1


def test_llm_resolves_leftovers_with_capped_confidence_and_masked_prompt():
    llm = FakeLlm(json.dumps([{"source": "cust_nm", "target": "FULL_NAME", "confidence": 0.99, "reason": "name"}]))
    sources = [SourceColumn(name="cust_nm", samples=["Ana Smith"]), SourceColumn(name="email")]
    result = suggest(sources, TARGETS, llm)
    llm_item = [s for s in result.suggestions if s.method == "llm"][0]
    assert llm_item.target == "FULL_NAME" and llm_item.confidence == 0.8 and llm_item.requires_review
    assert result.llm_used
    assert "Ana Smith" not in llm.prompts[0] and "Aaa Aaaaa" in llm.prompts[0]
    assert "email" not in llm.prompts[0].split("Available target columns")[0]  # already mapped, not sent


def test_llm_hallucinations_are_discarded():
    reply = json.dumps([
        {"source": "cust_nm", "target": "NOT_A_COLUMN", "confidence": 0.9},
        {"source": "ghost", "target": "FULL_NAME", "confidence": 0.9},
    ])
    result = suggest([SourceColumn(name="cust_nm")], TARGETS, FakeLlm(reply))
    assert result.suggestions == []
    assert len(result.warnings) == 2
    assert result.unmapped_sources == ["cust_nm"]


def test_llm_garbage_and_failure_do_not_break_the_service():
    sources = [SourceColumn(name="cust_nm")]
    assert "not valid JSON" in suggest(sources, TARGETS, FakeLlm("[oops]")).warnings[0]
    assert "no JSON array" in suggest(sources, TARGETS, FakeLlm("sorry")).warnings[0]
    failed = suggest(sources, TARGETS, FakeLlm(error=RuntimeError("timeout")))
    assert "LLM call failed" in failed.warnings[0] and failed.unmapped_sources == ["cust_nm"]


def test_works_without_llm():
    result = suggest([SourceColumn(name="cust_nm")], TARGETS, None)
    assert not result.llm_used and result.unmapped_sources == ["cust_nm"]


def test_api_endpoint_with_overridden_llm():
    app.dependency_overrides[get_llm_client] = lambda: None
    try:
        body = {"sources": [{"name": "full_name", "samples": ["Ana"]}],
                "targets": [{"name": "FULL_NAME", "type": "VARCHAR2"}]}
        r = TestClient(app).post("/mapping/suggest", json=body)
        assert r.status_code == 200
        assert r.json()["suggestions"][0]["target"] == "FULL_NAME"
    finally:
        app.dependency_overrides.clear()

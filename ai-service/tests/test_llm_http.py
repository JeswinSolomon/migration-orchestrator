import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from app.agent.diagnose import diagnose, narrate
from app.agent.evals import SCENARIOS
from app.llm import OpenAICompatibleClient, get_llm_client


class _FakeOpenAI(BaseHTTPRequestHandler):
    """Speaks the same /v1/chat/completions protocol as OpenAI and Ollama."""
    requests = []
    status = 200

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        _FakeOpenAI.requests.append((self.path, self.headers.get("Authorization"), body))
        payload = json.dumps({"choices": [{"message": {"content": "Orphan loans dominate the failures."}}]}).encode()
        self.send_response(_FakeOpenAI.status)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


@pytest.fixture
def llm_server():
    _FakeOpenAI.requests, _FakeOpenAI.status = [], 200
    server = HTTPServer(("127.0.0.1", 0), _FakeOpenAI)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield "http://127.0.0.1:%d/v1" % server.server_address[1]
    server.shutdown()


def test_narration_over_a_real_http_round_trip(llm_server):
    client = OpenAICompatibleClient(llm_server, "secret-key", "tiny-model")
    out = narrate(diagnose(SCENARIOS[11].evidence), client)
    assert out.llm_used is True and "Orphan loans" in out.narrative
    path, auth, body = _FakeOpenAI.requests[0]
    assert path == "/v1/chat/completions" and auth == "Bearer secret-key"
    assert body["model"] == "tiny-model"
    assert "LEGACY_ID" not in json.dumps(body)  # no row data leaves the service


def test_a_failing_llm_endpoint_falls_back_to_rules(llm_server):
    _FakeOpenAI.status = 500
    out = narrate(diagnose(SCENARIOS[11].evidence), OpenAICompatibleClient(llm_server, "k", "m", timeout=2))
    assert out.cause == "ORPHAN_CASCADE" and out.llm_used is False and out.warnings


def test_a_dead_llm_endpoint_falls_back_to_rules():
    out = narrate(diagnose(SCENARIOS[11].evidence), OpenAICompatibleClient("http://127.0.0.1:1/v1", "k", "m", timeout=1))
    assert out.cause == "ORPHAN_CASCADE" and out.llm_used is False


def test_environment_configures_the_client(monkeypatch):
    monkeypatch.setenv("LLM_BASE_URL", "http://localhost:11434/v1")
    monkeypatch.setenv("LLM_MODEL", "qwen2.5:3b")
    client = get_llm_client()
    assert client.model == "qwen2.5:3b" and client.api_key == "none"
    monkeypatch.delenv("LLM_MODEL")
    assert get_llm_client() is None

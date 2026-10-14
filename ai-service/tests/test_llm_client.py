import httpx

import app.llm as llm
from app.llm import OpenAICompatibleClient


def test_client_sends_bearer_key_model_and_zero_temperature(monkeypatch):
    seen = {}

    def fake_post(url, headers, json, timeout):
        seen.update(url=url, headers=headers, body=json)
        return httpx.Response(200, json={"choices": [{"message": {"content": "hello"}}]},
                              request=httpx.Request("POST", url))

    monkeypatch.setattr(llm.httpx, "post", fake_post)
    out = OpenAICompatibleClient("http://llm.local/v1/", "k-123", "tiny").complete("hi")
    assert out == "hello"
    assert seen["url"] == "http://llm.local/v1/chat/completions"
    assert seen["headers"]["Authorization"] == "Bearer k-123"
    assert seen["body"]["model"] == "tiny" and seen["body"]["temperature"] == 0

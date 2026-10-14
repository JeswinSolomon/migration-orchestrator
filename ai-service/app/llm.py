import os
from abc import ABC, abstractmethod
from typing import Optional

import httpx


class LlmClient(ABC):
    """Provider-agnostic interface (ADR-5). The mapping logic only depends on this."""

    @abstractmethod
    def complete(self, prompt: str) -> str:
        ...


class OpenAICompatibleClient(LlmClient):
    """Works with OpenAI, Azure-style gateways, and local Ollama (/v1/chat/completions)."""

    def __init__(self, base_url: str, api_key: str, model: str, timeout: float = 30.0):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.model = model
        self.timeout = timeout

    def complete(self, prompt: str) -> str:
        response = httpx.post(
            f"{self.base_url}/chat/completions",
            headers={"Authorization": f"Bearer {self.api_key}"},
            json={
                "model": self.model,
                "temperature": 0,
                "messages": [{"role": "user", "content": prompt}],
            },
            timeout=self.timeout,
        )
        response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"]


def get_llm_client() -> Optional[LlmClient]:
    """None when not configured: the service still works using heuristics only."""
    base_url = os.getenv("LLM_BASE_URL")
    model = os.getenv("LLM_MODEL")
    if not base_url or not model:
        return None
    return OpenAICompatibleClient(base_url, os.getenv("LLM_API_KEY", "none"), model)

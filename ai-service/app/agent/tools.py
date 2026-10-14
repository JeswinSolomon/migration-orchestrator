import os
from typing import Any, Dict, Optional

import httpx

# The only things the agent can do. Every entry is a GET on the orchestrator: there is deliberately no
# tool that transitions, loads, approves or edits anything (ADR-4: the agent is read-only).
READ_ONLY_TOOLS = {
    "get_run": "/api/runs/{run_id}",
    "get_reconciliation": "/api/runs/{run_id}/reconciliation",
    "get_exceptions": "/api/runs/{run_id}/exceptions",
    "get_audit": "/api/runs/{run_id}/audit",
    "get_mappings": "/api/runs/{run_id}/mappings",
    "get_shadow_diff": "/api/runs/{run_id}/shadow-diff",
}  # type: Dict[str, str]


class ToolError(Exception):
    pass


class RunNotFound(ToolError):
    pass


def _upper_keys(value: Any) -> Any:
    """The orchestrator returns raw DB rows; normalise their column names so rules do not depend on case."""
    if isinstance(value, list):
        return [_upper_keys(v) for v in value]
    if isinstance(value, dict):
        return {k.upper(): v for k, v in value.items()}
    return value


class OrchestratorTools:
    def __init__(self, base_url: Optional[str] = None, transport: Optional[httpx.BaseTransport] = None,
                 timeout: float = 30.0, user: Optional[str] = None, password: Optional[str] = None):
        user = user or os.getenv("ORCHESTRATOR_USER")
        password = password or os.getenv("ORCHESTRATOR_PASSWORD")
        auth = httpx.BasicAuth(user, password) if user and password else None
        self._client = httpx.Client(
            base_url=base_url or os.getenv("ORCHESTRATOR_URL", "http://localhost:8081"),
            transport=transport, timeout=timeout, auth=auth)

    def call(self, name: str, run_id: int) -> Any:
        path = READ_ONLY_TOOLS.get(name)
        if path is None:
            raise ToolError("Tool not allowed: " + name)
        try:
            response = self._client.get(path.format(run_id=int(run_id)))
        except httpx.HTTPError as e:
            raise ToolError("Orchestrator unreachable: " + str(e))
        if response.status_code == 404:
            raise RunNotFound("Run %s not found" % run_id)
        if response.status_code in (401, 403):
            raise ToolError("%s rejected: the agent's service account is missing or lacks READER" % name)
        if response.status_code >= 400:
            raise ToolError("%s failed with HTTP %d" % (name, response.status_code))
        data = response.json()
        # Run and reconciliation are single JSON objects with their own (camelCase) keys: leave them alone.
        return _upper_keys(data) if name in ("get_exceptions", "get_audit", "get_mappings") else data

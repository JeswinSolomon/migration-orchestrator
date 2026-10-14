from typing import List, Optional

from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field

from app.agent.diagnose import Diagnosis, collect_evidence, diagnose, narrate
from app.agent.tools import OrchestratorTools, RunNotFound, ToolError
from app.llm import LlmClient, get_llm_client
from app.mapping import MappingResult, SourceColumn, TargetColumn, suggest

app = FastAPI(title="Migration AI Service", version="0.1.0")


class SuggestRequest(BaseModel):
    sources: List[SourceColumn]
    targets: List[TargetColumn]


class DiagnoseRequest(BaseModel):
    run_id: int
    symptom: Optional[str] = Field(default=None, max_length=500)


def get_tools() -> OrchestratorTools:
    return OrchestratorTools()


@app.get("/health")
def health():
    return {"status": "UP"}


@app.post("/agent/diagnose", response_model=Diagnosis)
def agent_diagnose(request: DiagnoseRequest, tools: OrchestratorTools = Depends(get_tools),
                   llm: Optional[LlmClient] = Depends(get_llm_client)):
    """Read-only: gathers evidence and proposes actions. Nothing is executed (ADR-4)."""
    try:
        evidence = collect_evidence(tools, request.run_id)
    except RunNotFound as e:
        raise HTTPException(status_code=404, detail=str(e))
    except ToolError as e:
        raise HTTPException(status_code=502, detail=str(e))
    return narrate(diagnose(evidence, request.symptom), llm)


@app.post("/mapping/suggest", response_model=MappingResult)
def suggest_mapping(request: SuggestRequest, llm: Optional[LlmClient] = Depends(get_llm_client)):
    """Suggestions only: a human must approve them before they are used (ADR-4)."""
    return suggest(request.sources, request.targets, llm)

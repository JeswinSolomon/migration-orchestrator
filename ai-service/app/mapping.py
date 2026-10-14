import json
import re
from difflib import SequenceMatcher
from typing import Dict, List, Optional, Tuple

from pydantic import BaseModel

from app.llm import LlmClient
from app.masking import mask_value

LLM_MAX_CONFIDENCE = 0.8  # an LLM guess must never outrank a deterministic match
REVIEW_BELOW = 0.9
FUZZY_MIN_RATIO = 0.75

SYNONYMS = {
    "dob": {"birthdate", "dateofbirth", "birthday"},
    "phone": {"telephone", "mobile", "phonenumber", "tel"},
    "email": {"emailaddress", "mail"},
    "fullname": {"name", "customername", "borrowername"},
    "paidon": {"paymentdate", "paiddate"},
    "startdate": {"opendate", "originationdate"},
}


class SourceColumn(BaseModel):
    name: str
    samples: List[str] = []


class TargetColumn(BaseModel):
    name: str
    type: str  # DATE, NUMBER, VARCHAR2 ...


class Suggestion(BaseModel):
    source: str
    target: str
    confidence: float
    method: str  # exact | synonym | fuzzy | llm
    reason: str
    requires_review: bool


class MappingResult(BaseModel):
    suggestions: List[Suggestion]
    unmapped_sources: List[str]
    unmapped_targets: List[str]
    llm_used: bool
    warnings: List[str]


def _norm(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", name.lower())


def infer_type(samples: List[str]) -> str:
    values = [s.strip() for s in samples if s and s.strip()]
    if not values:
        return "UNKNOWN"
    if all(re.fullmatch(r"-?\d+(\.\d+)?%?", v) for v in values):
        return "NUMBER"
    if all(re.fullmatch(r"\d{4}-\d{2}-\d{2}|\d{2}/\d{2}/\d{4}|\d{2}-[A-Za-z]{3}-\d{2}", v) for v in values):
        return "DATE"
    return "TEXT"


def _type_compatible(inferred: str, target_type: str) -> bool:
    if inferred == "UNKNOWN":
        return True
    t = target_type.upper()
    if t.startswith("DATE") or t.startswith("TIMESTAMP"):
        return inferred == "DATE"
    if t.startswith("NUMBER"):
        return inferred == "NUMBER"
    return True  # anything can be stored as text


def _score(source: SourceColumn, target: TargetColumn) -> Optional[Tuple[float, str, str]]:
    s, t = _norm(source.name), _norm(target.name)
    if s == t:
        return 0.95, "exact", "column names match after normalization"
    for canonical, alts in SYNONYMS.items():
        group = alts | {canonical}
        if s in group and t in group:
            return 0.85, "synonym", "known synonym of '%s'" % canonical
    ratio = SequenceMatcher(None, s, t).ratio()
    if ratio >= FUZZY_MIN_RATIO:
        return round(ratio * 0.8, 2), "fuzzy", "similar names (similarity %.2f)" % ratio
    return None


def heuristic_suggest(sources: List[SourceColumn], targets: List[TargetColumn]) -> List[Suggestion]:
    candidates = []
    for src in sources:
        inferred = infer_type(src.samples)
        for tgt in targets:
            scored = _score(src, tgt)
            if scored is None:
                continue
            confidence, method, reason = scored
            if not _type_compatible(inferred, tgt.type):
                confidence = round(confidence * 0.5, 2)
                reason += "; WARNING samples look like %s but target is %s" % (inferred, tgt.type)
            candidates.append((confidence, src.name, tgt.name, method, reason))

    # greedy one-to-one assignment, best score first
    used_src, used_tgt, out = set(), set(), []
    for confidence, src, tgt, method, reason in sorted(candidates, key=lambda c: -c[0]):
        if src in used_src or tgt in used_tgt:
            continue
        used_src.add(src)
        used_tgt.add(tgt)
        out.append(Suggestion(source=src, target=tgt, confidence=confidence, method=method, reason=reason,
                              requires_review=confidence < REVIEW_BELOW))
    return out


def build_prompt(sources: List[SourceColumn], targets: List[TargetColumn]) -> str:
    src_lines = "\n".join(
        "- %s (masked samples: %s)" % (s.name, ", ".join(mask_value(v) for v in s.samples[:3]))
        for s in sources)
    tgt_lines = "\n".join("- %s (%s)" % (t.name, t.type) for t in targets)
    return (
        "You map legacy database columns to a target schema.\n"
        "Source columns:\n%s\n\nAvailable target columns:\n%s\n\n"
        "Reply with ONLY a JSON array of objects "
        '{"source": str, "target": str or null, "confidence": 0..1, "reason": str}. '
        "Use null when no target fits. Never invent target names." % (src_lines, tgt_lines))


def parse_llm_output(text: str, sources: List[SourceColumn], targets: List[TargetColumn],
                     warnings: List[str]) -> List[Suggestion]:
    """The LLM is untrusted: keep only well-formed items that reference real columns, one-to-one."""
    start, end = text.find("["), text.rfind("]")
    if start == -1 or end <= start:
        warnings.append("LLM reply contained no JSON array; ignored")
        return []
    try:
        items = json.loads(text[start:end + 1])
    except ValueError:
        warnings.append("LLM reply was not valid JSON; ignored")
        return []

    valid_src = {s.name for s in sources}
    valid_tgt = {t.name: t for t in targets}
    src_types = {s.name: infer_type(s.samples) for s in sources}
    used_src, used_tgt, out = set(), set(), []
    for item in items if isinstance(items, list) else []:
        if not isinstance(item, dict):
            continue
        src, tgt = item.get("source"), item.get("target")
        if tgt is None:
            continue
        if src not in valid_src or tgt not in valid_tgt:
            warnings.append("LLM suggested unknown column %r -> %r; discarded" % (src, tgt))
            continue
        if src in used_src or tgt in used_tgt:
            warnings.append("LLM suggested duplicate use of %r -> %r; discarded" % (src, tgt))
            continue
        try:
            confidence = min(float(item.get("confidence", 0)), LLM_MAX_CONFIDENCE)
        except (TypeError, ValueError):
            confidence = 0.0
        reason = str(item.get("reason", ""))[:200]
        if not _type_compatible(src_types[src], valid_tgt[tgt].type):
            confidence = round(confidence * 0.5, 2)
            reason += "; WARNING type mismatch"
        used_src.add(src)
        used_tgt.add(tgt)
        out.append(Suggestion(source=src, target=tgt, confidence=round(confidence, 2), method="llm",
                              reason=reason, requires_review=True))
    return out


def suggest(sources: List[SourceColumn], targets: List[TargetColumn],
            llm: Optional[LlmClient]) -> MappingResult:
    warnings: List[str] = []
    suggestions = heuristic_suggest(sources, targets)
    mapped_src = {s.source for s in suggestions}
    mapped_tgt = {s.target for s in suggestions}
    left_src = [s for s in sources if s.name not in mapped_src]
    left_tgt = [t for t in targets if t.name not in mapped_tgt]

    llm_used = False
    if left_src and left_tgt and llm is not None:
        try:
            reply = llm.complete(build_prompt(left_src, left_tgt))
            llm_used = True
            suggestions += parse_llm_output(reply, left_src, left_tgt, warnings)
        except Exception as exc:  # a broken LLM must never break the migration tooling
            warnings.append("LLM call failed (%s); heuristics only" % type(exc).__name__)

    mapped_src = {s.source for s in suggestions}
    mapped_tgt = {s.target for s in suggestions}
    return MappingResult(
        suggestions=suggestions,
        unmapped_sources=[s.name for s in sources if s.name not in mapped_src],
        unmapped_targets=[t.name for t in targets if t.name not in mapped_tgt],
        llm_used=llm_used,
        warnings=warnings,
    )

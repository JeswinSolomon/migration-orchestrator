import re

EMAIL = re.compile(r"[^@\s]+@[^@\s]+\.[^@\s]+")


def mask_value(value: str) -> str:
    """Hides PII before a value is put in an LLM prompt: keeps shape, drops content."""
    value = (value or "").strip()
    if EMAIL.fullmatch(value):
        return "<email>"
    # digits -> 9, letters -> a/A, so '555-0100' becomes '999-9999' and 'Ana Smith' becomes 'Aaa Aaaaa'
    out = []
    for ch in value:
        if ch.isdigit():
            out.append("9")
        elif ch.isalpha():
            out.append("A" if ch.isupper() else "a")
        else:
            out.append(ch)
    return "".join(out)[:40]

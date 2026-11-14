"""Phone-number -> name mapping.

WhatsApp's backup (msgstore.db) does NOT contain contact names - those live in wa.db,
which is not part of the backup. So for 1-to-1 chats we match phone numbers against
your Google Contacts export (contacts.google.com > Export > vCard or Google CSV),
plus an optional aliases.json for manual names, e.g. {"919876543210": "Papa"}.
"""
from __future__ import annotations

import csv
import io
import json
import re
from pathlib import Path


def normalize_phone(raw: str, default_cc: str = "91") -> str | None:
    s = raw.strip()
    plus = s.startswith("+") or s.startswith("00")
    digits = re.sub(r"\D", "", s)
    if s.startswith("00"):
        digits = digits[2:]
    if not digits:
        return None
    if not plus:
        digits = digits.lstrip("0")
        if len(digits) <= 10:  # local number without country code
            digits = default_cc + digits
    return digits if len(digits) >= 7 else None


def _parse_vcf(text: str, cc: str) -> dict[str, str]:
    out: dict[str, str] = {}
    # unfold folded lines
    text = re.sub(r"\r?\n[ \t]", "", text)
    for card in re.split(r"(?im)^BEGIN:VCARD", text):
        name = None
        phones = []
        for line in card.splitlines():
            key, _, val = line.partition(":")
            k = key.split(";")[0].upper()
            if k == "FN" and val.strip():
                name = val.strip()
            elif k == "TEL":
                p = normalize_phone(val, cc)
                if p:
                    phones.append(p)
        if name:
            for p in phones:
                out.setdefault(p, name)
    return out


def _parse_csv(text: str, cc: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for row in csv.DictReader(io.StringIO(text)):
        name = (row.get("Name") or "").strip()
        if not name:
            parts = [row.get(k, "") for k in ("First Name", "Middle Name", "Last Name")]
            name = " ".join(p.strip() for p in parts if p and p.strip())
        if not name:
            name = (row.get("Organization Name") or row.get("Organization 1 - Name") or "").strip()
        if not name:
            continue
        for col, val in row.items():
            if col and "phone" in col.lower() and "value" in col.lower() and val:
                for piece in val.split(":::"):
                    p = normalize_phone(piece, cc)
                    if p:
                        out.setdefault(p, name)
    return out


def load_contacts(paths: list[str | Path], default_cc: str = "91") -> dict[str, str]:
    names: dict[str, str] = {}
    aliases: dict[str, str] = {}
    for path in paths:
        path = Path(path)
        text = path.read_text(encoding="utf-8-sig", errors="replace")
        if path.suffix.lower() == ".json":
            for k, v in json.loads(text).items():
                p = normalize_phone(k, default_cc) or k
                aliases[p] = v
        elif path.suffix.lower() == ".csv":
            names.update({k: v for k, v in _parse_csv(text, default_cc).items() if k not in names})
        else:
            names.update({k: v for k, v in _parse_vcf(text, default_cc).items() if k not in names})
    names.update(aliases)  # manual aliases always win
    return names

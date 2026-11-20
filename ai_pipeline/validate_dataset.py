#!/usr/bin/env python3
"""
Strict quality & Islamic audit validator for Nabd Al-Dhikr AI datasets (db_[lang].json).
Enforces schema completeness, UI length caps, authentic non-abbreviated Quranic text,
valid integer repeat counts, 15-20 diverse user queries per intent, and zero fatwas.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path
from typing import Any

REQUIRED_FIELDS = {
    "id",
    "order_index",
    "language",
    "category",
    "audit_status",
    "audit_notes",
    "dhikr_text",
    "transliteration",
    "repeat_count_number",
    "repeat_count_label",
    "source_reference",
    "short_virtue_for_card",
    "detailed_explanation",
    "vocabulary_explanation",
    "qa_pairs",
    "suggested_followups",
}

VALID_AUDIT_STATUSES = {"valid", "corrected", "weak_replaced"}

FORBIDDEN_FATWA_PATTERNS = [
    r"\bفتوى\b",
    r"\bالفتوى\b",
    r"\bحكم شرعي\b",
    r"\bحلال وحرام\b",
    r"\bلا يجوز\b",
    r"\bيجوز شرعا\b",
    r"\bمكروه\b",
    r"\bفرض عين\b",
    r"\bمذهب\b",
    r"\bالمذاهب\b",
    r"\bطائفة\b",
    r"\bبدعة\b",
    r"\bfatwa\b",
    r"\bhalal or haram\b",
    r"\bfiqh ruling\b",
    r"\bsectarian\b",
]

ABBREVIATION_PATTERNS = [
    r"\.\.\.",
    r"…",
    r"إلى آخر",
    r"^قراءة\s",
]


def count_words(text: str) -> int:
    tokens = [tok for tok in re.split(r"\s+", text.strip()) if tok]
    return len(tokens)


def validate_entry(entry: dict[str, Any], index: int, seen_ids: set[str]) -> list[str]:
    errors: list[str] = []
    prefix = f"[entry #{index} id={entry.get('id', '?')}]"

    missing = REQUIRED_FIELDS - set(entry.keys())
    if missing:
        errors.append(f"{prefix} Missing required keys: {sorted(missing)}")
        return errors

    entry_id = entry["id"]
    if not isinstance(entry_id, str) or not entry_id.strip():
        errors.append(f"{prefix} 'id' must be a non-empty string")
    elif entry_id in seen_ids:
        errors.append(f"{prefix} Duplicate id '{entry_id}'")
    else:
        seen_ids.add(entry_id)

    if not isinstance(entry["order_index"], int) or entry["order_index"] < 1:
        errors.append(f"{prefix} 'order_index' must be a positive integer")

    if entry["audit_status"] not in VALID_AUDIT_STATUSES:
        errors.append(f"{prefix} Invalid audit_status: {entry['audit_status']}")

    if not isinstance(entry["repeat_count_number"], int) or entry["repeat_count_number"] < 1:
        errors.append(f"{prefix} 'repeat_count_number' must be a positive integer")

    dhikr_text = entry["dhikr_text"]
    if not isinstance(dhikr_text, str) or not dhikr_text.strip():
        errors.append(f"{prefix} Empty dhikr_text")
    else:
        for pat in ABBREVIATION_PATTERNS:
            if re.search(pat, dhikr_text):
                errors.append(f"{prefix} Abbreviated or instructional dhikr_text matched '{pat}'")

    if entry["language"] == "ar" and entry["transliteration"] != "":
        errors.append(f"{prefix} 'transliteration' must be empty string for Arabic ('ar')")

    short_virtue = entry["short_virtue_for_card"]
    if not isinstance(short_virtue, str) or not short_virtue.strip():
        errors.append(f"{prefix} Empty short_virtue_for_card")
    elif count_words(short_virtue) >= 15:
        errors.append(
            f"{prefix} short_virtue_for_card has {count_words(short_virtue)} words (must be < 15): '{short_virtue}'"
        )

    followups = entry["suggested_followups"]
    if not isinstance(followups, list) or len(followups) != 3:
        errors.append(f"{prefix} suggested_followups must contain exactly 3 items")
    else:
        for i, q in enumerate(followups):
            if not isinstance(q, str) or not q.strip():
                errors.append(f"{prefix} suggested_followups[{i}] is empty")
            elif count_words(q) >= 6:
                errors.append(
                    f"{prefix} suggested_followups[{i}] has {count_words(q)} words (must be < 6): '{q}'"
                )

    vocab = entry["vocabulary_explanation"]
    if not isinstance(vocab, list) or len(vocab) < 1:
        errors.append(f"{prefix} vocabulary_explanation must have at least 1 item")
    else:
        for i, item in enumerate(vocab):
            if not isinstance(item, dict) or not item.get("word") or not item.get("meaning"):
                errors.append(f"{prefix} vocabulary_explanation[{i}] invalid")

    qa_pairs = entry["qa_pairs"]
    if not isinstance(qa_pairs, list) or len(qa_pairs) < 1:
        errors.append(f"{prefix} qa_pairs must contain at least 1 QA block")
    else:
        for i, qa in enumerate(qa_pairs):
            questions = qa.get("user_questions", [])
            if not isinstance(questions, list) or not (15 <= len(questions) <= 20):
                errors.append(
                    f"{prefix} qa_pairs[{i}].user_questions count={len(questions)} (must be 15..20)"
                )
            elif len(set(q.strip() for q in questions)) != len(questions):
                errors.append(f"{prefix} qa_pairs[{i}].user_questions contains duplicates")

            bot_answer = qa.get("bot_answer", "")
            if not isinstance(bot_answer, str) or not bot_answer.strip():
                errors.append(f"{prefix} qa_pairs[{i}].bot_answer is empty")
            else:
                for pat in FORBIDDEN_FATWA_PATTERNS:
                    if re.search(pat, bot_answer, flags=re.IGNORECASE):
                        errors.append(f"{prefix} Forbidden fatwa term '{pat}' found in bot_answer")

    explanation = entry.get("detailed_explanation", "")
    if not isinstance(explanation, str) or not explanation.strip():
        errors.append(f"{prefix} detailed_explanation is empty")
    else:
        for pat in FORBIDDEN_FATWA_PATTERNS:
            if re.search(pat, explanation, flags=re.IGNORECASE):
                errors.append(f"{prefix} Forbidden fatwa term '{pat}' found in detailed_explanation")

    return errors


def validate_file(path: Path) -> tuple[bool, int, list[str]]:
    if not path.exists():
        return False, 0, [f"File not found: {path}"]

    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list) or len(data) == 0:
        return False, 0, [f"Dataset {path} must be a non-empty JSON array"]

    seen_ids: set[str] = set()
    all_errors: list[str] = []
    for idx, entry in enumerate(data, start=1):
        if not isinstance(entry, dict):
            all_errors.append(f"[entry #{idx}] Must be a JSON object")
            continue
        all_errors.extend(validate_entry(entry, idx, seen_ids))

    return len(all_errors) == 0, len(data), all_errors


def main() -> int:
    targets = [Path(arg) for arg in sys.argv[1:]]
    if not targets:
        targets = sorted((Path(__file__).parent / "data").glob("db_*.json"))

    if not targets:
        print("No db_*.json files found to validate.", file=sys.stderr)
        return 1

    overall_ok = True
    for file_path in targets:
        ok, count, errors = validate_file(file_path)
        if ok:
            print(f"[PASS] {file_path.name}: {count} entries validated with 0 errors.")
        else:
            overall_ok = False
            print(f"[FAIL] {file_path.name}: {len(errors)} errors across {count} entries:")
            for err in errors[:30]:
                print(f"  - {err}")
            if len(errors) > 30:
                print(f"  ... and {len(errors) - 30} more errors.")

    return 0 if overall_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())

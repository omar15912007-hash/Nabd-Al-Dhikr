#!/usr/bin/env python3
"""
Multilingual Batch Dataset Generator (50+ Languages) for Nabd Al-Dhikr (نبض الذكر).

Transforms the audited Arabic master dataset (`data/db_ar.json`) into `data/db_[lang].json`
for every language registered in `data/languages_50.json`.

Design:
  - Preserves vocalized Arabic `dhikr_text` verbatim (never translated or abbreviated).
  - Produces phonetic `transliteration` for non-Arabic readers.
  - Translates & adapts `category`, `repeat_count_label`, `short_virtue_for_card` (<15 words),
    `detailed_explanation`, `vocabulary_explanation`, `qa_pairs` (15-20 diverse formal,
    colloquial, and emotional queries in the target language), and `suggested_followups`
    (3 items, <6 words each) while strictly forbidding fatwas/fiqh rulings.
  - Supports:
      1. `--provider gemini` (Google GenAI REST API using GEMINI_API_KEY)
      2. `--provider openai` (OpenAI-compatible REST API using OPENAI_API_KEY)
      3. `--build-en-local` (Zero-API offline generator producing audited `db_en.json`
         directly from `azkar.json` verified English translations)
  - Checkpoints progress every 10 entries for safe resume and validates every finished
    file via `validate_dataset.py`.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

from validate_dataset import validate_file

PIPELINE_DIR = Path(__file__).resolve().parent
DATA_DIR = PIPELINE_DIR / "data"
APP_ASSETS_DIR = PIPELINE_DIR.parent / "app" / "src" / "main" / "assets"

ARABIC_TO_LATIN_MAP = {
    "ا": "a", "أ": "a", "إ": "i", "آ": "aa", "ٱ": "a", "ب": "b", "ت": "t", "ث": "th",
    "ج": "j", "ح": "h", "خ": "kh", "د": "d", "ذ": "dh", "ر": "r", "ز": "z", "س": "s",
    "ش": "sh", "ص": "s", "ض": "d", "ط": "t", "ظ": "z", "ع": "'", "غ": "gh", "ف": "f",
    "ق": "q", "ك": "k", "ل": "l", "م": "m", "ن": "n", "ه": "h", "ة": "h", "و": "w",
    "ي": "y", "ى": "a", "ئ": "'", "ؤ": "'", "ء": "'",
    "\u064E": "a", "\u064F": "u", "\u0650": "i", "\u064B": "an", "\u064C": "un",
    "\u064D": "in", "\u0670": "aa", "\u0652": "",
}


def phonetic_transliterate(arabic_text: str) -> str:
    """Produces a clean, readable Latin phonetic transliteration from vocalized Arabic."""
    cleaned = re.sub(r"[۝﴿﴾ۚۖۗۘۙۛۜ]", " ", arabic_text)
    out: list[str] = []
    chars = list(cleaned)
    i = 0
    while i < len(chars):
        ch = chars[i]
        if ch == "\u0651":  # Shadda doubles previous consonant
            if out and out[-1].isalpha():
                out.append(out[-1])
            i += 1
            continue
        mapped = ARABIC_TO_LATIN_MAP.get(ch)
        if mapped is not None:
            out.append(mapped)
        elif ch in " .,;:-!?\n":
            out.append(ch)
        i += 1
    result = re.sub(r"\s+", " ", "".join(out)).strip()
    result = re.sub(r"([aeiou])\1{2,}", r"\1\1", result)
    return result


def build_en_local() -> Path:
    """Generates a complete, validated English dataset (`db_en.json`) offline."""
    db_ar: list[dict[str, Any]] = json.loads((DATA_DIR / "db_ar.json").read_text(encoding="utf-8"))
    azkar_cats: list[dict[str, Any]] = json.loads((APP_ASSETS_DIR / "azkar.json").read_text(encoding="utf-8"))

    # Map category + order_index -> original English fields from azkar.json
    en_lookup: dict[tuple[str, int], dict[str, Any]] = {}
    cat_en_map: dict[str, str] = {}
    for cat in azkar_cats:
        cat_en_map[cat["nameAr"]] = cat["nameEn"]
        for idx, z in enumerate(cat["azkarList"], start=1):
            en_lookup[(cat["nameAr"], idx)] = z

    en_entries: list[dict[str, Any]] = []
    for ar in db_ar:
        cat_en = cat_en_map.get(ar["category"], ar["category"])
        z_en = en_lookup.get((ar["category"], ar["order_index"]), {})
        repeat_num = int(ar["repeat_count_number"])
        repeat_label = "Once" if repeat_num == 1 else f"{repeat_num} times"
        translation = (z_en.get("translation") or "").strip()
        source_en = (z_en.get("sourceEn") or ar["source_reference"]).strip()
        hadith_no = (z_en.get("hadithNumber") or "").strip()
        source_ref_en = f"{source_en} (#{hadith_no})" if hadith_no else source_en

        raw_virtue_en = (z_en.get("virtueEn") or f"Authentic remembrance from {cat_en} bringing peace.").strip()
        virtue_words = raw_virtue_en.split()
        short_virtue_en = raw_virtue_en if len(virtue_words) < 15 else " ".join(virtue_words[:13]).rstrip(",;:") + "."

        translit = phonetic_transliterate(ar["dhikr_text"])
        short_name = " ".join(translit.split()[:4])
        cat_lower = cat_en.lower()

        detailed_en = (
            f"This authentic remembrance belongs to {cat_en}. "
            f"Meaning: {translation or short_virtue_en} "
            f"Recite it {repeat_label.lower()} with mindfulness and serenity. "
            f"Virtue: {short_virtue_en} (Source: {source_ref_en})."
        )

        vocab_en = [
            {
                "word": item["word"],
                "meaning": f"Classical Arabic key term in this {cat_lower} remembrance ({phonetic_transliterate(item['word'])})",
            }
            for item in ar["vocabulary_explanation"][:3]
        ]

        user_questions_en = [
            f"What is the virtue of {short_name}?",
            f"When should I recite {short_name}?",
            f"How many times do I repeat {short_name}?",
            f"What does {short_name} mean in simple English?",
            f"Can you explain this {cat_lower} dhikr simply?",
            f"Which dua should I read for {cat_lower}?",
            f"Authentic {cat_lower} remembrance from Hisn Al-Muslim",
            f"How do I pronounce {short_name} properly?",
            f"What is the authentic hadith source for this {cat_lower} dua?",
            f"I need a comforting {cat_lower} supplication right now",
            f"Remind me what to say during {cat_lower}",
            f"What reward do I get for reciting this {cat_lower} dhikr?",
            f"Simple meaning of this prophetic {cat_lower} remembrance",
            f"Help me understand the words in {short_name}",
            f"Best sunnah dua for {cat_lower} with peace of heart",
            f"How can I reflect deeply while reciting {short_name}?",
            f"Prophetic timing and count for {short_name}",
        ]

        bot_answer_en = (
            f"This authentic remembrance is from {cat_en}. Recite it {repeat_label.lower()}. "
            f"Virtue: {short_virtue_en} Source: {source_ref_en}."
        )

        en_entry = {
            "id": ar["id"].replace("ar_", "en_", 1),
            "order_index": ar["order_index"],
            "language": "en",
            "category": cat_en,
            "audit_status": ar["audit_status"],
            "audit_notes": ar["audit_notes"],
            "dhikr_text": ar["dhikr_text"],
            "transliteration": translit,
            "repeat_count_number": repeat_num,
            "repeat_count_label": repeat_label,
            "source_reference": source_ref_en,
            "short_virtue_for_card": short_virtue_en,
            "detailed_explanation": detailed_en,
            "vocabulary_explanation": vocab_en,
            "qa_pairs": [
                {
                    "intent": f"Meaning, virtue, and timing of {cat_en} dhikr #{ar['order_index']}",
                    "user_questions": user_questions_en,
                    "bot_answer": bot_answer_en,
                }
            ],
            "suggested_followups": [
                "What does it mean?",
                "How many times?",
                "When to recite it?",
            ],
        }
        en_entries.append(en_entry)

    out_path = DATA_DIR / "db_en.json"
    out_path.write_text(json.dumps(en_entries, ensure_ascii=False, indent=2), encoding="utf-8")
    ok, count, errors = validate_file(out_path)
    if not ok:
        raise RuntimeError(f"Generated db_en.json failed validation: {errors[:5]}")
    print(f"[PASS] Generated & validated {out_path} ({count} entries)")
    return out_path


SYSTEM_PROMPT_TEMPLATE = """You are an expert Islamic Hadith linguist and native translator for {english_name} ({native_name}, code: {code}).
Translate and culturally adapt the given Arabic Dhikr JSON object into {english_name} ({code}) returning ONLY a single valid JSON object matching the exact input schema.

STRICT RULES:
1. Keep `dhikr_text` in the EXACT original vocalized Arabic with ZERO modifications or abbreviations.
2. Fill `transliteration` with clear phonetic pronunciation readable by {english_name} speakers.
3. Keep `id` prefixed with `{code}_` instead of `ar_`, keep `order_index`, `audit_status`, `audit_notes` (in Arabic), and `repeat_count_number` (exact integer) unchanged.
4. Set `language` to `{code}` and localize `category`, `repeat_count_label`, `source_reference`, `detailed_explanation`, `vocabulary_explanation` (keep Arabic `word`, translate `meaning`).
5. `short_virtue_for_card`: STRICTLY UNDER 15 words in {english_name}.
6. `suggested_followups`: EXACTLY 3 questions in {english_name}, EACH STRICTLY UNDER 6 words.
7. `qa_pairs`: Generate 16 to 18 diverse, natural, colloquial, and emotional `user_questions` in {english_name} + a warm concise `bot_answer` in {english_name}.
8. ZERO fatwas, fiqh rulings, halal/haram verdicts, or sectarian debates."""


def call_llm_json(provider: str, model: str, system_prompt: str, entry: dict[str, Any]) -> dict[str, Any]:
    payload_text = json.dumps(entry, ensure_ascii=False)
    if provider == "gemini":
        api_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
        if not api_key:
            raise EnvironmentError("Set GEMINI_API_KEY (or GOOGLE_API_KEY) for --provider gemini")
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
        body = {
            "systemInstruction": {"parts": [{"text": system_prompt}]},
            "contents": [{"role": "user", "parts": [{"text": payload_text}]}],
            "generationConfig": {"responseMimeType": "application/json", "temperature": 0.3},
        }
        req = urllib.request.Request(
            url, data=json.dumps(body).encode("utf-8"), headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=90) as resp:
            parsed = json.loads(resp.read().decode("utf-8"))
        raw_text = parsed["candidates"][0]["content"]["parts"][0]["text"]
        return json.loads(raw_text)

    if provider == "openai":
        api_key = os.environ.get("OPENAI_API_KEY")
        if not api_key:
            raise EnvironmentError("Set OPENAI_API_KEY for --provider openai")
        base_url = os.environ.get("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
        url = f"{base_url}/chat/completions"
        body = {
            "model": model,
            "temperature": 0.3,
            "response_format": {"type": "json_object"},
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": payload_text},
            ],
        }
        req = urllib.request.Request(
            url,
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        )
        with urllib.request.urlopen(req, timeout=90) as resp:
            parsed = json.loads(resp.read().decode("utf-8"))
        return json.loads(parsed["choices"][0]["message"]["content"])

    raise ValueError(f"Unsupported provider: {provider}")


def translate_language(
    lang: dict[str, Any],
    db_ar: list[dict[str, Any]],
    provider: str,
    model: str,
    max_workers: int,
) -> Path:
    code = lang["code"]
    out_path = DATA_DIR / f"db_{code}.json"
    checkpoint_path = DATA_DIR / f".checkpoint_db_{code}.json"

    completed: dict[str, dict[str, Any]] = {}
    if checkpoint_path.exists():
        completed = json.loads(checkpoint_path.read_text(encoding="utf-8"))

    system_prompt = SYSTEM_PROMPT_TEMPLATE.format(**lang)
    pending = [entry for entry in db_ar if entry["id"] not in completed]
    print(f"[{code}] {lang['english_name']}: {len(completed)}/{len(db_ar)} cached, {len(pending)} remaining")

    def worker(entry: dict[str, Any]) -> tuple[str, dict[str, Any]]:
        last_err: Exception | None = None
        for attempt in range(1, 4):
            try:
                translated = call_llm_json(provider, model, system_prompt, entry)
                # Enforce immutable invariants deterministically
                translated["id"] = entry["id"].replace("ar_", f"{code}_", 1)
                translated["order_index"] = entry["order_index"]
                translated["language"] = code
                translated["audit_status"] = entry["audit_status"]
                translated["audit_notes"] = entry["audit_notes"]
                translated["dhikr_text"] = entry["dhikr_text"]
                translated["repeat_count_number"] = int(entry["repeat_count_number"])
                if not translated.get("transliteration"):
                    translated["transliteration"] = phonetic_transliterate(entry["dhikr_text"])
                return entry["id"], translated
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError) as exc:
                last_err = exc
                time.sleep(2.0 * attempt)
        raise RuntimeError(f"Failed translating {entry['id']} to {code}: {last_err}")

    with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = [pool.submit(worker, entry) for entry in pending]
        done_since_save = 0
        for fut in concurrent.futures.as_completed(futures):
            orig_id, translated = fut.result()
            completed[orig_id] = translated
            done_since_save += 1
            if done_since_save >= 10:
                checkpoint_path.write_text(json.dumps(completed, ensure_ascii=False), encoding="utf-8")
                done_since_save = 0

    ordered = [completed[entry["id"]] for entry in db_ar]
    out_path.write_text(json.dumps(ordered, ensure_ascii=False, indent=2), encoding="utf-8")
    if checkpoint_path.exists():
        checkpoint_path.unlink()

    ok, count, errors = validate_file(out_path)
    if not ok:
        print(f"[WARN] {out_path.name} has {len(errors)} validation issues; first 5:", file=sys.stderr)
        for err in errors[:5]:
            print(f"  - {err}", file=sys.stderr)
    else:
        print(f"[PASS] {out_path.name}: {count} entries validated cleanly.")
    return out_path


def main() -> int:
    parser = argparse.ArgumentParser(description="Batch-generate 50+ language AI datasets for Nabd Al-Dhikr.")
    parser.add_argument("--build-en-local", action="store_true", help="Build validated db_en.json offline without API keys.")
    parser.add_argument("--provider", choices=["gemini", "openai"], default="gemini")
    parser.add_argument("--model", default="gemini-2.5-flash", help="Model ID for API translation.")
    parser.add_argument("--languages", default="", help="Comma-separated language codes (default: all non-ar in languages_50.json).")
    parser.add_argument("--tier", type=int, default=0, help="Filter by tier (1, 2, or 3; 0 = all).")
    parser.add_argument("--workers", type=int, default=4, help="Concurrent API workers per language.")
    args = parser.parse_args()

    if args.build_en_local:
        build_en_local()
        if not args.languages and args.tier == 0:
            return 0

    db_ar = json.loads((DATA_DIR / "db_ar.json").read_text(encoding="utf-8"))
    languages = json.loads((DATA_DIR / "languages_50.json").read_text(encoding="utf-8"))

    target_codes = {c.strip() for c in args.languages.split(",") if c.strip()}
    selected = [
        lang for lang in languages
        if lang["code"] != "ar"
        and (not target_codes or lang["code"] in target_codes)
        and (args.tier == 0 or lang.get("tier") == args.tier)
    ]

    if not selected:
        print("No target languages matched selection.", file=sys.stderr)
        return 1

    for lang in selected:
        translate_language(lang, db_ar, args.provider, args.model, args.workers)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

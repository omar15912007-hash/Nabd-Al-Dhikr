#!/usr/bin/env python3
"""
Model Fine-Tuning, INT8 ONNX Quantization, Pre-Computed Vector Packing & Manifest Builder
for Nabd Al-Dhikr (نبض الذكر) Offline Multilingual AI.

Pipeline:
  1. Loads validated multilingual JSON datasets (`data/db_[lang].json`).
  2. Fine-tunes `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` on
     `(user_question, rich_dhikr_passage)` pairs using MultipleNegativesRankingLoss.
  3. Wraps backbone + Mean Pooling + L2 Normalization in a single ONNX graph
     (input: `input_ids`, `attention_mask` -> output: `sentence_embedding` [B, 384]).
  4. Quantizes to INT8 dynamic ONNX (`dist/nabd_ai_minilm.onnx`, ~35-42 MB) for fast
     ARM NEON inference via `com.microsoft.onnxruntime:onnxruntime-android`.
  5. Verifies cosine parity between PyTorch FP32 and quantized INT8 ONNX.
  6. Encodes all language datasets WITH the quantized ONNX model (zero drift) and packs
     L2-normalized embeddings into binary `dist/vectors_[lang].bin` + minified `dist/db_[lang].json`.
  7. Generates `dist/manifest.json` with byte sizes, SHA-256 checksums, and GitHub Release URLs.
"""

from __future__ import annotations

import argparse
import hashlib
import inspect
import json
import os
import shutil
import struct
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

os.environ.setdefault("WANDB_DISABLED", "true")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
import torch.nn.functional as F
from onnxruntime.quantization import QuantType, quantize_dynamic
from sentence_transformers import InputExample, SentenceTransformer, losses
from torch.utils.data import DataLoader
from transformers import AutoModel, AutoTokenizer

from validate_dataset import validate_file

PIPELINE_DIR = Path(__file__).resolve().parent
DATA_DIR = PIPELINE_DIR / "data"
DIST_DIR = PIPELINE_DIR / "dist"

BASE_MODEL_ID = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
DEFAULT_GITHUB_REPO = "omar15912007-hash/Nabd-Al-Dhikr"
DEFAULT_RELEASE_TAG = "ai-v1.0.0"
EMBEDDING_DIM = 384
MAX_SEQ_LEN = 128
VECTOR_MAGIC = b"NABD"
VECTOR_FORMAT_VERSION = 1


class NormalizedSentenceEncoder(nn.Module):
    """Wraps Transformer encoder + attention-masked Mean Pooling + L2 Normalization."""

    def __init__(self, transformer: nn.Module) -> None:
        super().__init__()
        self.transformer = transformer

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        outputs = self.transformer(input_ids=input_ids, attention_mask=attention_mask)
        token_embeddings = outputs.last_hidden_state
        mask_expanded = attention_mask.unsqueeze(-1).expand(token_embeddings.size()).float()
        sum_embeddings = torch.sum(token_embeddings * mask_expanded, dim=1)
        sum_mask = torch.clamp(mask_expanded.sum(dim=1), min=1e-9)
        mean_pooled = sum_embeddings / sum_mask
        return F.normalize(mean_pooled, p=2, dim=1)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_all_datasets(data_dir: Path) -> dict[str, list[dict[str, Any]]]:
    datasets: dict[str, list[dict[str, Any]]] = {}
    for path in sorted(data_dir.glob("db_*.json")):
        ok, count, errors = validate_file(path)
        if not ok:
            raise ValueError(f"Dataset {path.name} failed validation ({len(errors)} errors): {errors[:3]}")
        lang_code = path.stem.removeprefix("db_")
        datasets[lang_code] = json.loads(path.read_text(encoding="utf-8"))
        print(f"[DATA] Loaded {path.name}: {count} entries (lang={lang_code})")
    if not datasets:
        raise FileNotFoundError(f"No db_*.json files found in {data_dir}")
    return datasets


def build_training_examples(datasets: dict[str, list[dict[str, Any]]]) -> list[InputExample]:
    examples: list[InputExample] = []
    for entries in datasets.values():
        for entry in entries:
            anchor_passage = (
                f"{entry['category']}. {entry['short_virtue_for_card']} "
                f"{entry['detailed_explanation']}"
            )
            for qa in entry.get("qa_pairs", []):
                target_text = f"{qa.get('intent', '')}. {qa.get('bot_answer', '')} {anchor_passage}".strip()
                for question in qa.get("user_questions", []):
                    q = question.strip()
                    if q:
                        examples.append(InputExample(texts=[q, target_text]))
    return examples


def fine_tune_model(
    datasets: dict[str, list[dict[str, Any]]],
    work_dir: Path,
    epochs: int,
    batch_size: int,
    skip_train: bool,
) -> Path:
    tuned_dir = work_dir / "finetuned_minilm"
    tuned_dir.mkdir(parents=True, exist_ok=True)

    print(f"[MODEL] Loading base encoder: {BASE_MODEL_ID}")
    model = SentenceTransformer(BASE_MODEL_ID)

    if skip_train or epochs <= 0:
        print("[MODEL] Skipping fine-tuning (--skip-train / epochs=0); exporting pretrained weights.")
        model.save(str(tuned_dir))
        return tuned_dir

    examples = build_training_examples(datasets)
    print(f"[TRAIN] Fine-tuning on {len(examples)} multilingual query-dhikr pairs for {epochs} epoch(s)...")

    dataloader = DataLoader(examples, shuffle=True, batch_size=batch_size, drop_last=True)
    train_loss = losses.MultipleNegativesRankingLoss(model=model)
    warmup_steps = max(10, int(len(dataloader) * epochs * 0.1))

    model.fit(
        train_objectives=[(dataloader, train_loss)],
        epochs=epochs,
        warmup_steps=warmup_steps,
        output_path=str(tuned_dir),
        show_progress_bar=True,
    )
    print(f"[TRAIN] Saved fine-tuned SentenceTransformer to {tuned_dir}")
    return tuned_dir


def export_quantized_onnx(tuned_dir: Path, dist_dir: Path) -> tuple[Path, Path]:
    dist_dir.mkdir(parents=True, exist_ok=True)
    fp32_onnx = dist_dir / "_tmp_fp32.onnx"
    int8_onnx = dist_dir / "nabd_ai_minilm.onnx"
    tokenizer_out = dist_dir / "tokenizer.json"

    tokenizer = AutoTokenizer.from_pretrained(str(tuned_dir), use_fast=True)
    hf_model = AutoModel.from_pretrained(str(tuned_dir))
    hf_model.eval()

    wrapper = NormalizedSentenceEncoder(hf_model).eval()
    sample = tokenizer(
        ["ما فضل آية الكرسي في الصباح؟", "What dua should I read before sleep?"],
        padding=True,
        truncation=True,
        max_length=MAX_SEQ_LEN,
        return_tensors="pt",
    )

    print(f"[ONNX] Exporting FP32 graph (opset 17) to {fp32_onnx}...")
    export_kwargs: dict[str, Any] = {
        "input_names": ["input_ids", "attention_mask"],
        "output_names": ["sentence_embedding"],
        "dynamic_axes": {
            "input_ids": {0: "batch_size", 1: "sequence_length"},
            "attention_mask": {0: "batch_size", 1: "sequence_length"},
            "sentence_embedding": {0: "batch_size"},
        },
        "opset_version": 17,
        "do_constant_folding": True,
    }
    if "dynamo" in inspect.signature(torch.onnx.export).parameters:
        export_kwargs["dynamo"] = False

    with torch.no_grad():
        torch_embeddings = wrapper(sample["input_ids"], sample["attention_mask"]).numpy()
        torch.onnx.export(
            wrapper,
            (sample["input_ids"], sample["attention_mask"]),
            str(fp32_onnx),
            **export_kwargs,
        )

    print(f"[ONNX] Applying dynamic INT8 quantization -> {int8_onnx}...")
    try:
        quantize_dynamic(
            model_input=str(fp32_onnx),
            model_output=str(int8_onnx),
            weight_type=QuantType.QUInt8,
        )
    except Exception as quant_err:
        print(f"[ONNX] Full dynamic quant fallback ({quant_err}); quantizing MatMul/Gemm...")
        quantize_dynamic(
            model_input=str(fp32_onnx),
            model_output=str(int8_onnx),
            op_types_to_quantize=["MatMul", "Gemm"],
            weight_type=QuantType.QUInt8,
        )

    # Save tokenizer.json for pure-Kotlin Android tokenizer (zero native Rust tokenizer bloat)
    tokenizer_src = tuned_dir / "tokenizer.json"
    if not tokenizer_src.exists():
        tokenizer.save_pretrained(str(tuned_dir))
    shutil.copy2(tokenizer_src, tokenizer_out)

    # Verify quantized ONNX parity against FP32 PyTorch
    sess = ort.InferenceSession(str(int8_onnx), providers=["CPUExecutionProvider"])
    onnx_out = sess.run(
        ["sentence_embedding"],
        {
            "input_ids": sample["input_ids"].numpy().astype(np.int64),
            "attention_mask": sample["attention_mask"].numpy().astype(np.int64),
        },
    )[0]
    cos_scores = np.sum(torch_embeddings * onnx_out, axis=1)
    mean_parity = float(np.mean(cos_scores))
    print(f"[VERIFY] FP32 vs INT8 ONNX cosine parity: {mean_parity:.4f}")
    if mean_parity < 0.94:
        raise RuntimeError(f"Quantization parity degraded below 0.94 ({mean_parity:.4f})")

    if fp32_onnx.exists():
        fp32_onnx.unlink()

    size_mb = int8_onnx.stat().st_size / (1024 * 1024)
    print(f"[ONNX] Ready: {int8_onnx.name} ({size_mb:.2f} MB) + {tokenizer_out.name}")
    return int8_onnx, tokenizer_out


def encode_texts_onnx(
    session: ort.InferenceSession,
    tokenizer: Any,
    texts: list[str],
    batch_size: int = 64,
) -> np.ndarray:
    chunks: list[np.ndarray] = []
    for start in range(0, len(texts), batch_size):
        batch = texts[start : start + batch_size]
        encoded = tokenizer(
            batch,
            padding=True,
            truncation=True,
            max_length=MAX_SEQ_LEN,
            return_tensors="np",
        )
        vecs = session.run(
            ["sentence_embedding"],
            {
                "input_ids": encoded["input_ids"].astype(np.int64),
                "attention_mask": encoded["attention_mask"].astype(np.int64),
            },
        )[0].astype(np.float32)
        # Re-normalize defensively for exact unit length
        norms = np.linalg.norm(vecs, axis=1, keepdims=True)
        vecs = vecs / np.maximum(norms, 1e-9)
        chunks.append(vecs)
    return np.vstack(chunks) if chunks else np.zeros((0, EMBEDDING_DIM), dtype=np.float32)


def pack_language_vectors(
    lang_code: str,
    entries: list[dict[str, Any]],
    session: ort.InferenceSession,
    tokenizer: Any,
    dist_dir: Path,
    use_fp16: bool,
) -> tuple[Path, Path, int]:
    """
    Encodes all user_questions + dhikr intents for a language using the quantized ONNX model
    and writes:
      - `vectors_[lang].bin`:
          Header (20 bytes LE): magic("NABD"), version(u32=1), count(u32), dim(u32=384), dtype(u32: 0=fp32, 1=fp16)
          Index Table (count * 8 bytes LE): [dhikr_index: int32, qa_index: int32]
          Embeddings (count * dim * (4 or 2) bytes LE): L2-normalized vectors
      - `db_[lang].json` (minified for minimal download size)
    """
    texts: list[str] = []
    index_rows: list[tuple[int, int]] = []

    for d_idx, entry in enumerate(entries):
        # 1. Dhikr-level semantic anchor
        anchor = f"{entry['category']} — {entry['short_virtue_for_card']}"
        texts.append(anchor)
        index_rows.append((d_idx, -1))

        # 2. All curated user_questions per QA intent
        for q_idx, qa in enumerate(entry.get("qa_pairs", [])):
            for question in qa.get("user_questions", []):
                q = question.strip()
                if q:
                    texts.append(q)
                    index_rows.append((d_idx, q_idx))

    embeddings = encode_texts_onnx(session, tokenizer, texts)
    count, dim = embeddings.shape
    if dim != EMBEDDING_DIM:
        raise ValueError(f"Expected embedding dim {EMBEDDING_DIM}, got {dim}")

    dtype_flag = 1 if use_fp16 else 0
    vec_path = dist_dir / f"vectors_{lang_code}.bin"
    with vec_path.open("wb") as fh:
        fh.write(struct.pack("<4sIIII", VECTOR_MAGIC, VECTOR_FORMAT_VERSION, count, dim, dtype_flag))
        for dhikr_idx, qa_idx in index_rows:
            fh.write(struct.pack("<ii", dhikr_idx, qa_idx))
        if use_fp16:
            fh.write(embeddings.astype("<f2").tobytes())
        else:
            fh.write(embeddings.astype("<f4").tobytes())

    db_out = dist_dir / f"db_{lang_code}.json"
    db_out.write_text(json.dumps(entries, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")

    print(
        f"[PACK] {lang_code}: {count} vectors -> {vec_path.name} "
        f"({vec_path.stat().st_size / (1024 * 1024):.2f} MB, dtype={'fp16' if use_fp16 else 'fp32'})"
    )
    return vec_path, db_out, count


def generate_manifest(
    dist_dir: Path,
    model_path: Path,
    tokenizer_path: Path,
    packed_languages: list[dict[str, Any]],
    github_repo: str,
    release_tag: str,
) -> Path:
    base_url = f"https://github.com/{github_repo}/releases/download/{release_tag}"

    def asset_meta(path: Path) -> dict[str, Any]:
        return {
            "filename": path.name,
            "size_bytes": path.stat().st_size,
            "sha256": sha256_file(path),
            "url": f"{base_url}/{path.name}",
        }

    manifest = {
        "schema_version": 1,
        "release_tag": release_tag,
        "model_id": BASE_MODEL_ID,
        "embedding_dim": EMBEDDING_DIM,
        "similarity_threshold": 0.65,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "model": asset_meta(model_path),
        "tokenizer": asset_meta(tokenizer_path),
        "languages": packed_languages,
    }

    manifest_path = dist_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[MANIFEST] Wrote {manifest_path} with {len(packed_languages)} language pack(s)")
    return manifest_path


def main() -> int:
    parser = argparse.ArgumentParser(description="Fine-tune, quantize, and pack Nabd Al-Dhikr offline AI.")
    parser.add_argument("--data-dir", type=Path, default=DATA_DIR)
    parser.add_argument("--dist-dir", type=Path, default=DIST_DIR)
    parser.add_argument("--repo", default=DEFAULT_GITHUB_REPO, help="GitHub owner/repo for release URLs.")
    parser.add_argument("--tag", default=DEFAULT_RELEASE_TAG, help="GitHub release tag.")
    parser.add_argument("--epochs", type=int, default=2, help="Fine-tuning epochs.")
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--skip-train", action="store_true", help="Export base multilingual MiniLM without fine-tuning.")
    parser.add_argument("--fp16-vectors", action="store_true", help="Store pre-computed vectors in FP16 (half size).")
    args = parser.parse_args()

    lang_registry = {
        item["code"]: item
        for item in json.loads((DATA_DIR / "languages_50.json").read_text(encoding="utf-8"))
    }
    datasets = load_all_datasets(args.data_dir)
    work_dir = PIPELINE_DIR / "_build_work"
    tuned_dir = fine_tune_model(datasets, work_dir, args.epochs, args.batch_size, args.skip_train)
    model_path, tokenizer_path = export_quantized_onnx(tuned_dir, args.dist_dir)

    ort_session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    hf_tokenizer = AutoTokenizer.from_pretrained(str(tuned_dir), use_fast=True)
    base_url = f"https://github.com/{args.repo}/releases/download/{args.tag}"

    packed_languages: list[dict[str, Any]] = []
    for lang_code, entries in datasets.items():
        vec_path, db_out, vec_count = pack_language_vectors(
            lang_code=lang_code,
            entries=entries,
            session=ort_session,
            tokenizer=hf_tokenizer,
            dist_dir=args.dist_dir,
            use_fp16=args.fp16_vectors,
        )
        reg = lang_registry.get(
            lang_code,
            {"code": lang_code, "native_name": lang_code.upper(), "english_name": lang_code.upper(), "rtl": False},
        )
        packed_languages.append({
            "code": lang_code,
            "native_name": reg["native_name"],
            "english_name": reg["english_name"],
            "rtl": bool(reg.get("rtl", False)),
            "entry_count": len(entries),
            "vector_count": vec_count,
            "vectors": {
                "filename": vec_path.name,
                "size_bytes": vec_path.stat().st_size,
                "sha256": sha256_file(vec_path),
                "url": f"{base_url}/{vec_path.name}",
            },
            "dataset": {
                "filename": db_out.name,
                "size_bytes": db_out.stat().st_size,
                "sha256": sha256_file(db_out),
                "url": f"{base_url}/{db_out.name}",
            },
        })

    generate_manifest(args.dist_dir, model_path, tokenizer_path, packed_languages, args.repo, args.tag)
    print("[DONE] All AI model assets built successfully in:", args.dist_dir)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

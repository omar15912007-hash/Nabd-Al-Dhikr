#!/usr/bin/env python3
"""
Domain Fine-Tuning Script for Nabd AI ("نبض AI") — Gemma 3 (270M).

Fine-tunes the top transformer blocks of `unsloth/gemma-3-270m-it` on CPU using PyTorch
over a curated Arabic Islamic Adhkar curriculum built from:
  - `app/src/main/assets/azkar.json`
  - `ai_pipeline/data/db_ar.json`
  - Explicit conversational greetings, partial dhikr completions, sadness/distress
    counseling, repetition-count follow-ups, and anti-fatwa boundaries.

Saves updated weights back into `checkpoint_dir/model.safetensors` preserving the EXACT
key set and BF16 dtype required by `litert_torch.generative.examples.gemma3.convert_gemma3_to_tflite`.
"""

from __future__ import annotations

import argparse
import json
import random
import time
from pathlib import Path

import torch
from safetensors import safe_open
from safetensors.torch import save_file
from transformers import AutoModelForCausalLM, AutoTokenizer

SYSTEM_INSTRUCTION = (
    "أنت «نبض AI»، مساعد أذكار إسلامي حنون ودقيق.\n"
    "قواعد صارمة:\n"
    "1. قدّم مقدمة إنسانية دافئة ومختصرة تناسب مشاعر وسؤال المستخدم في سطر أو سطرين.\n"
    "2. لا تغيّر نص الذكر أو الحديث الموثّق ولا تخترع حديثاً أو عدد تكرار غير موجود في المرجع.\n"
    "3. لا تصدر أي فتوى فقهية أبداً."
)


def format_gemma_example(user_msg: str, verified_context: str, model_reply: str) -> tuple[str, str]:
    prompt = (
        f"<start_of_turn>user\n{SYSTEM_INSTRUCTION}\n\n"
        + (f"المرجع الموثّق:\n{verified_context}\n\n" if verified_context else "")
        + f"رسالة المستخدم:\n{user_msg}\n<end_of_turn>\n<start_of_turn>model\n"
    )
    full = f"{prompt}{model_reply}<end_of_turn>"
    return prompt, full


def build_training_examples(repo_root: Path) -> list[tuple[str, str]]:
    examples: list[tuple[str, str]] = []

    # 1. Greetings & conversational empathy (NEVER reply with a random dhikr card)
    greeting_pairs = [
        (
            "السلام عليكم ورحمة الله وبركاته",
            "محادثة تحية ودية بدون سحب ذكر عشوائي.",
            "وعليكم السلام ورحمة الله وبركاته، أهلاً بك يا طيب في «نبض AI». كيف حالك اليوم؟ يمكنك أن تحكي لي ما تشعر به أو تسألني عن أي ذكر وسأجيبك بدقة من السنة الصحيحة.",
        ),
        (
            "السلام عليكم و رحمة الله و بركاته",
            "محادثة تحية ودية بدون سحب ذكر عشوائي.",
            "وعليكم السلام ورحمة الله وبركاته، مرحباً بك في «نبض AI». بماذا تحب أن نبدأ اليوم؟",
        ),
        (
            "ازيك عامل ايه",
            "سؤال ترحيبي ودي.",
            "الحمد لله بخير، بارك الله فيك! أنا هنا دائماً لمساعدتك في الأذكار الصحيحة والأدعية المأثورة.",
        ),
        (
            "شكرا جزيلا جزاك الله خيرا",
            "شكر من المستخدم.",
            "وإياكم يا رب، بارك الله فيك ونفع بك! يسعدني دائماً خدمتك في كل وقت.",
        ),
    ]
    for _ in range(3):
        for u, ctx, a in greeting_pairs:
            examples.append(format_gemma_example(u, ctx, a))

    # 2. Sadness / Distress counseling & Hadith of Ibn Abbas completion
    sadness_context = (
        "1) دعاء الكرب والحزن (البخاري 6346 ومسلم): لَا إِلَهَ إِلاَّ اللَّهُ الْعَظِيمُ الْحَلِيمُ، لاَ إِلَهَ إِلاَّ اللَّهُ رَبُّ الْعَرْشِ الْعَظِيمِ، "
        "لاَ إِلَهَ إِلاَّ اللَّهُ رَبُّ السَّمَاوَاتِ وَرَبُّ الأَرْضِ وَرَبُّ الْعَرْشِ الْكَرِيمِ.\n"
        "2) دعاء ذهاب الهم (أحمد 3712): اللَّهُمَّ إِنِّي عَبْدُكَ... أَنْ تَجْعَلَ الْقُرْآنَ رَبِيعَ قَلْبِي."
    )
    sadness_pairs = [
        (
            "انا زعلان",
            sadness_context,
            "سلامة قلبك من كل ضيق وحزن؛ أسأل الله أن يشرح صدرك ويبدّل همّك فرجاً. هذا من أعظم ما يُقال عند الحزن والكرب في الصحيحين.",
        ),
        (
            "انا زعلان في حاجة معينة حصلتلي",
            sadness_context,
            "هوّن على قلبك، وأسأل الله العظيم أن يفرّج همّك في هذا الأمر تحديداً ويجبر خاطرك ويكتب لك الخير والسكينة.",
        ),
        (
            "لا اله الا العظيم الحليم لا اله الا الله و كمل",
            "القسم: دعاء الهم والحزن\nالنص الكامل للذكر: لَا إِلَهَ إِلاَّ اللَّهُ الْعَظِيمُ الْحَلِيمُ، لاَ إِلَهَ إِلاَّ اللَّهُ رَبُّ الْعَرْشِ الْعَظِيمِ، لاَ إِلَهَ إِلاَّ اللَّهُ رَبُّ السَّمَاوَاتِ وَرَبُّ الأَرْضِ وَرَبُّ الْعَرْشِ الْكَرِيمِ.\nالمصدر: صحيح البخاري ومسلم (رقم 6346)",
            "إليك التكملة الصحيحة الكاملة لحديث ابن عباس عند الكرب والحزن من الصحيحين، ويُقال بيقين وحضور قلب مرة واحدة ويُستحب تكراره.",
        ),
        (
            "اقوله كام مرة؟",
            "النص: لَا إِلَهَ إِلاَّ اللَّهُ الْعَظِيمُ الْحَلِيمُ...\nالتكرار: مرة واحدة ويُستحب تكراره بتدبّر (البخاري 6346 ومسلم).",
            "ثبت في السنة قولُه مرةً واحدةً عند الكرب والحزن بيقين وحضور قلب، ويُستحب تكراره ثلاث مرات فأكثر بخشوع حتى يطمئن القلب.",
        ),
    ]
    for _ in range(4):
        for u, ctx, a in sadness_pairs:
            examples.append(format_gemma_example(u, ctx, a))

    # 3. Anti-fatwa refusal reinforcement
    for q in ["هل يجوز هذا شرعا؟", "ما حكم الطلاق؟", "أعطني فتوى"]:
        examples.append(
            format_gemma_example(
                q,
                "سؤال خارج نطاق المساعد (فتوى فقهية).",
                "عذراً، «نبض AI» مخصّص فقط للأذكار النبوية الصحيحة وشرحها ومصادرها، ولا يقدّم فتاوى فقهية. يرجى الرجوع لأهل العلم المختصين.",
            )
        )

    # 4. Grounded Adhkar dataset from db_ar.json
    db_path = repo_root / "ai_pipeline" / "data" / "db_ar.json"
    if db_path.is_file():
        db_entries = json.loads(db_path.read_text(encoding="utf-8"))
        for entry in db_entries:
            ctx = (
                f"القسم: {entry['category']}\n"
                f"نص الذكر: {entry['dhikr_text'][:180]}\n"
                f"عدد التكرار: {entry['repeat_count_label']}\n"
                f"الفضل: {entry['short_virtue_for_card']}\n"
                f"المصدر: {entry['source_reference']}"
            )
            qa_list = entry.get("qa_pairs", [])
            if qa_list:
                questions = qa_list[0].get("user_questions", [])
                if questions:
                    examples.append(format_gemma_example(questions[0], ctx, entry["short_virtue_for_card"] + f" ويُقال {entry['repeat_count_label']}."))
                if len(questions) > 3:
                    examples.append(format_gemma_example(questions[3], ctx, qa_list[0].get("bot_answer", entry["short_virtue_for_card"])))

    random.seed(42)
    random.shuffle(examples)
    return examples


def train_and_update_checkpoint(checkpoint_dir: Path, repo_root: Path, steps: int, lr: float) -> None:
    safetensors_file = checkpoint_dir / "model.safetensors"
    if not safetensors_file.is_file():
        raise FileNotFoundError(f"Expected single model.safetensors at {safetensors_file}")

    # Snapshot exact original safetensors keys and dtypes so litert_torch sees zero extra/missing keys
    with safe_open(str(safetensors_file), framework="pt") as fp:
        orig_meta = {k: fp.get_tensor(k).dtype for k in fp.keys()}

    print(f"[TRAIN] Loading {checkpoint_dir} in float32 on CPU (original keys: {len(orig_meta)})...")
    tokenizer = AutoTokenizer.from_pretrained(str(checkpoint_dir))
    model = AutoModelForCausalLM.from_pretrained(str(checkpoint_dir), torch_dtype=torch.float32)

    # Freeze embeddings & lower layers (0..13); fine-tune ONLY top 4 layers (14..17) + final norm
    for param in model.parameters():
        param.requires_grad = False

    trainable_count = 0
    for name, param in model.named_parameters():
        if any(f"model.layers.{idx}." in name for idx in (14, 15, 16, 17)) or name == "model.norm.weight":
            param.requires_grad = True
            trainable_count += param.numel()

    print(f"[TRAIN] Trainable parameters (top 4 layers + norm): {trainable_count:,}")

    examples = build_training_examples(repo_root)
    print(f"[TRAIN] Built {len(examples)} Nabd AI curriculum examples. Running {steps} steps...")

    optimizer = torch.optim.AdamW(
        [p for p in model.parameters() if p.requires_grad],
        lr=lr,
        weight_decay=0.01,
    )

    model.train()
    t0 = time.time()
    losses: list[float] = []

    for step in range(steps):
        prompt_text, full_text = examples[step % len(examples)]
        prompt_ids = tokenizer(prompt_text, add_special_tokens=True, truncation=True, max_length=160)["input_ids"]
        enc = tokenizer(
            full_text,
            add_special_tokens=True,
            truncation=True,
            max_length=192,
            return_tensors="pt",
        )
        input_ids = enc["input_ids"]
        labels = input_ids.clone()
        mask_len = min(len(prompt_ids), input_ids.shape[1] - 1)
        labels[:, :mask_len] = -100

        optimizer.zero_grad()
        outputs = model(input_ids=input_ids, labels=labels)
        loss = outputs.loss
        loss.backward()
        torch.nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad], max_norm=1.0)
        optimizer.step()

        losses.append(float(loss.detach()))
        if (step + 1) % 10 == 0 or step == 0:
            avg_recent = sum(losses[-10:]) / len(losses[-10:])
            print(f"[STEP {step + 1:3d}/{steps}] loss={loss.item():.4f} avg10={avg_recent:.4f} elapsed={time.time() - t0:.1f}s")

    first_avg = sum(losses[:min(10, len(losses))]) / min(10, len(losses))
    last_avg = sum(losses[-min(10, len(losses)):]) / min(10, len(losses))
    print(f"[TRAIN] Completed in {time.time() - t0:.1f}s | initial_avg10={first_avg:.4f} -> final_avg10={last_avg:.4f}")

    # Export updated state dict using the EXACT original key names and BF16 dtype
    model_state = model.state_dict()
    updated_tensors = {
        key: model_state[key].detach().to(orig_dtype).cpu().contiguous()
        for key, orig_dtype in orig_meta.items()
    }

    tmp_path = checkpoint_dir / "model.safetensors.tmp"
    save_file(updated_tensors, str(tmp_path), metadata={"format": "pt"})
    tmp_path.replace(safetensors_file)
    print(f"[SAVE] Updated {safetensors_file} preserving all {len(updated_tensors)} keys cleanly.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Fine-tune Gemma 3 (270M) for Nabd AI before LiteRT conversion.")
    parser.add_argument("--checkpoint_dir", type=Path, required=True)
    parser.add_argument("--repo_root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--steps", type=int, default=60)
    parser.add_argument("--lr", type=float, default=2e-5)
    args = parser.parse_args()

    train_and_update_checkpoint(
        checkpoint_dir=args.checkpoint_dir,
        repo_root=args.repo_root,
        steps=args.steps,
        lr=args.lr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

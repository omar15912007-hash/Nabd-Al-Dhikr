# Nabd Al-Dhikr — Offline Multilingual AI Pipeline

## 1. Audit & Generate Arabic + English Datasets
```powershell
python ai_pipeline/generate_db_ar.py
python ai_pipeline/batch_translate_datasets.py --build-en-local
python ai_pipeline/validate_dataset.py
```

## 2. Generate Remaining 50 Languages (`db_[lang].json`)
```powershell
$env:GEMINI_API_KEY = "<your_gemini_api_key>"
# Tier 1 priority languages (ur, id, tr, fr, ms, bn, fa, ru, de, es):
python ai_pipeline/batch_translate_datasets.py --provider gemini --tier 1 --workers 4
# Or all 50+ languages:
python ai_pipeline/batch_translate_datasets.py --provider gemini --workers 4
```

## 3. Fine-Tune, Quantize INT8 ONNX & Pre-Compute `vectors_[lang].bin`
```powershell
pip install -r ai_pipeline/requirements.txt
python ai_pipeline/prepare_model.py --repo omar15912007-hash/Nabd-Al-Dhikr --tag ai-v1.0.0 --epochs 2
```
Outputs in `ai_pipeline/dist/`:
- `nabd_ai_minilm.onnx` (~35–42 MB INT8 ONNX)
- `tokenizer.json`
- `vectors_[lang].bin` (pre-computed unit-normalized embeddings)
- `db_[lang].json` (minified audited datasets)
- `manifest.json` (file sizes, SHA-256 hashes, GitHub Release URLs)

## 4. Upload to GitHub Releases
Option A — Automated Python uploader:
```powershell
$env:GITHUB_TOKEN = "<your_github_pat>"
python ai_pipeline/upload_to_github.py --repo omar15912007-hash/Nabd-Al-Dhikr --tag ai-v1.0.0
```

Option B — GitHub CLI (`gh`):
```powershell
python ai_pipeline/upload_to_github.py --print-gh-cli
```

Option C — Automated GitHub Actions:
Trigger `.github/workflows/deploy_ai.yml` directly from GitHub Actions (`Run workflow`) — zero local GPU/CPU needed!

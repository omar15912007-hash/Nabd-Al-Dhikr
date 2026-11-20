#!/usr/bin/env python3
"""
Automated GitHub Releases AI Asset Uploader for Nabd Al-Dhikr (نبض الذكر).

Uses zero external dependencies (Python standard library only) and reads `GITHUB_TOKEN`
securely from the environment (never logs or hardcodes tokens).

Uploads:
  - nabd_ai_minilm.onnx
  - tokenizer.json
  - manifest.json
  - vectors_[lang].bin
  - db_[lang].json

Also supports `--print-gh-cli` to output the exact equivalent `gh release create` commands.
"""

from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

DEFAULT_REPO = "omar15912007-hash/Nabd-Al-Dhikr"
DEFAULT_TAG = "ai-v1.0.0"
DEFAULT_DIST_DIR = Path(__file__).resolve().parent / "dist"


def collect_release_assets(dist_dir: Path) -> list[Path]:
    if not dist_dir.exists():
        raise FileNotFoundError(f"Distribution directory does not exist: {dist_dir}")

    assets: list[Path] = []
    for required in ("nabd_ai_minilm.onnx", "tokenizer.json", "manifest.json"):
        candidate = dist_dir / required
        if candidate.exists():
            assets.append(candidate)
    assets.extend(sorted(dist_dir.glob("vectors_*.bin")))
    assets.extend(sorted(dist_dir.glob("db_*.json")))

    if not assets:
        raise FileNotFoundError(f"No AI release assets found in {dist_dir}. Run prepare_model.py first.")
    return assets


def print_gh_cli_commands(repo: str, tag: str, dist_dir: Path) -> None:
    print("# === Exact GitHub CLI (`gh`) Deployment Commands ===")
    print(f"# Ensure GITHUB_TOKEN is exported or run `gh auth login` first:")
    print(
        f'gh release create "{tag}" \\\n'
        f'  --repo "{repo}" \\\n'
        f'  --title "Nabd Al-Dhikr Offline AI Pack ({tag})" \\\n'
        f'  --notes "Quantized INT8 MiniLM-L12-v2 ONNX model, tokenizer, manifest.json, and pre-computed multilingual vectors." \\\n'
        f'  "{dist_dir / "nabd_ai_minilm.onnx"}" \\\n'
        f'  "{dist_dir / "tokenizer.json"}" \\\n'
        f'  "{dist_dir / "manifest.json"}" \\\n'
        f'  "{dist_dir}"/vectors_*.bin \\\n'
        f'  "{dist_dir}"/db_*.json'
    )


def github_api_request(
    url: str,
    token: str,
    method: str = "GET",
    payload: bytes | None = None,
    content_type: str = "application/json",
) -> Any:
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "NabdAlDhikr-AI-Uploader/1.0",
    }
    if payload is not None:
        headers["Content-Type"] = content_type

    req = urllib.request.Request(url, data=payload, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=300) as resp:
        raw = resp.read()
        if not raw:
            return None
        return json.loads(raw.decode("utf-8"))


def ensure_release(repo: str, tag: str, token: str) -> dict[str, Any]:
    api_base = f"https://api.github.com/repos/{repo}/releases"
    try:
        release = github_api_request(f"{api_base}/tags/{urllib.parse.quote(tag)}", token)
        print(f"[GITHUB] Found existing release '{tag}' (id={release['id']})")
        return release
    except urllib.error.HTTPError as err:
        if err.code != 404:
            raise

    print(f"[GITHUB] Creating release '{tag}' in {repo}...")
    body = json.dumps({
        "tag_name": tag,
        "name": f"Nabd Al-Dhikr Offline AI ({tag})",
        "body": (
            "### Nabd Al-Dhikr Multilingual Offline AI Assets\n\n"
            "- **Model:** `nabd_ai_minilm.onnx` (Quantized INT8 `paraphrase-multilingual-MiniLM-L12-v2`)\n"
            "- **Tokenizer:** `tokenizer.json`\n"
            "- **Manifest:** `manifest.json` (SHA-256 verified)\n"
            "- **Language Packs:** `vectors_[lang].bin` + `db_[lang].json`"
        ),
        "draft": False,
        "prerelease": False,
    }).encode("utf-8")
    return github_api_request(api_base, token, method="POST", payload=body)


def upload_asset(release: dict[str, Any], file_path: Path, token: str) -> None:
    # Delete existing asset with identical filename for idempotent re-uploads
    for existing in release.get("assets", []):
        if existing["name"] == file_path.name:
            print(f"[GITHUB] Removing previous asset: {file_path.name}")
            github_api_request(existing["url"], token, method="DELETE")
            break

    upload_url_template = release["upload_url"].split("{")[0]
    upload_url = f"{upload_url_template}?name={urllib.parse.quote(file_path.name)}"
    mime_type, _ = mimetypes.guess_type(str(file_path))
    content_type = mime_type or "application/octet-stream"

    data = file_path.read_bytes()
    size_mb = len(data) / (1024 * 1024)
    print(f"[UPLOAD] {file_path.name} ({size_mb:.2f} MB) -> {release['tag_name']} ...")
    result = github_api_request(
        upload_url,
        token=token,
        method="POST",
        payload=data,
        content_type=content_type,
    )
    print(f"[OK]     {result['browser_download_url']}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Upload Nabd Al-Dhikr AI assets to GitHub Releases.")
    parser.add_argument("--repo", default=DEFAULT_REPO, help="Target GitHub repository (owner/repo).")
    parser.add_argument("--tag", default=DEFAULT_TAG, help="Release tag name (e.g., ai-v1.0.0).")
    parser.add_argument("--dist-dir", type=Path, default=DEFAULT_DIST_DIR)
    parser.add_argument("--print-gh-cli", action="store_true", help="Print equivalent `gh release create` CLI command.")
    args = parser.parse_args()

    if args.print_gh_cli:
        print_gh_cli_commands(args.repo, args.tag, args.dist_dir)
        return 0

    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        print(
            "ERROR: GITHUB_TOKEN environment variable is not set.\n"
            "Set it in PowerShell with: $env:GITHUB_TOKEN = '<your_pat_token>'",
            file=sys.stderr,
        )
        return 1

    assets = collect_release_assets(args.dist_dir)
    release = ensure_release(args.repo, args.tag, token)
    for asset in assets:
        upload_asset(release, asset, token)

    print(f"\n[COMPLETE] Uploaded {len(assets)} AI asset(s) to https://github.com/{args.repo}/releases/tag/{args.tag}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

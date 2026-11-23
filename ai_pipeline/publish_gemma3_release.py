#!/usr/bin/env python3
"""
100% Free GitHub Releases Publisher for Gemma 3 (270M) On-Device Model (`gemma3-270m-nabd.task`).

Uses Python standard library ONLY (zero external dependencies) and reads `GITHUB_TOKEN`
from the environment (never logs or hardcodes tokens).

Workflow:
  1. Verifies the local `.task` file (~240MB Gemma 3 270M MediaPipe bundle).
  2. Computes exact byte size and SHA-256 checksum.
  3. Generates `gemma_manifest.json` matching `GemmaManifest` in `AiModels.kt`.
  4. Creates/updates GitHub Release `gemma3-v1.0.0` and uploads both files idempotently.
  5. Supports `--print-gh-cli` to output equivalent `gh release create` commands.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

DEFAULT_REPO = "omar15912007-hash/Nabd-Al-Dhikr"
DEFAULT_TAG = "ai-v1.0.3"
DEFAULT_FILENAME = "gemma3-270m-nabd.task"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_manifest(model_path: Path, repo: str, tag: str) -> Path:
    manifest = {
        "schema_version": 1,
        "release_tag": tag,
        "model_name": "Gemma 3 (270M) INT8",
        "filename": DEFAULT_FILENAME,
        "size_bytes": model_path.stat().st_size,
        "sha256": sha256_file(model_path),
        "url": f"https://github.com/{repo}/releases/download/{tag}/{DEFAULT_FILENAME}",
        "min_ram_gb": 3.0,
        "min_storage_mb": 450,
    }
    manifest_path = model_path.parent / "gemma_manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[MANIFEST] Wrote {manifest_path} (size={manifest['size_bytes'] / (1024*1024):.2f} MB, sha256={manifest['sha256'][:12]}...)")
    return manifest_path


def github_request(
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
        "User-Agent": "NabdAlDhikr-Gemma3-Publisher/1.0",
    }
    if payload is not None:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=payload, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=600) as resp:
        raw = resp.read()
        return json.loads(raw.decode("utf-8")) if raw else None


def ensure_release(repo: str, tag: str, token: str) -> dict[str, Any]:
    api_base = f"https://api.github.com/repos/{repo}/releases"
    try:
        return github_request(f"{api_base}/tags/{urllib.parse.quote(tag)}", token)
    except urllib.error.HTTPError as err:
        if err.code != 404:
            raise
    body = json.dumps({
        "tag_name": tag,
        "name": f"نبض AI — نموذج Gemma 3 (270M) ({tag})",
        "body": (
            "### نموذج Gemma 3 (270M) المحلي لتطبيق نبض الذكر (مجاني مدى الحياة)\n\n"
            "- **النموذج:** `gemma3-270m-nabd.task` (~240 MB MediaPipe GenAI bundle)\n"
            "- **الفهرس الأمني:** `gemma_manifest.json` (بصمة SHA-256)\n"
            "- **الحد الأدنى للجهاز:** 3 جيجابايت RAM، 450 ميجابايت مساحة فارغة، Android 8.0+، معالج `arm64-v8a`."
        ),
        "draft": False,
        "prerelease": False,
    }).encode("utf-8")
    return github_request(api_base, token, method="POST", payload=body)


def upload_asset(release: dict[str, Any], path: Path, asset_name: str, token: str) -> None:
    for existing in release.get("assets", []):
        if existing["name"] == asset_name:
            github_request(existing["url"], token, method="DELETE")
            break
    upload_base = release["upload_url"].split("{")[0]
    upload_url = f"{upload_base}?name={urllib.parse.quote(asset_name)}"
    data = path.read_bytes()
    print(f"[UPLOAD] {asset_name} ({len(data)/(1024*1024):.2f} MB) -> {release['tag_name']} ...")
    res = github_request(upload_url, token, method="POST", payload=data, content_type="application/octet-stream")
    print(f"[OK]     {res['browser_download_url']}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Publish Gemma 3 (270M) .task model to GitHub Releases.")
    parser.add_argument("--model", type=Path, help="Path to local gemma3-270m .task bundle (~240-290MB).")
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--tag", default=DEFAULT_TAG)
    parser.add_argument(
        "--mirror-tags",
        default="",
        help="Optional comma-separated extra release tags to mirror the same model + manifest to (e.g. gemma3-v1.0.0).",
    )
    parser.add_argument("--print-gh-cli", action="store_true")
    args = parser.parse_args()

    if args.print_gh_cli:
        print(
            f'gh release create "{args.tag}" --repo "{args.repo}" \\\n'
            f'  --title "Nabd AI — Gemma 3 (270M) ({args.tag})" \\\n'
            f'  --notes "On-device Gemma 3 (270M) MediaPipe .task model + SHA-256 manifest." \\\n'
            f'  "{DEFAULT_FILENAME}" "gemma_manifest.json"'
        )
        return 0

    if not args.model or not args.model.is_file():
        print("ERROR: Provide --model /path/to/gemma3-270m-it-int8.task", file=sys.stderr)
        return 1

    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token:
        print("ERROR: Set GITHUB_TOKEN environment variable first.", file=sys.stderr)
        return 1

    target_tags: list[str] = [args.tag]
    for extra in args.mirror_tags.split(","):
        cleaned = extra.strip()
        if cleaned and cleaned not in target_tags:
            target_tags.append(cleaned)

    for tag in target_tags:
        manifest_path = build_manifest(args.model, args.repo, tag)
        release = ensure_release(args.repo, tag, token)
        upload_asset(release, args.model, DEFAULT_FILENAME, token)
        upload_asset(release, manifest_path, "gemma_manifest.json", token)
        print(f"[DONE] Published Gemma 3 (270M) to https://github.com/{args.repo}/releases/tag/{tag}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

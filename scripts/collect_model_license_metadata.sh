#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

OUT_FILE="${OUT_FILE:-docs/model_license_metadata.json}"
REVIEW_FILE="${REVIEW_FILE:-docs/model_license_review.json}"

if [[ ! -f "$REVIEW_FILE" ]]; then
  echo "Missing model license review file: $REVIEW_FILE" >&2
  exit 1
fi

python3 - "$REVIEW_FILE" "$OUT_FILE" <<'PY'
import datetime
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path

review_path = Path(sys.argv[1])
out_path = Path(sys.argv[2])
review = json.loads(review_path.read_text())

def repo_id_from_url(url: str) -> str:
    prefix = "https://huggingface.co/"
    if not url.startswith(prefix):
        raise ValueError(f"unsupported model URL: {url}")
    return url[len(prefix):].strip("/")

records = []
for model in review.get("models", []):
    repo_id = repo_id_from_url(model["licenseUrl"])
    api_url = f"https://huggingface.co/api/models/{repo_id}"
    with urllib.request.urlopen(api_url, timeout=30) as response:
        metadata = json.loads(response.read().decode("utf-8"))
    tags = metadata.get("tags") or []
    license_tags = [
        tag.removeprefix("license:")
        for tag in tags
        if isinstance(tag, str) and tag.startswith("license:")
    ]
    card_data = metadata.get("cardData") or {}
    card_license = card_data.get("license") if isinstance(card_data, dict) else None
    records.append({
        "id": model["id"],
        "repository": repo_id,
        "apiUrl": api_url,
        "modelSha": metadata.get("sha", ""),
        "lastModified": metadata.get("lastModified", ""),
        "gated": bool(metadata.get("gated", False)),
        "licenseTags": license_tags,
        "cardLicense": card_license or "",
        "manualReviewStatus": model.get("status", ""),
        "redistributionDecision": model.get("redistributionDecision", ""),
        "metadataOnly": True,
    })

out = {
    "version": 1,
    "recordedAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "source": "Hugging Face model API",
    "policy": "Metadata collection is not legal approval; docs/model_license_review.json remains the release gate.",
    "models": records,
}
out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
PY

echo "Model license metadata written to $OUT_FILE"

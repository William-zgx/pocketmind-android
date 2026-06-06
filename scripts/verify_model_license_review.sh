#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REVIEW_FILE="${MODEL_LICENSE_REVIEW_FILE:-docs/model_license_review.json}"
METADATA_FILE="${MODEL_LICENSE_METADATA_FILE:-docs/model_license_metadata.json}"
REPORT_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report)
      REPORT_FILE="${2:?missing report path}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

write_report() {
  local status="$1"
  local reason="$2"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=model-license-review\n'
      printf 'reviewFile=%s\n' "$REVIEW_FILE"
      printf 'metadataFile=%s\n' "$METADATA_FILE"
      printf 'reason=%s\n' "$reason"
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$REVIEW_FILE" ]]; then
  write_report failed missing-review-file
  echo "Model license review file is missing: $REVIEW_FILE" >&2
  exit 1
fi

if [[ ! -f "$METADATA_FILE" ]]; then
  write_report failed missing-metadata-file
  echo "Model license metadata file is missing: $METADATA_FILE" >&2
  exit 1
fi

TMP_FAILURES="$(mktemp)"
trap 'rm -f "$TMP_FAILURES"' EXIT

set +e
python3 - "$REVIEW_FILE" "$METADATA_FILE" <<'PY' > "$TMP_FAILURES"
import json
import re
import sys
from datetime import date
from pathlib import Path

review_path = Path(sys.argv[1])
metadata_path = Path(sys.argv[2])

try:
    review = json.loads(review_path.read_text())
    metadata = json.loads(metadata_path.read_text())
except Exception:
    print("json-parse-error")
    sys.exit(1)

failures = []
if review.get("version") != 1:
    failures.append("review-version-invalid")
if metadata.get("version") != 1:
    failures.append("metadata-version-invalid")

review_models = review.get("models")
metadata_models = metadata.get("models")
if not isinstance(review_models, list) or not review_models:
    failures.append("review-models-missing")
    review_models = []
if not isinstance(metadata_models, list) or not metadata_models:
    failures.append("metadata-models-missing")
    metadata_models = []

metadata_ids = []
for entry in metadata_models:
    if not isinstance(entry, dict):
        failures.append("metadata-entry-invalid")
        continue
    model_id = entry.get("id", "")
    if not model_id:
        failures.append("metadata-id-missing")
    metadata_ids.append(model_id)
    if entry.get("metadataOnly") is not True:
        failures.append(f"{model_id or 'unknown'}-metadata-only-not-true")

review_ids = []
seen_ids = set()
date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")
today = date.today()
for entry in review_models:
    if not isinstance(entry, dict):
        failures.append("review-entry-invalid")
        continue
    model_id = entry.get("id", "")
    if not model_id:
        failures.append("review-id-missing")
    elif model_id in seen_ids:
        failures.append(f"{model_id}-duplicate")
    seen_ids.add(model_id)
    review_ids.append(model_id)

    if entry.get("status") != "approved":
        failures.append(f"{model_id or 'unknown'}-status-not-approved")
    if entry.get("redistributionDecision") != "approved":
        failures.append(f"{model_id or 'unknown'}-redistribution-not-approved")
    if not entry.get("licenseName"):
        failures.append(f"{model_id or 'unknown'}-license-name-missing")

    license_source = entry.get("licenseUrl", "")
    if not license_source:
        failures.append(f"{model_id or 'unknown'}-license-source-missing")
    elif not license_source.startswith("https://") and not Path(license_source).is_file():
        failures.append(f"{model_id or 'unknown'}-license-source-invalid")

    if not entry.get("attributionNotice"):
        failures.append(f"{model_id or 'unknown'}-attribution-notice-missing")
    if not entry.get("reviewer"):
        failures.append(f"{model_id or 'unknown'}-reviewer-missing")
    review_date = entry.get("reviewDate", "")
    if not review_date:
        failures.append(f"{model_id or 'unknown'}-review-date-missing")
    elif not date_pattern.match(review_date):
        failures.append(f"{model_id or 'unknown'}-review-date-invalid")
    else:
        try:
            parsed_date = date.fromisoformat(review_date)
        except ValueError:
            failures.append(f"{model_id or 'unknown'}-review-date-invalid")
        else:
            if parsed_date > today:
                failures.append(f"{model_id or 'unknown'}-review-date-in-future")

if metadata_ids and review_ids != metadata_ids:
    failures.append("review-model-ids-do-not-match-metadata")

if failures:
    print(",".join(failures))
    sys.exit(1)

print("approved")
PY
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  reason="$(cat "$TMP_FAILURES")"
  write_report failed "${reason:-incomplete-license-review}"
  echo "Model license review is incomplete." >&2
  exit 1
fi

write_report passed approved
echo "Model license review verification passed."

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

REVIEW_FILE="${PRIVACY_REVIEW_FILE:-docs/privacy_review.json}"
NOTICE_FILE="${PRIVACY_NOTICE_FILE:-docs/privacy_notice.md}"
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
      printf 'target=privacy-review\n'
      printf 'reviewFile=%s\n' "$REVIEW_FILE"
      printf 'noticeFile=%s\n' "$NOTICE_FILE"
      printf 'reason=%s\n' "$reason"
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$REVIEW_FILE" ]]; then
  write_report failed missing-review-file
  echo "Privacy review file is missing: $REVIEW_FILE" >&2
  exit 1
fi

if [[ ! -f "$NOTICE_FILE" ]]; then
  write_report failed missing-notice-file
  echo "Privacy notice file is missing: $NOTICE_FILE" >&2
  exit 1
fi

TMP_FAILURES="$(mktemp)"
trap 'rm -f "$TMP_FAILURES"' EXIT

set +e
python3 - "$REVIEW_FILE" "$NOTICE_FILE" > "$TMP_FAILURES" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

review_path = Path(sys.argv[1])
notice_path = Path(sys.argv[2])
review = json.loads(review_path.read_text())
notice_sha = hashlib.sha256(notice_path.read_bytes()).hexdigest()

failures = []
if review.get("status") != "approved":
    failures.append("status-not-approved")
if review.get("noticePath") != str(notice_path):
    failures.append("notice-path-mismatch")
if review.get("noticeSha256") != notice_sha:
    failures.append("notice-sha-mismatch")

reviews = review.get("reviews")
if not isinstance(reviews, list):
    failures.append("reviews-missing")
    reviews = []

required_roles = {"release", "security", "legal"}
seen_roles = set()
for entry in reviews:
    if not isinstance(entry, dict):
        failures.append("review-entry-invalid")
        continue
    role = entry.get("role", "")
    seen_roles.add(role)
    if entry.get("decision") != "approved":
        failures.append(f"{role or 'unknown'}-decision-not-approved")
    if not entry.get("reviewer"):
        failures.append(f"{role or 'unknown'}-reviewer-missing")
    if not entry.get("reviewDate"):
        failures.append(f"{role or 'unknown'}-review-date-missing")

for role in sorted(required_roles - seen_roles):
    failures.append(f"{role}-review-missing")

if failures:
    print(",".join(failures))
    sys.exit(1)
print("approved")
PY
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  reason="$(cat "$TMP_FAILURES")"
  write_report failed "${reason:-incomplete-review}"
  echo "Privacy review is incomplete." >&2
  exit 1
fi

write_report passed approved
echo "Privacy review verification passed."

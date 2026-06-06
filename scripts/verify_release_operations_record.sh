#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

OPERATIONS_RECORD_FILE="${OPERATIONS_RECORD_FILE:-docs/release_operations_record.json}"
REPORT_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      OPERATIONS_RECORD_FILE="${2:?missing operations record file}"
      shift 2
      ;;
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
      printf 'target=release-operations-record\n'
      printf 'operationsRecordFile=%s\n' "$OPERATIONS_RECORD_FILE"
      printf 'reason=%s\n' "$reason"
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$OPERATIONS_RECORD_FILE" ]]; then
  write_report failed missing-operations-record-file
  echo "Release operations record file is missing: $OPERATIONS_RECORD_FILE" >&2
  exit 1
fi

TMP_FAILURES="$(mktemp)"
trap 'rm -f "$TMP_FAILURES"' EXIT

set +e
python3 - "$OPERATIONS_RECORD_FILE" > "$TMP_FAILURES" <<'PY'
import hashlib
import json
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

record_path = Path(sys.argv[1])

try:
    record = json.loads(record_path.read_text())
except Exception:
    print("json-parse-error")
    sys.exit(1)

def git_success(*args):
    try:
        subprocess.check_call(["git", *args], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except Exception:
        return False

def non_empty_string(value):
    return isinstance(value, str) and bool(value.strip())

def number_between(value, minimum, maximum):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and minimum <= value <= maximum

def validate_evidence_file(section, entry):
    if not isinstance(entry, dict):
        failures.append(f"{section}-evidence-missing")
        return
    evidence_path = entry.get("path", "")
    expected_sha = entry.get("sha256", "")
    if not non_empty_string(evidence_path):
        failures.append(f"{section}-evidence-path-missing")
        return
    path = Path(evidence_path)
    if not path.is_file():
        failures.append(f"{section}-evidence-file-missing")
        return
    actual_sha = hashlib.sha256(path.read_bytes()).hexdigest()
    if not non_empty_string(expected_sha):
        failures.append(f"{section}-evidence-sha-missing")
    elif expected_sha != actual_sha:
        failures.append(f"{section}-evidence-sha-mismatch")

failures = []
if record.get("version") != 1:
    failures.append("version-invalid")
if record.get("status") != "approved":
    failures.append("status-not-approved")

monitoring = record.get("monitoring")
if not isinstance(monitoring, dict):
    failures.append("monitoring-missing")
    monitoring = {}
if not non_empty_string(monitoring.get("owner")):
    failures.append("monitoring-owner-missing")
sources = monitoring.get("signalSources")
if not isinstance(sources, list) or not all(non_empty_string(source) for source in sources):
    failures.append("monitoring-signal-sources-missing")
    sources = []
if "Android Vitals" not in sources:
    failures.append("android-vitals-source-missing")
if not non_empty_string(monitoring.get("first24HoursWatcher")):
    failures.append("first-24-hours-watcher-missing")
if not number_between(monitoring.get("crashFreeRateThresholdPercent"), 90, 100):
    failures.append("crash-free-threshold-invalid")
if not number_between(monitoring.get("anrRateThresholdPercent"), 0, 10):
    failures.append("anr-threshold-invalid")
if monitoring.get("privacyReviewedForCrashSdk") is not True:
    failures.append("crash-sdk-privacy-review-not-confirmed")
validate_evidence_file("monitoring", monitoring.get("evidence"))

smoke = record.get("crashAnrSmoke")
if not isinstance(smoke, dict):
    failures.append("crash-anr-smoke-missing")
    smoke = {}
for field in ("window", "track", "failureEvidencePolicy"):
    if not non_empty_string(smoke.get(field)):
        failures.append(f"crash-anr-smoke-{field}-missing")
for field in (
    "noLaunchCrash",
    "noInstallCrash",
    "noCrashLoop",
    "noFatalNativeLiteRtLmFailure",
    "noReproducibleAnr",
):
    if smoke.get(field) is not True:
        failures.append(f"{field}-not-true")
validate_evidence_file("crash-anr-smoke", smoke.get("evidence"))

rollback = record.get("rollback")
if not isinstance(rollback, dict):
    failures.append("rollback-missing")
    rollback = {}
for field in (
    "owner",
    "decisionChannel",
    "firstStagedRolloutAction",
    "playVersionCodePolicy",
    "modelManifestRollbackPath",
    "userDataCompatibility",
):
    if not non_empty_string(rollback.get(field)):
        failures.append(f"rollback-{field}-missing")

criteria = rollback.get("criteria")
required_criteria = {
    "install failure",
    "crash loop",
    "model download verification failure",
    "privacy boundary failure",
    "critical tool execution regression",
}
if not isinstance(criteria, list):
    failures.append("rollback-criteria-missing")
    criteria = []
criteria_set = {criterion for criterion in criteria if isinstance(criterion, str)}
for criterion in sorted(required_criteria - criteria_set):
    failures.append("rollback-criterion-missing-" + re.sub(r"[^a-z0-9]+", "-", criterion.lower()).strip("-"))
validate_evidence_file("rollback", rollback.get("evidence"))

previous = rollback.get("previousKnownGood")
if not isinstance(previous, dict):
    failures.append("previous-known-good-missing")
    previous = {}
previous_status = previous.get("status")
if previous_status == "not_applicable_initial_release":
    if not non_empty_string(previous.get("releaseNotes")):
        failures.append("previous-known-good-release-notes-missing")
elif previous_status == "available":
    version_code = previous.get("versionCode")
    if not isinstance(version_code, int) or version_code <= 0:
        failures.append("previous-known-good-version-code-invalid")
    if not non_empty_string(previous.get("versionName")):
        failures.append("previous-known-good-version-name-missing")
    commit = previous.get("gitCommit", "")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        failures.append("previous-known-good-git-commit-invalid")
    elif not git_success("cat-file", "-e", f"{commit}^{{commit}}"):
        failures.append("previous-known-good-git-commit-missing")
    artifact_path = Path(previous.get("artifactPath", ""))
    if not previous.get("artifactPath"):
        failures.append("previous-known-good-artifact-path-missing")
    elif not artifact_path.is_file():
        failures.append("previous-known-good-artifact-missing")
    else:
        actual_sha = hashlib.sha256(artifact_path.read_bytes()).hexdigest()
        if previous.get("artifactSha256") != actual_sha:
            failures.append("previous-known-good-artifact-sha-mismatch")
    if not non_empty_string(previous.get("releaseNotes")):
        failures.append("previous-known-good-release-notes-missing")
else:
    failures.append("previous-known-good-status-invalid")

review = record.get("review")
if not isinstance(review, dict):
    failures.append("review-missing")
    review = {}
if not non_empty_string(review.get("reviewer")):
    failures.append("reviewer-missing")
review_date = review.get("reviewDate", "")
date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")
if not review_date:
    failures.append("review-date-missing")
elif not date_pattern.match(review_date):
    failures.append("review-date-invalid")
else:
    try:
        parsed_date = date.fromisoformat(review_date)
    except ValueError:
        failures.append("review-date-invalid")
    else:
        if parsed_date > date.today():
            failures.append("review-date-in-future")

if failures:
    print(",".join(failures))
    sys.exit(1)

print("approved")
PY
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  reason="$(cat "$TMP_FAILURES")"
  write_report failed "${reason:-incomplete-release-operations-record}"
  echo "Release operations record is incomplete." >&2
  exit 1
fi

write_report passed approved
echo "Release operations record verification passed."

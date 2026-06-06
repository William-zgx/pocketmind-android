#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

VALIDATION_RECORD_FILE="${VALIDATION_RECORD_FILE:-docs/release_validation_record.json}"
ANDROID_TEST_SOURCE_DIR="${ANDROID_TEST_SOURCE_DIR:-app/src/androidTest}"
REPORT_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      VALIDATION_RECORD_FILE="${2:?missing validation record file}"
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
      printf 'target=release-validation-record\n'
      printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
      printf 'androidTestSourceDir=%s\n' "$ANDROID_TEST_SOURCE_DIR"
      printf 'reason=%s\n' "$reason"
    } > "$REPORT_FILE"
  fi
}

if [[ ! -f "$VALIDATION_RECORD_FILE" ]]; then
  write_report failed missing-validation-record-file
  echo "Release validation record file is missing: $VALIDATION_RECORD_FILE" >&2
  exit 1
fi

TMP_FAILURES="$(mktemp)"
trap 'rm -f "$TMP_FAILURES"' EXIT

set +e
python3 - "$VALIDATION_RECORD_FILE" "$ANDROID_TEST_SOURCE_DIR" > "$TMP_FAILURES" <<'PY'
import json
import hashlib
import re
import sys
from datetime import date
from pathlib import Path

record_path = Path(sys.argv[1])
android_test_source_dir = Path(sys.argv[2])

try:
    record = json.loads(record_path.read_text())
except Exception:
    print("json-parse-error")
    sys.exit(1)

def non_empty_string(value):
    return isinstance(value, str) and bool(value.strip())

def properties_for(path):
    values = {}
    with Path(path).open() as handle:
        for raw_line in handle:
            line = raw_line.rstrip("\n")
            if "=" not in line:
                continue
            key, value = line.split("=", 1)
            values[key] = value
    return values

def validate_date_field(value, prefix):
    if not non_empty_string(value):
        failures.append(f"{prefix}-date-missing")
        return
    date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")
    if not date_pattern.match(value):
        failures.append(f"{prefix}-date-invalid")
        return
    try:
        parsed_date = date.fromisoformat(value)
    except ValueError:
        failures.append(f"{prefix}-date-invalid")
    else:
        if parsed_date > date.today():
            failures.append(f"{prefix}-date-in-future")

def validate_file_sha(prefix, path, expected_sha):
    if not non_empty_string(expected_sha):
        failures.append(f"{prefix}-sha-missing")
        return
    try:
        actual_sha = hashlib.sha256(Path(path).read_bytes()).hexdigest()
    except OSError:
        failures.append(f"{prefix}-sha-read-failed")
        return
    if actual_sha != expected_sha:
        failures.append(f"{prefix}-sha-mismatch")

def validate_evidence_record(section, key, value):
    prefix = f"{section}-{key}"
    if isinstance(value, str):
        if value == "passed":
            failures.append(f"{prefix}-evidence-record-invalid")
        else:
            failures.append(f"{prefix}-not-passed")
        return
    if not isinstance(value, dict):
        failures.append(f"{prefix}-evidence-record-invalid")
        return
    if value.get("status") != "passed":
        failures.append(f"{prefix}-not-passed")
    if not non_empty_string(value.get("evidence")):
        failures.append(f"{prefix}-evidence-missing")
    evidence_path = value.get("evidencePath", "")
    if not non_empty_string(evidence_path):
        failures.append(f"{prefix}-evidence-path-missing")
    elif not Path(evidence_path).is_file():
        failures.append(f"{prefix}-evidence-file-missing")
    else:
        validate_file_sha(f"{prefix}-evidence", evidence_path, value.get("evidenceSha256", ""))
        if section == "manual":
            validate_manual_evidence(key, evidence_path)
        if section == "flow":
            validate_flow_evidence(key, evidence_path)
    if not non_empty_string(value.get("owner")):
        failures.append(f"{prefix}-owner-missing")
    validate_date_field(value.get("date", ""), prefix)

def validate_api_matrix_evidence(api_level, evidence_path):
    prefix = f"api-{api_level}-evidence"
    props = properties_for(evidence_path)
    if props.get("status") != "passed":
        failures.append(f"{prefix}-status-not-passed")
    if props.get("target") != "regression-emulator":
        failures.append(f"{prefix}-target-invalid")
    if props.get("clean_device") != "1":
        failures.append(f"{prefix}-clean-device-not-true")
    if props.get("api_level") != str(api_level):
        failures.append(f"{prefix}-api-mismatch")
    if props.get("abi") != "arm64-v8a":
        failures.append(f"{prefix}-abi-mismatch")
    if not non_empty_string(props.get("avd", "")):
        failures.append(f"{prefix}-avd-missing")
    try:
        actual_count = int(props.get("actual_android_test_count", ""))
    except ValueError:
        failures.append(f"{prefix}-test-count-invalid")
    else:
        if actual_count < source_android_test_count:
            failures.append(f"{prefix}-test-count-too-low")

def validate_performance_evidence(key, evidence_path):
    prefix = f"performance-{key}-evidence"
    props = properties_for(evidence_path)
    if props.get("status") != "passed":
        failures.append(f"{prefix}-status-not-passed")
    if props.get("target") != "perf-baseline":
        failures.append(f"{prefix}-target-invalid")
    if props.get("performanceKey") != key:
        failures.append(f"{prefix}-key-mismatch")
    if props.get("missingFieldCount") != "0":
        failures.append(f"{prefix}-missing-fields")
    baseline_file = props.get("baselineFile", "")
    if not non_empty_string(baseline_file):
        failures.append(f"{prefix}-baseline-file-missing")
    elif not Path(baseline_file).is_file():
        failures.append(f"{prefix}-baseline-file-read-failed")

def validate_manual_evidence(key, evidence_path):
    prefix = f"manual-{key}-evidence"
    props = properties_for(evidence_path)
    if props.get("status") != "passed":
        failures.append(f"{prefix}-status-not-passed")
    if props.get("target") != "manual-acceptance":
        failures.append(f"{prefix}-target-invalid")
    if props.get("manualKey") != key:
        failures.append(f"{prefix}-key-mismatch")
    if props.get("manualAcceptance", "").lower() not in {"true", "1", "yes"}:
        failures.append(f"{prefix}-manual-acceptance-not-true")

def validate_flow_evidence(key, evidence_path):
    prefix = f"flow-{key}"
    evidence_prefix = f"{prefix}-evidence"
    props = properties_for(evidence_path)
    if props.get("status") != "passed":
        failures.append(f"{evidence_prefix}-status-not-passed")
    if props.get("target") != "release-flow":
        failures.append(f"{evidence_prefix}-target-invalid")
    if props.get("flowKey") != key:
        failures.append(f"{evidence_prefix}-key-mismatch")
    if props.get("candidateOnly", "").lower() in {"true", "1", "yes"}:
        failures.append(f"{prefix}-candidate-evidence-not-approved")
    if props.get("releaseFlowPassed", "").lower() not in {"true", "1", "yes"}:
        failures.append(f"{prefix}-release-flow-not-passed")

def validate_png_file(name, path):
    try:
        signature = Path(path).read_bytes()[:8]
    except OSError:
        failures.append(f"{name}-screenshot-read-failed")
        return
    if signature != b"\x89PNG\r\n\x1a\n":
        failures.append(f"{name}-screenshot-not-png")

def validate_screenshot_report(name, entry, path):
    report_path = entry.get("reportPath", "")
    if not non_empty_string(report_path):
        failures.append(f"{name}-screenshot-report-path-missing")
        return
    if not Path(report_path).is_file():
        failures.append(f"{name}-screenshot-report-missing")
        return
    validate_file_sha(f"{name}-screenshot-report", report_path, entry.get("reportSha256", ""))
    props = properties_for(report_path)
    if props.get("status") != "passed":
        failures.append(f"{name}-screenshot-report-status-not-passed")
    if props.get("target") != "release-screenshots":
        failures.append(f"{name}-screenshot-report-target-invalid")
    if props.get("clean_device") != "1":
        failures.append(f"{name}-screenshot-report-clean-device-not-true")
    if props.get(f"screenshot.{name}.path") != path:
        failures.append(f"{name}-screenshot-report-path-mismatch")
    if props.get(f"screenshot.{name}.sha256") != entry.get("sha256", ""):
        failures.append(f"{name}-screenshot-report-sha-mismatch")
    if props.get(f"screenshot.{name}.sanitized", "").lower() not in {"true", "1", "yes"}:
        failures.append(f"{name}-screenshot-report-not-sanitized")

def count_android_tests():
    count = 0
    if not android_test_source_dir.is_dir():
        return 0
    pattern = re.compile(r"^\s*@(org[.]junit[.])?Test(\s*[(]|[\s]|$)")
    for path in android_test_source_dir.rglob("*"):
        if path.suffix not in {".kt", ".java"}:
            continue
        try:
            for line in path.read_text(errors="ignore").splitlines():
                if pattern.search(line):
                    count += 1
        except OSError:
            pass
    return count

failures = []
if record.get("version") != 1:
    failures.append("version-invalid")
if record.get("status") != "approved":
    failures.append("status-not-approved")

source_android_test_count = count_android_tests()
if source_android_test_count <= 0:
    failures.append("android-test-source-count-invalid")

emulator = record.get("emulatorRegression")
if not isinstance(emulator, dict):
    failures.append("emulator-regression-missing")
    emulator = {}
if emulator.get("status") != "passed":
    failures.append("emulator-regression-not-passed")
emulator_report = emulator.get("reportPath", "")
if not emulator_report:
    failures.append("emulator-report-path-missing")
elif not Path(emulator_report).is_file():
    failures.append("emulator-report-missing")
else:
    validate_file_sha("emulator-report", emulator_report, emulator.get("reportSha256", ""))
    props = properties_for(emulator_report)
    if props.get("status") != "passed":
        failures.append("emulator-report-status-not-passed")
    if props.get("target") != "regression-emulator":
        failures.append("emulator-report-target-invalid")
    if props.get("clean_device") != "1":
        failures.append("emulator-report-clean-device-not-true")
    if props.get("avd") != emulator.get("avd"):
        failures.append("emulator-report-avd-mismatch")
    if props.get("api_level") != str(emulator.get("apiLevel")):
        failures.append("emulator-report-api-mismatch")
    if props.get("abi") != emulator.get("abi"):
        failures.append("emulator-report-abi-mismatch")
    try:
        actual_count = int(props.get("actual_android_test_count", ""))
    except ValueError:
        failures.append("emulator-report-test-count-invalid")
    else:
        if actual_count < source_android_test_count:
            failures.append("emulator-report-test-count-too-low")

physical = record.get("physicalDevice")
if not isinstance(physical, dict):
    failures.append("physical-device-missing")
    physical = {}
if physical.get("status") != "passed":
    failures.append("physical-device-not-passed")
physical_serial = physical.get("serial", "")
if not non_empty_string(physical_serial) or physical_serial.startswith("emulator-"):
    failures.append("physical-device-serial-invalid")
device_report = physical.get("reportPath", "")
if not device_report:
    failures.append("physical-device-report-path-missing")
elif not Path(device_report).is_file():
    failures.append("physical-device-report-missing")
else:
    validate_file_sha("physical-device-report", device_report, physical.get("reportSha256", ""))
    props = properties_for(device_report)
    report_serial = props.get("serial", "")
    if props.get("status") != "passed":
        failures.append("physical-device-report-status-not-passed")
    if props.get("target") != "device":
        failures.append("physical-device-report-target-invalid")
    if report_serial.startswith("emulator-"):
        failures.append("physical-device-report-serial-is-emulator")
    if report_serial != physical_serial:
        failures.append("physical-device-report-serial-mismatch")
    if props.get("api_level") != str(physical.get("apiLevel")):
        failures.append("physical-device-report-api-mismatch")
    if props.get("abi") != physical.get("abi"):
        failures.append("physical-device-report-abi-mismatch")
    expected_clean = "1" if physical.get("cleanDevice") is True else "0"
    if props.get("clean_device") != expected_clean:
        failures.append("physical-device-report-clean-device-mismatch")
    if props.get("instrumentation") != "passed":
        failures.append("physical-device-report-instrumentation-not-passed")
    try:
        actual_count = int(props.get("instrumentation_test_count", ""))
    except ValueError:
        failures.append("physical-device-report-test-count-invalid")
    else:
        if actual_count < source_android_test_count:
            failures.append("physical-device-report-test-count-too-low")

api_matrix = record.get("apiMatrix")
required_apis = {28, 32, 33, 34, 36}
seen_apis = set()
if not isinstance(api_matrix, list):
    failures.append("api-matrix-missing")
    api_matrix = []
for entry in api_matrix:
    if not isinstance(entry, dict):
        failures.append("api-matrix-entry-invalid")
        continue
    api_level = entry.get("apiLevel")
    seen_apis.add(api_level)
    if entry.get("status") != "passed":
        failures.append(f"api-{api_level}-not-passed")
    if not non_empty_string(entry.get("evidence")):
        failures.append(f"api-{api_level}-evidence-missing")
    evidence_path = entry.get("evidencePath", "")
    if not non_empty_string(evidence_path):
        failures.append(f"api-{api_level}-evidence-path-missing")
    elif not Path(evidence_path).is_file():
        failures.append(f"api-{api_level}-evidence-file-missing")
    else:
        validate_file_sha(f"api-{api_level}-evidence", evidence_path, entry.get("evidenceSha256", ""))
        validate_api_matrix_evidence(api_level, evidence_path)
for missing in sorted(required_apis - seen_apis):
    failures.append(f"api-{missing}-missing")

required_manual = {
    "modelSetup",
    "remoteModePrivacy",
    "toolConfirmation",
    "permissions",
    "backgroundReminders",
    "sharing",
    "multimodalEntryPoints",
    "voiceInput",
    "filePicker",
    "mediaProjection",
    "remoteSinglePublicEvidence",
    "remoteMultiEvidenceComparison",
    "mixedPrivateActionBatchFailClosed",
}
manual = record.get("manualAcceptance")
if not isinstance(manual, dict):
    failures.append("manual-acceptance-missing")
    manual = {}
for key in sorted(required_manual):
    validate_evidence_record("manual", key, manual.get(key))

required_flows = {
    "firstInstall",
    "upgradeInstall",
    "localModelDownloadVerification",
    "customModelImportOrUrlRejection",
    "remoteHttpsConfiguration",
    "encryptedApiKeyClear",
    "sessionPersistence",
    "memoryControls",
    "remindersAfterReboot",
    "shareAndPickerInput",
    "voiceInput",
    "accessibilityText",
    "recentMediaOcr",
    "mediaProjectionCancellation",
}
flows = record.get("flowMatrix")
if not isinstance(flows, dict):
    failures.append("flow-matrix-missing")
    flows = {}
for key in sorted(required_flows):
    validate_evidence_record("flow", key, flows.get(key))

screenshots = record.get("screenshots")
required_screenshots = {"chat-home", "model-manager", "confirmation-sheet", "background-tasks-or-audit"}
seen_screenshots = set()
if not isinstance(screenshots, list):
    failures.append("screenshots-missing")
    screenshots = []
for entry in screenshots:
    if not isinstance(entry, dict):
        failures.append("screenshot-entry-invalid")
        continue
    name = entry.get("name", "")
    seen_screenshots.add(name)
    path = entry.get("path", "")
    if not name:
        failures.append("screenshot-name-missing")
    if not path:
        failures.append(f"{name or 'unknown'}-screenshot-path-missing")
    elif not Path(path).is_file():
        failures.append(f"{name or 'unknown'}-screenshot-missing")
    else:
        validate_file_sha(f"{name or 'unknown'}-screenshot", path, entry.get("sha256", ""))
        validate_png_file(name or "unknown", path)
        validate_screenshot_report(name or "unknown", entry, path)
    if entry.get("sanitized") is not True:
        failures.append(f"{name or 'unknown'}-screenshot-not-sanitized")
for missing in sorted(required_screenshots - seen_screenshots):
    failures.append(f"{missing}-screenshot-missing")

performance = record.get("performanceSanity")
required_performance = {
    "firstLaunch",
    "modelLoad",
    "firstToken",
    "streamingStopCancel",
    "backgroundReminderDelivery",
    "memoryPressure",
}
if not isinstance(performance, dict):
    failures.append("performance-sanity-missing")
    performance = {}
for key in sorted(required_performance):
    value = performance.get(key)
    validate_evidence_record("performance", key, value)
    if isinstance(value, dict):
        evidence_path = value.get("evidencePath", "")
        if non_empty_string(evidence_path) and Path(evidence_path).is_file():
            validate_performance_evidence(key, evidence_path)

review = record.get("review")
if not isinstance(review, dict):
    failures.append("review-missing")
    review = {}
if not non_empty_string(review.get("reviewer")):
    failures.append("reviewer-missing")
review_date = review.get("reviewDate", "")
validate_date_field(review_date, "review")

if failures:
    print(",".join(failures))
    sys.exit(1)

print("approved")
PY
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  reason="$(cat "$TMP_FAILURES")"
  write_report failed "${reason:-incomplete-release-validation-record}"
  echo "Release validation record is incomplete." >&2
  exit 1
fi

write_report passed approved
echo "Release validation record verification passed."

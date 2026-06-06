#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

VALIDATION_RECORD_FILE="${VALIDATION_RECORD_FILE:-docs/release_validation_record.json}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/release-flow-matrix-current}"
REPORT_FILE="${REPORT_FILE:-$ARTIFACT_DIR/release-flow-matrix-candidate-evidence.properties}"
OWNER="${OWNER:-QA Automation}"
VALIDATION_DATE="${VALIDATION_DATE:-$(date +%F)}"

REQUIRED_FLOWS=(
  firstInstall
  upgradeInstall
  localModelDownloadVerification
  customModelImportOrUrlRejection
  remoteHttpsConfiguration
  encryptedApiKeyClear
  sessionPersistence
  memoryControls
  remindersAfterReboot
  shareAndPickerInput
  voiceInput
  accessibilityText
  recentMediaOcr
  mediaProjectionCancellation
)

GENERATED_FLOWS=(
  firstInstall
  customModelImportOrUrlRejection
  remoteHttpsConfiguration
  encryptedApiKeyClear
  sessionPersistence
  memoryControls
  accessibilityText
  mediaProjectionCancellation
)

FAILED_TARGET=""
FAILURE_REASON=""
GENERATED_EVIDENCE_PATHS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      VALIDATION_RECORD_FILE="${2:?missing validation record file}"
      shift 2
      ;;
    --artifact-dir)
      ARTIFACT_DIR="${2:?missing artifact directory}"
      if [[ "$REPORT_FILE" == "build/verification/release-flow-matrix-current/release-flow-matrix-candidate-evidence.properties" ]]; then
        REPORT_FILE="$ARTIFACT_DIR/release-flow-matrix-candidate-evidence.properties"
      fi
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

join_csv() {
  local IFS=,
  printf '%s' "$*"
}

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

property_value() {
  local key="$1"
  local file="$2"
  awk -F= -v key="$key" '$1 == key {print substr($0, index($0, "=") + 1); exit}' "$file"
}

json_value() {
  local selector="$1"
  python3 - "$VALIDATION_RECORD_FILE" "$selector" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
value = record
for part in sys.argv[2].split("."):
    if not isinstance(value, dict):
        value = ""
        break
    value = value.get(part, "")
if value is None:
    value = ""
print(value if isinstance(value, str) else str(value))
PY
}

write_report() {
  local status="$1"
  local reason="$2"
  local passed_flows="${3:-}"
  local pending_flows="${4:-}"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    printf 'status=%s\n' "$status"
    printf 'target=release-flow-matrix-candidate-evidence\n'
    printf 'failedTarget=%s\n' "$FAILED_TARGET"
    printf 'reason=%s\n' "$reason"
    printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
    printf 'artifactDir=%s\n' "$ARTIFACT_DIR"
    printf 'owner=%s\n' "$OWNER"
    printf 'date=%s\n' "$VALIDATION_DATE"
    printf 'requiredFlows=%s\n' "$(join_csv "${REQUIRED_FLOWS[@]}")"
    printf 'generatedCandidateFlows=%s\n' "$(join_csv "${GENERATED_FLOWS[@]}")"
    printf 'generatedCandidateEvidencePaths=%s\n' "$(join_csv "${GENERATED_EVIDENCE_PATHS[@]}")"
    printf 'passedRecordFlows=%s\n' "$passed_flows"
    printf 'pendingRecordFlows=%s\n' "$pending_flows"
  } > "$REPORT_FILE"
}

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  write_report failed "$FAILURE_REASON"
  echo "$*" >&2
  exit 1
}

flow_summary() {
  case "$1" in
    firstInstall)
      printf 'Clean API 36 emulator regression covers first-run setup dismissal, chat shell rendering, model manager entry, session controls, and background task empty state.'
      ;;
    customModelImportOrUrlRejection)
      printf 'API 36 emulator regression covers custom model URL rejection and custom .litertlm DownloadManager handoff; JVM URL contract rejects malformed, credentialed, and public HTTP URLs.'
      ;;
    remoteHttpsConfiguration)
      printf 'API 36 emulator regression configures remote mode against a local OpenAI-compatible fixture; JVM contract accepts HTTPS public endpoints and rejects non-local public HTTP.'
      ;;
    encryptedApiKeyClear)
      printf 'Repository tests prove blank API key clears the encrypted secret; API 36 emulator regression checks the legacy plaintext preference is not populated.'
      ;;
    sessionPersistence)
      printf 'API 36 emulator regression creates, switches, restores, and deletes sessions; repository tests cover active session and message persistence.'
      ;;
    memoryControls)
      printf 'API 36 emulator regression and memory panel UI tests cover explicit memory creation, forget, and clear controls.'
      ;;
    accessibilityText)
      printf 'API 36 emulator regression covers current screen Accessibility text confirmation, cancellation, audit evidence, and trace recording.'
      ;;
    mediaProjectionCancellation)
      printf 'API 36 emulator regression covers current screenshot OCR confirmation and user cancellation before MediaProjection execution, with audit and trace evidence.'
      ;;
  esac
}

flow_source_files() {
  case "$1" in
    firstInstall)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivitySmokeTest.kt
      ;;
    customModelImportOrUrlRejection)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/data/ModelRepositoryTest.kt
      ;;
    remoteHttpsConfiguration)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/RemoteModelConfigTest.kt
      ;;
    encryptedApiKeyClear)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/data/RemoteModelRepositoryTest.kt
      ;;
    sessionPersistence)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/data/SessionRepositoryTest.kt
      ;;
    memoryControls)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityComprehensiveTest.kt \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivityLongTermMemoryUiTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/memory/MemoryQualityContractTest.kt
      ;;
    accessibilityText)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivitySkillUiTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/tool/ToolRegistryTest.kt
      ;;
    mediaProjectionCancellation)
      printf '%s\n' \
        app/src/androidTest/java/com/bytedance/zgx/pocketmind/MainActivitySkillUiTest.kt \
        app/src/test/java/com/bytedance/zgx/pocketmind/multimodal/CurrentScreenshotOcrContractTest.kt
      ;;
  esac
}

if [[ ! -f "$VALIDATION_RECORD_FILE" ]]; then
  fail validation-record missing-validation-record-file "Release validation record file is missing: $VALIDATION_RECORD_FILE"
fi

EMULATOR_REPORT_PATH="$(json_value emulatorRegression.reportPath)"
EMULATOR_REPORT_SHA="$(json_value emulatorRegression.reportSha256)"
if [[ -z "$EMULATOR_REPORT_PATH" || ! -f "$EMULATOR_REPORT_PATH" ]]; then
  fail source-regression missing-emulator-regression-report \
    "Release flow matrix candidate evidence requires an existing clean emulator regression report."
fi
if [[ -z "$EMULATOR_REPORT_SHA" || "$(sha256_file "$EMULATOR_REPORT_PATH")" != "$EMULATOR_REPORT_SHA" ]]; then
  fail source-regression emulator-regression-report-sha-mismatch \
    "Release flow matrix candidate evidence requires the validation record emulator report SHA to match."
fi
if [[ "$(property_value status "$EMULATOR_REPORT_PATH")" != "passed" ]]; then
  fail source-regression emulator-regression-not-passed \
    "Release flow matrix candidate evidence requires a passed emulator regression report."
fi
if [[ "$(property_value target "$EMULATOR_REPORT_PATH")" != "regression-emulator" ]]; then
  fail source-regression emulator-regression-target-invalid \
    "Release flow matrix candidate evidence requires target=regression-emulator."
fi
if [[ "$(property_value clean_device "$EMULATOR_REPORT_PATH")" != "1" ]]; then
  fail source-regression emulator-regression-not-clean \
    "Release flow matrix candidate evidence requires clean_device=1."
fi

mkdir -p "$ARTIFACT_DIR"
for flow in "${GENERATED_FLOWS[@]}"; do
  evidence_path="$ARTIFACT_DIR/flow-$flow.properties"
  source_files=()
  source_shas=()
  while IFS= read -r source_file; do
    [[ -n "$source_file" ]] || continue
    if [[ ! -f "$source_file" ]]; then
      fail source-file "missing-source-file-$flow" \
        "Release flow matrix candidate evidence source is missing for $flow: $source_file"
    fi
    source_files+=("$source_file")
    source_shas+=("$(sha256_file "$source_file")")
  done < <(flow_source_files "$flow")

  {
    printf 'status=passed\n'
    printf 'target=release-flow-matrix-candidate-evidence\n'
    printf 'flow=%s\n' "$flow"
    printf 'evidenceKind=api36-clean-emulator-regression\n'
    printf 'candidateOnly=true\n'
    printf 'releaseFlowPassed=false\n'
    printf 'validationRecordFile=%s\n' "$VALIDATION_RECORD_FILE"
    printf 'sourceRegressionReport=%s\n' "$EMULATOR_REPORT_PATH"
    printf 'sourceRegressionReportSha256=%s\n' "$EMULATOR_REPORT_SHA"
    printf 'sourceTestFiles=%s\n' "$(join_csv "${source_files[@]}")"
    printf 'sourceTestFileSha256s=%s\n' "$(join_csv "${source_shas[@]}")"
    printf 'manualAcceptance=false\n'
    printf 'owner=%s\n' "$OWNER"
    printf 'date=%s\n' "$VALIDATION_DATE"
    printf 'summary=%s\n' "$(flow_summary "$flow")"
  } > "$evidence_path"
  GENERATED_EVIDENCE_PATHS+=("$evidence_path")
done

SUMMARY_FILE="$(mktemp)"
trap 'rm -f "$SUMMARY_FILE"' EXIT
python3 - "$VALIDATION_RECORD_FILE" "${REQUIRED_FLOWS[@]}" > "$SUMMARY_FILE" <<'PY'
import hashlib
import json
import re
import sys
from datetime import date
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
required = sys.argv[2:]
flows = record.get("flowMatrix")
if not isinstance(flows, dict):
    flows = {}

date_pattern = re.compile(r"^\d{4}-\d{2}-\d{2}$")

def non_empty_string(value):
    return isinstance(value, str) and bool(value.strip())

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def is_valid_evidence(value):
    if not isinstance(value, dict):
        return False
    if value.get("status") != "passed":
        return False
    if not non_empty_string(value.get("evidence")):
        return False
    if not non_empty_string(value.get("owner")):
        return False
    evidence_path = value.get("evidencePath", "")
    if not non_empty_string(evidence_path) or not Path(evidence_path).is_file():
        return False
    if value.get("evidenceSha256") != sha(evidence_path):
        return False
    recorded_date = value.get("date", "")
    if not non_empty_string(recorded_date) or not date_pattern.match(recorded_date):
        return False
    try:
        if date.fromisoformat(recorded_date) > date.today():
            return False
    except ValueError:
        return False
    return True

passed = []
pending = []
for flow in required:
    if is_valid_evidence(flows.get(flow)):
        passed.append(flow)
    else:
        pending.append(flow)

print("passedRecordFlows=" + ",".join(passed))
print("pendingRecordFlows=" + ",".join(pending))
PY

PASSED_RECORD_FLOWS="$(property_value passedRecordFlows "$SUMMARY_FILE")"
PENDING_RECORD_FLOWS="$(property_value pendingRecordFlows "$SUMMARY_FILE")"

if [[ -n "$PENDING_RECORD_FLOWS" ]]; then
  FAILED_TARGET="flow-matrix"
  FAILURE_REASON="missing-approved-release-evidence-${PENDING_RECORD_FLOWS}"
  write_report failed "$FAILURE_REASON" "$PASSED_RECORD_FLOWS" "$PENDING_RECORD_FLOWS"
  echo "Release flow matrix approved evidence is incomplete." >&2
  exit 1
fi

write_report passed "" "$PASSED_RECORD_FLOWS" ""
echo "Release flow matrix approved evidence passed."

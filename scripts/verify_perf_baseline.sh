#!/usr/bin/env bash
set -euo pipefail

BASELINE_FILE=""
REPORT_FILE=""
EXPECTED_ARTIFACT_SHA256=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file)
      BASELINE_FILE="${2:?missing perf baseline file}"
      shift 2
      ;;
    --report)
      REPORT_FILE="${2:?missing report path}"
      shift 2
      ;;
    --artifact-sha256)
      EXPECTED_ARTIFACT_SHA256="${2:?missing artifact sha256}"
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
  local missing="$2"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=perf-baseline\n'
      printf 'baselineFile=%s\n' "${BASELINE_FILE:-}"
      printf 'missingFieldCount=%s\n' "$missing"
      printf 'expectedArtifactSha256=%s\n' "$EXPECTED_ARTIFACT_SHA256"
    } > "$REPORT_FILE"
  fi
}

if [[ -z "$BASELINE_FILE" || ! -f "$BASELINE_FILE" ]]; then
  write_report failed 1
  echo "Perf baseline file is missing." >&2
  exit 1
fi

required_fields=(
  status
  deviceSerial
  deviceModel
  androidApi
  abi
  appVersion
  releaseArtifactSha256
  modelId
  backend
  firstLaunchInteractiveMs
  modelLoadMs
  firstTokenMs
  tokensPerSecond
  stopGenerationRecoveryMs
  gpuFallbackStatus
  visionInputMs
  memorySearch5kMs
  memoryPeakMb
  oomOrAnrObserved
  recordedAt
)

missing=0
for field in "${required_fields[@]}"; do
  if ! grep -qE "^${field}=.+" "$BASELINE_FILE"; then
    echo "Missing perf baseline field: $field" >&2
    missing=$((missing + 1))
  fi
done

if ! grep -qx 'status=passed' "$BASELINE_FILE"; then
  echo "Perf baseline status must be passed." >&2
  missing=$((missing + 1))
fi

if ! grep -qx 'oomOrAnrObserved=false' "$BASELINE_FILE"; then
  echo "Perf baseline must record oomOrAnrObserved=false." >&2
  missing=$((missing + 1))
fi

if [[ -n "$EXPECTED_ARTIFACT_SHA256" ]]; then
  recorded_sha="$(awk -F= '$1 == "releaseArtifactSha256" {print $2; exit}' "$BASELINE_FILE")"
  if [[ "$recorded_sha" != "$EXPECTED_ARTIFACT_SHA256" ]]; then
    echo "Perf baseline releaseArtifactSha256 does not match release artifact." >&2
    missing=$((missing + 1))
  fi
fi

numeric_fields=(
  androidApi
  firstLaunchInteractiveMs
  modelLoadMs
  firstTokenMs
  stopGenerationRecoveryMs
  visionInputMs
  memorySearch5kMs
  memoryPeakMb
)

for field in "${numeric_fields[@]}"; do
  value="$(awk -F= -v key="$field" '$1 == key {print $2; exit}' "$BASELINE_FILE")"
  if [[ -n "$value" && ! "$value" =~ ^[0-9]+$ ]]; then
    echo "Perf baseline field $field must be an integer." >&2
    missing=$((missing + 1))
  fi
done

tps="$(awk -F= '$1 == "tokensPerSecond" {print $2; exit}' "$BASELINE_FILE")"
if [[ -n "$tps" && ! "$tps" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "Perf baseline field tokensPerSecond must be numeric." >&2
  missing=$((missing + 1))
elif [[ -n "$tps" ]]; then
  awk -v value="$tps" 'BEGIN { exit(value > 0 ? 0 : 1) }' || {
    echo "Perf baseline field tokensPerSecond must be > 0." >&2
    missing=$((missing + 1))
  }
fi

if [[ "$missing" -gt 0 ]]; then
  write_report failed "$missing"
  exit 1
fi

write_report passed 0
echo "Perf baseline verification passed."

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/release-gate}"
PERF_BASELINE_FILE="${PERF_BASELINE_FILE:-}"
RELEASE_APK="${RELEASE_APK:-app/build/outputs/apk/release/app-release-unsigned.apk}"
RELEASE_AAB="${RELEASE_AAB:-app/build/outputs/bundle/release/app-release.aab}"
VERIFY_MODEL_LICENSES="${VERIFY_MODEL_LICENSES:-0}"

mkdir -p "$ARTIFACT_DIR"

write_gate_report() {
  local status="$1"
  {
    printf 'status=%s\n' "$status"
    printf 'target=release-gate\n'
    printf 'artifactDir=%s\n' "$ARTIFACT_DIR"
    printf 'verifyModelLicenses=%s\n' "$VERIFY_MODEL_LICENSES"
  } > "$ARTIFACT_DIR/release-gate.properties"
}

scripts/privacy_scan.sh --report "$ARTIFACT_DIR/privacy-scan.properties" app/src/main docs scripts

artifact_args=()
if [[ -f "$RELEASE_APK" ]]; then
  artifact_args+=(--apk "$RELEASE_APK")
fi
if [[ -f "$RELEASE_AAB" ]]; then
  artifact_args+=(--aab "$RELEASE_AAB")
fi
if [[ "${#artifact_args[@]}" -gt 0 ]]; then
  scripts/scan_android_artifacts.sh "${artifact_args[@]}" \
    --report "$ARTIFACT_DIR/android-artifact-scan.properties"
else
  {
    printf 'status=skipped\n'
    printf 'target=android-artifact-scan\n'
    printf 'reason=no-release-apk-or-aab\n'
  } > "$ARTIFACT_DIR/android-artifact-scan.properties"
fi

if [[ -n "$PERF_BASELINE_FILE" ]]; then
  scripts/verify_perf_baseline.sh \
    --file "$PERF_BASELINE_FILE" \
    --report "$ARTIFACT_DIR/perf-baseline-verification.properties"
else
  {
    printf 'status=failed\n'
    printf 'target=perf-baseline\n'
    printf 'reason=PERF_BASELINE_FILE-not-set\n'
  } > "$ARTIFACT_DIR/perf-baseline-verification.properties"
  echo "PERF_BASELINE_FILE must point at the RC perf-baseline.properties file." >&2
  write_gate_report failed
  exit 1
fi

if [[ "$VERIFY_MODEL_LICENSES" == "1" ]]; then
  if grep -q '"status": "pending_manual_review"\|"redistributionDecision": "not_approved"\|"licenseName": ""\|"reviewer": ""\|"reviewDate": ""' docs/model_license_review.json; then
    {
      printf 'status=failed\n'
      printf 'target=model-license-review\n'
      printf 'reason=incomplete-license-review\n'
    } > "$ARTIFACT_DIR/model-license-review.properties"
    echo "Model license review is incomplete." >&2
    write_gate_report failed
    exit 1
  fi
  {
    printf 'status=passed\n'
    printf 'target=model-license-review\n'
  } > "$ARTIFACT_DIR/model-license-review.properties"
else
  {
    printf 'status=skipped\n'
    printf 'target=model-license-review\n'
    printf 'reason=VERIFY_MODEL_LICENSES-not-enabled\n'
  } > "$ARTIFACT_DIR/model-license-review.properties"
fi

write_gate_report passed
echo "Release gate passed."

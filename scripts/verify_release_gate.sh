#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/release-gate}"
PERF_BASELINE_FILE="${PERF_BASELINE_FILE:-}"
DEFAULT_RELEASE_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
RELEASE_APK_WAS_SET=0
if [[ -n "${RELEASE_APK+x}" ]]; then
  RELEASE_APK_WAS_SET=1
fi
RELEASE_APK="${RELEASE_APK:-$DEFAULT_RELEASE_APK}"
RELEASE_AAB="${RELEASE_AAB:-app/build/outputs/bundle/release/app-release.aab}"
PUBLIC_RELEASE="${PUBLIC_RELEASE:-0}"
VERIFY_MODEL_LICENSES="${VERIFY_MODEL_LICENSES:-0}"
VERIFY_PRIVACY_REVIEW="${VERIFY_PRIVACY_REVIEW:-0}"
REQUIRE_AAB="${REQUIRE_AAB:-0}"
REQUIRE_SIGNED_ARTIFACT="${REQUIRE_SIGNED_ARTIFACT:-0}"
VERIFY_CONTRACT_TESTS="${VERIFY_CONTRACT_TESTS:-1}"
EXPECTED_SIGNING_CERT_SHA256="${EXPECTED_SIGNING_CERT_SHA256:-}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"

mkdir -p "$ARTIFACT_DIR"

if [[ "$PUBLIC_RELEASE" == "1" ]]; then
  VERIFY_MODEL_LICENSES=1
  VERIFY_PRIVACY_REVIEW=1
  REQUIRE_AAB=1
  REQUIRE_SIGNED_ARTIFACT=1
fi

write_gate_report() {
  local status="$1"
  {
    printf 'status=%s\n' "$status"
    printf 'target=release-gate\n'
    printf 'artifactDir=%s\n' "$ARTIFACT_DIR"
    printf 'publicRelease=%s\n' "$PUBLIC_RELEASE"
    printf 'verifyModelLicenses=%s\n' "$VERIFY_MODEL_LICENSES"
    printf 'verifyPrivacyReview=%s\n' "$VERIFY_PRIVACY_REVIEW"
    printf 'requireAab=%s\n' "$REQUIRE_AAB"
    printf 'requireSignedArtifact=%s\n' "$REQUIRE_SIGNED_ARTIFACT"
    printf 'verifyContractTests=%s\n' "$VERIFY_CONTRACT_TESTS"
    printf 'expectedSigningCertSha256=%s\n' "$EXPECTED_SIGNING_CERT_SHA256"
  } > "$ARTIFACT_DIR/release-gate.properties"
}

if [[ "$PUBLIC_RELEASE" == "1" && -z "$EXPECTED_SIGNING_CERT_SHA256" ]]; then
  {
    printf 'status=failed\n'
    printf 'target=signing-cert\n'
    printf 'reason=PUBLIC_RELEASE-EXPECTED_SIGNING_CERT_SHA256-not-set\n'
  } > "$ARTIFACT_DIR/signing-cert.properties"
  echo "PUBLIC_RELEASE=1 requires EXPECTED_SIGNING_CERT_SHA256." >&2
  write_gate_report failed
  exit 1
fi

scripts/privacy_scan.sh --report "$ARTIFACT_DIR/privacy-scan.properties" app/src/main docs scripts

if [[ "$VERIFY_CONTRACT_TESTS" == "1" ]]; then
  "$GRADLE_CMD" :app:testDebugUnitTest \
    --tests com.bytedance.zgx.pocketmind.docs.CapabilityMatrixDocumentationTest \
    --tests com.bytedance.zgx.pocketmind.docs.ModelManifestDocumentationTest \
    --tests com.bytedance.zgx.pocketmind.docs.AgentCoreDocumentationTest
  {
    printf 'status=passed\n'
    printf 'target=contract-tests\n'
  } > "$ARTIFACT_DIR/contract-tests.properties"
else
  {
    printf 'status=skipped\n'
    printf 'target=contract-tests\n'
    printf 'reason=VERIFY_CONTRACT_TESTS-not-enabled\n'
  } > "$ARTIFACT_DIR/contract-tests.properties"
fi

artifact_args=()
if [[ -f "$RELEASE_APK" && ! ("$REQUIRE_AAB" == "1" && "$REQUIRE_SIGNED_ARTIFACT" == "1" && "$RELEASE_APK_WAS_SET" == "0") ]]; then
  artifact_args+=(--apk "$RELEASE_APK")
fi
if [[ -f "$RELEASE_AAB" ]]; then
  artifact_args+=(--aab "$RELEASE_AAB")
fi
if [[ "$REQUIRE_AAB" == "1" && ! -f "$RELEASE_AAB" ]]; then
  {
    printf 'status=failed\n'
    printf 'target=android-artifact-scan\n'
    printf 'reason=REQUIRE_AAB-but-release-aab-missing\n'
    printf 'releaseAab=%s\n' "$RELEASE_AAB"
  } > "$ARTIFACT_DIR/android-artifact-scan.properties"
  echo "REQUIRE_AAB=1 but release AAB is missing: $RELEASE_AAB" >&2
  write_gate_report failed
  exit 1
fi
if [[ "${#artifact_args[@]}" -gt 0 ]]; then
  scan_args=("${artifact_args[@]}" --report "$ARTIFACT_DIR/android-artifact-scan.properties")
  if [[ "$REQUIRE_SIGNED_ARTIFACT" == "1" ]]; then
    scan_args+=(--require-signed)
  fi
  if [[ -n "$EXPECTED_SIGNING_CERT_SHA256" ]]; then
    scan_args+=(--expected-certificate-sha256 "$EXPECTED_SIGNING_CERT_SHA256")
  fi
  scripts/scan_android_artifacts.sh "${scan_args[@]}"
else
  {
    printf 'status=skipped\n'
    printf 'target=android-artifact-scan\n'
    printf 'reason=no-release-apk-or-aab\n'
  } > "$ARTIFACT_DIR/android-artifact-scan.properties"
fi

if [[ -n "$PERF_BASELINE_FILE" ]]; then
  perf_args=(
    --file "$PERF_BASELINE_FILE" \
    --report "$ARTIFACT_DIR/perf-baseline-verification.properties"
  )
  if [[ -f "$RELEASE_AAB" ]]; then
    perf_args+=(--artifact-sha256 "$(shasum -a 256 "$RELEASE_AAB" | awk '{print $1}')")
  elif [[ -f "$RELEASE_APK" ]]; then
    perf_args+=(--artifact-sha256 "$(shasum -a 256 "$RELEASE_APK" | awk '{print $1}')")
  fi
  scripts/verify_perf_baseline.sh "${perf_args[@]}"
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

if [[ "$VERIFY_PRIVACY_REVIEW" == "1" ]]; then
  if ! scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review.properties"; then
    write_gate_report failed
    exit 1
  fi
else
  {
    printf 'status=skipped\n'
    printf 'target=privacy-review\n'
    printf 'reason=VERIFY_PRIVACY_REVIEW-not-enabled\n'
  } > "$ARTIFACT_DIR/privacy-review.properties"
fi

write_gate_report passed
echo "Release gate passed."

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
VERIFY_RELEASE_RECORD="${VERIFY_RELEASE_RECORD:-0}"
RELEASE_RECORD_FILE="${RELEASE_RECORD_FILE:-docs/release_record.json}"
VERIFY_STORE_POLICY="${VERIFY_STORE_POLICY:-0}"
STORE_POLICY_FILE="${STORE_POLICY_FILE:-docs/store_policy_record.json}"
REQUIRE_AAB="${REQUIRE_AAB:-0}"
REQUIRE_SIGNED_ARTIFACT="${REQUIRE_SIGNED_ARTIFACT:-0}"
VERIFY_RELEASE_MAPPING="${VERIFY_RELEASE_MAPPING:-0}"
RELEASE_MAPPING_FILE="${RELEASE_MAPPING_FILE:-app/build/outputs/mapping/release/mapping.txt}"
VERIFY_CONTRACT_TESTS="${VERIFY_CONTRACT_TESTS:-1}"
EXPECTED_SIGNING_CERT_SHA256="${EXPECTED_SIGNING_CERT_SHA256:-}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"

mkdir -p "$ARTIFACT_DIR"

if [[ "$PUBLIC_RELEASE" == "1" ]]; then
  VERIFY_RELEASE_RECORD=1
  VERIFY_STORE_POLICY=1
  VERIFY_MODEL_LICENSES=1
  VERIFY_PRIVACY_REVIEW=1
  REQUIRE_AAB=1
  REQUIRE_SIGNED_ARTIFACT=1
  VERIFY_RELEASE_MAPPING=1
fi

write_gate_report() {
  local status="$1"
  {
    printf 'status=%s\n' "$status"
    printf 'target=release-gate\n'
    printf 'artifactDir=%s\n' "$ARTIFACT_DIR"
    printf 'publicRelease=%s\n' "$PUBLIC_RELEASE"
    printf 'verifyReleaseRecord=%s\n' "$VERIFY_RELEASE_RECORD"
    printf 'releaseRecordFile=%s\n' "$RELEASE_RECORD_FILE"
    printf 'verifyStorePolicy=%s\n' "$VERIFY_STORE_POLICY"
    printf 'storePolicyFile=%s\n' "$STORE_POLICY_FILE"
    printf 'verifyModelLicenses=%s\n' "$VERIFY_MODEL_LICENSES"
    printf 'verifyPrivacyReview=%s\n' "$VERIFY_PRIVACY_REVIEW"
    printf 'requireAab=%s\n' "$REQUIRE_AAB"
    printf 'requireSignedArtifact=%s\n' "$REQUIRE_SIGNED_ARTIFACT"
    printf 'verifyReleaseMapping=%s\n' "$VERIFY_RELEASE_MAPPING"
    printf 'releaseMappingFile=%s\n' "$RELEASE_MAPPING_FILE"
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

if [[ "$VERIFY_RELEASE_MAPPING" == "1" ]]; then
  if ! scripts/verify_release_mapping.sh --file "$RELEASE_MAPPING_FILE" --report "$ARTIFACT_DIR/release-mapping.properties"; then
    write_gate_report failed
    exit 1
  fi
else
  {
    printf 'status=skipped\n'
    printf 'target=release-mapping\n'
    printf 'reason=VERIFY_RELEASE_MAPPING-not-enabled\n'
    printf 'mappingFile=%s\n' "$RELEASE_MAPPING_FILE"
  } > "$ARTIFACT_DIR/release-mapping.properties"
fi

if [[ "$VERIFY_RELEASE_RECORD" == "1" ]]; then
  if ! scripts/verify_release_record.sh --file "$RELEASE_RECORD_FILE" --report "$ARTIFACT_DIR/release-record.properties"; then
    write_gate_report failed
    exit 1
  fi
else
  {
    printf 'status=skipped\n'
    printf 'target=release-record\n'
    printf 'reason=VERIFY_RELEASE_RECORD-not-enabled\n'
    printf 'recordFile=%s\n' "$RELEASE_RECORD_FILE"
  } > "$ARTIFACT_DIR/release-record.properties"
fi

if [[ "$VERIFY_STORE_POLICY" == "1" ]]; then
  if ! scripts/verify_store_policy_record.sh --file "$STORE_POLICY_FILE" --report "$ARTIFACT_DIR/store-policy-record.properties"; then
    write_gate_report failed
    exit 1
  fi
else
  {
    printf 'status=skipped\n'
    printf 'target=store-policy-record\n'
    printf 'reason=VERIFY_STORE_POLICY-not-enabled\n'
    printf 'storePolicyFile=%s\n' "$STORE_POLICY_FILE"
  } > "$ARTIFACT_DIR/store-policy-record.properties"
fi

if [[ "$VERIFY_MODEL_LICENSES" == "1" ]]; then
  if ! scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-review.properties"; then
    write_gate_report failed
    exit 1
  fi
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

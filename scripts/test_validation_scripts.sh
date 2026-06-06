#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

fail() {
  echo "validation-script-test: $*" >&2
  exit 1
}

LAST_OUTPUT=""

expect_success() {
  local name="$1"
  shift
  if ! output="$("$@" 2>&1)"; then
    printf '%s\n' "$output" >&2
    fail "$name unexpectedly failed"
  fi
  LAST_OUTPUT="$output"
}

expect_failure() {
  local name="$1"
  shift
  if output="$("$@" 2>&1)"; then
    printf '%s\n' "$output" >&2
    fail "$name unexpectedly succeeded"
  fi
  LAST_OUTPUT="$output"
}

create_base_sdk() {
  local sdk="$1"
  mkdir -p "$sdk/platforms/android-36" "$sdk/build-tools/36.0.0"
  cat > "$sdk/build-tools/36.0.0/aapt" <<'FAKE_AAPT'
#!/usr/bin/env bash
exit 0
FAKE_AAPT
  chmod +x "$sdk/build-tools/36.0.0/aapt"
}

create_fake_adb() {
  local sdk="$1"
  mkdir -p "$sdk/platform-tools"
  cat > "$sdk/platform-tools/adb" <<'FAKE_ADB'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$*" >> "${FAKE_ADB_LOG:?}"

if [[ "${1:-}" == "devices" ]]; then
  echo "List of devices attached"
  if [[ -n "${FAKE_ADB_DEVICES:-}" ]]; then
    printf '%s\n' "$FAKE_ADB_DEVICES"
  fi
  exit 0
fi

if [[ "${1:-}" == "-s" ]]; then
  shift 2
fi

case "${1:-}" in
  shell)
    shift
    case "$*" in
      "getprop ro.product.cpu.abilist64")
        echo "${FAKE_ABI_LIST:-arm64-v8a,armeabi-v7a}"
        ;;
      "getprop ro.build.version.sdk")
        echo "36"
        ;;
      "getprop sys.boot_completed")
        echo "1"
        ;;
      "df -k /data")
        printf 'Filesystem 1K-blocks Used Available Use%% Mounted on\n'
        printf '/dev/block 5000000 1000 %s 1%% /data\n' "${FAKE_DATA_FREE_KB:-4000000}"
        ;;
      am\ instrument\ -w\ -r*)
        if [[ -n "${FAKE_INSTRUMENTATION_OUTPUT:-}" ]]; then
          printf '%s\n' "$FAKE_INSTRUMENTATION_OUTPUT"
        else
          echo "INSTRUMENTATION_STATUS: numtests=20"
          echo "OK (20 tests)"
        fi
        ;;
      am\ start\ -W\ -n*)
        echo "Status: ok"
        ;;
      run-as\ com.bytedance.zgx.pocketmind\ am\ broadcast\ -n\ com.bytedance.zgx.pocketmind/.debug.DebugRemoteConfigReceiver*)
        echo "Broadcast completed: result=0"
        ;;
      input\ tap*|input\ text*|input\ keyevent*)
        echo "OK"
        ;;
      uiautomator\ dump\ /sdcard/pocketmind-live-remote.xml)
        echo "UI hierchary dumped to: /sdcard/pocketmind-live-remote.xml"
        ;;
      *)
        echo "unexpected shell command: $*" >&2
        exit 2
        ;;
    esac
    ;;
  emu)
    shift
    case "$*" in
      "avd name")
        echo "test-avd"
        echo "OK"
        ;;
      *)
        echo "unexpected emulator command: $*" >&2
        exit 2
        ;;
    esac
    ;;
  install|uninstall)
    echo "Success"
    ;;
  exec-out)
    shift
    case "$*" in
      "screencap -p")
        printf 'fake-png\n'
        ;;
      *)
        echo "unexpected exec-out command: $*" >&2
        exit 2
        ;;
    esac
    ;;
  pull)
    source="${2:-}"
    destination="${3:-}"
    if [[ "$source" != "/sdcard/pocketmind-live-remote.xml" || -z "$destination" ]]; then
      echo "unexpected pull command: $*" >&2
      exit 2
    fi
    mkdir -p "$(dirname "$destination")"
    printf '<hierarchy><node text="%s" /></hierarchy>\n' "${POCKETMIND_LIVE_REMOTE_EXPECTED_TEXT:-POCKETMIND_LIVE_OK}" > "$destination"
    echo "1 file pulled"
    ;;
  *)
    echo "unexpected adb command: $*" >&2
    exit 2
    ;;
esac
FAKE_ADB
  chmod +x "$sdk/platform-tools/adb"
}

create_fake_emulator() {
  local sdk="$1"
  mkdir -p "$sdk/emulator"
  cat > "$sdk/emulator/emulator" <<'FAKE_EMULATOR'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == "-list-avds" ]]; then
  if [[ -n "${FAKE_EMULATOR_AVDS+x}" ]]; then
    printf '%s\n' "$FAKE_EMULATOR_AVDS"
  else
    echo "test-avd"
  fi
  exit 0
fi
printf '%s\n' "$*" >> "${FAKE_EMULATOR_LOG:?}"
FAKE_EMULATOR
  chmod +x "$sdk/emulator/emulator"
}

create_fake_gradle() {
  local path="$1"
  cat > "$path" <<'FAKE_GRADLE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "${FAKE_GRADLE_LOG:?}"
FAKE_GRADLE
  chmod +x "$path"
}

assert_no_gradle_call() {
  if [[ -s "$FAKE_GRADLE_LOG" ]]; then
    cat "$FAKE_GRADLE_LOG" >&2
    fail "Gradle should not be called before device preflight succeeds"
  fi
}

assert_gradle_called() {
  grep -q "assembleDebug assembleDebugAndroidTest" "$FAKE_GRADLE_LOG" ||
    fail "Expected install helper to assemble debug and androidTest APKs"
}

assert_report_contains() {
  local file="$1"
  local expected="$2"
  [[ -f "$file" ]] || fail "Expected verification report at $file"
  grep -qxF "$expected" "$file" ||
    fail "Expected $file to contain: $expected"
}

reset_logs() {
  : > "$FAKE_ADB_LOG"
  : > "$FAKE_EMULATOR_LOG"
  : > "$FAKE_GRADLE_LOG"
  rm -rf "$ARTIFACT_DIR"
  mkdir -p "$ARTIFACT_DIR"
}

count_android_tests() {
  find app/src/androidTest \( -name '*.kt' -o -name '*.java' \) -print0 |
    xargs -0 awk '/^[[:space:]]*@(org[.]junit[.])?Test([[:space:](]|$)/ {count += 1} END {print count + 0}'
}

NO_ADB_SDK="$TMP_DIR/no-adb-sdk"
NO_EMULATOR_SDK="$TMP_DIR/no-emulator-sdk"
FAKE_SDK="$TMP_DIR/fake-sdk"
FAKE_GRADLE="$TMP_DIR/fake-gradle"
export FAKE_ADB_LOG="$TMP_DIR/fake-adb.log"
export FAKE_EMULATOR_LOG="$TMP_DIR/fake-emulator.log"
export FAKE_GRADLE_LOG="$TMP_DIR/fake-gradle.log"
export ARTIFACT_DIR="$TMP_DIR/verification"

create_base_sdk "$NO_ADB_SDK"
create_base_sdk "$NO_EMULATOR_SDK"
create_fake_adb "$NO_EMULATOR_SDK"
create_base_sdk "$FAKE_SDK"
create_fake_adb "$FAKE_SDK"
create_fake_emulator "$FAKE_SDK"
create_fake_gradle "$FAKE_GRADLE"
reset_logs

SOURCE_ANDROID_TEST_COUNT="$(count_android_tests)"
if [[ "$SOURCE_ANDROID_TEST_COUNT" -le 1 ]]; then
  fail "Expected more than one AndroidTest source method for regression count tests"
fi
LOW_ANDROID_TEST_COUNT=$((SOURCE_ANDROID_TEST_COUNT - 1))
HIGH_ANDROID_TEST_COUNT=$((SOURCE_ANDROID_TEST_COUNT + 1))
SOURCE_INSTRUMENTATION_OUTPUT="$(printf 'INSTRUMENTATION_STATUS: numtests=%s\nOK (%s tests)' "$SOURCE_ANDROID_TEST_COUNT" "$SOURCE_ANDROID_TEST_COUNT")"
LOW_INSTRUMENTATION_OUTPUT="$(printf 'INSTRUMENTATION_STATUS: numtests=%s\nOK (%s tests)' "$LOW_ANDROID_TEST_COUNT" "$LOW_ANDROID_TEST_COUNT")"
HIGH_INSTRUMENTATION_OUTPUT="$(printf 'INSTRUMENTATION_STATUS: numtests=%s\nOK (%s tests)' "$HIGH_ANDROID_TEST_COUNT" "$HIGH_ANDROID_TEST_COUNT")"

ksp_line="$(grep -n 'GRADLE_CMD.*:app:kspReleaseKotlin' scripts/verify_local.sh | cut -d: -f1 | head -n 1)"
verify_line="$(grep -n 'GRADLE_CMD.*testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease' scripts/verify_local.sh | cut -d: -f1 | head -n 1)"
if [[ -z "$ksp_line" || -z "$verify_line" || "$ksp_line" -ge "$verify_line" ]]; then
  fail "verify_local.sh must generate release KSP sources before lintDebug"
fi
grep -q 'scripts/regression_emulator.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include regression_emulator.sh in shell syntax checks"
grep -q 'scripts/live_remote_emulator.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include live_remote_emulator.sh in shell syntax checks"
grep -q 'scripts/privacy_scan.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include privacy_scan.sh in shell syntax checks"
grep -q 'scripts/scan_android_artifacts.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include scan_android_artifacts.sh in shell syntax checks"
grep -q 'scripts/verify_perf_baseline.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_perf_baseline.sh in shell syntax checks"
grep -q 'scripts/verify_privacy_review.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_privacy_review.sh in shell syntax checks"
grep -q 'scripts/verify_model_license_review.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_model_license_review.sh in shell syntax checks"
grep -q 'scripts/verify_release_gate.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_release_gate.sh in shell syntax checks"
grep -q 'scripts/collect_perf_baseline.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include collect_perf_baseline.sh in shell syntax checks"
grep -q 'scripts/collect_model_license_metadata.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include collect_model_license_metadata.sh in shell syntax checks"
grep -q 'scripts/sign_release_artifacts.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include sign_release_artifacts.sh in shell syntax checks"

VALID_PERF="$TMP_DIR/perf-baseline.properties"
cat > "$VALID_PERF" <<'VALID_PERF_BASELINE'
status=passed
deviceSerial=device-a
deviceModel=Pixel Test
androidApi=36
abi=arm64-v8a
appVersion=1.0
releaseArtifactSha256=abc123
modelId=chat-e2b
backend=GPU
firstLaunchInteractiveMs=1200
modelLoadMs=3500
firstTokenMs=900
tokensPerSecond=12.5
stopGenerationRecoveryMs=200
gpuFallbackStatus=not-needed
visionInputMs=500
memorySearch5kMs=25
memoryPeakMb=512
oomOrAnrObserved=false
recordedAt=2026-06-06T00:00:00Z
VALID_PERF_BASELINE
expect_success \
  "perf baseline verifier accepts complete record" \
  scripts/verify_perf_baseline.sh --file "$VALID_PERF" --report "$ARTIFACT_DIR/perf.properties"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "status=passed"
expect_success \
  "perf baseline verifier accepts matching artifact sha" \
  scripts/verify_perf_baseline.sh --file "$VALID_PERF" --artifact-sha256 abc123 --report "$ARTIFACT_DIR/perf-sha.properties"
assert_report_contains "$ARTIFACT_DIR/perf-sha.properties" "expectedArtifactSha256=abc123"

INVALID_PERF="$TMP_DIR/perf-baseline-invalid.properties"
printf 'status=failed\n' > "$INVALID_PERF"
expect_failure \
  "perf baseline verifier rejects incomplete record" \
  scripts/verify_perf_baseline.sh --file "$INVALID_PERF" --report "$ARTIFACT_DIR/perf-invalid.properties"
assert_report_contains "$ARTIFACT_DIR/perf-invalid.properties" "status=failed"
expect_failure \
  "perf baseline verifier rejects mismatched artifact sha" \
  scripts/verify_perf_baseline.sh --file "$VALID_PERF" --artifact-sha256 different-sha --report "$ARTIFACT_DIR/perf-sha-failed.properties"
assert_report_contains "$ARTIFACT_DIR/perf-sha-failed.properties" "status=failed"

SAFE_PRIVACY_DIR="$TMP_DIR/privacy-safe"
mkdir -p "$SAFE_PRIVACY_DIR"
printf 'hello pocketmind\n' > "$SAFE_PRIVACY_DIR/readme.txt"
expect_success \
  "privacy scan accepts safe directory" \
  scripts/privacy_scan.sh --report "$ARTIFACT_DIR/privacy.properties" "$SAFE_PRIVACY_DIR"
assert_report_contains "$ARTIFACT_DIR/privacy.properties" "status=passed"

UNSAFE_PRIVACY_DIR="$TMP_DIR/privacy-unsafe"
mkdir -p "$UNSAFE_PRIVACY_DIR"
printf 'token=sk-%s\n' "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" > "$UNSAFE_PRIVACY_DIR/secret.txt"
expect_failure \
  "privacy scan rejects high-confidence token" \
  scripts/privacy_scan.sh --report "$ARTIFACT_DIR/privacy-failed.properties" "$UNSAFE_PRIVACY_DIR"
assert_report_contains "$ARTIFACT_DIR/privacy-failed.properties" "status=failed"

PRIVACY_NOTICE="$TMP_DIR/privacy-notice.md"
PRIVACY_REVIEW_PENDING="$TMP_DIR/privacy-review-pending.json"
PRIVACY_REVIEW_APPROVED="$TMP_DIR/privacy-review-approved.json"
printf 'PocketMind privacy notice\n' > "$PRIVACY_NOTICE"
PRIVACY_NOTICE_SHA="$(shasum -a 256 "$PRIVACY_NOTICE" | awk '{print $1}')"
cat > "$PRIVACY_REVIEW_PENDING" <<'PRIVACY_REVIEW_PENDING_JSON'
{
  "version": 1,
  "noticePath": "PLACEHOLDER",
  "noticeSha256": "PLACEHOLDER",
  "status": "pending_manual_review",
  "reviews": []
}
PRIVACY_REVIEW_PENDING_JSON
expect_failure \
  "privacy review verifier rejects pending records" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_PENDING" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-pending.properties"
assert_report_contains "$ARTIFACT_DIR/privacy-review-pending.properties" "status=failed"
cat > "$PRIVACY_REVIEW_APPROVED" <<PRIVACY_REVIEW_APPROVED_JSON
{
  "version": 1,
  "noticePath": "$PRIVACY_NOTICE",
  "noticeSha256": "$PRIVACY_NOTICE_SHA",
  "status": "approved",
  "reviews": [
    {
      "role": "release",
      "decision": "approved",
      "reviewer": "Release Reviewer",
      "reviewDate": "2026-06-06"
    },
    {
      "role": "security",
      "decision": "approved",
      "reviewer": "Security Reviewer",
      "reviewDate": "2026-06-06"
    },
    {
      "role": "legal",
      "decision": "approved",
      "reviewer": "Legal Reviewer",
      "reviewDate": "2026-06-06"
    }
  ]
}
PRIVACY_REVIEW_APPROVED_JSON
expect_success \
  "privacy review verifier accepts approved current notice" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_APPROVED" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-approved.properties"
assert_report_contains "$ARTIFACT_DIR/privacy-review-approved.properties" "status=passed"
PRIVACY_REVIEW_FUTURE="$TMP_DIR/privacy-review-future.json"
sed 's/2026-06-06/2999-01-01/g' "$PRIVACY_REVIEW_APPROVED" > "$PRIVACY_REVIEW_FUTURE"
expect_failure \
  "privacy review verifier rejects future review dates" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_FUTURE" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-future.properties"
assert_report_contains "$ARTIFACT_DIR/privacy-review-future.properties" "status=failed"

MODEL_LICENSE_METADATA="$TMP_DIR/model-license-metadata.json"
MODEL_LICENSE_PENDING="$TMP_DIR/model-license-pending.json"
MODEL_LICENSE_APPROVED="$TMP_DIR/model-license-approved.json"
cat > "$MODEL_LICENSE_METADATA" <<'MODEL_LICENSE_METADATA_JSON'
{
  "version": 1,
  "models": [
    {
      "id": "chat-e2b",
      "metadataOnly": true
    },
    {
      "id": "memory-embedding-300m",
      "metadataOnly": true
    }
  ]
}
MODEL_LICENSE_METADATA_JSON
cat > "$MODEL_LICENSE_PENDING" <<'MODEL_LICENSE_PENDING_JSON'
{
  "version": 1,
  "models": [
    {
      "id": "chat-e2b",
      "status": "pending_manual_review",
      "licenseName": "",
      "licenseUrl": "https://example.com/model",
      "redistributionDecision": "not_approved",
      "attributionNotice": "",
      "reviewer": "",
      "reviewDate": ""
    }
  ]
}
MODEL_LICENSE_PENDING_JSON
expect_failure \
  "model license verifier rejects incomplete review records" \
  env MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_PENDING" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-pending.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-pending.properties" "status=failed"
cat > "$MODEL_LICENSE_APPROVED" <<'MODEL_LICENSE_APPROVED_JSON'
{
  "version": 1,
  "models": [
    {
      "id": "chat-e2b",
      "status": "approved",
      "licenseName": "Apache-2.0",
      "licenseUrl": "https://example.com/chat-license",
      "redistributionDecision": "approved",
      "attributionNotice": "Include Apache-2.0 notice.",
      "reviewer": "Model Reviewer",
      "reviewDate": "2026-06-06"
    },
    {
      "id": "memory-embedding-300m",
      "status": "approved",
      "licenseName": "Apache-2.0",
      "licenseUrl": "https://example.com/memory-license",
      "redistributionDecision": "approved",
      "attributionNotice": "Include Apache-2.0 notice.",
      "reviewer": "Model Reviewer",
      "reviewDate": "2026-06-06"
    }
  ]
}
MODEL_LICENSE_APPROVED_JSON
expect_success \
  "model license verifier accepts approved metadata-aligned records" \
  env MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-approved.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-approved.properties" "status=passed"
MODEL_LICENSE_FUTURE="$TMP_DIR/model-license-future.json"
sed 's/2026-06-06/2999-01-01/g' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_FUTURE"
expect_failure \
  "model license verifier rejects future review dates" \
  env MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_FUTURE" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-future.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-future.properties" "status=failed"

SAFE_APK="$TMP_DIR/safe.apk"
SAFE_AAB="$TMP_DIR/safe.aab"
UNSAFE_APK="$TMP_DIR/unsafe.apk"
mkdir -p "$TMP_DIR/safe-zip/assets" "$TMP_DIR/unsafe-zip/assets"
printf 'ok\n' > "$TMP_DIR/safe-zip/assets/readme.txt"
printf 'model\n' > "$TMP_DIR/unsafe-zip/assets/model.litertlm"
(cd "$TMP_DIR/safe-zip" && zip -qr "$SAFE_APK" .)
cp "$SAFE_APK" "$SAFE_AAB"
(cd "$TMP_DIR/unsafe-zip" && zip -qr "$UNSAFE_APK" .)
expect_success \
  "artifact scan accepts safe zip" \
  scripts/scan_android_artifacts.sh --apk "$SAFE_APK" --report "$ARTIFACT_DIR/artifact.properties"
assert_report_contains "$ARTIFACT_DIR/artifact.properties" "status=passed"
grep -q '^artifact1Sha256=' "$ARTIFACT_DIR/artifact.properties" ||
  fail "artifact scan report must include artifact sha"
grep -q '^artifact1SizeBytes=' "$ARTIFACT_DIR/artifact.properties" ||
  fail "artifact scan report must include artifact size"
expect_failure \
  "artifact scan rejects bundled model" \
  scripts/scan_android_artifacts.sh --apk "$UNSAFE_APK" --report "$ARTIFACT_DIR/artifact-failed.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-failed.properties" "status=failed"
expect_failure \
  "artifact scan require-signed rejects unsigned zip" \
  scripts/scan_android_artifacts.sh --apk "$SAFE_APK" --require-signed --report "$ARTIFACT_DIR/artifact-unsigned.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-unsigned.properties" "status=failed"
expect_failure \
  "artifact scan require-signed rejects unsigned aab" \
  scripts/scan_android_artifacts.sh --aab "$SAFE_AAB" --require-signed --report "$ARTIFACT_DIR/artifact-unsigned-aab.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-unsigned-aab.properties" "status=failed"
DEBUG_SCAN_KEYSTORE="$TMP_DIR/debug-scan.keystore"
DEBUG_SIGNED_AAB="$TMP_DIR/debug-signed.aab"
cp "$SAFE_AAB" "$DEBUG_SIGNED_AAB"
keytool -genkeypair \
  -keystore "$DEBUG_SCAN_KEYSTORE" \
  -storepass android \
  -keypass android \
  -alias androiddebugkey \
  -dname "CN=Android Debug,O=Android,C=US" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 >/dev/null
jarsigner \
  -keystore "$DEBUG_SCAN_KEYSTORE" \
  -storepass android \
  -keypass android \
  "$DEBUG_SIGNED_AAB" \
  androiddebugkey >/dev/null
DEBUG_SIGNED_AAB_CERT_SHA="$(
  keytool -printcert -jarfile "$DEBUG_SIGNED_AAB" 2>/dev/null |
    awk -F': ' '/SHA256:/ {gsub(":", "", $2); print tolower($2); exit}'
)"
expect_failure \
  "artifact scan require-signed rejects debug certificate" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --report "$ARTIFACT_DIR/artifact-debug-cert.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-debug-cert.properties" "status=failed"
expect_success \
  "artifact scan allows debug certificate only for smoke" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --allow-debug-certificate --report "$ARTIFACT_DIR/artifact-debug-cert-smoke.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-debug-cert-smoke.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/artifact-debug-cert-smoke.properties" "allowDebugCertificate=1"
expect_failure \
  "artifact scan rejects unexpected signing certificate" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --allow-debug-certificate --expected-certificate-sha256 0000000000000000000000000000000000000000000000000000000000000000 --report "$ARTIFACT_DIR/artifact-cert-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-cert-mismatch.properties" "status=failed"
expect_success \
  "artifact scan accepts expected signing certificate" \
  scripts/scan_android_artifacts.sh --aab "$DEBUG_SIGNED_AAB" --require-signed --allow-debug-certificate --expected-certificate-sha256 "$DEBUG_SIGNED_AAB_CERT_SHA" --report "$ARTIFACT_DIR/artifact-cert-match.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-cert-match.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/artifact-cert-match.properties" "expectedCertificateSha256=$DEBUG_SIGNED_AAB_CERT_SHA"

VALID_GATE_PERF="$TMP_DIR/perf-baseline-safe-apk.properties"
SAFE_APK_SHA="$(shasum -a 256 "$SAFE_APK" | awk '{print $1}')"
cat > "$VALID_GATE_PERF" <<VALID_GATE_PERF_BASELINE
status=passed
deviceSerial=device-a
deviceModel=Pixel Test
androidApi=36
abi=arm64-v8a
appVersion=1.0
releaseArtifactSha256=$SAFE_APK_SHA
modelId=chat-e2b
backend=GPU
firstLaunchInteractiveMs=1200
modelLoadMs=3500
firstTokenMs=900
tokensPerSecond=12.5
stopGenerationRecoveryMs=200
gpuFallbackStatus=not-needed
visionInputMs=500
memorySearch5kMs=25
memoryPeakMb=512
oomOrAnrObserved=false
recordedAt=2026-06-06T00:00:00Z
VALID_GATE_PERF_BASELINE
expect_failure \
  "release gate requires approved privacy review when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-privacy-review" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_PRIVACY_REVIEW=1 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-privacy-review/privacy-review.properties" "status=failed"
expect_failure \
  "release gate requires approved model license review when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-model-license" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_MODEL_LICENSES=1 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-model-license/model-license-review.properties" "status=failed"
expect_failure \
  "public release profile requires expected signing certificate" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/public-release-missing-cert" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  PUBLIC_RELEASE=1 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "publicRelease=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyPrivacyReview=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyModelLicenses=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireAab=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireSignedArtifact=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/signing-cert.properties" "status=failed"

expect_failure \
  "release gate requires aab when public gate requests it" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-require-aab" \
  PERF_BASELINE_FILE="$VALID_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  REQUIRE_AAB=1 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-require-aab/android-artifact-scan.properties" "status=failed"
expect_failure \
  "signing helper requires private keystore environment" \
  scripts/sign_release_artifacts.sh
DEBUG_KEYSTORE="$TMP_DIR/debug.keystore"
printf 'not-a-real-keystore\n' > "$DEBUG_KEYSTORE"
expect_failure \
  "signing helper rejects debug keystore by default" \
  env RELEASE_KEYSTORE="$DEBUG_KEYSTORE" \
  RELEASE_KEY_ALIAS=androiddebugkey \
  RELEASE_KEYSTORE_PASSWORD=android \
  RELEASE_KEY_PASSWORD=android \
  scripts/sign_release_artifacts.sh
grep -q 'Refusing Android debug keystore' <<<"$LAST_OUTPUT" ||
  fail "Expected signing helper to refuse debug keystore before signing"
PRODUCTION_KEYSTORE="$TMP_DIR/production-upload.keystore"
printf 'not-a-real-keystore\n' > "$PRODUCTION_KEYSTORE"
expect_failure \
  "signing helper requires expected production certificate" \
  env RELEASE_KEYSTORE="$PRODUCTION_KEYSTORE" \
  RELEASE_KEY_ALIAS=upload \
  RELEASE_KEYSTORE_PASSWORD=secret \
  RELEASE_KEY_PASSWORD=secret \
  scripts/sign_release_artifacts.sh
grep -q 'Production release signing requires EXPECTED_SIGNING_CERT_SHA256' <<<"$LAST_OUTPUT" ||
  fail "Expected signing helper to require expected production certificate before signing"

COLLECTED_PERF="$ARTIFACT_DIR/collected-perf.properties"
expect_success \
  "perf baseline collector writes verifiable record from measured inputs" \
  env ADB="$TMP_DIR/missing-adb" \
  OUT_FILE="$COLLECTED_PERF" \
  RELEASE_ARTIFACT="$SAFE_APK" \
  ANDROID_SERIAL=device-a \
  DEVICE_MODEL="Pixel Test" \
  ANDROID_API=36 \
  ABI=arm64-v8a \
  APP_VERSION=1.0 \
  MODEL_ID=chat-e2b \
  BACKEND=GPU \
  FIRST_LAUNCH_INTERACTIVE_MS=1200 \
  MODEL_LOAD_MS=3500 \
  FIRST_TOKEN_MS=900 \
  TOKENS_PER_SECOND=12.5 \
  STOP_GENERATION_RECOVERY_MS=200 \
  GPU_FALLBACK_STATUS=not-needed \
  VISION_INPUT_MS=500 \
  MEMORY_SEARCH_5K_MS=25 \
  MEMORY_PEAK_MB=512 \
  OOM_OR_ANR_OBSERVED=false \
  scripts/collect_perf_baseline.sh
assert_report_contains "$COLLECTED_PERF" "status=passed"

expect_success \
  "doctor local without adb" \
  env ANDROID_SDK_ROOT="$NO_ADB_SDK" ANDROID_HOME="$NO_ADB_SDK" \
  scripts/doctor.sh --local

expect_failure \
  "doctor device without adb" \
  env ANDROID_SDK_ROOT="$NO_ADB_SDK" ANDROID_HOME="$NO_ADB_SDK" \
  scripts/doctor.sh --device

reset_logs
expect_failure \
  "install helper without devices" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES="" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "target=device"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=not-run"

reset_logs
expect_failure \
  "install helper with unauthorized device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tunauthorized' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with multiple devices and no serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice\ndevice-b\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with offline selected serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\toffline' ANDROID_SERIAL="device-a" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper with missing selected serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-b" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "install helper rejects non arm64 device before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' FAKE_ABI_LIST="armeabi-v7a,x86" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_no_gradle_call
grep -q "not arm64-v8a compatible" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to reject non arm64-v8a devices"
grep -q -- "-s device-a shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to check selected device ABI before Gradle"

reset_logs
expect_failure \
  "install helper rejects low data partition space before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' FAKE_ABI_LIST="arm64-v8a,armeabi-v7a" \
  FAKE_DATA_FREE_KB="3145727" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_no_gradle_call
grep -q "less than 3 GB free on /data" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to reject devices with low /data free space"
grep -q -- "-s device-a shell df -k /data" "$FAKE_ADB_LOG" ||
  fail "Expected install helper to check /data free space before Gradle"

reset_logs
expect_success \
  "install helper selects the only authorized device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "abi=arm64-v8a,armeabi-v7a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=20"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
grep -q "OK (20 tests)" "$ARTIFACT_DIR/instrumentation.txt" ||
  fail "Expected install helper to persist instrumentation output"
grep -q -- "-s device-a shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected adb device commands to target the only authorized device"

reset_logs
expect_failure \
  "install helper rejects instrumentation numtests without final OK" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'INSTRUMENTATION_STATUS: numtests=20' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=20"
grep -q "final OK/success marker" <<<"$LAST_OUTPUT" ||
  fail "Expected install helper to reject malformed instrumentation output without final OK"

reset_logs
expect_success \
  "install helper selects requested serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice\ndevice-b\tdevice' ANDROID_SERIAL="device-b" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
grep -q -- "-s device-b shell getprop ro.product.cpu.abilist64" "$FAKE_ADB_LOG" ||
  fail "Expected adb device commands to target ANDROID_SERIAL"
grep -q -- "-s device-b install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected debug APK install to target ANDROID_SERIAL"

reset_logs
expect_failure \
  "install helper fails failed instrumentation output even when adb exits zero" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'FAILURES!!!\nTests run: 3,  Failures: 1\nINSTRUMENTATION_CODE: -1' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/install_and_test_device.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=device-a"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=failed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=3"
grep -q "FAILURES!!!" "$ARTIFACT_DIR/instrumentation.txt" ||
  fail "Expected failed instrumentation output to be persisted"

reset_logs
expect_failure \
  "emulator helper rejects physical serial" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' ANDROID_SERIAL="device-a" \
  EMULATOR_SELECT_TIMEOUT_SECONDS=0 GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "emulator helper rejects physical-only devices" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'device-a\tdevice' EMULATOR_SELECT_TIMEOUT_SECONDS=0 \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_no_gradle_call

reset_logs
expect_failure \
  "emulator helper without emulator binary" \
  env ANDROID_SDK_ROOT="$NO_EMULATOR_SDK" ANDROID_HOME="$NO_EMULATOR_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_no_gradle_call
grep -q "Android emulator binary not found" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to report missing emulator binary"

reset_logs
expect_failure \
  "emulator helper rejects unknown requested AVD" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES="" FAKE_EMULATOR_AVDS="other-avd" AVD_NAME="test-avd" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_no_gradle_call
grep -q "AVD_NAME=test-avd not found" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to report unknown AVD"

reset_logs
expect_success \
  "emulator helper selects the only authorized emulator" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "target=emulator"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "avd=test-avd"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "device_report_file=$ARTIFACT_DIR/device-verification.properties"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation=passed"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_test_count=20"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
grep -q -- "-s emulator-5554 shell getprop sys.boot_completed" "$FAKE_ADB_LOG" ||
  fail "Expected emulator helper to wait for emulator boot completion"
grep -q -- "-s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk" "$FAKE_ADB_LOG" ||
  fail "Expected emulator helper to install debug APK on selected emulator"

reset_logs
expect_success \
  "emulator helper starts requested AVD" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5556\tdevice' AVD_NAME="test-avd" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/verify_emulator.sh
assert_gradle_called
grep -q "Starting emulator AVD: test-avd" <<<"$LAST_OUTPUT" ||
  fail "Expected emulator helper to enter AVD startup path"

reset_logs
SAVED_ARTIFACT_DIR="$ARTIFACT_DIR"
expect_success \
  "emulator helper keeps default device artifacts with emulator artifacts" \
  env -u ARTIFACT_DIR ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  scripts/verify_emulator.sh
EMULATOR_DEFAULT_REPORT="$(sed -nE 's/^Emulator verification report: (.*)$/\1/p' <<<"$LAST_OUTPUT" | tail -n 1)"
[[ -n "$EMULATOR_DEFAULT_REPORT" && -f "$EMULATOR_DEFAULT_REPORT" ]] ||
  fail "Expected emulator helper to print its default report path"
DEFAULT_DEVICE_REPORT="$(awk -F= '$1 == "device_report_file" {print $2}' "$EMULATOR_DEFAULT_REPORT")"
[[ -n "$DEFAULT_DEVICE_REPORT" && -f "$DEFAULT_DEVICE_REPORT" ]] ||
  fail "Expected default emulator report to link an existing nested device report"
DEFAULT_INSTRUMENTATION_OUTPUT="$(awk -F= '$1 == "instrumentation_output_file" {print $2}' "$DEFAULT_DEVICE_REPORT")"
[[ "$DEFAULT_INSTRUMENTATION_OUTPUT" == "$(dirname "$DEFAULT_DEVICE_REPORT")/instrumentation.txt" ]] ||
  fail "Expected default nested instrumentation output to live beside the device report"
[[ -s "$DEFAULT_INSTRUMENTATION_OUTPUT" ]] ||
  fail "Expected default nested instrumentation output to be non-empty"
ARTIFACT_DIR="$SAVED_ARTIFACT_DIR"
export ARTIFACT_DIR

reset_logs
LIVE_REMOTE_TEST_TOKEN="$TMP_DIR/live-remote-token-from-env"
expect_success \
  "live remote emulator uses app uid for debug receiver broadcasts" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' GRADLE_CMD="$FAKE_GRADLE" \
  POCKETMIND_LIVE_REMOTE_BASE_URL="https://remote.example.test/v1" \
  POCKETMIND_LIVE_REMOTE_MODEL="validation-model" \
  POCKETMIND_LIVE_REMOTE_API_KEY="$LIVE_REMOTE_TEST_TOKEN" \
  POCKETMIND_LIVE_REMOTE_WAIT_SECONDS=0 \
  scripts/live_remote_emulator.sh
grep -q -- ":app:assembleDebug" "$FAKE_GRADLE_LOG" ||
  fail "Expected live remote helper to assemble the debug APK"
receiver_broadcast_count="$(
  grep -c -- "shell run-as com.bytedance.zgx.pocketmind am broadcast -n com.bytedance.zgx.pocketmind/.debug.DebugRemoteConfigReceiver" "$FAKE_ADB_LOG" || true
)"
[[ "$receiver_broadcast_count" -ge 2 ]] ||
  fail "Expected live remote helper to configure and clear the debug receiver through run-as"
if grep -q -- "-s emulator-5554 shell am broadcast" "$FAKE_ADB_LOG"; then
  fail "Live remote helper must not broadcast to the debug receiver from the shell uid"
fi
grep -q -- "--ez clearState true" "$FAKE_ADB_LOG" ||
  fail "Expected live remote helper to request state clearing during setup"
grep -q -- "--ez clearRemoteConfig true" "$FAKE_ADB_LOG" ||
  fail "Expected live remote helper to clear remote config on exit"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "api_key_source=POCKETMIND_LIVE_REMOTE_API_KEY"
assert_report_contains "$ARTIFACT_DIR/live-remote-emulator.properties" "base_url=<redacted>"
if grep -Fq "$LIVE_REMOTE_TEST_TOKEN" "$ARTIFACT_DIR/live-remote-emulator.properties"; then
  fail "Live remote report must not persist the remote API key"
fi

REGRESSION_COUNT_FIXTURE="$TMP_DIR/android-test-count-fixture"
mkdir -p "$REGRESSION_COUNT_FIXTURE/java/example"
cat > "$REGRESSION_COUNT_FIXTURE/java/example/FixtureTest.kt" <<'COUNT_FIXTURE'
package example

class FixtureTest {
    @Test
    fun bareAnnotation() = Unit

    @Test()
    fun emptyArguments() = Unit

    @Test(timeout = 1)
    fun annotationArguments() = Unit

    @org.junit.Test
    fun fullyQualifiedAnnotation() = Unit
}
COUNT_FIXTURE

reset_logs
expect_success \
  "regression emulator validates reports and forces clean device" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT="$SOURCE_INSTRUMENTATION_OUTPUT" \
  CLEAN_DEVICE=0 GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "target=regression-emulator"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "source_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/emulator-verification.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "clean_device=1"
assert_report_contains "$ARTIFACT_DIR/device-verification.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
grep -q -- "-s emulator-5554 uninstall com.bytedance.zgx.pocketmind" "$FAKE_ADB_LOG" ||
  fail "Expected regression emulator to force CLEAN_DEVICE=1 through device helper"

reset_logs
expect_failure \
  "regression emulator fails when instrumentation count is below source count" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT="$LOW_INSTRUMENTATION_OUTPUT" GRADLE_CMD="$FAKE_GRADLE" \
  scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=$LOW_ANDROID_TEST_COUNT"
grep -q "expected at least $SOURCE_ANDROID_TEST_COUNT" <<<"$LAST_OUTPUT" ||
  fail "Expected regression emulator to explain insufficient instrumentation count"

reset_logs
expect_success \
  "regression emulator honors higher expected count override" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT="$HIGH_INSTRUMENTATION_OUTPUT" EXPECTED_ANDROID_TEST_COUNT="$HIGH_ANDROID_TEST_COUNT" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "source_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$HIGH_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=$HIGH_ANDROID_TEST_COUNT"

reset_logs
expect_failure \
  "regression emulator rejects expected count override below source count before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' EXPECTED_ANDROID_TEST_COUNT="$LOW_ANDROID_TEST_COUNT" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "source_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$LOW_ANDROID_TEST_COUNT"
grep -q "cannot be lower than AndroidTest source count" <<<"$LAST_OUTPUT" ||
  fail "Expected regression emulator to reject lowered expected count override"

reset_logs
expect_success \
  "regression emulator counts supported JUnit test annotations" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  ANDROID_TEST_SOURCE_DIR="$REGRESSION_COUNT_FIXTURE" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'INSTRUMENTATION_STATUS: numtests=4\nOK (4 tests)' \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=4"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=4"

reset_logs
expect_failure \
  "regression emulator rejects invalid expected count before Gradle" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' EXPECTED_ANDROID_TEST_COUNT=abc \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=abc"

reset_logs
expect_failure \
  "regression emulator fails when instrumentation count is missing" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' FAKE_INSTRUMENTATION_OUTPUT="OK" \
  EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT" GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "expected_android_test_count=$SOURCE_ANDROID_TEST_COUNT"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count="
grep -q "instrumentation_test_count" <<<"$LAST_OUTPUT" ||
  fail "Expected regression emulator to explain missing instrumentation count"

reset_logs
expect_failure \
  "regression emulator writes failed report when emulator helper fails preflight" \
  env ANDROID_SDK_ROOT="$NO_EMULATOR_SDK" ANDROID_HOME="$NO_EMULATOR_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT" \
  GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_no_gradle_call
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "target=regression-emulator"

reset_logs
expect_failure \
  "regression emulator failed report harvests nested device evidence" \
  env ANDROID_SDK_ROOT="$FAKE_SDK" ANDROID_HOME="$FAKE_SDK" \
  FAKE_ADB_DEVICES=$'emulator-5554\tdevice' \
  FAKE_INSTRUMENTATION_OUTPUT=$'INSTRUMENTATION_STATUS: numtests=3\nFAILURES!!!\nTests run: 3,  Failures: 1\nINSTRUMENTATION_CODE: -1' \
  EXPECTED_ANDROID_TEST_COUNT="$SOURCE_ANDROID_TEST_COUNT" GRADLE_CMD="$FAKE_GRADLE" scripts/regression_emulator.sh
assert_gradle_called
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "serial=emulator-5554"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "api_level=36"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "abi=arm64-v8a,armeabi-v7a"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "actual_android_test_count=3"
assert_report_contains "$ARTIFACT_DIR/regression-emulator.properties" "instrumentation_output_file=$ARTIFACT_DIR/instrumentation.txt"
grep -q "FAILURES!!!" "$ARTIFACT_DIR/instrumentation.txt" ||
  fail "Expected regression failed report to link persisted instrumentation failure output"

echo "Validation script tests passed."

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

TMP_DIR="$(mktemp -d)"
CLEANUP_PATHS=()
cleanup_validation_test() {
  rm -rf "$TMP_DIR"
  local path
  for path in "${CLEANUP_PATHS[@]}"; do
    rm -f "$path"
  done
}
trap cleanup_validation_test EXIT

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

assert_report_contains_text() {
  local file="$1"
  local expected="$2"
  [[ -f "$file" ]] || fail "Expected verification report at $file"
  grep -qF "$expected" "$file" ||
    fail "Expected $file to contain text: $expected"
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
verify_line="$(grep -n 'GRADLE_CMD.*testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleRelease bundleRelease' scripts/verify_local.sh | cut -d: -f1 | head -n 1)"
if [[ -z "$ksp_line" || -z "$verify_line" || "$ksp_line" -ge "$verify_line" ]]; then
  fail "verify_local.sh must generate release KSP sources before lintDebug"
fi
grep -q 'RELEASE_AAB="app/build/outputs/bundle/release/app-release.aab"' scripts/verify_local.sh ||
  fail "verify_local.sh must verify the release AAB artifact"
grep -q -- '--aab "$RELEASE_AAB"' scripts/verify_local.sh ||
  fail "verify_local.sh must scan the release AAB artifact"
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
grep -q 'scripts/verify_release_record.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_release_record.sh in shell syntax checks"
grep -q 'scripts/verify_store_policy_record.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_store_policy_record.sh in shell syntax checks"
grep -q 'scripts/verify_release_operations_record.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_release_operations_record.sh in shell syntax checks"
grep -q 'scripts/verify_release_validation_record.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_release_validation_record.sh in shell syntax checks"
grep -q 'scripts/verify_model_license_review.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_model_license_review.sh in shell syntax checks"
grep -q 'scripts/verify_release_mapping.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_release_mapping.sh in shell syntax checks"
grep -q 'scripts/verify_release_gate.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include verify_release_gate.sh in shell syntax checks"
grep -q 'scripts/collect_perf_baseline.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include collect_perf_baseline.sh in shell syntax checks"
grep -q 'scripts/collect_model_license_metadata.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include collect_model_license_metadata.sh in shell syntax checks"
grep -q 'scripts/sign_release_artifacts.sh' scripts/verify_local.sh ||
  fail "verify_local.sh must include sign_release_artifacts.sh in shell syntax checks"

VALID_PERF="$TMP_DIR/perf-baseline.properties"
VALID_PERF_SHA="1111111111111111111111111111111111111111111111111111111111111111"
PERF_RECORDED_AT="$(date -u +%Y-%m-%dT00:00:00Z)"
cat > "$VALID_PERF" <<VALID_PERF_BASELINE
status=passed
deviceSerial=device-a
deviceModel=Pixel Test
androidApi=36
abi=arm64-v8a
appVersion=0.1.0
releaseArtifactSha256=$VALID_PERF_SHA
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
recordedAt=$PERF_RECORDED_AT
VALID_PERF_BASELINE
expect_success \
  "perf baseline verifier accepts complete record" \
  scripts/verify_perf_baseline.sh --file "$VALID_PERF" --app-version 0.1.0 --report "$ARTIFACT_DIR/perf.properties"
assert_report_contains "$ARTIFACT_DIR/perf.properties" "status=passed"
expect_success \
  "perf baseline verifier accepts matching artifact sha" \
  scripts/verify_perf_baseline.sh --file "$VALID_PERF" --artifact-sha256 "$VALID_PERF_SHA" --report "$ARTIFACT_DIR/perf-sha.properties"
assert_report_contains "$ARTIFACT_DIR/perf-sha.properties" "expectedArtifactSha256=$VALID_PERF_SHA"

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
EMULATOR_PERF="$TMP_DIR/perf-baseline-emulator.properties"
sed 's/deviceSerial=device-a/deviceSerial=emulator-5554/' "$VALID_PERF" > "$EMULATOR_PERF"
expect_failure \
  "perf baseline verifier rejects emulator serials" \
  scripts/verify_perf_baseline.sh --file "$EMULATOR_PERF" --report "$ARTIFACT_DIR/perf-emulator.properties"
assert_report_contains "$ARTIFACT_DIR/perf-emulator.properties" "status=failed"
ZERO_PERF="$TMP_DIR/perf-baseline-zero.properties"
sed 's/firstTokenMs=900/firstTokenMs=0/' "$VALID_PERF" > "$ZERO_PERF"
expect_failure \
  "perf baseline verifier rejects zero critical timings" \
  scripts/verify_perf_baseline.sh --file "$ZERO_PERF" --report "$ARTIFACT_DIR/perf-zero.properties"
assert_report_contains "$ARTIFACT_DIR/perf-zero.properties" "status=failed"
FUTURE_PERF="$TMP_DIR/perf-baseline-future.properties"
sed 's/recordedAt=.*/recordedAt=2999-01-01T00:00:00Z/' "$VALID_PERF" > "$FUTURE_PERF"
expect_failure \
  "perf baseline verifier rejects future recordedAt" \
  scripts/verify_perf_baseline.sh --file "$FUTURE_PERF" --report "$ARTIFACT_DIR/perf-future.properties"
assert_report_contains "$ARTIFACT_DIR/perf-future.properties" "status=failed"

RELEASE_MAPPING="$TMP_DIR/mapping.txt"
printf 'com.bytedance.zgx.pocketmind.Sample -> a:\n' > "$RELEASE_MAPPING"
expect_success \
  "release mapping verifier accepts non-empty mapping file" \
  scripts/verify_release_mapping.sh --file "$RELEASE_MAPPING" --report "$ARTIFACT_DIR/release-mapping.properties"
assert_report_contains "$ARTIFACT_DIR/release-mapping.properties" "status=passed"
grep -q '^mappingSha256=' "$ARTIFACT_DIR/release-mapping.properties" ||
  fail "release mapping report must include mapping sha"
grep -q '^mappingSizeBytes=' "$ARTIFACT_DIR/release-mapping.properties" ||
  fail "release mapping report must include mapping size"
expect_failure \
  "release mapping verifier rejects missing mapping file" \
  scripts/verify_release_mapping.sh --file "$TMP_DIR/missing-mapping.txt" --report "$ARTIFACT_DIR/release-mapping-missing.properties"
assert_report_contains "$ARTIFACT_DIR/release-mapping-missing.properties" "status=failed"
EMPTY_RELEASE_MAPPING="$TMP_DIR/empty-mapping.txt"
: > "$EMPTY_RELEASE_MAPPING"
expect_failure \
  "release mapping verifier rejects empty mapping file" \
  scripts/verify_release_mapping.sh --file "$EMPTY_RELEASE_MAPPING" --report "$ARTIFACT_DIR/release-mapping-empty.properties"
assert_report_contains "$ARTIFACT_DIR/release-mapping-empty.properties" "status=failed"

RELEASE_RECORD_ARTIFACT="$TMP_DIR/release-record.aab"
RELEASE_RECORD_REPORT="$TMP_DIR/release-record-report.properties"
RELEASE_RECORD_FAILED_REPORT="$TMP_DIR/release-record-failed-report.properties"
RELEASE_RECORD_PENDING="$TMP_DIR/release-record-pending.json"
RELEASE_RECORD_APPROVED="$TMP_DIR/release-record-approved.json"
printf 'release artifact\n' > "$RELEASE_RECORD_ARTIFACT"
printf 'status=passed\ntarget=local-verification\n' > "$RELEASE_RECORD_REPORT"
printf 'status=failed\ntarget=local-verification\n' > "$RELEASE_RECORD_FAILED_REPORT"
RELEASE_RECORD_ARTIFACT_SHA="$(shasum -a 256 "$RELEASE_RECORD_ARTIFACT" | awk '{print $1}')"
RELEASE_RECORD_ARTIFACT_SIZE="$(wc -c < "$RELEASE_RECORD_ARTIFACT" | tr -d ' ')"
RELEASE_RECORD_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_REPORT" | awk '{print $1}')"
RELEASE_RECORD_FAILED_REPORT_SHA="$(shasum -a 256 "$RELEASE_RECORD_FAILED_REPORT" | awk '{print $1}')"
RELEASE_RECORD_HEAD="$(git rev-parse HEAD)"
RELEASE_RECORD_NON_HEAD="$(git rev-parse HEAD^)"
RELEASE_RECORD_DATE="$(date +%F)"
cat > "$RELEASE_RECORD_PENDING" <<'RELEASE_RECORD_PENDING_JSON'
{
  "version": 1,
  "status": "pending_release_record",
  "release": {}
}
RELEASE_RECORD_PENDING_JSON
expect_failure \
  "release record verifier rejects pending records" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PENDING" --report "$ARTIFACT_DIR/release-record-pending.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-pending.properties" "status=failed"
cat > "$RELEASE_RECORD_APPROVED" <<RELEASE_RECORD_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "release": {
    "applicationId": "com.bytedance.zgx.pocketmind",
    "versionCode": 1,
    "versionName": "0.1.0",
    "gitCommit": "$RELEASE_RECORD_HEAD",
    "gitBranch": "main",
    "targetChannel": "internal_testing",
    "releaseDate": "$RELEASE_RECORD_DATE",
    "owner": "Release Owner",
    "reviewer": "Release Reviewer",
    "changelog": "Initial release candidate.",
    "releaseNotes": "Initial internal release.",
    "agentBehaviorSummary": "Remote OpenAI-style public read-only tool calls execute through the local Agent runtime and mixed private/action batches fail closed before execution.",
    "unsupportedCapabilities": [
      "Full PDF parsing",
      "Local image semantic understanding without a configured vision model"
    ],
    "artifact": {
      "type": "aab",
      "path": "$RELEASE_RECORD_ARTIFACT",
      "sha256": "$RELEASE_RECORD_ARTIFACT_SHA",
      "sizeBytes": $RELEASE_RECORD_ARTIFACT_SIZE,
      "signingCertificateSha256": "1111111111111111111111111111111111111111111111111111111111111111"
    },
    "verificationReports": [
      {
        "name": "local",
        "path": "$RELEASE_RECORD_REPORT",
        "sha256": "$RELEASE_RECORD_REPORT_SHA"
      }
    ],
    "blockers": [
      {
        "id": "privacy-review",
        "status": "accepted",
        "owner": "Release Owner",
        "date": "$RELEASE_RECORD_DATE",
        "riskNote": "Accepted for internal testing only."
      }
    ]
  }
}
RELEASE_RECORD_APPROVED_JSON
expect_success \
  "release record verifier accepts approved current record" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_APPROVED" --report "$ARTIFACT_DIR/release-record-approved.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-approved.properties" "status=passed"
expect_failure \
  "release record verifier rejects internal channel in public context" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  ALLOW_DIRTY_RELEASE=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$RELEASE_RECORD_ARTIFACT_SHA" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_APPROVED" --report "$ARTIFACT_DIR/release-record-public-internal-channel.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public-internal-channel.properties" "status=failed"
RELEASE_RECORD_PUBLIC="$TMP_DIR/release-record-public.json"
sed 's/"targetChannel": "internal_testing"/"targetChannel": "open_testing"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_PUBLIC"
expect_success \
  "release record verifier accepts matching public aab record" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  ALLOW_DIRTY_RELEASE=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$RELEASE_RECORD_ARTIFACT_SHA" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PUBLIC" --report "$ARTIFACT_DIR/release-record-public.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public.properties" "status=passed"
assert_report_contains "$ARTIFACT_DIR/release-record-public.properties" "allowDirtyRelease=1"
DIRTY_RELEASE_MARKER="$ROOT_DIR/release-record-dirty-test.tmp"
CLEANUP_PATHS+=("$DIRTY_RELEASE_MARKER")
printf 'dirty release record test\n' > "$DIRTY_RELEASE_MARKER"
expect_failure \
  "release record verifier rejects dirty public source tree" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$RELEASE_RECORD_ARTIFACT_SHA" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PUBLIC" --report "$ARTIFACT_DIR/release-record-public-dirty.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public-dirty.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-record-public-dirty.properties" "reason=git-worktree-dirty"
rm -f "$DIRTY_RELEASE_MARKER"
RELEASE_RECORD_OTHER_ARTIFACT="$TMP_DIR/release-record-other.aab"
printf 'other release artifact\n' > "$RELEASE_RECORD_OTHER_ARTIFACT"
expect_failure \
  "release record verifier rejects mismatched public artifact path" \
  env PUBLIC_RELEASE_CONTEXT=1 \
  ALLOW_DIRTY_RELEASE=1 \
  EXPECTED_RELEASE_ARTIFACT_PATH="$RELEASE_RECORD_OTHER_ARTIFACT" \
  EXPECTED_RELEASE_ARTIFACT_TYPE=aab \
  EXPECTED_RELEASE_ARTIFACT_SHA256="$(shasum -a 256 "$RELEASE_RECORD_OTHER_ARTIFACT" | awk '{print $1}')" \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_PUBLIC" --report "$ARTIFACT_DIR/release-record-public-artifact-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-public-artifact-mismatch.properties" "status=failed"
RELEASE_RECORD_FUTURE="$TMP_DIR/release-record-future.json"
sed 's/"releaseDate": "'"$RELEASE_RECORD_DATE"'"/"releaseDate": "2999-01-01"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_FUTURE"
expect_failure \
  "release record verifier rejects future release dates" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_FUTURE" --report "$ARTIFACT_DIR/release-record-future.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-future.properties" "status=failed"
RELEASE_RECORD_OLD_COMMIT="$TMP_DIR/release-record-old-commit.json"
sed 's/"gitCommit": "'"$RELEASE_RECORD_HEAD"'"/"gitCommit": "'"$RELEASE_RECORD_NON_HEAD"'"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_OLD_COMMIT"
expect_failure \
  "release record verifier rejects non-head source commit" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_OLD_COMMIT" --report "$ARTIFACT_DIR/release-record-old-commit.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-old-commit.properties" "status=failed"
RELEASE_RECORD_BAD_SHA="$TMP_DIR/release-record-bad-sha.json"
sed 's/"sha256": "'"$RELEASE_RECORD_ARTIFACT_SHA"'"/"sha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_BAD_SHA"
expect_failure \
  "release record verifier rejects artifact sha mismatch" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_BAD_SHA" --report "$ARTIFACT_DIR/release-record-bad-sha.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-bad-sha.properties" "status=failed"
RELEASE_RECORD_FAILED_REPORT_JSON="$TMP_DIR/release-record-failed-report.json"
sed \
  -e 's#'"$RELEASE_RECORD_REPORT"'#'"$RELEASE_RECORD_FAILED_REPORT"'#' \
  -e 's#'"$RELEASE_RECORD_REPORT_SHA"'#'"$RELEASE_RECORD_FAILED_REPORT_SHA"'#' \
  "$RELEASE_RECORD_APPROVED" > "$RELEASE_RECORD_FAILED_REPORT_JSON"
expect_failure \
  "release record verifier rejects failed verification report" \
  scripts/verify_release_record.sh --file "$RELEASE_RECORD_FAILED_REPORT_JSON" --report "$ARTIFACT_DIR/release-record-failed-report.properties"
assert_report_contains "$ARTIFACT_DIR/release-record-failed-report.properties" "status=failed"

STORE_POLICY_NOTICE="$TMP_DIR/store-privacy-notice.md"
STORE_POLICY_MANIFEST="$TMP_DIR/AndroidManifest.xml"
STORE_POLICY_PENDING="$TMP_DIR/store-policy-pending.json"
STORE_POLICY_APPROVED="$TMP_DIR/store-policy-approved.json"
printf 'PocketMind store privacy notice\n' > "$STORE_POLICY_NOTICE"
STORE_POLICY_NOTICE_SHA="$(shasum -a 256 "$STORE_POLICY_NOTICE" | awk '{print $1}')"
cat > "$STORE_POLICY_MANIFEST" <<'STORE_POLICY_MANIFEST_XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
</manifest>
STORE_POLICY_MANIFEST_XML
cat > "$STORE_POLICY_PENDING" <<'STORE_POLICY_PENDING_JSON'
{
  "version": 1,
  "status": "pending_policy_review"
}
STORE_POLICY_PENDING_JSON
expect_failure \
  "store policy verifier rejects pending records" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_PENDING" --report "$ARTIFACT_DIR/store-policy-pending.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-pending.properties" "status=failed"
cat > "$STORE_POLICY_APPROVED" <<STORE_POLICY_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "privacyNoticePath": "$STORE_POLICY_NOTICE",
  "privacyNoticeSha256": "$STORE_POLICY_NOTICE_SHA",
  "appListing": {
    "appName": "PocketMind",
    "shortDescription": "Local-first AI assistant.",
    "fullDescription": "PocketMind is a local-first personal AI assistant for internal testing. It stores user sessions locally, protects private context with confirmation, and clearly separates optional remote model calls from local-only data.",
    "category": "Productivity",
    "contactEmail": "release@pocketmind.app",
    "privacyPolicyUrl": "https://pocketmind.app/privacy"
  },
  "dataSafety": {
    "userDataCollected": true,
    "userDataShared": true,
    "encryptedInTransit": true,
    "userDeletable": true,
    "optionalRemoteModelEndpoints": true,
    "externalRecipients": [
      "User-configured remote model endpoints",
      "Recommended and custom model download hosts",
      "Android system or destination apps opened by confirmed external intents"
    ],
    "noFirstPartyAnalyticsUpload": true,
    "localStorageDisclosed": true,
    "remoteModelCallsDisclosed": true,
    "modelDownloadsDisclosed": true,
    "androidPermissionsDisclosed": true
  },
  "modelDownloads": {
    "describedAsLargeOptionalAssets": true,
    "declaresNotBundledInApk": true
  },
  "permissions": [
    {
      "name": "android.permission.INTERNET",
      "purpose": "Connects only to user-configured remote model endpoints and model download hosts."
    },
    {
      "name": "android.permission.RECORD_AUDIO",
      "purpose": "Lets the user dictate text through explicit voice input before sending."
    }
  ],
  "specialAccessDisclosures": [
    {
      "name": "UsageAccess",
      "purpose": "Used only after confirmation to summarize the current foreground app."
    },
    {
      "name": "AccessibilityService",
      "purpose": "Used only after confirmation to read current-screen text nodes."
    },
    {
      "name": "MediaProjection",
      "purpose": "Used only after confirmation for one-shot current screenshot OCR."
    }
  ],
  "review": {
    "reviewer": "Store Reviewer",
    "reviewDate": "$(date +%F)"
  }
}
STORE_POLICY_APPROVED_JSON
expect_success \
  "store policy verifier accepts approved manifest-aligned record" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_APPROVED" --report "$ARTIFACT_DIR/store-policy-approved.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-approved.properties" "status=passed"
STORE_POLICY_BAD_SHA="$TMP_DIR/store-policy-bad-sha.json"
sed 's/"privacyNoticeSha256": "'"$STORE_POLICY_NOTICE_SHA"'"/"privacyNoticeSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_BAD_SHA"
expect_failure \
  "store policy verifier rejects privacy notice sha mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_BAD_SHA" --report "$ARTIFACT_DIR/store-policy-bad-sha.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-bad-sha.properties" "status=failed"
STORE_POLICY_EXTRA_PERMISSION="$TMP_DIR/store-policy-extra-permission.json"
sed 's/"name": "android.permission.RECORD_AUDIO"/"name": "android.permission.READ_CONTACTS"/' "$STORE_POLICY_APPROVED" > "$STORE_POLICY_EXTRA_PERMISSION"
expect_failure \
  "store policy verifier rejects manifest permission mismatch" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_EXTRA_PERMISSION" --report "$ARTIFACT_DIR/store-policy-permission-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/store-policy-permission-mismatch.properties" "status=failed"
STORE_POLICY_PLACEHOLDER_CONTACT="$TMP_DIR/store-policy-placeholder-contact.json"
sed \
  -e 's#release@pocketmind.app#release@example.com#' \
  -e 's#https://pocketmind.app/privacy#https://example.com/privacy#' \
  "$STORE_POLICY_APPROVED" > "$STORE_POLICY_PLACEHOLDER_CONTACT"
expect_failure \
  "store policy verifier rejects placeholder contact and privacy URL" \
  env PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  scripts/verify_store_policy_record.sh --file "$STORE_POLICY_PLACEHOLDER_CONTACT" --report "$ARTIFACT_DIR/store-policy-placeholder-contact.properties"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-placeholder-contact.properties" "contact-email-placeholder"
assert_report_contains_text "$ARTIFACT_DIR/store-policy-placeholder-contact.properties" "privacy-policy-url-placeholder"

OPERATIONS_PENDING="$TMP_DIR/release-operations-pending.json"
OPERATIONS_APPROVED="$TMP_DIR/release-operations-approved.json"
OPERATIONS_DATE="$(date +%F)"
OPERATIONS_MONITORING_EVIDENCE="$TMP_DIR/release-operations-monitoring.properties"
OPERATIONS_SMOKE_EVIDENCE="$TMP_DIR/release-operations-smoke.properties"
OPERATIONS_ROLLBACK_EVIDENCE="$TMP_DIR/release-operations-rollback.properties"
printf 'status=passed\nsource=Android Vitals\nwatcher=Launch Watcher\n' > "$OPERATIONS_MONITORING_EVIDENCE"
printf 'status=passed\nnoLaunchCrash=true\nnoReproducibleAnr=true\n' > "$OPERATIONS_SMOKE_EVIDENCE"
printf 'status=passed\nrollback=initial-release\n' > "$OPERATIONS_ROLLBACK_EVIDENCE"
OPERATIONS_MONITORING_SHA="$(shasum -a 256 "$OPERATIONS_MONITORING_EVIDENCE" | awk '{print $1}')"
OPERATIONS_SMOKE_SHA="$(shasum -a 256 "$OPERATIONS_SMOKE_EVIDENCE" | awk '{print $1}')"
OPERATIONS_ROLLBACK_SHA="$(shasum -a 256 "$OPERATIONS_ROLLBACK_EVIDENCE" | awk '{print $1}')"
cat > "$OPERATIONS_PENDING" <<'OPERATIONS_PENDING_JSON'
{
  "version": 1,
  "status": "pending_operations_review"
}
OPERATIONS_PENDING_JSON
expect_failure \
  "release operations verifier rejects pending records" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_PENDING" --report "$ARTIFACT_DIR/release-operations-pending.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-pending.properties" "status=failed"
cat > "$OPERATIONS_APPROVED" <<OPERATIONS_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "monitoring": {
    "owner": "Release Owner",
    "signalSources": ["Android Vitals", "Internal dogfood feedback"],
    "first24HoursWatcher": "Launch Watcher",
    "crashFreeRateThresholdPercent": 99.5,
    "anrRateThresholdPercent": 1.0,
    "privacyReviewedForCrashSdk": true,
    "evidence": {
      "path": "$OPERATIONS_MONITORING_EVIDENCE",
      "sha256": "$OPERATIONS_MONITORING_SHA"
    }
  },
  "crashAnrSmoke": {
    "window": "2026-06-06 internal smoke",
    "track": "internal_testing",
    "noLaunchCrash": true,
    "noInstallCrash": true,
    "noCrashLoop": true,
    "noFatalNativeLiteRtLmFailure": true,
    "noReproducibleAnr": true,
    "failureEvidencePolicy": "Attach logcat, tombstones, and ANR traces for any failure; state no crash or ANR when none were observed.",
    "evidence": {
      "path": "$OPERATIONS_SMOKE_EVIDENCE",
      "sha256": "$OPERATIONS_SMOKE_SHA"
    }
  },
  "rollback": {
    "owner": "Release Owner",
    "decisionChannel": "#pocketmind-release",
    "criteria": [
      "install failure",
      "crash loop",
      "model download verification failure",
      "privacy boundary failure",
      "critical tool execution regression"
    ],
    "firstStagedRolloutAction": "Halt rollout, keep collecting Android Vitals and user reports, then decide whether to resume, replace, or ship a fixed build.",
    "playVersionCodePolicy": "Any replacement artifact must use a higher versionCode; Play cannot ordinary-update users to a lower versionCode.",
    "modelManifestRollbackPath": "Revert model download metadata when supported; otherwise ship a fixed APK with a higher versionCode.",
    "userDataCompatibility": "Room migrations are forward-only, so downgrade is unsupported unless explicitly tested.",
    "evidence": {
      "path": "$OPERATIONS_ROLLBACK_EVIDENCE",
      "sha256": "$OPERATIONS_ROLLBACK_SHA"
    },
    "previousKnownGood": {
      "status": "not_applicable_initial_release",
      "versionCode": 0,
      "versionName": "",
      "gitCommit": "",
      "artifactPath": "",
      "artifactSha256": "",
      "releaseNotes": "Initial release has no previous production artifact."
    }
  },
  "review": {
    "reviewer": "Release Reviewer",
    "reviewDate": "$OPERATIONS_DATE"
  }
}
OPERATIONS_APPROVED_JSON
expect_success \
  "release operations verifier accepts approved initial-release record" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_APPROVED" --report "$ARTIFACT_DIR/release-operations-approved.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-approved.properties" "status=passed"
OPERATIONS_NO_VITALS="$TMP_DIR/release-operations-no-vitals.json"
sed 's/"Android Vitals", //' "$OPERATIONS_APPROVED" > "$OPERATIONS_NO_VITALS"
expect_failure \
  "release operations verifier requires Android Vitals source" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_NO_VITALS" --report "$ARTIFACT_DIR/release-operations-no-vitals.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-no-vitals.properties" "status=failed"
OPERATIONS_FUTURE="$TMP_DIR/release-operations-future.json"
sed 's/"reviewDate": "'"$OPERATIONS_DATE"'"/"reviewDate": "2999-01-01"/' "$OPERATIONS_APPROVED" > "$OPERATIONS_FUTURE"
expect_failure \
  "release operations verifier rejects future review dates" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_FUTURE" --report "$ARTIFACT_DIR/release-operations-future.properties"
assert_report_contains "$ARTIFACT_DIR/release-operations-future.properties" "status=failed"
OPERATIONS_SMOKE_BAD_SHA="$TMP_DIR/release-operations-smoke-bad-sha.json"
sed 's/"sha256": "'"$OPERATIONS_SMOKE_SHA"'"/"sha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$OPERATIONS_APPROVED" > "$OPERATIONS_SMOKE_BAD_SHA"
expect_failure \
  "release operations verifier rejects crash smoke evidence sha mismatch" \
  scripts/verify_release_operations_record.sh --file "$OPERATIONS_SMOKE_BAD_SHA" --report "$ARTIFACT_DIR/release-operations-smoke-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-operations-smoke-bad-sha.properties" "crash-anr-smoke-evidence-sha-mismatch"

VALIDATION_PENDING="$TMP_DIR/release-validation-pending.json"
VALIDATION_APPROVED="$TMP_DIR/release-validation-approved.json"
VALIDATION_EMULATOR_REPORT="$TMP_DIR/regression-emulator.properties"
VALIDATION_DEVICE_REPORT="$TMP_DIR/device-verification.properties"
VALIDATION_EMULATOR_DEVICE_REPORT="$TMP_DIR/emulator-device-verification.properties"
VALIDATION_DATE="$(date +%F)"
mkdir -p "$TMP_DIR/validation-screenshots"
for screenshot_name in chat-home model-manager confirmation-sheet background-tasks-or-audit; do
  printf 'fake screenshot %s\n' "$screenshot_name" > "$TMP_DIR/validation-screenshots/$screenshot_name.png"
done
mkdir -p "$TMP_DIR/validation-api-evidence"
for api_level in 28 32 33 34 36; do
  printf 'status=passed\napi_level=%s\n' "$api_level" > "$TMP_DIR/validation-api-evidence/api-$api_level.properties"
done
mkdir -p "$TMP_DIR/validation-manual-evidence" "$TMP_DIR/validation-flow-evidence" "$TMP_DIR/validation-performance-evidence"
for manual_key in \
  modelSetup remoteModePrivacy toolConfirmation permissions backgroundReminders sharing \
  multimodalEntryPoints voiceInput filePicker mediaProjection remoteSinglePublicEvidence \
  remoteMultiEvidenceComparison mixedPrivateActionBatchFailClosed; do
  printf 'status=passed\nmanual=%s\n' "$manual_key" > "$TMP_DIR/validation-manual-evidence/$manual_key.properties"
done
for flow_key in \
  firstInstall upgradeInstall localModelDownloadVerification customModelImportOrUrlRejection \
  remoteHttpsConfiguration encryptedApiKeyClear sessionPersistence memoryControls \
  remindersAfterReboot shareAndPickerInput voiceInput accessibilityText recentMediaOcr \
  mediaProjectionCancellation; do
  printf 'status=passed\nflow=%s\n' "$flow_key" > "$TMP_DIR/validation-flow-evidence/$flow_key.properties"
done
for perf_key in firstLaunch modelLoad firstToken streamingStopCancel backgroundReminderDelivery memoryPressure; do
  printf 'status=passed\nperformance=%s\n' "$perf_key" > "$TMP_DIR/validation-performance-evidence/$perf_key.properties"
done
cat > "$VALIDATION_PENDING" <<'VALIDATION_PENDING_JSON'
{
  "version": 1,
  "status": "pending_validation"
}
VALIDATION_PENDING_JSON
expect_failure \
  "release validation verifier rejects pending records" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_PENDING" --report "$ARTIFACT_DIR/release-validation-pending.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-pending.properties" "status=failed"
cat > "$VALIDATION_EMULATOR_REPORT" <<VALIDATION_EMULATOR_REPORT_PROPERTIES
status=passed
target=regression-emulator
clean_device=1
actual_android_test_count=$SOURCE_ANDROID_TEST_COUNT
avd=test-avd
api_level=36
abi=arm64-v8a
VALIDATION_EMULATOR_REPORT_PROPERTIES
cat > "$VALIDATION_DEVICE_REPORT" <<VALIDATION_DEVICE_REPORT_PROPERTIES
status=passed
target=device
serial=device-a
api_level=36
abi=arm64-v8a
clean_device=1
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
VALIDATION_DEVICE_REPORT_PROPERTIES
cat > "$VALIDATION_EMULATOR_DEVICE_REPORT" <<VALIDATION_EMULATOR_DEVICE_REPORT_PROPERTIES
status=passed
target=device
serial=emulator-5554
api_level=36
abi=arm64-v8a
clean_device=1
instrumentation=passed
instrumentation_test_count=$SOURCE_ANDROID_TEST_COUNT
VALIDATION_EMULATOR_DEVICE_REPORT_PROPERTIES
cat > "$VALIDATION_APPROVED" <<VALIDATION_APPROVED_JSON
{
  "version": 1,
  "status": "approved",
  "emulatorRegression": {
    "status": "passed",
    "reportPath": "$VALIDATION_EMULATOR_REPORT",
    "avd": "test-avd",
    "apiLevel": 36,
    "abi": "arm64-v8a",
    "cleanDevice": true
  },
  "physicalDevice": {
    "status": "passed",
    "reportPath": "$VALIDATION_DEVICE_REPORT",
    "serial": "device-a",
    "apiLevel": 36,
    "abi": "arm64-v8a",
    "cleanDevice": true
  },
  "apiMatrix": [
    {"apiLevel": 28, "status": "passed", "evidence": "API 28 smoke passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-28.properties"},
    {"apiLevel": 32, "status": "passed", "evidence": "API 32 legacy storage path passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-32.properties"},
    {"apiLevel": 33, "status": "passed", "evidence": "API 33 media and notification path passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-33.properties"},
    {"apiLevel": 34, "status": "passed", "evidence": "API 34 selected visual media path passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-34.properties"},
    {"apiLevel": 36, "status": "passed", "evidence": "API 36 target behavior passed.", "evidencePath": "$TMP_DIR/validation-api-evidence/api-36.properties"}
  ],
  "manualAcceptance": {
    "modelSetup": {"status": "passed", "evidence": "Model setup manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/modelSetup.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteModePrivacy": {"status": "passed", "evidence": "Remote mode privacy manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/remoteModePrivacy.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "toolConfirmation": {"status": "passed", "evidence": "Tool confirmation manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/toolConfirmation.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "permissions": {"status": "passed", "evidence": "Permission prompt manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/permissions.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "backgroundReminders": {"status": "passed", "evidence": "Background reminders manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/backgroundReminders.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "sharing": {"status": "passed", "evidence": "Sharing manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/sharing.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "multimodalEntryPoints": {"status": "passed", "evidence": "Multimodal entry points manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/multimodalEntryPoints.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "voiceInput": {"status": "passed", "evidence": "Voice input manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/voiceInput.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "filePicker": {"status": "passed", "evidence": "File picker manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/filePicker.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "mediaProjection": {"status": "passed", "evidence": "MediaProjection manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/mediaProjection.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteSinglePublicEvidence": {"status": "passed", "evidence": "Remote single public evidence manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/remoteSinglePublicEvidence.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteMultiEvidenceComparison": {"status": "passed", "evidence": "Remote multi evidence comparison manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/remoteMultiEvidenceComparison.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "mixedPrivateActionBatchFailClosed": {"status": "passed", "evidence": "Mixed private/action batch fail-closed manual acceptance passed.", "evidencePath": "$TMP_DIR/validation-manual-evidence/mixedPrivateActionBatchFailClosed.properties", "owner": "QA", "date": "$VALIDATION_DATE"}
  },
  "flowMatrix": {
    "firstInstall": {"status": "passed", "evidence": "First install flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/firstInstall.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "upgradeInstall": {"status": "passed", "evidence": "Upgrade install flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/upgradeInstall.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "localModelDownloadVerification": {"status": "passed", "evidence": "Local model download verification flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/localModelDownloadVerification.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "customModelImportOrUrlRejection": {"status": "passed", "evidence": "Custom model import or URL rejection flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/customModelImportOrUrlRejection.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remoteHttpsConfiguration": {"status": "passed", "evidence": "Remote HTTPS configuration flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/remoteHttpsConfiguration.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "encryptedApiKeyClear": {"status": "passed", "evidence": "Encrypted API key clear flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/encryptedApiKeyClear.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "sessionPersistence": {"status": "passed", "evidence": "Session persistence flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/sessionPersistence.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "memoryControls": {"status": "passed", "evidence": "Memory controls flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/memoryControls.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "remindersAfterReboot": {"status": "passed", "evidence": "Reminders after reboot flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/remindersAfterReboot.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "shareAndPickerInput": {"status": "passed", "evidence": "Share and picker input flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/shareAndPickerInput.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "voiceInput": {"status": "passed", "evidence": "Voice input flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/voiceInput.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "accessibilityText": {"status": "passed", "evidence": "Accessibility text flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/accessibilityText.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "recentMediaOcr": {"status": "passed", "evidence": "Recent media OCR flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/recentMediaOcr.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "mediaProjectionCancellation": {"status": "passed", "evidence": "MediaProjection cancellation flow passed.", "evidencePath": "$TMP_DIR/validation-flow-evidence/mediaProjectionCancellation.properties", "owner": "QA", "date": "$VALIDATION_DATE"}
  },
  "screenshots": [
    {"name": "chat-home", "path": "$TMP_DIR/validation-screenshots/chat-home.png", "sanitized": true},
    {"name": "model-manager", "path": "$TMP_DIR/validation-screenshots/model-manager.png", "sanitized": true},
    {"name": "confirmation-sheet", "path": "$TMP_DIR/validation-screenshots/confirmation-sheet.png", "sanitized": true},
    {"name": "background-tasks-or-audit", "path": "$TMP_DIR/validation-screenshots/background-tasks-or-audit.png", "sanitized": true}
  ],
  "performanceSanity": {
    "firstLaunch": {"status": "passed", "evidence": "First launch performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/firstLaunch.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "modelLoad": {"status": "passed", "evidence": "Model load performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/modelLoad.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "firstToken": {"status": "passed", "evidence": "First token performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/firstToken.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "streamingStopCancel": {"status": "passed", "evidence": "Streaming stop/cancel performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/streamingStopCancel.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "backgroundReminderDelivery": {"status": "passed", "evidence": "Background reminder delivery performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/backgroundReminderDelivery.properties", "owner": "QA", "date": "$VALIDATION_DATE"},
    "memoryPressure": {"status": "passed", "evidence": "Memory pressure performance sanity passed.", "evidencePath": "$TMP_DIR/validation-performance-evidence/memoryPressure.properties", "owner": "QA", "date": "$VALIDATION_DATE"}
  },
  "review": {
    "reviewer": "Validation Reviewer",
    "reviewDate": "$VALIDATION_DATE"
  }
}
VALIDATION_APPROVED_JSON
python3 - "$VALIDATION_APPROVED" <<'PY'
import hashlib
import json
import sys
from pathlib import Path

record_path = Path(sys.argv[1])
record = json.loads(record_path.read_text())

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

record["emulatorRegression"]["reportSha256"] = sha(record["emulatorRegression"]["reportPath"])
record["physicalDevice"]["reportSha256"] = sha(record["physicalDevice"]["reportPath"])
for entry in record["apiMatrix"]:
    entry["evidenceSha256"] = sha(entry["evidencePath"])
for section in ("manualAcceptance", "flowMatrix", "performanceSanity"):
    for item in record[section].values():
        item["evidenceSha256"] = sha(item["evidencePath"])
for entry in record["screenshots"]:
    entry["sha256"] = sha(entry["path"])

record_path.write_text(json.dumps(record, indent=2))
PY
expect_success \
  "release validation verifier accepts approved evidence record" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_APPROVED" --report "$ARTIFACT_DIR/release-validation-approved.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-approved.properties" "status=passed"
VALIDATION_BARE_MANUAL="$TMP_DIR/release-validation-bare-manual.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_BARE_MANUAL" <<'PY'
import json
import sys
from pathlib import Path

source = Path(sys.argv[1])
target = Path(sys.argv[2])
record = json.loads(source.read_text())
record["manualAcceptance"]["modelSetup"] = "passed"
target.write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects bare passed manual acceptance" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_BARE_MANUAL" --report "$ARTIFACT_DIR/release-validation-bare-manual.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-bare-manual.properties" "manual-modelSetup-evidence-record-invalid"
VALIDATION_EMULATOR_BAD_SHA="$TMP_DIR/release-validation-emulator-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_EMULATOR_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["emulatorRegression"]["reportSha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects emulator report sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_EMULATOR_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-emulator-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-emulator-bad-sha.properties" "emulator-report-sha-mismatch"
VALIDATION_API_BAD_SHA="$TMP_DIR/release-validation-api-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["apiMatrix"][0]["evidenceSha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects api evidence sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-api-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-api-bad-sha.properties" "api-28-evidence-sha-mismatch"
VALIDATION_MANUAL_BAD_SHA="$TMP_DIR/release-validation-manual-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_MANUAL_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["manualAcceptance"]["modelSetup"]["evidenceSha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects manual evidence sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MANUAL_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-manual-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-manual-bad-sha.properties" "manual-modelSetup-evidence-sha-mismatch"
VALIDATION_SCREENSHOT_BAD_SHA="$TMP_DIR/release-validation-screenshot-bad-sha.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_SCREENSHOT_BAD_SHA" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
record["screenshots"][0]["sha256"] = "0" * 64
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects screenshot sha mismatch" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_SCREENSHOT_BAD_SHA" --report "$ARTIFACT_DIR/release-validation-screenshot-bad-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/release-validation-screenshot-bad-sha.properties" "chat-home-screenshot-sha-mismatch"
VALIDATION_MISSING_DEVICE="$TMP_DIR/release-validation-missing-device.json"
sed 's#"reportPath": "'"$VALIDATION_DEVICE_REPORT"'"#"reportPath": "'"$TMP_DIR/missing-device.properties"'"#' "$VALIDATION_APPROVED" > "$VALIDATION_MISSING_DEVICE"
expect_failure \
  "release validation verifier rejects missing physical report" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_MISSING_DEVICE" --report "$ARTIFACT_DIR/release-validation-missing-device.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-missing-device.properties" "status=failed"
VALIDATION_EMULATOR_AS_PHYSICAL="$TMP_DIR/release-validation-emulator-as-physical.json"
sed \
  -e 's#"reportPath": "'"$VALIDATION_DEVICE_REPORT"'"#"reportPath": "'"$VALIDATION_EMULATOR_DEVICE_REPORT"'"#' \
  -e 's/"serial": "device-a"/"serial": "emulator-5554"/' \
  "$VALIDATION_APPROVED" > "$VALIDATION_EMULATOR_AS_PHYSICAL"
expect_failure \
  "release validation verifier rejects emulator device report as physical evidence" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_EMULATOR_AS_PHYSICAL" --report "$ARTIFACT_DIR/release-validation-emulator-as-physical.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-emulator-as-physical.properties" "status=failed"
VALIDATION_API_GAP="$TMP_DIR/release-validation-api-gap.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_API_GAP" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
for entry in record["apiMatrix"]:
    if entry.get("apiLevel") == 34:
        entry["status"] = "pending"
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects incomplete api matrix" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_GAP" --report "$ARTIFACT_DIR/release-validation-api-gap.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-api-gap.properties" "status=failed"
VALIDATION_API_MISSING_EVIDENCE="$TMP_DIR/release-validation-api-missing-evidence.json"
sed 's#'"$TMP_DIR"'/validation-api-evidence/api-34.properties#'"$TMP_DIR"'/validation-api-evidence/missing-api-34.properties#' "$VALIDATION_APPROVED" > "$VALIDATION_API_MISSING_EVIDENCE"
expect_failure \
  "release validation verifier rejects missing api evidence file" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_API_MISSING_EVIDENCE" --report "$ARTIFACT_DIR/release-validation-api-missing-evidence.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-api-missing-evidence.properties" "status=failed"
VALIDATION_UNSANITIZED_SCREENSHOT="$TMP_DIR/release-validation-unsanitized-screenshot.json"
python3 - "$VALIDATION_APPROVED" "$VALIDATION_UNSANITIZED_SCREENSHOT" <<'PY'
import json
import sys
from pathlib import Path

record = json.loads(Path(sys.argv[1]).read_text())
for entry in record["screenshots"]:
    if entry.get("name") == "chat-home":
        entry["sanitized"] = False
Path(sys.argv[2]).write_text(json.dumps(record, indent=2))
PY
expect_failure \
  "release validation verifier rejects unsanitized screenshots" \
  scripts/verify_release_validation_record.sh --file "$VALIDATION_UNSANITIZED_SCREENSHOT" --report "$ARTIFACT_DIR/release-validation-unsanitized.properties"
assert_report_contains "$ARTIFACT_DIR/release-validation-unsanitized.properties" "status=failed"

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
PRIVACY_REVIEW_RELEASE_EVIDENCE="$TMP_DIR/privacy-review-release.properties"
PRIVACY_REVIEW_SECURITY_EVIDENCE="$TMP_DIR/privacy-review-security.properties"
PRIVACY_REVIEW_LEGAL_EVIDENCE="$TMP_DIR/privacy-review-legal.properties"
printf 'PocketMind privacy notice\n' > "$PRIVACY_NOTICE"
printf 'status=approved\nrole=release\nscope=privacy-notice\n' > "$PRIVACY_REVIEW_RELEASE_EVIDENCE"
printf 'status=approved\nrole=security\nscope=privacy-notice\n' > "$PRIVACY_REVIEW_SECURITY_EVIDENCE"
printf 'status=approved\nrole=legal\nscope=privacy-notice\n' > "$PRIVACY_REVIEW_LEGAL_EVIDENCE"
PRIVACY_NOTICE_SHA="$(shasum -a 256 "$PRIVACY_NOTICE" | awk '{print $1}')"
PRIVACY_REVIEW_RELEASE_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_RELEASE_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_SECURITY_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_SECURITY_EVIDENCE" | awk '{print $1}')"
PRIVACY_REVIEW_LEGAL_EVIDENCE_SHA="$(shasum -a 256 "$PRIVACY_REVIEW_LEGAL_EVIDENCE" | awk '{print $1}')"
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
      "reviewDate": "2026-06-06",
      "evidencePath": "$PRIVACY_REVIEW_RELEASE_EVIDENCE",
      "evidenceSha256": "$PRIVACY_REVIEW_RELEASE_EVIDENCE_SHA"
    },
    {
      "role": "security",
      "decision": "approved",
      "reviewer": "Security Reviewer",
      "reviewDate": "2026-06-06",
      "evidencePath": "$PRIVACY_REVIEW_SECURITY_EVIDENCE",
      "evidenceSha256": "$PRIVACY_REVIEW_SECURITY_EVIDENCE_SHA"
    },
    {
      "role": "legal",
      "decision": "approved",
      "reviewer": "Legal Reviewer",
      "reviewDate": "2026-06-06",
      "evidencePath": "$PRIVACY_REVIEW_LEGAL_EVIDENCE",
      "evidenceSha256": "$PRIVACY_REVIEW_LEGAL_EVIDENCE_SHA"
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
PRIVACY_REVIEW_BAD_EVIDENCE_SHA="$TMP_DIR/privacy-review-bad-evidence-sha.json"
sed 's/"evidenceSha256": "'"$PRIVACY_REVIEW_RELEASE_EVIDENCE_SHA"'"/"evidenceSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$PRIVACY_REVIEW_APPROVED" > "$PRIVACY_REVIEW_BAD_EVIDENCE_SHA"
expect_failure \
  "privacy review verifier rejects evidence sha mismatch" \
  env PRIVACY_REVIEW_FILE="$PRIVACY_REVIEW_BAD_EVIDENCE_SHA" PRIVACY_NOTICE_FILE="$PRIVACY_NOTICE" \
  scripts/verify_privacy_review.sh --report "$ARTIFACT_DIR/privacy-review-bad-evidence-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/privacy-review-bad-evidence-sha.properties" "release-evidence-sha-mismatch"

MODEL_LICENSE_METADATA="$TMP_DIR/model-license-metadata.json"
MODEL_LICENSE_MANIFEST="$TMP_DIR/model-manifest.md"
MODEL_LICENSE_PENDING="$TMP_DIR/model-license-pending.json"
MODEL_LICENSE_APPROVED="$TMP_DIR/model-license-approved.json"
MODEL_LICENSE_CHAT_EVIDENCE="$TMP_DIR/model-license-chat-e2b-review.properties"
MODEL_LICENSE_MEMORY_EVIDENCE="$TMP_DIR/model-license-memory-embedding-300m-review.properties"
printf 'status=approved\nmodel=chat-e2b\nscope=license-redistribution-attribution\n' > "$MODEL_LICENSE_CHAT_EVIDENCE"
printf 'status=approved\nmodel=memory-embedding-300m\nscope=license-redistribution-attribution\n' > "$MODEL_LICENSE_MEMORY_EVIDENCE"
MODEL_LICENSE_CHAT_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_CHAT_EVIDENCE" | awk '{print $1}')"
MODEL_LICENSE_MEMORY_EVIDENCE_SHA="$(shasum -a 256 "$MODEL_LICENSE_MEMORY_EVIDENCE" | awk '{print $1}')"
cat > "$MODEL_LICENSE_MANIFEST" <<'MODEL_LICENSE_MANIFEST_MD'
| ID | File | Repository | Upstream revision | Bytes | SHA-256 | License status |
| --- | --- | --- | --- | ---: | --- | --- |
| `chat-e2b` | `chat.litertlm` | `https://huggingface.co/example/chat-e2b` | `chat-revision-a` | `1` | `abc` | Pending. |
| `memory-embedding-300m` | `memory.litertlm` | `https://huggingface.co/example/memory-embedding-300m` | `memory-revision-a` | `1` | `def` | Pending. |
MODEL_LICENSE_MANIFEST_MD
cat > "$MODEL_LICENSE_METADATA" <<'MODEL_LICENSE_METADATA_JSON'
{
  "version": 1,
  "recordedAt": "2026-06-05T00:00:00Z",
  "models": [
    {
      "id": "chat-e2b",
      "repository": "example/chat-e2b",
      "manifestRevision": "chat-revision-a",
      "apiUrl": "https://huggingface.co/api/models/example/chat-e2b",
      "modelSha": "chat-current-api-sha",
      "gated": false,
      "licenseTags": ["apache-2.0"],
      "cardLicense": "apache-2.0",
      "metadataOnly": true
    },
    {
      "id": "memory-embedding-300m",
      "repository": "example/memory-embedding-300m",
      "manifestRevision": "memory-revision-a",
      "apiUrl": "https://huggingface.co/api/models/example/memory-embedding-300m",
      "modelSha": "memory-current-api-sha",
      "gated": false,
      "licenseTags": ["apache-2.0"],
      "cardLicense": "apache-2.0",
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
      "reviewDate": "",
      "reviewEvidencePath": "",
      "reviewEvidenceSha256": ""
    }
  ]
}
MODEL_LICENSE_PENDING_JSON
expect_failure \
  "model license verifier rejects incomplete review records" \
  env MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_PENDING" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-pending.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-pending.properties" "status=failed"
cat > "$MODEL_LICENSE_APPROVED" <<MODEL_LICENSE_APPROVED_JSON
{
  "version": 1,
  "models": [
    {
      "id": "chat-e2b",
      "repository": "example/chat-e2b",
      "upstreamRevision": "chat-revision-a",
      "status": "approved",
      "licenseName": "Apache-2.0",
      "licenseUrl": "https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md",
      "redistributionDecision": "approved",
      "attributionNotice": "Include Apache-2.0 notice.",
      "reviewer": "Model Reviewer",
      "reviewDate": "2026-06-06",
      "reviewEvidencePath": "$MODEL_LICENSE_CHAT_EVIDENCE",
      "reviewEvidenceSha256": "$MODEL_LICENSE_CHAT_EVIDENCE_SHA"
    },
    {
      "id": "memory-embedding-300m",
      "repository": "example/memory-embedding-300m",
      "upstreamRevision": "memory-revision-a",
      "status": "approved",
      "licenseName": "Apache-2.0",
      "licenseUrl": "https://huggingface.co/example/memory-embedding-300m/blob/memory-revision-a/README.md",
      "redistributionDecision": "approved",
      "attributionNotice": "Include Apache-2.0 notice.",
      "reviewer": "Model Reviewer",
      "reviewDate": "2026-06-06",
      "reviewEvidencePath": "$MODEL_LICENSE_MEMORY_EVIDENCE",
      "reviewEvidenceSha256": "$MODEL_LICENSE_MEMORY_EVIDENCE_SHA"
    }
  ]
}
MODEL_LICENSE_APPROVED_JSON
expect_success \
  "model license verifier accepts approved metadata-aligned records" \
  env MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_APPROVED" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-approved.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-approved.properties" "status=passed"
MODEL_LICENSE_SOURCE_MISMATCH="$TMP_DIR/model-license-source-mismatch.json"
sed 's#https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md#https://huggingface.co/example/wrong-model/blob/chat-revision-a/README.md#' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_SOURCE_MISMATCH"
expect_failure \
  "model license verifier rejects Hugging Face license source for a different repository" \
  env MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_SOURCE_MISMATCH" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-source-mismatch.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-source-mismatch.properties" "status=failed"
MODEL_LICENSE_REPO_ROOT="$TMP_DIR/model-license-repo-root.json"
sed 's#https://huggingface.co/example/chat-e2b/blob/chat-revision-a/README.md#https://huggingface.co/example/chat-e2b#' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_REPO_ROOT"
expect_failure \
  "model license verifier rejects Hugging Face repository root as license source" \
  env MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_REPO_ROOT" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-repo-root.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-repo-root.properties" "chat-e2b-license-source-not-concrete"
MODEL_LICENSE_BAD_REVIEW_EVIDENCE_SHA="$TMP_DIR/model-license-bad-review-evidence-sha.json"
sed 's/"reviewEvidenceSha256": "'"$MODEL_LICENSE_CHAT_EVIDENCE_SHA"'"/"reviewEvidenceSha256": "0000000000000000000000000000000000000000000000000000000000000000"/' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_BAD_REVIEW_EVIDENCE_SHA"
expect_failure \
  "model license verifier rejects review evidence sha mismatch" \
  env MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_BAD_REVIEW_EVIDENCE_SHA" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-bad-review-evidence-sha.properties"
assert_report_contains_text "$ARTIFACT_DIR/model-license-bad-review-evidence-sha.properties" "chat-e2b-review-evidence-sha-mismatch"
MODEL_LICENSE_STALE_REVIEW="$TMP_DIR/model-license-stale-review.json"
sed 's/2026-06-06/2026-06-04/g' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_STALE_REVIEW"
expect_failure \
  "model license verifier rejects review dates before metadata collection" \
  env MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_STALE_REVIEW" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-stale-review.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-stale-review.properties" "status=failed"
MODEL_LICENSE_FUTURE="$TMP_DIR/model-license-future.json"
sed 's/2026-06-06/2999-01-01/g' "$MODEL_LICENSE_APPROVED" > "$MODEL_LICENSE_FUTURE"
expect_failure \
  "model license verifier rejects future review dates" \
  env MODEL_LICENSE_REVIEW_FILE="$MODEL_LICENSE_FUTURE" MODEL_LICENSE_METADATA_FILE="$MODEL_LICENSE_METADATA" MODEL_MANIFEST_FILE="$MODEL_LICENSE_MANIFEST" \
  scripts/verify_model_license_review.sh --report "$ARTIFACT_DIR/model-license-future.properties"
assert_report_contains "$ARTIFACT_DIR/model-license-future.properties" "status=failed"

SAFE_APK="$TMP_DIR/safe.apk"
SAFE_AAB="$TMP_DIR/safe.aab"
BAD_AAB="$TMP_DIR/bad.aab"
UNSAFE_APK="$TMP_DIR/unsafe.apk"
mkdir -p "$TMP_DIR/safe-apk/assets" "$TMP_DIR/safe-aab/base/manifest" "$TMP_DIR/unsafe-zip/assets"
printf '<manifest />\n' > "$TMP_DIR/safe-apk/AndroidManifest.xml"
printf 'bundle-config\n' > "$TMP_DIR/safe-aab/BundleConfig.pb"
printf '<manifest />\n' > "$TMP_DIR/safe-aab/base/manifest/AndroidManifest.xml"
printf 'ok\n' > "$TMP_DIR/safe-apk/assets/readme.txt"
printf 'ok\n' > "$TMP_DIR/safe-aab/base/readme.txt"
printf 'model\n' > "$TMP_DIR/unsafe-zip/assets/model.litertlm"
(cd "$TMP_DIR/safe-apk" && zip -qr "$SAFE_APK" .)
(cd "$TMP_DIR/safe-aab" && zip -qr "$SAFE_AAB" .)
(cd "$TMP_DIR/unsafe-zip" && zip -qr "$UNSAFE_APK" .)
printf 'not a bundle\n' > "$BAD_AAB"
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
  "artifact scan rejects unreadable aab" \
  scripts/scan_android_artifacts.sh --aab "$BAD_AAB" --report "$ARTIFACT_DIR/artifact-bad-aab.properties"
assert_report_contains "$ARTIFACT_DIR/artifact-bad-aab.properties" "status=failed"
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
SAFE_AAB_SHA="$(shasum -a 256 "$SAFE_AAB" | awk '{print $1}')"
cat > "$VALID_GATE_PERF" <<VALID_GATE_PERF_BASELINE
status=passed
deviceSerial=device-a
deviceModel=Pixel Test
androidApi=36
abi=arm64-v8a
appVersion=0.1.0
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
recordedAt=$PERF_RECORDED_AT
VALID_GATE_PERF_BASELINE
VALID_GATE_AAB_PERF="$TMP_DIR/perf-baseline-safe-aab.properties"
sed "s/releaseArtifactSha256=$SAFE_APK_SHA/releaseArtifactSha256=$SAFE_AAB_SHA/" "$VALID_GATE_PERF" > "$VALID_GATE_AAB_PERF"
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
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseRecord=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyStorePolicy=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseOperations=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseValidation=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyPrivacyReview=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyModelLicenses=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireAab=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "requireSignedArtifact=1"
assert_report_contains "$ARTIFACT_DIR/public-release-missing-cert/release-gate.properties" "verifyReleaseMapping=1"
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
  "release gate defaults signed aab path when signed aab is required" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-signed-default-aab" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  REQUIRE_AAB=1 \
  REQUIRE_SIGNED_ARTIFACT=1 \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/android-artifact-scan.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/android-artifact-scan.properties" "releaseAab=app/build/outputs/bundle/release/app-release-signed.aab"
assert_report_contains "$ARTIFACT_DIR/release-signed-default-aab/release-gate.properties" "releaseAab=app/build/outputs/bundle/release/app-release-signed.aab"
expect_failure \
  "release gate binds release record to scanned artifact in non-public mode" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-record-artifact-mismatch" \
  PERF_BASELINE_FILE="$VALID_GATE_AAB_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$SAFE_AAB" \
  VERIFY_RELEASE_RECORD=1 \
  RELEASE_RECORD_FILE="$RELEASE_RECORD_APPROVED" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-record-artifact-mismatch/release-record.properties" "status=failed"
assert_report_contains "$ARTIFACT_DIR/release-record-artifact-mismatch/release-record.properties" "expectedReleaseArtifactPath=$SAFE_AAB"
expect_failure \
  "release gate requires mapping when mapping gate is enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-mapping-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_MAPPING=1 \
  RELEASE_MAPPING_FILE="$TMP_DIR/missing-mapping.txt" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-mapping-gate/release-mapping.properties" "status=failed"
expect_failure \
  "release gate requires approved release record when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-record-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_RECORD=1 \
  RELEASE_RECORD_FILE="$RELEASE_RECORD_PENDING" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-record-gate/release-record.properties" "status=failed"
expect_failure \
  "release gate requires approved store policy when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-store-policy-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_STORE_POLICY=1 \
  STORE_POLICY_FILE="$STORE_POLICY_PENDING" \
  PRIVACY_NOTICE_FILE="$STORE_POLICY_NOTICE" \
  MANIFEST_FILE="$STORE_POLICY_MANIFEST" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-store-policy-gate/store-policy-record.properties" "status=failed"
expect_failure \
  "release gate requires approved operations record when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-operations-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_OPERATIONS=1 \
  OPERATIONS_RECORD_FILE="$OPERATIONS_PENDING" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-operations-gate/release-operations-record.properties" "status=failed"
expect_failure \
  "release gate requires approved validation record when enabled" \
  env ARTIFACT_DIR="$ARTIFACT_DIR/release-validation-gate" \
  PERF_BASELINE_FILE="$VALID_GATE_PERF" \
  RELEASE_APK="$SAFE_APK" \
  RELEASE_AAB="$TMP_DIR/missing.aab" \
  VERIFY_RELEASE_VALIDATION=1 \
  VALIDATION_RECORD_FILE="$VALIDATION_PENDING" \
  VERIFY_CONTRACT_TESTS=0 \
  scripts/verify_release_gate.sh
assert_report_contains "$ARTIFACT_DIR/release-validation-gate/release-validation-record.properties" "status=failed"
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
expect_failure \
  "signing helper requires unsigned aab for production signing" \
  env RELEASE_KEYSTORE="$PRODUCTION_KEYSTORE" \
  RELEASE_KEY_ALIAS=upload \
  RELEASE_KEYSTORE_PASSWORD=secret \
  RELEASE_KEY_PASSWORD=secret \
  EXPECTED_SIGNING_CERT_SHA256=1111111111111111111111111111111111111111111111111111111111111111 \
  UNSIGNED_APK="$SAFE_APK" \
  UNSIGNED_AAB="$TMP_DIR/missing-release.aab" \
  scripts/sign_release_artifacts.sh
grep -q 'Release signing requires unsigned AAB' <<<"$LAST_OUTPUT" ||
  fail "Expected signing helper to require unsigned AAB before production signing"

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

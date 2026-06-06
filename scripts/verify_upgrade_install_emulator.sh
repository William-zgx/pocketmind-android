#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"

ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
EMULATOR_BIN="${ANDROID_EMULATOR:-${ANDROID_SDK}/emulator/emulator}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/upgrade-install-emulator-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/upgrade-install-emulator.properties}"
INSTRUMENTATION_OUTPUT_FILE="${INSTRUMENTATION_OUTPUT_FILE:-${ARTIFACT_DIR}/instrumentation.txt}"
BASE_PACKAGE_FILE="${BASE_PACKAGE_FILE:-${ARTIFACT_DIR}/package-before-upgrade.txt}"
CURRENT_PACKAGE_FILE="${CURRENT_PACKAGE_FILE:-${ARTIFACT_DIR}/package-after-upgrade.txt}"
EMULATOR_LOG="${ARTIFACT_DIR}-emulator.log"
BOOT_TIMEOUT_SECONDS="${BOOT_TIMEOUT_SECONDS:-180}"
EMULATOR_SELECT_TIMEOUT_SECONDS="${EMULATOR_SELECT_TIMEOUT_SECONDS:-10}"
UPGRADE_TEST_CLASSES="${UPGRADE_TEST_CLASSES:-com.bytedance.zgx.pocketmind.MainActivitySmokeTest}"

PACKAGE_NAME="com.bytedance.zgx.pocketmind"
TEST_PACKAGE_NAME="${PACKAGE_NAME}.test"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
TEST_RUNNER="${TEST_PACKAGE_NAME}/androidx.test.runner.AndroidJUnitRunner"
CURRENT_DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
CURRENT_ANDROID_TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SELECTED_SERIAL=""
API_LEVEL=""
ABI_LIST=""
AVD_LABEL=""
BASE_REF="${UPGRADE_BASE_REF:-}"
BASE_COMMIT=""
BASE_WORKTREE=""
BASE_DEBUG_APK=""
BASE_DEBUG_APK_SHA=""
CURRENT_DEBUG_APK_SHA=""
CURRENT_ANDROID_TEST_APK_SHA=""
BASE_FIRST_INSTALL_TIME=""
BASE_LAST_UPDATE_TIME=""
BASE_VERSION_CODE=""
BASE_VERSION_NAME=""
CURRENT_FIRST_INSTALL_TIME=""
CURRENT_LAST_UPDATE_TIME=""
CURRENT_VERSION_CODE=""
CURRENT_VERSION_NAME=""
VERSION_CODE_INCREASED="false"
INSTRUMENTATION_STATUS="not-run"
INSTRUMENTATION_TEST_COUNT=""
FAILED_TARGET=""
FAILURE_REASON=""

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

package_value() {
  local file="$1"
  local key="$2"
  awk -F= -v key="$key" '
    index($1, key) {
      value = substr($0, index($0, "=") + 1)
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", value)
      print value
      exit
    }
  ' "$file"
}

device_state_for() {
  local serial="$1"
  "$ADB_BIN" devices | awk -v serial="$serial" '$1 == serial {print $2; found = 1} END {if (!found) print ""}'
}

authorized_emulators() {
  "$ADB_BIN" devices | awk 'NR > 1 && $1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}'
}

available_avd_summary() {
  if [[ ! -x "$EMULATOR_BIN" ]]; then
    echo "none"
    return
  fi
  "$EMULATOR_BIN" -list-avds 2>/dev/null | awk 'NF {items = items ? items ", " $0 : $0} END {print items ? items : "none"}'
}

emulator_avd_name() {
  "${ADB[@]}" emu avd name 2>/dev/null |
    tr -d '\r' |
    awk 'NF && $0 != "OK" {value = $0} END {print value}'
}

select_emulator_once() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    [[ "$ANDROID_SERIAL" == emulator-* ]] || return 1
    [[ "$(device_state_for "$ANDROID_SERIAL")" == "device" ]] || return 1
    SELECTED_SERIAL="$ANDROID_SERIAL"
    return
  fi

  local emulator_serials=()
  local serial
  while IFS= read -r serial; do
    [[ -n "$serial" ]] && emulator_serials+=("$serial")
  done < <(authorized_emulators)
  [[ "${#emulator_serials[@]}" -eq 1 ]] || return 1
  SELECTED_SERIAL="${emulator_serials[0]}"
}

wait_for_emulator_selection() {
  local deadline=$((SECONDS + EMULATOR_SELECT_TIMEOUT_SECONDS))
  while true; do
    if select_emulator_once; then
      return
    fi
    [[ "$SECONDS" -lt "$deadline" ]] ||
      fail emulator-selection no-single-authorized-emulator \
        "Timed out waiting for a single authorized emulator. Start exactly one emulator-* device or set AVD_NAME."
    sleep 2
  done
}

wait_for_boot_completed() {
  local deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
  while true; do
    local state boot_completed
    state="$(device_state_for "$SELECTED_SERIAL")"
    boot_completed="$("$ADB_BIN" -s "$SELECTED_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [[ "$state" == "device" && "$boot_completed" == "1" ]]; then
      return
    fi
    [[ "$SECONDS" -lt "$deadline" ]] ||
      fail emulator-boot emulator-boot-timeout "Timed out waiting for $SELECTED_SERIAL to finish booting."
    sleep 2
  done
}

instrumentation_output_failed() {
  grep -qE '^(FAILURES!!!|INSTRUMENTATION_STATUS_CODE: -2|INSTRUMENTATION_RESULT: shortMsg=|INSTRUMENTATION_STATUS: stack=|Error in )' <<<"$1"
}

instrumentation_output_succeeded() {
  sed 's/\r$//' <<<"$1" | grep -qE '^OK( \([0-9]+ tests?\))?$'
}

instrumentation_test_count_for() {
  local output="$1"
  local count
  count="$(awk -F= '/^INSTRUMENTATION_STATUS: numtests=/ {value = $2} END {print value}' <<<"$output" | tr -d '\r')"
  if [[ "$count" =~ ^[0-9]+$ ]]; then
    echo "$count"
    return
  fi
  sed -nE 's/.*OK \(([0-9]+) tests?\).*/\1/p' <<<"$output" | tail -n 1 | tr -d '\r'
}

default_base_ref() {
  git log --format=%H -- app/src/main app/src/debug app/build.gradle.kts | sed -n '2p'
}

write_report() {
  local exit_code="$1"
  local status_label="failed"
  [[ "$exit_code" -eq 0 ]] && status_label="passed"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    echo "status=$status_label"
    echo "exit_code=$exit_code"
    echo "target=upgrade-install-emulator"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=${FAILURE_REASON:-}"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=${SELECTED_SERIAL:-}"
    echo "api_level=${API_LEVEL:-}"
    echo "abi=${ABI_LIST:-}"
    echo "avd=${AVD_LABEL:-${AVD_NAME:-}}"
    echo "clean_device=1"
    echo "installMode=adb-install-r"
    echo "baseRef=${BASE_REF:-}"
    echo "baseCommit=${BASE_COMMIT:-}"
    echo "currentCommit=$(git rev-parse HEAD)"
    echo "baseDebugApk=${BASE_DEBUG_APK:-}"
    echo "baseDebugApkSha256=${BASE_DEBUG_APK_SHA:-}"
    echo "currentDebugApk=$CURRENT_DEBUG_APK"
    echo "currentDebugApkSha256=${CURRENT_DEBUG_APK_SHA:-}"
    echo "currentAndroidTestApk=$CURRENT_ANDROID_TEST_APK"
    echo "currentAndroidTestApkSha256=${CURRENT_ANDROID_TEST_APK_SHA:-}"
    echo "packageBeforeUpgradeFile=$BASE_PACKAGE_FILE"
    echo "packageAfterUpgradeFile=$CURRENT_PACKAGE_FILE"
    echo "releaseFlowPassed=false"
    echo "evidenceKind=upgrade-install-smoke"
    echo "baseFirstInstallTime=${BASE_FIRST_INSTALL_TIME:-}"
    echo "baseLastUpdateTime=${BASE_LAST_UPDATE_TIME:-}"
    echo "baseVersionCode=${BASE_VERSION_CODE:-}"
    echo "baseVersionName=${BASE_VERSION_NAME:-}"
    echo "currentFirstInstallTime=${CURRENT_FIRST_INSTALL_TIME:-}"
    echo "currentLastUpdateTime=${CURRENT_LAST_UPDATE_TIME:-}"
    echo "currentVersionCode=${CURRENT_VERSION_CODE:-}"
    echo "currentVersionName=${CURRENT_VERSION_NAME:-}"
    echo "versionCodeIncreased=$VERSION_CODE_INCREASED"
    echo "testClasses=$UPGRADE_TEST_CLASSES"
    echo "instrumentation=$INSTRUMENTATION_STATUS"
    echo "instrumentation_test_count=${INSTRUMENTATION_TEST_COUNT:-}"
    echo "instrumentation_output_file=$INSTRUMENTATION_OUTPUT_FILE"
  } > "$REPORT_FILE"
  echo "Upgrade install emulator report: $REPORT_FILE"
}

cleanup() {
  local status=$?
  write_report "$status"
  if [[ -n "$BASE_WORKTREE" ]]; then
    git worktree remove --force "$BASE_WORKTREE" >/dev/null 2>&1 || rm -rf "$BASE_WORKTREE"
  fi
  exit "$status"
}
trap cleanup EXIT

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "verify_upgrade_install_emulator: $*" >&2
  if [[ -x "$EMULATOR_BIN" ]]; then
    echo "Available AVDs: $(available_avd_summary)" >&2
  fi
  exit 1
}

if ! scripts/doctor.sh --device; then
  fail doctor doctor-device-failed "Android emulator environment check failed."
fi
[[ -x "$EMULATOR_BIN" ]] || fail emulator-binary emulator-binary-missing "Android emulator binary not found at $EMULATOR_BIN."
if [[ -n "${ANDROID_SERIAL:-}" && "$ANDROID_SERIAL" != emulator-* ]]; then
  fail android-serial android-serial-not-emulator "ANDROID_SERIAL=$ANDROID_SERIAL is not an emulator serial."
fi

mkdir -p "$ARTIFACT_DIR"
[[ -n "$BASE_REF" ]] || BASE_REF="$(default_base_ref)"
[[ -n "$BASE_REF" ]] || fail base-ref base-ref-missing "Could not infer an upgrade base ref from app source history."
BASE_COMMIT="$(git rev-parse "$BASE_REF")"
BASE_WORKTREE="$(mktemp -d "${TMPDIR:-/tmp}/pocketmind-upgrade-base.XXXXXX")"
rm -rf "$BASE_WORKTREE"
git worktree add --detach "$BASE_WORKTREE" "$BASE_COMMIT" >/dev/null

echo "Building upgrade base APK from $BASE_COMMIT"
"$BASE_WORKTREE/gradlew" -p "$BASE_WORKTREE" :app:assembleDebug
BASE_DEBUG_APK="$ARTIFACT_DIR/base-app-debug.apk"
cp "$BASE_WORKTREE/app/build/outputs/apk/debug/app-debug.apk" "$BASE_DEBUG_APK"
BASE_DEBUG_APK_SHA="$(sha256_file "$BASE_DEBUG_APK")"

echo "Building current debug and AndroidTest APKs"
"$GRADLE_CMD" :app:assembleDebug :app:assembleDebugAndroidTest
[[ -f "$CURRENT_DEBUG_APK" ]] || fail current-build current-debug-apk-missing "Current debug APK missing: $CURRENT_DEBUG_APK"
[[ -f "$CURRENT_ANDROID_TEST_APK" ]] || fail current-build current-android-test-apk-missing "Current AndroidTest APK missing: $CURRENT_ANDROID_TEST_APK"
CURRENT_DEBUG_APK_SHA="$(sha256_file "$CURRENT_DEBUG_APK")"
CURRENT_ANDROID_TEST_APK_SHA="$(sha256_file "$CURRENT_ANDROID_TEST_APK")"

if [[ -n "${AVD_NAME:-}" ]]; then
  "$EMULATOR_BIN" -list-avds 2>/dev/null | grep -Fx -- "$AVD_NAME" >/dev/null ||
    fail requested-avd requested-avd-not-found "AVD_NAME=$AVD_NAME not found."
  EXTRA_EMULATOR_ARGS=()
  if [[ -n "${EMULATOR_ARGS:-}" ]]; then
    read -r -a EXTRA_EMULATOR_ARGS <<< "$EMULATOR_ARGS"
  fi
  EMULATOR_CMD=("$EMULATOR_BIN" -avd "$AVD_NAME")
  if [[ "${#EXTRA_EMULATOR_ARGS[@]}" -gt 0 ]]; then
    EMULATOR_CMD+=("${EXTRA_EMULATOR_ARGS[@]}")
  fi
  echo "Starting emulator AVD: $AVD_NAME"
  echo "Emulator log: $EMULATOR_LOG"
  "${EMULATOR_CMD[@]}" > "$EMULATOR_LOG" 2>&1 &
fi

wait_for_emulator_selection
wait_for_boot_completed
ADB=("$ADB_BIN" -s "$SELECTED_SERIAL")
API_LEVEL="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
ABI_LIST="$("${ADB[@]}" shell getprop ro.product.cpu.abilist64 | tr -d '\r')"
AVD_LABEL="$(emulator_avd_name || true)"

if [[ "$ABI_LIST" != *"arm64-v8a"* ]]; then
  fail device-abi device-abi-not-arm64 "Selected emulator is not arm64-v8a compatible: ${ABI_LIST:-unknown}"
fi

echo "Using Android emulator: $SELECTED_SERIAL"
echo "API: ${API_LEVEL:-unknown}"
echo "ABI: ${ABI_LIST:-unknown}"
echo "AVD: ${AVD_LABEL:-${AVD_NAME:-unknown}}"

"${ADB[@]}" uninstall "$TEST_PACKAGE_NAME" >/dev/null 2>&1 || true
"${ADB[@]}" uninstall "$PACKAGE_NAME" >/dev/null 2>&1 || true

"${ADB[@]}" install -r "$BASE_DEBUG_APK"
"${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null
"${ADB[@]}" shell dumpsys package "$PACKAGE_NAME" > "$BASE_PACKAGE_FILE"
BASE_FIRST_INSTALL_TIME="$(package_value "$BASE_PACKAGE_FILE" firstInstallTime)"
BASE_LAST_UPDATE_TIME="$(package_value "$BASE_PACKAGE_FILE" lastUpdateTime)"
BASE_VERSION_CODE="$(package_value "$BASE_PACKAGE_FILE" versionCode)"
BASE_VERSION_NAME="$(package_value "$BASE_PACKAGE_FILE" versionName)"

sleep 2
"${ADB[@]}" install -r "$CURRENT_DEBUG_APK"
"${ADB[@]}" install -r -t "$CURRENT_ANDROID_TEST_APK"
"${ADB[@]}" shell dumpsys package "$PACKAGE_NAME" > "$CURRENT_PACKAGE_FILE"
CURRENT_FIRST_INSTALL_TIME="$(package_value "$CURRENT_PACKAGE_FILE" firstInstallTime)"
CURRENT_LAST_UPDATE_TIME="$(package_value "$CURRENT_PACKAGE_FILE" lastUpdateTime)"
CURRENT_VERSION_CODE="$(package_value "$CURRENT_PACKAGE_FILE" versionCode)"
CURRENT_VERSION_NAME="$(package_value "$CURRENT_PACKAGE_FILE" versionName)"
if [[ "$BASE_VERSION_CODE" =~ ^[0-9]+ && "$CURRENT_VERSION_CODE" =~ ^[0-9]+ ]]; then
  base_code="${BASE_VERSION_CODE%% *}"
  current_code="${CURRENT_VERSION_CODE%% *}"
  if [[ "$current_code" -gt "$base_code" ]]; then
    VERSION_CODE_INCREASED="true"
  fi
fi

if [[ -z "$CURRENT_FIRST_INSTALL_TIME" || -z "$CURRENT_LAST_UPDATE_TIME" ]]; then
  fail package-info package-times-missing "Could not read package firstInstallTime/lastUpdateTime after upgrade."
fi
if [[ -n "$BASE_FIRST_INSTALL_TIME" && "$CURRENT_FIRST_INSTALL_TIME" != "$BASE_FIRST_INSTALL_TIME" ]]; then
  fail package-info first-install-time-changed \
    "firstInstallTime changed across install -r; expected $BASE_FIRST_INSTALL_TIME, got $CURRENT_FIRST_INSTALL_TIME."
fi
if [[ -n "$BASE_LAST_UPDATE_TIME" && "$CURRENT_LAST_UPDATE_TIME" == "$BASE_LAST_UPDATE_TIME" ]]; then
  fail package-info last-update-time-not-changed "lastUpdateTime did not change across install -r."
fi

set +e
TEST_OUTPUT="$("${ADB[@]}" shell am instrument -w -r -e class "$UPGRADE_TEST_CLASSES" "$TEST_RUNNER" 2>&1)"
TEST_STATUS=$?
set -e
mkdir -p "$(dirname "$INSTRUMENTATION_OUTPUT_FILE")"
printf '%s\n' "$TEST_OUTPUT" > "$INSTRUMENTATION_OUTPUT_FILE"
printf '%s\n' "$TEST_OUTPUT"
INSTRUMENTATION_TEST_COUNT="$(instrumentation_test_count_for "$TEST_OUTPUT")"
if [[ "$TEST_STATUS" -eq 0 ]] && instrumentation_output_failed "$TEST_OUTPUT"; then
  TEST_STATUS=1
  FAILED_TARGET="instrumentation"
  FAILURE_REASON="instrumentation-failed"
fi
if [[ "$TEST_STATUS" -eq 0 ]] && ! instrumentation_output_succeeded "$TEST_OUTPUT"; then
  TEST_STATUS=1
  FAILED_TARGET="instrumentation"
  FAILURE_REASON="instrumentation-success-marker-missing"
fi
if [[ "$TEST_STATUS" -ne 0 ]]; then
  INSTRUMENTATION_STATUS="failed"
  [[ -n "$FAILED_TARGET" ]] || FAILED_TARGET="instrumentation"
  [[ -n "$FAILURE_REASON" ]] || FAILURE_REASON="instrumentation-command-failed"
  exit "$TEST_STATUS"
fi
INSTRUMENTATION_STATUS="passed"

echo "Upgrade install emulator verification passed."

#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_SDK}"
GRADLE_CMD="${GRADLE_CMD:-./gradlew}"
ADB_BIN="${ANDROID_SDK}/platform-tools/adb"
ARTIFACT_DIR="${ARTIFACT_DIR:-build/verification/live-remote-emulator-$(date +%Y%m%d-%H%M%S)}"
REPORT_FILE="${REPORT_FILE:-${ARTIFACT_DIR}/live-remote-emulator.properties}"
SCREENSHOT_FILE="${ARTIFACT_DIR}/live-remote-result.png"
UI_DUMP_FILE="${ARTIFACT_DIR}/live-remote-result.xml"
LOGCAT_FILE="${ARTIFACT_DIR}/live-remote-logcat.txt"
PACKAGE_NAME="com.bytedance.zgx.pocketmind"
MAIN_ACTIVITY="${PACKAGE_NAME}/.MainActivity"
DEBUG_CONFIG_RECEIVER="${PACKAGE_NAME}/.debug.DebugRemoteConfigReceiver"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"

LIVE_REMOTE_BASE_URL="${POCKETMIND_LIVE_REMOTE_BASE_URL:-}"
LIVE_REMOTE_MODEL="${POCKETMIND_LIVE_REMOTE_MODEL:-}"
LIVE_REMOTE_API_KEY="${POCKETMIND_LIVE_REMOTE_API_KEY:-}"
LIVE_REMOTE_PROMPT="${POCKETMIND_LIVE_REMOTE_PROMPT:-return uppercase token formed by joining word pocketmind with words live and ok using underscores only}"
LIVE_REMOTE_EXPECTED_TEXT="${POCKETMIND_LIVE_REMOTE_EXPECTED_TEXT:-POCKETMIND_LIVE_OK}"
SELECTED_SERIAL=""
STARTED_AT_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
API_KEY_SOURCE=""
BASE_URL_SOURCE=""
MODEL_SOURCE=""
FAILED_TARGET=""
FAILURE_REASON=""

write_report() {
  local exit_code="$1"
  local status="failed"
  [[ "$exit_code" -eq 0 ]] && status="passed"
  mkdir -p "$(dirname "$REPORT_FILE")"
  {
    echo "status=$status"
    echo "exit_code=$exit_code"
    echo "target=live-remote-emulator"
    echo "failedTarget=${FAILED_TARGET:-}"
    echo "reason=${FAILURE_REASON:-}"
    echo "started_at_utc=$STARTED_AT_UTC"
    echo "finished_at_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "serial=${SELECTED_SERIAL:-}"
    echo "base_url=<redacted>"
    echo "base_url_source=${BASE_URL_SOURCE:-}"
    echo "model=<redacted>"
    echo "model_source=${MODEL_SOURCE:-}"
    echo "api_key_source=${API_KEY_SOURCE:-}"
    echo "expected_text=$LIVE_REMOTE_EXPECTED_TEXT"
    echo "debug_apk=$DEBUG_APK"
    echo "evidence_dir=$ARTIFACT_DIR"
    echo "screenshot=$SCREENSHOT_FILE"
    echo "ui_dump=$UI_DUMP_FILE"
    echo "logcat_file=$LOGCAT_FILE"
  } > "$REPORT_FILE"
  echo "Live remote emulator report: $REPORT_FILE"
}

fail() {
  FAILED_TARGET="$1"
  FAILURE_REASON="$2"
  shift 2
  echo "live_remote_emulator: $*" >&2
  exit 1
}

debug_receiver_broadcast() {
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell run-as "$PACKAGE_NAME" am broadcast \
    --user 0 \
    -n "$DEBUG_CONFIG_RECEIVER" \
    "$@"
}

clear_remote_config() {
  if [[ -z "${SELECTED_SERIAL:-}" || ! -x "$ADB_BIN" ]]; then
    return
  fi
  debug_receiver_broadcast --ez clearRemoteConfig true >/dev/null 2>&1 || true
}

select_emulator() {
  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    [[ "$ANDROID_SERIAL" == emulator-* ]] ||
      fail emulator-selection android-serial-not-emulator "ANDROID_SERIAL=$ANDROID_SERIAL is not an emulator serial."
    local state
    state="$("$ADB_BIN" devices | awk -v serial="$ANDROID_SERIAL" '$1 == serial {print $2; found = 1} END {if (!found) print ""}')"
    [[ "$state" == "device" ]] ||
      fail emulator-selection selected-emulator-unavailable "ANDROID_SERIAL=$ANDROID_SERIAL is not an authorized emulator; state is ${state:-missing}."
    SELECTED_SERIAL="$ANDROID_SERIAL"
    return
  fi

  local serials=()
  while IFS= read -r serial; do
    [[ -n "$serial" ]] && serials+=("$serial")
  done < <("$ADB_BIN" devices | awk 'NR > 1 && $1 ~ /^emulator-[0-9]+$/ && $2 == "device" {print $1}')
  [[ "${#serials[@]}" -eq 1 ]] ||
    fail emulator-selection no-single-authorized-emulator "Start exactly one authorized emulator or set ANDROID_SERIAL."
  SELECTED_SERIAL="${serials[0]}"
}

capture_failure_evidence() {
  local status="$1"
  if [[ "$status" -eq 0 || -z "${SELECTED_SERIAL:-}" || ! -x "$ADB_BIN" ]]; then
    return
  fi
  mkdir -p "$ARTIFACT_DIR"
  "$ADB_BIN" -s "$SELECTED_SERIAL" exec-out screencap -p > "$SCREENSHOT_FILE" 2>/dev/null || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" shell uiautomator dump /sdcard/pocketmind-live-remote.xml >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" pull /sdcard/pocketmind-live-remote.xml "$UI_DUMP_FILE" >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$SELECTED_SERIAL" logcat -d -t 300 > "$LOGCAT_FILE" 2>/dev/null || true
}

trap 'status=$?; capture_failure_evidence "$status"; clear_remote_config; write_report "$status"; exit "$status"' EXIT

[[ -x "$ADB_BIN" ]] || fail adb adb-missing "adb not found at $ADB_BIN."
[[ -n "$LIVE_REMOTE_BASE_URL" ]] ||
  fail configuration missing-base-url "Set POCKETMIND_LIVE_REMOTE_BASE_URL before running live remote validation."
[[ -n "$LIVE_REMOTE_MODEL" ]] ||
  fail configuration missing-model "Set POCKETMIND_LIVE_REMOTE_MODEL before running live remote validation."
[[ -n "$LIVE_REMOTE_API_KEY" ]] ||
  fail configuration missing-api-key "Set POCKETMIND_LIVE_REMOTE_API_KEY before running live remote validation."
BASE_URL_SOURCE="POCKETMIND_LIVE_REMOTE_BASE_URL"
MODEL_SOURCE="POCKETMIND_LIVE_REMOTE_MODEL"
API_KEY_SOURCE="POCKETMIND_LIVE_REMOTE_API_KEY"

if ! scripts/doctor.sh --device; then
  fail doctor doctor-device-failed "Android emulator environment check failed."
fi
select_emulator
ADB=("$ADB_BIN" -s "$SELECTED_SERIAL")
echo "Using Android emulator: $SELECTED_SERIAL"
mkdir -p "$ARTIFACT_DIR"

if ! "$GRADLE_CMD" :app:assembleDebug; then
  fail gradle assemble-debug-failed "Debug APK assembly failed."
fi
if ! "${ADB[@]}" install -r "$DEBUG_APK" >/dev/null; then
  fail install debug-apk-install-failed "Debug APK install failed."
fi

set +x
if ! debug_receiver_broadcast \
  --es baseUrl "$LIVE_REMOTE_BASE_URL" \
  --es modelName "$LIVE_REMOTE_MODEL" \
  --es apiKey "$LIVE_REMOTE_API_KEY" \
  --ez clearState true >/dev/null; then
  fail remote-config remote-config-broadcast-failed "Debug remote config broadcast failed."
fi

if ! "${ADB[@]}" shell am start -W -n "$MAIN_ACTIVITY" >/dev/null; then
  fail app-launch app-launch-failed "MainActivity launch failed."
fi
sleep 2
if ! "${ADB[@]}" shell input tap 320 2225; then
  fail ui-input prompt-field-tap-failed "Prompt field tap failed."
fi
encoded_prompt="${LIVE_REMOTE_PROMPT// /%s}"
if ! "${ADB[@]}" shell input text "$encoded_prompt"; then
  fail ui-input prompt-text-input-failed "Prompt text input failed."
fi
sleep 0.5
if ! "${ADB[@]}" shell input keyevent 4; then
  fail ui-input keyboard-dismiss-failed "Keyboard dismiss failed."
fi
sleep 0.8
if ! "${ADB[@]}" shell input tap 980 2245; then
  fail ui-input send-button-tap-failed "Send button tap failed."
fi
sleep "${POCKETMIND_LIVE_REMOTE_WAIT_SECONDS:-45}"

if ! "${ADB[@]}" exec-out screencap -p > "$SCREENSHOT_FILE"; then
  fail evidence screenshot-capture-failed "Live remote screenshot capture failed."
fi
if ! "${ADB[@]}" shell uiautomator dump /sdcard/pocketmind-live-remote.xml >/dev/null; then
  fail evidence ui-dump-command-failed "Live remote UI dump command failed."
fi
if ! "${ADB[@]}" pull /sdcard/pocketmind-live-remote.xml "$UI_DUMP_FILE" >/dev/null; then
  fail evidence ui-dump-pull-failed "Live remote UI dump pull failed."
fi

if grep -Fq "远程模型请求失败" "$UI_DUMP_FILE"; then
  fail remote-request remote-request-failed "Live remote request failed; inspect $UI_DUMP_FILE."
fi

grep -Fq -- "$LIVE_REMOTE_EXPECTED_TEXT" "$UI_DUMP_FILE" ||
  fail expected-response expected-text-not-found "Expected live remote response evidence in UI dump; inspect $UI_DUMP_FILE."

set +x
echo "Live remote emulator validation passed."

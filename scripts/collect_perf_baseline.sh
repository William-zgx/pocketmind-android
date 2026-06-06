#!/usr/bin/env bash
set -euo pipefail

OUT_FILE="${OUT_FILE:-build/verification/rc/perf-baseline.properties}"
RELEASE_ARTIFACT="${RELEASE_ARTIFACT:-}"
ANDROID_SERIAL="${ANDROID_SERIAL:-}"
STATUS="${STATUS:-passed}"

usage() {
  cat >&2 <<'USAGE'
Usage:
  OUT_FILE=build/verification/rc/perf-baseline.properties \
  RELEASE_ARTIFACT=app-release-signed.apk \
  ANDROID_SERIAL=<device> \
  APP_VERSION=<versionName> MODEL_ID=chat-e2b BACKEND=GPU \
  FIRST_LAUNCH_INTERACTIVE_MS=... MODEL_LOAD_MS=... FIRST_TOKEN_MS=... \
  TOKENS_PER_SECOND=... STOP_GENERATION_RECOVERY_MS=... GPU_FALLBACK_STATUS=... \
  VISION_INPUT_MS=... MEMORY_SEARCH_5K_MS=... MEMORY_PEAK_MB=... \
  OOM_OR_ANR_OBSERVED=false \
  scripts/collect_perf_baseline.sh

This script records measured RC performance inputs; it does not invent timings.
USAGE
}

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: $name" >&2
    usage
    exit 1
  fi
}

require_env RELEASE_ARTIFACT
require_env APP_VERSION
require_env MODEL_ID
require_env BACKEND
require_env FIRST_LAUNCH_INTERACTIVE_MS
require_env MODEL_LOAD_MS
require_env FIRST_TOKEN_MS
require_env TOKENS_PER_SECOND
require_env STOP_GENERATION_RECOVERY_MS
require_env GPU_FALLBACK_STATUS
require_env VISION_INPUT_MS
require_env MEMORY_SEARCH_5K_MS
require_env MEMORY_PEAK_MB
require_env OOM_OR_ANR_OBSERVED

if [[ ! -f "$RELEASE_ARTIFACT" ]]; then
  echo "Release artifact is missing: $RELEASE_ARTIFACT" >&2
  exit 1
fi

ADB="${ADB:-adb}"
if [[ -n "$ANDROID_SERIAL" ]]; then
  ADB_CMD=("$ADB" -s "$ANDROID_SERIAL")
else
  ADB_CMD=("$ADB")
fi

device_serial="$ANDROID_SERIAL"
device_model="${DEVICE_MODEL:-}"
android_api="${ANDROID_API:-}"
abi="${ABI:-}"

if command -v "$ADB" >/dev/null 2>&1; then
  device_serial="${device_serial:-$("${ADB_CMD[@]}" get-serialno 2>/dev/null || true)}"
  device_model="${device_model:-$("${ADB_CMD[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)}"
  android_api="${android_api:-$("${ADB_CMD[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)}"
  abi="${abi:-$("${ADB_CMD[@]}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true)}"
fi

require_value() {
  local name="$1"
  local value="$2"
  if [[ -z "$value" ]]; then
    echo "Missing $name; provide it via environment when adb cannot read the device." >&2
    exit 1
  fi
}

require_value deviceSerial "$device_serial"
require_value deviceModel "$device_model"
require_value androidApi "$android_api"
require_value abi "$abi"

mkdir -p "$(dirname "$OUT_FILE")"
{
  printf 'status=%s\n' "$STATUS"
  printf 'deviceSerial=%s\n' "$device_serial"
  printf 'deviceModel=%s\n' "$device_model"
  printf 'androidApi=%s\n' "$android_api"
  printf 'abi=%s\n' "$abi"
  printf 'appVersion=%s\n' "$APP_VERSION"
  printf 'releaseArtifactSha256=%s\n' "$(shasum -a 256 "$RELEASE_ARTIFACT" | awk '{print $1}')"
  printf 'modelId=%s\n' "$MODEL_ID"
  printf 'backend=%s\n' "$BACKEND"
  printf 'firstLaunchInteractiveMs=%s\n' "$FIRST_LAUNCH_INTERACTIVE_MS"
  printf 'modelLoadMs=%s\n' "$MODEL_LOAD_MS"
  printf 'firstTokenMs=%s\n' "$FIRST_TOKEN_MS"
  printf 'tokensPerSecond=%s\n' "$TOKENS_PER_SECOND"
  printf 'stopGenerationRecoveryMs=%s\n' "$STOP_GENERATION_RECOVERY_MS"
  printf 'gpuFallbackStatus=%s\n' "$GPU_FALLBACK_STATUS"
  printf 'visionInputMs=%s\n' "$VISION_INPUT_MS"
  printf 'memorySearch5kMs=%s\n' "$MEMORY_SEARCH_5K_MS"
  printf 'memoryPeakMb=%s\n' "$MEMORY_PEAK_MB"
  printf 'oomOrAnrObserved=%s\n' "$OOM_OR_ANR_OBSERVED"
  printf 'recordedAt=%s\n' "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
} > "$OUT_FILE"

scripts/verify_perf_baseline.sh \
  --file "$OUT_FILE" \
  --artifact-sha256 "$(shasum -a 256 "$RELEASE_ARTIFACT" | awk '{print $1}')"

echo "Perf baseline written to $OUT_FILE"

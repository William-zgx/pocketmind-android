#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
BUILD_TOOLS_DIR="${BUILD_TOOLS_DIR:-$(find "$ANDROID_SDK/build-tools" -maxdepth 1 -type d 2>/dev/null | sort | tail -n 1)}"
ZIPALIGN="${ZIPALIGN:-$BUILD_TOOLS_DIR/zipalign}"
APKSIGNER="${APKSIGNER:-$BUILD_TOOLS_DIR/apksigner}"

UNSIGNED_APK="${UNSIGNED_APK:-app/build/outputs/apk/release/app-release-unsigned.apk}"
UNSIGNED_AAB="${UNSIGNED_AAB:-app/build/outputs/bundle/release/app-release.aab}"
SIGNED_APK="${SIGNED_APK:-app/build/outputs/apk/release/app-release-signed.apk}"
SIGNED_AAB="${SIGNED_AAB:-app/build/outputs/bundle/release/app-release-signed.aab}"
ALIGNED_APK="${ALIGNED_APK:-${SIGNED_APK%.apk}-aligned.apk}"
REPORT_FILE="${REPORT_FILE:-build/verification/signing/signing.properties}"

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: $name" >&2
    exit 1
  fi
}

require_tool() {
  local path="$1"
  local name="$2"
  if [[ -z "$path" || ! -x "$path" ]]; then
    echo "$name not found or not executable: $path" >&2
    exit 1
  fi
}

require_env RELEASE_KEYSTORE
require_env RELEASE_KEY_ALIAS
require_env RELEASE_KEYSTORE_PASSWORD
require_env RELEASE_KEY_PASSWORD

if [[ ! -f "$RELEASE_KEYSTORE" ]]; then
  echo "Release keystore not found: $RELEASE_KEYSTORE" >&2
  exit 1
fi

require_tool "$ZIPALIGN" zipalign
require_tool "$APKSIGNER" apksigner
command -v jarsigner >/dev/null 2>&1 || {
  echo "jarsigner not found in PATH." >&2
  exit 1
}

mkdir -p "$(dirname "$SIGNED_APK")" "$(dirname "$SIGNED_AAB")" "$(dirname "$REPORT_FILE")"

if [[ -f "$UNSIGNED_APK" ]]; then
  "$ZIPALIGN" -p -f 4 "$UNSIGNED_APK" "$ALIGNED_APK"
  "$APKSIGNER" sign \
    --ks "$RELEASE_KEYSTORE" \
    --ks-key-alias "$RELEASE_KEY_ALIAS" \
    --ks-pass "pass:$RELEASE_KEYSTORE_PASSWORD" \
    --key-pass "pass:$RELEASE_KEY_PASSWORD" \
    --out "$SIGNED_APK" \
    "$ALIGNED_APK"
  "$APKSIGNER" verify --verbose --print-certs "$SIGNED_APK" > "$REPORT_FILE.apk-certs.txt"
fi

if [[ -f "$UNSIGNED_AAB" ]]; then
  cp "$UNSIGNED_AAB" "$SIGNED_AAB"
  jarsigner \
    -keystore "$RELEASE_KEYSTORE" \
    -storepass "$RELEASE_KEYSTORE_PASSWORD" \
    -keypass "$RELEASE_KEY_PASSWORD" \
    "$SIGNED_AAB" \
    "$RELEASE_KEY_ALIAS" >/dev/null
  jarsigner -verify -certs -verbose "$SIGNED_AAB" > "$REPORT_FILE.aab-certs.txt" 2>&1
fi

scan_args=()
if [[ -f "$SIGNED_APK" ]]; then
  scan_args+=(--apk "$SIGNED_APK")
fi
if [[ -f "$SIGNED_AAB" ]]; then
  scan_args+=(--aab "$SIGNED_AAB")
fi
if [[ "${#scan_args[@]}" -eq 0 ]]; then
  echo "No signed APK or AAB was produced." >&2
  exit 1
fi
scripts/scan_android_artifacts.sh \
  "${scan_args[@]}" \
  --require-signed \
  --report "$REPORT_FILE.artifact-scan.properties"

{
  printf 'status=passed\n'
  printf 'target=release-signing\n'
  printf 'signedApk=%s\n' "$SIGNED_APK"
  printf 'signedAab=%s\n' "$SIGNED_AAB"
  if [[ -f "$SIGNED_APK" ]]; then
    printf 'signedApkSha256=%s\n' "$(shasum -a 256 "$SIGNED_APK" | awk '{print $1}')"
  fi
  if [[ -f "$SIGNED_AAB" ]]; then
    printf 'signedAabSha256=%s\n' "$(shasum -a 256 "$SIGNED_AAB" | awk '{print $1}')"
  fi
} > "$REPORT_FILE"

echo "Release artifacts signed. Report: $REPORT_FILE"

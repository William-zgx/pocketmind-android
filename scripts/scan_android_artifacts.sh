#!/usr/bin/env bash
set -euo pipefail

REPORT_FILE=""
REQUIRE_SIGNED=0
ALLOW_DEBUG_CERTIFICATE=0
ARTIFACTS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --apk|--aab)
      ARTIFACTS+=("${2:?missing artifact path}")
      shift 2
      ;;
    --report)
      REPORT_FILE="${2:?missing report path}"
      shift 2
      ;;
    --require-signed)
      REQUIRE_SIGNED=1
      shift
      ;;
    --allow-debug-certificate)
      ALLOW_DEBUG_CERTIFICATE=1
      shift
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

write_report() {
  local status="$1"
  local finding_count="$2"
  if [[ -n "$REPORT_FILE" ]]; then
    mkdir -p "$(dirname "$REPORT_FILE")"
    {
      printf 'status=%s\n' "$status"
      printf 'target=android-artifact-scan\n'
      printf 'artifactCount=%s\n' "${#ARTIFACTS[@]}"
      printf 'findingCount=%s\n' "$finding_count"
      printf 'requireSigned=%s\n' "$REQUIRE_SIGNED"
      printf 'allowDebugCertificate=%s\n' "$ALLOW_DEBUG_CERTIFICATE"
      local index=0
      for artifact in "${ARTIFACTS[@]}"; do
        index=$((index + 1))
        printf 'artifact%sPath=%s\n' "$index" "$artifact"
        if [[ -f "$artifact" ]]; then
          printf 'artifact%sSha256=%s\n' "$index" "$(shasum -a 256 "$artifact" | awk '{print $1}')"
          printf 'artifact%sSizeBytes=%s\n' "$index" "$(wc -c < "$artifact" | tr -d ' ')"
          printf 'artifact%sType=%s\n' "$index" "${artifact##*.}"
          printf 'artifact%sSigningStatus=%s\n' "$index" "$(artifact_signing_status "$artifact")"
          printf 'artifact%sCertificateSha256=%s\n' "$index" "$(artifact_certificate_sha256 "$artifact")"
          printf 'artifact%sCertificateSubject=%s\n' "$index" "$(artifact_certificate_subject "$artifact")"
        fi
      done
    } > "$REPORT_FILE"
  fi
}

if [[ "${#ARTIFACTS[@]}" -eq 0 ]]; then
  write_report failed 1
  echo "No APK or AAB artifact was provided." >&2
  exit 1
fi

TMP_FINDINGS="$(mktemp)"
trap 'rm -f "$TMP_FINDINGS"' EXIT

find_apksigner() {
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return
  fi
  local sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
  find "$sdk/build-tools" -name apksigner -type f 2>/dev/null | sort | tail -n 1
}

artifact_signing_status() {
  local artifact="$1"
  case "$artifact" in
    *.apk)
      local apksigner_bin
      apksigner_bin="$(find_apksigner)"
      if [[ -z "$apksigner_bin" || ! -x "$apksigner_bin" ]]; then
        echo "tool-missing"
        return
      fi
      if "$apksigner_bin" verify "$artifact" >/dev/null 2>&1; then
        echo "verified"
      else
        echo "failed"
      fi
      ;;
    *.aab)
      if ! command -v jarsigner >/dev/null 2>&1; then
        echo "tool-missing"
        return
      fi
      local output
      output="$(jarsigner -verify "$artifact" 2>&1 || true)"
      if grep -q 'jar verified[.]' <<<"$output" && ! grep -qi 'jar is unsigned' <<<"$output"; then
        echo "verified"
      else
        echo "failed"
      fi
      ;;
    *)
      echo "unknown"
      ;;
  esac
}

artifact_certificate_sha256() {
  local artifact="$1"
  case "$artifact" in
    *.apk)
      local apksigner_bin
      apksigner_bin="$(find_apksigner)"
      if [[ -z "$apksigner_bin" || ! -x "$apksigner_bin" ]]; then
        echo ""
        return
      fi
      ("$apksigner_bin" verify --print-certs "$artifact" 2>/dev/null || true) |
        awk -F': ' '/certificate SHA-256 digest/ {print $2; exit}'
      ;;
    *.aab)
      if ! command -v keytool >/dev/null 2>&1; then
        echo ""
        return
      fi
      (keytool -printcert -jarfile "$artifact" 2>/dev/null || true) |
        awk -F': ' '/SHA256:/ {gsub(":", "", $2); print tolower($2); exit}'
      ;;
    *)
      echo ""
      ;;
  esac
}

artifact_certificate_subject() {
  local artifact="$1"
  case "$artifact" in
    *.apk)
      local apksigner_bin
      apksigner_bin="$(find_apksigner)"
      if [[ -z "$apksigner_bin" || ! -x "$apksigner_bin" ]]; then
        echo ""
        return
      fi
      ("$apksigner_bin" verify --print-certs "$artifact" 2>/dev/null || true) |
        awk -F': ' '/certificate DN/ {print $2; exit}'
      ;;
    *.aab)
      if ! command -v keytool >/dev/null 2>&1; then
        echo ""
        return
      fi
      (keytool -printcert -jarfile "$artifact" 2>/dev/null || true) |
        awk -F': ' '/Owner:/ {print $2; exit}'
      ;;
    *)
      echo ""
      ;;
  esac
}

for artifact in "${ARTIFACTS[@]}"; do
  if [[ ! -f "$artifact" ]]; then
    printf '%s: missing artifact\n' "$artifact" >> "$TMP_FINDINGS"
    continue
  fi
  if unzip -Z1 "$artifact" | grep -E '(^|/)[^/]+[.](litertlm|jks|keystore|pem|p12)$' >/tmp/pocketmind-artifact-files.$$; then
    sed "s#^#$artifact:#" /tmp/pocketmind-artifact-files.$$ >> "$TMP_FINDINGS"
  fi
  rm -f /tmp/pocketmind-artifact-files.$$
  if unzip -p "$artifact" 2>/dev/null |
    strings |
    grep -E '(-----BEGIN [A-Z ]*PRIVATE KEY-----|AKIA[0-9A-Z]{16}|ASIA[0-9A-Z]{16}|AIza[0-9A-Za-z_-]{35}|xox[abprs]-[0-9A-Za-z-]{16,}|sk-[A-Za-z0-9_-]{24,}|code[.]byted[.]org)' \
      >/tmp/pocketmind-artifact-strings.$$; then
    sed "s#^#$artifact:string:#" /tmp/pocketmind-artifact-strings.$$ >> "$TMP_FINDINGS"
  fi
  rm -f /tmp/pocketmind-artifact-strings.$$
  if [[ "$REQUIRE_SIGNED" == "1" ]]; then
    signing_status="$(artifact_signing_status "$artifact")"
    if [[ "$signing_status" != "verified" ]]; then
      printf '%s: signing status is %s\n' "$artifact" "$signing_status" >> "$TMP_FINDINGS"
    fi
    certificate_subject="$(artifact_certificate_subject "$artifact")"
    if [[ "$ALLOW_DEBUG_CERTIFICATE" != "1" ]] && grep -qi 'CN=Android Debug' <<<"$certificate_subject"; then
      printf '%s: signed with Android debug certificate\n' "$artifact" >> "$TMP_FINDINGS"
    fi
  fi
done

FINDING_COUNT="$(grep -c . "$TMP_FINDINGS" || true)"
if [[ "$FINDING_COUNT" -gt 0 ]]; then
  write_report failed "$FINDING_COUNT"
  cat "$TMP_FINDINGS" >&2
  echo "Android artifact scan failed." >&2
  exit 1
fi

write_report passed 0
echo "Android artifact scan passed."

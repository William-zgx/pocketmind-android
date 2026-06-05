#!/usr/bin/env bash
set -euo pipefail

REPORT_FILE=""
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

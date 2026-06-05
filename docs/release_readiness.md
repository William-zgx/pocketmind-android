# Release Readiness

PocketMind now has the core storage, trust-boundary, and build gates needed for
internal testing. Broad external distribution still needs the remaining release
items below.

## Completed

- MIT license added in `LICENSE`.
- Recommended model manifest pins upstream revision, byte size, and SHA-256.
- Privacy notice drafted for local chat storage, remote context transfer,
  encrypted API key storage, model downloads, Android intents, device context
  tools, audit traces, and retention controls.
- Manual release checklist added for store metadata, screenshots,
  privacy/license review, signing, test gates, and rollback planning.
- Machine-readable release gates now cover capability matrix drift,
  privacy scanning, APK/AAB artifact scanning, model license review records,
  RC perf-baseline verification, artifact SHA matching, and optional public
  release enforcement for signed artifacts plus AAB presence.
- Recommended downloads are registered only after SHA-256 verification.
- Legacy recommended files are registered as `LegacyUnverified` and verified
  asynchronously before they can become active.
- Custom URL/imported models remain `UnverifiedCustom`, even when their file
  name matches a recommended model.
- Chat sessions, messages, model registry, and download records use Room.
- Non-sensitive settings use DataStore; remote API keys use encrypted storage.
- Remote model transport requires HTTPS, except local HTTP debug hosts.
- Remote streaming uses OkHttp and cancels the underlying call when stopped.
- PR verification is local-only by default; model URL provenance is manual or
  scheduled with `VERIFY_MODEL_URLS=1`.
- Memory is documented as a lightweight local index. Action planning is
  documented as experimental model planning with rule fallback.
- Remote OpenAI-compatible tool calls now go through the local Agent runtime:
  single public read-only evidence calls and all-public evidence batches can
  execute without confirmation, while mixed private/action/side-effect batches
  fail closed before any tool runs.
- Latest local gate for the current working tree passed
  `scripts/verify_local.sh`, including JVM tests, lint, debug/androidTest APK
  assembly, release assembly, and APK content checks; see
  `docs/validation_report.md` for the dated command log.
- Latest internal ad hoc release APK was locally signed and coverage-installed
  on physical device `fb6272c` for smoke launch validation on
  2026-06-03. This is not a replacement for the final release-candidate
  device/instrumentation gate.
- Current release-candidate emulator regression passed with
  `scripts/regression_emulator.sh` on `focus_agent_api36_arm64` /
  `emulator-5554` (API 36, `arm64-v8a`):
  `build/verification/regression-emulator-20260604-040806/regression-emulator.properties`
  records `status=passed`, nested emulator/device reports passed, and
  `instrumentation_test_count=26` matching the 26 AndroidTest source count.

## Remaining

- Review `docs/privacy_notice.md` with release, security, and legal owners
  before publishing it as an external policy.
- For all four recommended model downloads, manually verify the upstream model
  license name, license URL or file path, redistribution rights, attribution or
  notice requirements, reviewer, and review date. Record the result in
  `docs/model_manifest.md`, `docs/model_license_review.json`, and the release
  checklist. `VERIFY_MODEL_URLS=1` checks URL/content metadata only; it does
  not establish license readiness. `scripts/collect_model_license_metadata.sh`
  can refresh Hugging Face model-card metadata in
  `docs/model_license_metadata.json`, but it does not replace legal/release
  approval.
- Configure release signing outside source control.
- Use `scripts/sign_release_artifacts.sh` from the private signing environment
  to produce signed APK/AAB artifacts and certificate reports once production
  keystore material is available. The script rejects Android debug keystores by
  default; `ALLOW_DEBUG_KEYSTORE=1` is only for local smoke validation.
- Run a final release-candidate validation pass on target physical hardware
  before broad distribution; emulator validation does not cover all LiteRT-LM
  GPU/performance behavior.
- Record final physical-device SLOs with `scripts/collect_perf_baseline.sh`
  or an equivalent measured `perf-baseline.properties` based on
  `docs/perf_baseline_template.properties`, then pass it to
  `scripts/verify_release_gate.sh` with `PERF_BASELINE_FILE=...`.
- For public distribution, run the release gate with
  `VERIFY_MODEL_LICENSES=1 REQUIRE_AAB=1 REQUIRE_SIGNED_ARTIFACT=1` after
  production signing and bundle generation are complete.

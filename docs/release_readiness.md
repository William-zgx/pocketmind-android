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
  release record completeness, store policy/data-safety disclosure, privacy
  scanning, rollout monitoring/rollback readiness, release validation matrix
  evidence, privacy notice review records, APK/AAB artifact scanning, model
  license review records, RC perf-baseline verification, artifact SHA matching,
  and optional public release enforcement for signed artifacts plus AAB presence
  and release mapping output.
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
  `build/verification/regression-emulator-20260606-131714/regression-emulator.properties`
  records `status=passed`, nested emulator/device reports passed, and
  `instrumentation_test_count=26` matching the 26 AndroidTest source count.

## Remaining

- Review `docs/privacy_notice.md` with release, security, and legal owners
  before publishing it as an external policy. Record approvals in
  `docs/privacy_review.json`; `VERIFY_PRIVACY_REVIEW=1` verifies that all
  required roles approved the current notice SHA.
- Fill `docs/release_record.json` for the final release candidate with owner,
  reviewer, target channel, changelog, release notes, artifact checksum, signing
  certificate fingerprint, verification reports, and resolved or accepted
  blockers. `VERIFY_RELEASE_RECORD=1` verifies the record against the current
  Gradle version, a Git commit reachable from the current checkout, local
  artifact, and evidence files.
- Fill `docs/store_policy_record.json` with approved store listing, data-safety
  answers, privacy policy URL, model download disclosure, Android permission
  purposes, and special-access disclosures. `VERIFY_STORE_POLICY=1` verifies
  the record against the current privacy notice SHA and Android manifest.
- Fill `docs/release_operations_record.json` with approved crash/ANR monitoring
  owner, signal sources, first-24-hour watcher, staged rollout thresholds,
  crash/ANR smoke result, and rollback plan. `VERIFY_RELEASE_OPERATIONS=1`
  verifies Android Vitals coverage, rollout thresholds, previous known-good
  metadata or initial-release exemption, rollback criteria, and review date.
- Fill `docs/release_validation_record.json` with approved emulator regression,
  physical-device instrumentation, API matrix coverage, manual acceptance,
  flow matrix, sanitized screenshots, and performance sanity evidence.
  `VERIFY_RELEASE_VALIDATION=1` verifies linked emulator/device reports,
  rejects `emulator-*` serials as physical-device evidence, checks AndroidTest
  count coverage, required API levels, manual/system-mediated flows, screenshot
  files, and review date.
- For all four recommended model downloads, manually verify the upstream model
  license name, license URL or file path, redistribution rights, attribution or
  notice requirements, reviewer, and review date. Record the result in
  `docs/model_manifest.md`, `docs/model_license_review.json`, and the release
  checklist. `VERIFY_MODEL_URLS=1` checks URL/content metadata only; it does
  not establish license readiness. `scripts/collect_model_license_metadata.sh`
  derives the model list from `docs/model_manifest.md` and can refresh Hugging
  Face model-card metadata in `docs/model_license_metadata.json`, but it does
  not replace legal/release approval. `VERIFY_MODEL_LICENSES=1` runs
  `scripts/verify_model_license_review.sh` and requires approved review records
  aligned with the current manifest, metadata, license source, and metadata
  collection date.
- Configure release signing outside source control.
- Use `scripts/sign_release_artifacts.sh` from the private signing environment
  to produce signed APK/AAB artifacts and certificate reports once production
  keystore material is available. The script rejects Android debug keystores by
  default and requires `EXPECTED_SIGNING_CERT_SHA256` for production signing;
  `ALLOW_DEBUG_KEYSTORE=1` is only for local smoke validation.
  Public release gates also reject Android Debug certificates in signed APK/AAB
  artifacts and can pin `EXPECTED_SIGNING_CERT_SHA256` to the production upload
  certificate.
- Run a final release-candidate validation pass on target physical hardware
  before broad distribution; emulator validation does not cover all LiteRT-LM
  GPU/performance behavior.
- Record final physical-device SLOs with `scripts/collect_perf_baseline.sh`
  or an equivalent measured `perf-baseline.properties` based on
  `docs/perf_baseline_template.properties`, then pass it to
  `scripts/verify_release_gate.sh` with `PERF_BASELINE_FILE=...`.
- For public distribution, run the release gate with
  `PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=<production upload cert>` after
  production signing and bundle generation are complete. `PUBLIC_RELEASE=1`
  enables release record, store policy, rollout monitoring/rollback readiness,
  release validation matrix, privacy review, model license, AAB,
  signed-artifact, and certificate fingerprint checks, plus release mapping
  verification.

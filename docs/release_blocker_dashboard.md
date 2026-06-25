# Release Blocker Dashboard

Generated from `docs/roadmap_gap_matrix.json` and `docs/release_readiness.md` only. This is a compact local evidence view; it does not resolve device, performance, signing, store, legal, privacy, or release-owner blockers.

- Roadmap updated: `2026-06-23`
- Policy: This ledger tracks roadmap completion evidence. Local verifier evidence never replaces physical device, emulator, production signing, performance, store, security, legal, or release-owner approval evidence.

## Active Blockers

| Blocker | Status | Owner / phase | Blocking evidence | Next evidence |
| --- | --- | --- | --- | --- |
| Real-app search replay coverage | partial | Agent 2 / Phase 2 | physical real app search eval deferred | Continue adding replay fixtures for changing Taobao, Gaode, JD, and browser search surfaces; verify a fresh physical real-app-search pass rate later. |
| Real-app search physical pass rate | deferred | Agent 2 / Phase 2 | no device test in this phase | Run fresh real-app-search eval on arm64 physical hardware and reach the target pass rate without expanding high-risk automation. |
| Agent behavior actual runtime trace | partial | Agent 4 / Phase 3 | fresh agent loop runtime trace not collected in this phase | Collect a fresh agent_loop_runtime actual trace in public-strict mode and keep traceDiffAllowedFailureCount at zero with runtime provenance. |
| Privacy / store / release approvals | partial | Agent 6 / Phase 4 | release owner evidence required, store policy owner evidence required, privacy security legal approval required, model license approval required | Use the preflight fields in verifier reports to fill real owners, reviewers, public policy URLs, approvals, signing identity, artifact SHA values, and approved evidence files. |
| Physical validation and arm64 API matrix | deferred | Agent 3 / Phase 4 | no device test in this phase, arm64 api matrix environment required | Resolve physical instrumentation crash, run arm64 physical validation, prepare API 28/32/33/34 arm64 AVDs, and collect accepted validation reports. Deferred-mode reports now carry... |
| Perf baseline on physical arm64 | deferred | Agent 5 / Phase 4 | no device test in this phase, physical arm64 performance required | Collect RC model load, first token, tokens/s, memory, ANR/OOM, and GPU fallback evidence on physical arm64 hardware. |
| Real model runtime validation | partial | Agent 5 / Phase 5 | real model runtime validation deferred | Optionally run the targeted JVM slice and later collect real model loading/performance evidence on physical arm64 hardware. |
| Public release approvals, signing, validation, perf baseline | blocked | Agent 0 / Phase 6 | human approval required, production signing required, physical validation required, perf baseline required | Complete production signing, approved release records, store/privacy/legal/model-license reviews, validation record, operations record, public AAB, mapping, and final public relea... |

## Deferred

- **Real-app search replay coverage**: `partial`; physical real app search eval deferred.
- **Real-app search physical pass rate**: `deferred`; no device test in this phase.
- **Physical validation and arm64 API matrix**: `deferred`; no device test in this phase, arm64 api matrix environment required.
- **Perf baseline on physical arm64**: `deferred`; no device test in this phase, physical arm64 performance required.
- **Real model runtime validation**: `partial`; real model runtime validation deferred.

## Human Approval

- **Release approvals**: Fill `docs/release_record.json` with final owner, reviewer, target channel, changelog, release notes, artifact checksum, signing certificate fingerprint, fresh schema/owner-tagged...
- **Store approvals**: Fill `docs/store_policy_record.json` with an approved status, real support contact, public privacy-policy URL, reviewer, review date, and approved store-policy evidence. Current m...
- **Release operations owner**: Fill `docs/release_operations_record.json` with crash/ANR monitoring owner, signal source, first-24-hour watcher, staged rollout thresholds, crash/ANR smoke result, and rollback p...
- **Validation owner**: Fill `docs/release_validation_record.json` with approved emulator regression, physical-device instrumentation, API matrix, manual acceptance, flow matrix, sanitized screenshots, a...
- **Privacy approvals**: Review `docs/privacy_notice.md` and `docs/capability_matrix.json` before publishing the external policy and record role approvals in `docs/privacy_review.json`.
- **Model/license approvals**: For all four recommended model downloads, verify upstream license name, concrete license/notice URL or file path, redistribution rights, attribution/notice requirements, reviewer,...

## Physical Hardware

- **Physical validation**: Investigate the current full physical-device instrumentation crash before binding physical-device release evidence. On 2026-06-17, `fb6272c` (`Xiaomi 23127PN0CC`, API 36, `arm64-v...
- **Perf baseline**: Run final release-candidate validation and performance SLO collection on target physical arm64 hardware. Emulator validation does not cover LiteRT-LM GPU/performance behavior.
- **Real-app search**: Run fresh real-app-search eval on arm64 physical hardware and reach the target pass rate without expanding high-risk automation.

## Next Commands

- `VERIFY_RELEASE_RECORD=1 scripts/verify_release_gate.sh`
- `VERIFY_STORE_POLICY=1 scripts/verify_release_gate.sh`
- `VERIFY_PRIVACY_REVIEW=1 scripts/verify_release_gate.sh`
- `VERIFY_MODEL_LICENSES=1 scripts/verify_release_gate.sh`
- `scripts/check_emulator_api_matrix.sh`
- `scripts/prepare_emulator_api_matrix.sh`
- `ANDROID_SERIAL=<physical-device-serial> scripts/install_and_test_device.sh`
- `ANDROID_SERIAL=<physical-device-serial> scripts/run_real_app_search_eval.sh`
- `ANDROID_SERIAL=<physical-device-serial> scripts/collect_perf_baseline.sh`
- `PUBLIC_RELEASE=1 EXPECTED_SIGNING_CERT_SHA256=<production upload cert> PERF_BASELINE_FILE=<rc perf baseline> AI_BEHAVIOR_ACTUAL_TRACE_FILE=<actual-trace.jsonl> scripts/verify_release_gate.sh`

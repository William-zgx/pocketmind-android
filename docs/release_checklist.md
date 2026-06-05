# Production Release Checklist

Use this checklist for each release candidate. It is intentionally operational:
every checked item should have a named owner, date, and artifact link in the
release ticket or PR.

## Scope

- [ ] Release version name and version code are final.
- [ ] Release branch, commit SHA, and changelog are recorded.
- [ ] Release owner, reviewer, date, and target channel are recorded.
- [ ] APK/AAB artifact path, SHA-256, signing certificate fingerprint, and
  verification evidence links are recorded.
- [ ] Open blockers are either resolved or explicitly accepted by the release
  owner with a dated risk note.
- [ ] Target audience is clear: internal testing, closed testing, or broader
  distribution.
- [ ] Known unsupported capabilities are called out, especially screenshot
  capture, semantic screen understanding, full PDF parsing, legacy Office
  parsing, local image semantic understanding without a configured remote
  vision model, and arbitrary media OCR.
- [ ] Agent/tool behavior changes are summarized, including remote
  OpenAI-style `tool_calls`, public evidence batch execution, all-or-nothing
  mixed-batch rejection, and the privacy boundary for LocalOnly tool results.

## Versioning And Release Track

- [ ] Current Gradle values are recorded:
  `applicationId=com.bytedance.zgx.pocketmind`, `minSdk=28`, `targetSdk=36`,
  current `versionCode=1`, and current `versionName=0.1.0`.
- [ ] `versionCode` is strictly higher than every artifact ever uploaded to the
  same Play application; never reuse a version code, even for a rejected build.
- [ ] `versionName` follows the user-visible release train, for example
  `MAJOR.MINOR.PATCH` for public releases and `MAJOR.MINOR.PATCH-rc.N` only
  for internal/closed testing when the channel allows it.
- [ ] Release notes map version name, version code, Git SHA, artifact checksum,
  and target track: internal, closed, open, staged production, or full
  production.
- [ ] Upgrade paths from the previous production version and the latest internal
  test version are installed and smoke-tested. Downgrade is either tested or
  explicitly unsupported because Room migrations are forward-only.

## Signing, AAB, And Play App Signing

- [ ] Local debug builds are clearly separated from release artifacts:
  `./gradlew :app:assembleDebug` and the Android debug keystore are only for
  developer/device checks and are never uploaded, distributed, or used as
  production evidence.
- [ ] Release signing material is provided outside source control. The release
  record names the signing owner, keystore custody location, upload key alias,
  certificate SHA-256 fingerprint, and recovery contact.
- [ ] `scripts/sign_release_artifacts.sh` is run from the private signing
  environment with `RELEASE_KEYSTORE`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEYSTORE_PASSWORD`, and `RELEASE_KEY_PASSWORD`; attach
  `build/verification/signing/signing.properties` and the certificate reports.
  `ALLOW_DEBUG_KEYSTORE` must be unset for production signing.
- [ ] For Google Play, Play App Signing is enabled or its status is explicitly
  recorded. The app signing certificate fingerprint and upload certificate
  fingerprint are both captured because they are different trust anchors.
- [ ] The Play candidate is an Android App Bundle built with release settings:
  `./gradlew :app:bundleRelease`. Record
  `app/build/outputs/bundle/release/app-release.aab`, SHA-256, file size, and
  signing certificate fingerprint after external signing.
- [ ] If an APK is used for internal ad hoc validation, it is separately signed
  outside source control, labeled as non-Play evidence, and not confused with
  the Play AAB.
- [ ] APK/AAB inspection confirms no `.litertlm` model binaries, API keys,
  bearer tokens, private hostnames, or release keystore files are bundled.
- [ ] `scripts/scan_android_artifacts.sh` is run against the final APK/AAB and
  `android-artifact-scan.properties` is attached to the release record.
- [ ] Release artifact size is within the documented budget, and model files are
  described as optional/recommended downloads rather than packaged assets.

## Store Metadata And Policy

- [ ] App name, short description, full description, category, and contact
  email are reviewed.
- [ ] Privacy policy or privacy notice URL points to the approved external
  version of `docs/privacy_notice.md`.
- [ ] Google Play Data safety answers match the implemented behavior for local
  Room/DataStore storage, encrypted remote API keys, user-configured remote
  model calls, recommended/custom model downloads, Android permissions,
  external intents, and the absence of first-party analytics upload in this
  codebase.
- [ ] The Data safety form records whether data is collected, shared, encrypted
  in transit, user-deletable, optional, and purpose-limited. Treat user-chosen
  remote model endpoints and model hosts as external recipients where their own
  policies apply.
- [ ] Model downloads are described as large optional/recommended assets and
  not as APK-bundled files.
- [ ] Required Android permissions and special-access flows are explained in
  user-facing language.
- [ ] Sensitive permission disclosures are complete for `RECORD_AUDIO`,
  `READ_CALENDAR`, `READ_CONTACTS`, legacy `READ_EXTERNAL_STORAGE`,
  `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`,
  `READ_MEDIA_VISUAL_USER_SELECTED`, `POST_NOTIFICATIONS`,
  `PACKAGE_USAGE_STATS`, the Accessibility service, and one-shot
  MediaProjection consent for current-screen OCR.
- [ ] Play declarations or review notes explain why Usage Access,
  Accessibility, notifications, calendar/contact reads, media reads, voice
  input, document/share input, external navigation, and reminders are requested
  only for user-confirmed flows.

## Screenshots

- [ ] Chat home screen screenshot uses non-sensitive sample text.
- [ ] Model manager screenshot shows local/remote model controls without API
  keys or private endpoints.
- [ ] Confirmation sheet screenshot shows an example low-risk tool request.
- [ ] Background tasks or audit screenshot uses synthetic task names and
  redacted metadata.
- [ ] Screenshots do not include real contacts, notifications, clipboard text,
  current-screen text, API keys, emails, phone numbers, or internal hostnames.

## Privacy And License

- [ ] `docs/privacy_notice.md` is reviewed by release, security, and legal
  owners before publication.
- [ ] All four recommended model downloads in `docs/model_manifest.md` have
  manually verified license name, license source URL or file path,
  redistribution decision, attribution or notice requirements, reviewer, and
  review date.
- [ ] `docs/model_license_review.json` is updated from pending to approved
  records before broad distribution, and `VERIFY_MODEL_LICENSES=1
  scripts/verify_release_gate.sh` passes for the release candidate.
- [ ] README License wording distinguishes app code from third-party model
  artifacts.
- [ ] No API keys, bearer tokens, private model endpoints, raw prompts, or
  private device-context payloads are present in docs, screenshots, logs, or
  release notes.
- [ ] `scripts/privacy_scan.sh` passes and its `privacy-scan.properties`
  artifact is attached.

## Build Verification

- [ ] `scripts/verify_local.sh` passes on a clean checkout.
- [ ] `VERIFY_MODEL_URLS=1 scripts/verify_local.sh` is run when model URL
  availability/provenance needs fresh evidence. License readiness is still
  reviewed manually.
- [ ] `PERF_BASELINE_FILE=<rc perf-baseline.properties>
  scripts/verify_release_gate.sh` passes before release sign-off. Set
  `VERIFY_MODEL_LICENSES=1 REQUIRE_AAB=1 REQUIRE_SIGNED_ARTIFACT=1` when
  checking the public-distribution gate.
- [ ] Release assembly and bundle tasks pass with release minification/resource
  shrinking enabled.
- [ ] ProGuard/R8 mapping files for the release candidate are archived with the
  artifact so crash stacks can be decoded.

## Test Matrix

- [ ] `scripts/regression_emulator.sh` passes on a prepared arm64 AVD, or the
  release record explains why emulator validation was not applicable.
- [ ] `scripts/install_and_test_device.sh` passes on at least one physical
  arm64 device before a broad release candidate.
- [ ] Validation record includes device serial or AVD name, API level, ABI,
  `CLEAN_DEVICE` value, executed command, instrumentation result, and
  `instrumentation_test_count` from the verification report. Emulator release
  records should link `regression-emulator.properties` plus the nested
  emulator/device reports.
- [ ] Manual acceptance in `docs/phone_acceptance.md` is sampled for model
  setup, remote-mode privacy, tool confirmation, permissions, background
  reminders, sharing, and multimodal entry points.
- [ ] Manual acceptance records voice input, the Android system document picker,
  and MediaProjection consent separately from scripted regression; these
  system-mediated flows are not marked passed solely from scripts, mocked
  intents, or direct reader/ViewModel calls.
- [ ] Remote model manual acceptance samples both a single public evidence
  tool request and a multi-evidence question such as two-location comparison;
  mixed private/action tool batches must fail closed before execution.
- [ ] Matrix covers at least: API 28 minimum behavior, API 32 legacy storage
  permission behavior, API 33 media/notification permissions, API 34 selected
  visual media access, API 36 target behavior, and one physical arm64 device
  with realistic LiteRT-LM CPU/GPU fallback.
- [ ] Matrix covers first install, upgrade install, local model download and
  verification, custom model import or custom URL rejection path, remote model
  HTTPS configuration, encrypted API key clear, session persistence, memory
  controls, reminders after reboot, share/picker input, voice input,
  Accessibility text, recent media OCR, and MediaProjection cancellation.
- [ ] Performance sanity is recorded for first launch, model load, first token,
  streaming stop/cancel, background reminder delivery, and memory pressure on
  the largest recommended model expected for the channel.

## Crash, ANR, And Monitoring

- [ ] Monitoring owner and signal source are named. For Play-distributed builds,
  Android Vitals in Play Console is the minimum source; if a crash SDK is added
  later, its privacy disclosure and opt-in/retention behavior must be reviewed
  before release.
- [ ] Release candidate has a crash/ANR smoke window on internal or closed
  testing with no unresolved launch crash, install crash, crash loop, fatal
  native LiteRT-LM failure, or reproducible ANR.
- [ ] Manual validation captures `adb logcat`, tombstone/native crash evidence,
  and ANR traces for any failure; release notes link the issue or state that no
  crash/ANR was observed in the RC window.
- [ ] Crash-free and ANR thresholds for staged rollout are written in the
  release record, with a named person watching the first 24 hours after each
  rollout step.

## Rollback

- [ ] Previous known-good APK/AAB, version code, commit SHA, and release notes
  are available.
- [ ] Rollback owner and decision channel are named.
- [ ] Rollback criteria are defined, including install failure, crash loop,
  model download verification failure, privacy boundary failure, or critical
  tool execution regression.
- [ ] For staged Play rollout, the immediate first action is documented: halt
  rollout, keep collecting Android Vitals/user reports, and decide whether to
  resume, replace, or ship a fixed build.
- [ ] If production has already advanced past the prior artifact, the rollback
  plan uses a new artifact with a higher `versionCode`; Play cannot roll users
  back to a lower version code as an ordinary update.
- [ ] Model download manifest changes can be reverted without requiring an APK
  code change when the release process supports remote metadata updates; if not,
  the required APK rollback path is documented.
- [ ] User data compatibility is reviewed: Room migrations are forward-only, so
  downgrades must be tested or explicitly unsupported.

## Final Gate

- [ ] Release readiness remaining items are either complete or accepted by the
  release owner with a dated risk note.
- [ ] The release artifact, checksums, test logs, privacy/license review, and
  screenshots are attached to the release record.
- [ ] The release owner signs off.

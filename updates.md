# Updates

## 2026-10-30 — 1.2.5 floating restart color

- Changes Stop and Restart to the same red (RGB 255, 116, 105) as Stop & save and Stop and Delete. Recording and restart behavior are unchanged.
- Version 1.2.5/build 13. Verification and artifact identity are recorded in TESTING.md and local artifacts.

## 2026-10-30 — 1.2.4 Stop and Restart

- Adds Stop and Restart to the floating menu during recording or pause. It discards the active output and automatically requests fresh Android capture consent with current recording settings.
- Uses an explicit successful-deletion result from RecordingService; deletion failure reports the error, preserves the recovery marker and keeps the floating menu open without starting a new capture.
- Defers the new consent launch until the old stop/delete callback has returned, rejects duplicate restart taps and does not reuse projection tokens. Cancelling fresh consent leaves the recorder idle.
- Adds emulator coverage for restart from paused/recording states, permanent deletion, new output and retained settings, cancelled consent, updater exclusion and permission-revocation deletion failure/recovery.
- Local emulator tests now run through `.testing/run-bounded-release-tests.ps1`, which aborts if a diagnostic log exceeds 8 MiB, limits the run to 15 minutes and stops task-owned emulator processes on exit. Failed attempts and full diagnostic logs are retained.
- Version 1.2.4/build 12. Final runtime and artifact identity are recorded in TESTING.md and local artifacts.

## 2026-10-27 — 1.2.3 automatic floating controls

- Shows floating controls on main-screen resume when overlay permission is enabled and the bubble is absent. No recording starts automatically.
- Keeps Hide available for the current visit; opening or returning to the main screen restores the bubble.
- Explains the permission and Show floating controls action on the main screen. Missing or revoked permission does not trigger an automatic settings launch.
- Adds instrumentation coverage for automatic return, Hide persistence until return, and denied/revoked permission; existing floating-recording tests use the automatic behavior.
- Version 1.2.3/build 11. Final verification and artifact identity are recorded in TESTING.md and local artifacts.

## 2026-10-27 — 1.2.2 recording-history reconciliation

- Checks saved recording URIs on a background worker when MainActivity resumes, removing only confirmed missing entries and evicting their memory-only thumbnails.
- Custom-folder checks confirm access to the destination and enumerate its children; null cursors, storage unavailability and permission failures preserve history.
- Makes saved-video deletion tolerate an already-missing file, including disappearance while the confirmation is open. Failed deletion of an inaccessible file retains its entry.
- Serializes history changes across reconciliation and recording completion to avoid losing newly saved entries.
- Adds Android 16 instrumentation for external deletion, resume cleanup, already-missing Delete, intact-file preservation and revoked-access retention for both storage paths where applicable.
- Version 1.2.2/build 10; final verification and artifact identity are recorded in TESTING.md and local artifacts. Earlier failed build/emulator/test logs are retained.

## 2026-10-27 — 1.1.1 completed verification and handoff

- Resumed after the usage-limit interruption; confirmed the final signed APK and all eight passing Android 16 test groups were intact.
- Rechecked no INTERNET or broad-storage permission, matching update signing identity, and absence of bundled ad/test/native libraries. Saved `artifacts/verification-1.1.1.json`.
- Finished and visually inspected the floating-controls preview, updated README/DESIGN/TESTING for the new release, and preserved the historical test report and all diagnostic logs.
- Final APK: `artifacts/ScreenOhRecord-1.1.1.apk`, 69,888 bytes; SHA-256 `5a53b572e930d0b1fd628027f75af5d41206f3798d08cf2b11e5dbfe96254e2f`.
- No physical phone was modified. Install this APK over 1.0.2, then enable optional Display over other apps from Show floating controls.

## 2026-10-26 — 1.1.1 Android 16 overlay context

- Runtime testing found that Android 16 rejects creating an overlay window context directly from a background Service context. The retained stack trace identifies that boundary precisely.
- Bound the overlay to the default display before creating its window context. Optional overlay initialization failures now close only the bubble service and preserve recorder/notification operation.
- The test harness thread dump also confirmed startActivitySync can wait indefinitely when Android reuses an existing task; tests now use a launcher-equivalent action with a focus deadline.

## 2026-10-26 — 1.1.0 floating recording controls

- User approved optional draggable floating Start, Pause/Resume and Stop controls.
- Added a private, user-enabled overlay service with a visible foreground notification, Hide action and permission-revocation cleanup. Added SYSTEM_ALERT_WINDOW and FOREGROUND_SERVICE_SPECIAL_USE; INTERNET remains absent.
- Added a private capture-consent host shared by app and bubble starts. Android consent is still required for every recording; microphone starts wait for the foreground recording session before the host closes.
- Added local capture-option preferences so the bubble uses the quality/audio selection made in the app. Microphone is off on a fresh install and an explicit selection is remembered.
- Bubble updates follow state-change listeners; it does not poll while idle or start automatically at boot/process restart.
- Expanded Android 16 instrumentation for overlay permission, real dragging, background start/cancellation, floating controls and revocation. Final release results are pending.
- The first attempted debug installation was rejected because the emulator contained the prior release signing identity; those results are retained as an old-version run, not counted as 1.1.0 verification. Subsequent tests use the same local release signing identity and check installation exit codes.

## 2026-10-26 — 1.0.2 Android 16 target

- User clarified Android 16 and newer as the required compatibility target. Updated compile/target SDK to 36, AGP to 8.10.1 and Gradle to 8.11.1.
- Minimum SDK is now 29 to use scoped MediaStore consistently; removed the legacy Android 8–9 public-folder path and WRITE_EXTERNAL_STORAGE permission rather than adding broad read access.
- The old API 28 prototype test exposed that public-folder access was denied despite write permission. Its diagnostic logs are preserved; it is not a supported-runtime success claim for this release.
- Downloading checksum-verified official Android 16 SDK/emulator components for current-platform recording tests.
- Built the signed Android 16/API 36 release and passed assembly/lint with zero lint errors. APK contains five permissions, no INTERNET/storage permission, no native libraries and no third-party runtime dependencies.
- Passed actual Android 16 recording tests on both debug and final signed release: OS socket denial, cancelled consent, silent decodable MP4, pause/resume, notification pause/resume/stop, microphone AAC and screen-off stop/finalization.
- Preserved all intermediate failure logs, diagnosed Android 16's two-stage consent/multi-activity test behavior, and corrected the harness to target its focused window.
- Added README.md installation/build/privacy guidance and TESTING.md with exact artifact identity and runtime coverage. No physical phone was modified.

## 2026-10-26 — 1.0.1 verification refinements

- Prevented redundant status-text updates from continuously emitting UI accessibility events.
- Added generated version display, a monochrome launcher icon, and clarified intentional synchronous recovery-marker writes.
- Expanding instrumentation to cover actual consent, capture, MP4 output, pause/resume and microphone behavior.

## 2026-10-26 — 1.0.0 initial offline recorder

- Created independent ScreenOhRecord source after user approval of the offline-recorder approach.
- Implemented MediaProjection/MediaRecorder local MP4 capture, pause/resume/stop, notification controls, 720p/1080p caps and opt-in microphone audio.
- Added local recording history, system-player access, explicit deletion, interrupted-file cleanup and screen-off stop.
- Manifest omits INTERNET, network-state, camera, ad identifiers and broad media-reading permissions. No third-party application dependencies or livestreaming features.
- Disabled app-data backup/device transfer and kept projection consent tokens ephemeral.
- Added framework-only privacy/UI instrumentation checks and documented scope/architecture in DESIGN.md.
- Build, APK inspection and runtime verification results will be recorded after execution.

## 2026-10-27 - 1.1.2 settings (verification in progress)
- Approved recording settings: local save folder, 720p/1080p, 15/30 fps, H.264/available HEVC, 1/2/4/6 Mbps and estimated MB/min.
- New defaults: 720p, 15 fps, 1 Mbps; microphone enabled once on upgrade and remembered afterward. Entire-screen consent requested with the supported Android API.
- Local folder selection is restricted to Android external-storage documents; no cloud providers or broad storage/network permissions. Existing recordings remain in place.
- Release version 1.1.2/code 6; signed build and Android 16 runtime verification pending.

## 2026-10-27 - 1.1.2 verification completed
- Built signed release (code 6), passed lint with zero errors/ten warnings, and passed all eleven unique signed-release Android 16 test groups. Kept every intermediate build/test log and prior release report.
- Tested Android local-folder picker, persistent destination, actual 1080p MP4/audio, deletion, unfinished cleanup, finalized-file recovery, denied access without fallback, and restored 720p small-file defaults.
- Corrected HEVC detection to check selected size/fps/bitrate: emulator encoder is capped at 512x512. Full-resolution HEVC and removable storage require phone testing. Frame-rate controls request targets; achieved emulator rates are documented.
- Confirmed same signing identity, seven permissions, no INTERNET/broad-storage permission, no native/ad/test libraries. Reviewed Settings screenshot and saved package evidence/verification JSON.
- Final APK artifacts/ScreenOhRecord-1.1.2.apk: 84,276 bytes; SHA-256 fb732601028e66c540ac2016328bae9680dd0cfb97e6794c130a4a8a8530a6b9. README/DESIGN/TESTING updated. No physical phone modified.

## 2026-10-27 - 1.1.3 lower bitrate choices (verification pending)
- Added user-requested 500, 350 and 250 kbps alongside existing 1/2/4/6 Mbps choices for either supported codec. Current default and saved preferences stay unchanged.
- Corrected summary units for sub-Mbps rates and retained microphone-inclusive estimates (4.5/3.3/2.6 MB per minute).
- Updated release version to 1.1.3/code 7 and added actual MP4/audio recording checks for all three low bitrates.

## 2026-10-27 - 1.1.3 verification completed
- Passed signed release build/lint (zero errors, ten warnings) and fourteen unique Android 16 runtime test groups, including actual 500/350/250 kbps MP4 recordings with microphone and correct estimates.
- Corrected only the drag test to move toward the centre when the bubble has a saved position near an edge. Retained the initial failed log/layout evidence and all build logs. Production floating controls unchanged.
- Reconfirmed unchanged signing identity, no INTERNET/broad-storage permission and no bundled test/network/ad/native libraries. Updated README/DESIGN/TESTING and verification-1.1.3.json.
- APK artifacts/ScreenOhRecord-1.1.3.apk: 84500 bytes; SHA-256 0269793e09e60bf5f9f3ecf6d39c9352f13714e4238fe208d19f99c1588133c8. Defaults unchanged. No physical phone modified; HEVC still requires supported phone testing.

## 2026-10-27 - 1.2.0 approved implementation (verification pending)
- Added AV1 codec selection/capability checks and Delete and Close for the active output, bypassing trash and history and reporting deletion failures.
- Added GitHub-only update metadata/download client with size/hash/package/version/signing checks and user-confirmed Android PackageInstaller integration.
- Added optional weekly metadata-only Wi-Fi checks, disabled initially. Internet/network-state/install/boot permissions are now required; old no-INTERNET claims are superseded. No recording uploads or advertising added.
- Added project AGENTS.md, approved design record and a source-allowlisted publisher preserving the existing remote history and keeping signing keys/test assets private.

## 2026-10-27 - 1.2.0 core verification completed
- Final signed APK passed build/lint (zero errors, 29 warnings) and eighteen unique Android 16 regression groups, including real AV1 MP4/audio, permanent discard in MediaStore/custom folder, weekly opt-in and alternate signer rejection.
- Tightened weekly requests to bind specifically to unmetered Wi-Fi and kept capture excluded through the installation stage. Checked the publisher syntax/source allowlist and preserved all diagnostics.
- Final APK: 115,108 bytes; SHA-256 d8308b4cb35446d29e852ffe0eb59d00cc6bd3f93f40253813c3871343d865d7. Live public-release installer verification follows publication.

## 2026-10-27 - 1.2.0 published and live updater verified
- Published source/tag v1.2.0 and signed APK/checksum/update.json at https://github.com/mikeymoo618/ScreenOhRecord/releases/tag/v1.2.0. Preserved the existing remote history; excluded signing keys, credentials, test runtimes, analysis and recordings.
- Actual older-build updater downloaded the public APK and Android confirmation installed code 8. Pulled installed APK matches the release SHA-256 exactly. Verified private settings survived, foreground check reports latest, and a real Wi-Fi job fetches metadata without downloading an APK; weekly remains off.
- Registered the separate integration runner through a Gradle test-only property. Retained all failed harness/background-DNS attempts and improved test error diagnostics. Runtime APK unchanged.
- Source reproduction differs only in embedded Git revision metadata after initializing this project repository; all code/resource payload entries match. Kept rebuild separately and restored local delivery to exact published APK. Updated final verification records; physical phone untouched.

## 2026-10-27 - 1.2.1 approved UI improvements (verification pending)
- Moved installed package version/build number to a dedicated final footer line, refreshed on resume.
- Added background local thumbnails with a bounded memory-only cache, document-provider fallback and cancellation/invalidation on deletion or row rebuild. No preview files are written to storage.
- Renamed floating Delete and Close to Stop and Delete; active video is discarded and floating controls stay open for another recording.
- Incremented app to 1.2.1/code 9; build, thumbnail/deletion tests and release publication pending.

## 2026-10-27 - 1.2.1 verification completed
- Final signed release passed assembly/lint (zero errors, 32 warnings) and all twenty Android 16 test groups, including preview loading/deletion/cache eviction/cancelled decode for custom-folder and MediaStore recordings, final installed-version footer, and Stop and Delete leaving the expanded floating menu open.
- Used direct scaled-frame extraction with a 4 MiB memory-only cache; no provider thumbnail caches, new permissions, dependencies or uploads. Visually reviewed complete list rows and ScreenOhRecord 1.2.1 / build 9 as the final page line.
- APK: 122136 bytes; SHA-256 a1ca624b83a3b2a1c337554ba09e7b1ce962a6094f2ad2d44cb40d1627a31c39. Same signing identity retained. Updated docs/release notes/verification JSON and retained all logs; no physical-phone install or data changes.

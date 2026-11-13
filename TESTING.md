# ScreenOhRecord 1.3.0 verification

Validated on 2026-11-13 using task-owned Android 9/API 28 and Android 16/API 36 emulators. Physical Galaxy S8 and S22 testing remains pending.

- Package `com.screenohrecord.app`, version 1.3.0/build 16, minimum SDK 28, target SDK 36.
- Exact signed APK `artifacts/ScreenOhRecord-1.3.0.apk` contains 6,564,848 bytes.
- SHA-256 `7bcfb7142423aefe39bf6d3dca3366b9682af978034a4e6a7255b5e9d6acc3ce`.
- Preserved signing certificate SHA-256 `7d1a8bb17518df543a9e193289f4dc379f63748de2b6920986f4432e42c8dbc8`. APK v2 verification and signed emulator installation passed.
- Build and lint passed with zero errors and 38 warnings in `artifacts/build-1.3.0-shipping.log`. The harness build passed in `artifacts/build-test-1.3.0-shipping.log`.
- Android 9 transfer suite passed 16 unique groups in `artifacts/instrumentation-release-1.3.0-transfer9-tunnel.log` using the official Google APIs x86-64 revision 11 image, task AVD `ScreenOhRecord9`, port 5566.
- Android 16 transfer suite passed 16 unique groups in `artifacts/instrumentation-release-1.3.0-transfer16-shipping.log`, task AVD `ScreenOhRecord16`, port 5564.
- Android 9 recording regression passed 13 unique groups in `artifacts/instrumentation-release-1.3.0-regression9-shipping.log`.
- Android 16 focused recording regression passed 20 unique groups in `artifacts/instrumentation-release-1.3.0-regression16-finalcheck.log`. Selected resolution, frame rate, bitrate, codec, microphone and destination preferences were compared before and after recording.
- The final APK passed direct tests against the user-provided LAN FTP server on port 21, starting in the login's `/` directory. Four groups passed in `artifacts/instrumentation-release-1.3.0-lan16-shipping.log`, including a 1 MiB synthetic upload, five exact SHA-256 read-backs, rename and cleanup. All temporary remote test files were removed. Credentials were handed to the test process through a disposable file and remain absent from the release APK and source.
- Combined successful instrumentation evidence is `artifacts/instrumentation-release-1.3.0-combined.log`. Exact identity and per-run group lists are recorded in `artifacts/verification-1.3.0.json`. Each selected bounded runner reported success and shut down its task emulator.

## Transfer coverage

Both Android versions exercised the disabled default, Keystore-encrypted password round-trip, persisted scheduling and disable, concurrent-worker exclusion, settings controls, completion delay, clock rollback, current-folder selection and capture-consent exclusion. The suites checked directory discovery beyond recent history, unfinished-file exclusion and a queue exceeding the 50-row recent-list limit.

SMB and FTP each passed connection upload/read-back/rename/delete, rejected credentials with local-file preservation, interrupted-transfer recovery, conflicting destination preservation and reuse of an already verified remote copy. Additional checks cover deliberate same-length remote corruption, a read-only destination, a missing folder, actual document-provider source files, Wi-Fi disappearance and recovery, and a real Android JobScheduler transfer. Changing the save-folder preference between file creation and completion preserves the actual output's folder identity.

The existing recording suites cover recording/playback, microphone audio, permission flows, pause, Stop and Delete, Stop and Restart, thumbnails, missing-file handling, storage recovery and updater verification. Android 16 also exercised 1080p/30fps settings, low bitrates, AV1 and Open app navigation. Encoder fitting continues to apply only to Android 9.

## Network environment and validation limits

Direct Android 9 emulator FTP reads reproduced intermittent single-byte losses at packet boundaries against both loopback fixtures and the supplied LAN server, using both old and current emulator runtimes. Checksums caught these failures before local deletion. The successful Android 9 transfer suite uses ADB reverse tunnels for the synthetic SMB/FTP fixture connections, bypassing the faulty virtual TCP path. The Wi-Fi requirements, connection guards, Wi-Fi-off tests and real Android background jobs remain active. Direct LAN validation covers the Android 16 emulator. Physical Android 9 LAN testing remains pending. Android documents the emulator networking defect under issue 150758736. [Emulator release notes](https://developer.android.com/studio/releases/emulator)

Loopback fixtures use pyftpdlib and Impacket 0.13.1 with disposable synthetic accounts. The Windows-hosted SMB fixture was adapted for SMB2 error response encoding, optional final-frame padding and Windows open-handle rename semantics. Production SMB signature validation stays enabled. Fixtures, credentials, images, logs and test-server processes remain outside the published source and release assets.

SMB supports SMB2.02/2.1 on servers accepting SMB2. SMB1 and mandatory SMB3 encryption are outside this build. IPv4 addresses and DNS hostnames are supported. The destination folder must already exist and allow upload, read, rename and test-file deletion. Android battery management and job limits may defer or interrupt transfers. Interrupted attempts retain local recordings for retry. Remote filenames include an installation/source/content suffix to avoid overwriting existing files.

Some post-test diagnostic snapshots reached their monitored size threshold. All collected diagnostic logs, including failed attempts, are retained locally. Emulator results leave physical-phone, future-Android and Play Protect validation pending.

---

# ScreenOhRecord 1.2.7 verification

Validated on 2026-11-12 using task-owned Android 9/API 28 and Android 16/API 36 emulators. Both task emulators stopped after testing.

- Package `com.screenohrecord.app`, version 1.2.7/build 15, minimum SDK 28, target SDK 36.
- Tested APK `artifacts/ScreenOhRecord-1.2.7.apk` contains 131,736 bytes.
- SHA-256 `552358b704210fb59004308032457db5c87c3fc0a57593d0f9634cdfe05d82a8`.
- Original signing certificate SHA-256 `7d1a8bb17518df543a9e193289f4dc379f63748de2b6920986f4432e42c8dbc8`. APK v2 verification passed.
- Build and lint passed with zero errors and 30 warnings. Exact APK build log is `artifacts/build-1.2.7-encoder.log`.
- Android 9 passed 13 unique groups in `artifacts/instrumentation-release-1.2.7-android9-picker-verified.log`, including the completion marker. Coverage includes install, storage denial/grant, playback, microphone AAC, pause/resume, cleanup/recovery, floating Stop/Delete/Restart, thumbnails, selected-folder recording with global storage access blocked, and updater validation.
- Android 16 passed 20 unique groups in `artifacts/instrumentation-release-1.2.7-android16-final.log`. Coverage includes Open app navigation, floating controls, deletion/recovery, thumbnails, 720p, 1080p/30fps/2Mbps, low bitrates and AV1. Recording was checked against a saved copy of all selected recording preferences, including the destination and microphone setting.
- Both complete instrumentation logs contain zero failure/crash markers. Combined release evidence is `artifacts/instrumentation-release-1.2.7-combined.log` and `artifacts/verification-1.2.7.json`.

## Android 9 compatibility and settings preservation

Legacy storage permissions are capped at API 28. Android 9 requests access for the default Movies destination. Selected local folders use their existing URI grants. Encoder fitting is restricted to API 28 and leaves stored recording preferences intact. The Android 9 emulator required 612 by 1088 output within the selected 720p cap. Android 16 retains exact selected dimensions and passed preference-preservation assertions.

## Validation limits

Physical Galaxy S8 and Galaxy S22 testing remains pending. Android 9's final suite focuses on storage and recording after an earlier cold-task Open app automation failure. Android 16 covers that navigation case. The Android 16 post-test diagnostic snapshot reached its monitored size threshold after the tests passed. The runner stopped capture, retained diagnostic logs and shut down the emulator. These checks establish emulator coverage and do not establish physical-device or future-Android compatibility or Play Protect clearance.

---

# ScreenOhRecord 1.2.6 verification

Validated on 2026-11-12 on the task-owned ScreenOhRecord16 Android 16/API 36 emulator at port 5564. The bounded runner shut down the emulator after testing.

- Package com.screenohrecord.app, version 1.2.6/build 14, minimum SDK 29 and target SDK 36.
- Signed APK artifacts/ScreenOhRecord-1.2.6.apk contains 127,680 bytes.
- SHA-256 98fd419bbd6ee00fccff9bdf90fa74192577c49b65a5d9626b769645413225f9.
- Signing certificate SHA-256 7d1a8bb17518df543a9e193289f4dc379f63748de2b6920986f4432e42c8dbc8. APK v2 verification and signed emulator upgrade passed.
- Build and lint passed in artifacts/build-1.2.6.log with zero errors and 31 warnings. Harness build passed in artifacts/build-test-1.2.6.log.
- All 20 unique groups passed in artifacts/instrumentation-release-1.2.6.log. The focused runner exited 0, with completion and teardown recorded in artifacts/bounded-1.2.6.log. Repeated progress and summary lines are counted once.
- Permission and packaged-content inspections passed. Artifact identity and the complete group list are recorded in artifacts/verification-1.2.6.json.

## Open app behavior

The test checks the visible idle menu order as Start recording, Open app, Hide bubble. It opens the app from the home screen through the actual overlay button and confirms the existing MainActivity instance regains focus. It then finishes that app task while the overlay service stays active and uses Open app to create a fresh main screen. Repeating the action while that screen is foreground reuses it. Each action collapses the menu and preserves the visible bubble. Recording and capture-request state remain idle throughout these navigation checks.

The existing floating restart tests also check that Open app is hidden during recording and pause for MediaStore and custom-folder sessions. The focused suite exercised capture consent, pause/restart/save/delete, deletion failure and recovery, settings, low bitrates, AV1, version footer, previews and missing-recording cleanup.

Runtime coverage is the Android 16 emulator. The launch-exception fallback was reviewed in source. The diagnostic snapshot size guard stopped capture when its monitored threshold was exceeded, and collected logs were retained. The runner completed emulator teardown.

---

## Historical 1.2.5 verification

# ScreenOhRecord 1.2.5 verification

Validated on 2026-10-30 on task-owned ScreenOhRecord16 Android 16/API 36 emulator, port 5564. No physical phone was modified. The emulator was shut down after testing.

- Package com.screenohrecord.app; version 1.2.5/build 13; min SDK 29, target 36.
- APK: artifacts/ScreenOhRecord-1.2.5.apk, 126,848 bytes.
- SHA-256: 30ef2d5a0296639ec4e8fe1f5b3306dcb67cba73bd08bcac618785771bb0dadb.
- Preserved signer SHA-256: 7d1a8bb17518df543a9e193289f4dc379f63748de2b6920986f4432e42c8dbc8. Signature v2 verification and in-place signed emulator installation passed.
- Build/lint: artifacts/build-1.2.5.log; zero errors, 32 warnings. Harness: artifacts/build-test-1.2.5.log.
- Existing focused settingsOnly suite: artifacts/instrumentation-release-1.2.5.log; all 19 unique groups passed, no FAIL/crash markers. Runner exit 0 and cleanup: artifacts/bounded-1.2.5.log. This was not a repeat of the full 27-group suite for 1.2.4.
- Source inspection confirms Stop & save, Stop and Delete and Stop and Restart all use RGB 255, 116, 105 (#FF7469) through the same button/background constructor. The only recording-code change is that color argument; no new tests were added for the cosmetic change.
- Packaged permissions and signing identity are unchanged. Verification identity/group list: artifacts/verification-1.2.5.json.

The existing focused suite exercised the visible restart action, restart from paused/recording states, deletion/cancellation, failure/recovery, both storage paths, thumbnails, version footer, low bitrates and AV1. Bounded diagnostic snapshots and all logs were retained; a completed snapshot crossed the monitored 8 MiB threshold between checks and was reported without truncation. Emulator teardown completed. These checks do not establish physical S22 or future-Android compatibility or Play Protect clearance.

---

## Historical 1.2.4 verification

# ScreenOhRecord 1.2.4 verification

Validated on 2026-10-30 using only the task-owned ScreenOhRecord16 Android 16/API 36 emulator, port 5564. No physical phone was modified. The emulator was shut down and confirmed absent from adb after the final run.

- Package com.screenohrecord.app; version 1.2.4/build 12; min SDK 29, target 36.
- Signed APK: artifacts/ScreenOhRecord-1.2.4.apk, 126,848 bytes.
- SHA-256: b1c73ce9ac0c0370617d51ef7c75a2ecb5cdb6b75a92fbfadec96aa852a09359.
- Preserved certificate SHA-256: 7d1a8bb17518df543a9e193289f4dc379f63748de2b6920986f4432e42c8dbc8. APK v2 signature verification passed; signed emulator upgrade succeeded.
- Release build/lint: artifacts/build-1.2.4.log, zero errors and 32 warnings. Final harness build: artifacts/build-test-1.2.4-verified.log.
- Final full runtime: artifacts/instrumentation-release-1.2.4-clean.log, 27 unique passing groups and no FAIL/crash markers. Bounded runner exit 0 and cleanup: artifacts/bounded-1.2.4-clean.log. Duplicate progress/summary lines are not extra tests.
- Packaged permissions match 1.2.3 exactly; no runtime dependencies, native libraries, ads or test code were added to the release APK. Identity and full group list: artifacts/verification-1.2.4.json.

## New behavior verified

For both MediaStore and the custom local folder, the floating Stop and Restart action works from PAUSED and RECORDING. The old URI is no longer readable before fresh Android consent appears, discarded output never enters history, and the updater gate remains closed during consent. Accepting creates a different output URI and a playable MP4 using the selected resolution, frame-rate target, codec and microphone option. Saving publishes the fresh output; cancellation deletes the preceding output and leaves recording idle with no retry or leftover output marker.

Revoking the custom-folder grant during recording causes deletion to fail. Restart then reports failure, keeps the menu and recovery marker, and requests no new capture. Restoring the grant via the actual Android picker permits recovery to delete that fixture. Existing revoked-access history retention, floating controls, recording/audio, all low bitrates, AV1, saved-recording thumbnails and missing-file cleanup also passed in the final complete run.

## Diagnostic attempts and limits

All earlier attempt logs remain in artifacts. Test corrections accounted for temporary folder grants outliving a picker return, the existing 50-entry history limit, and rejected accessibility clicks during window relayout. A separate earlier full run hit MediaRecorder.prepare failure at 250 kbps; its cause was not proven, and no encoder settings were changed. The final full run passed all bitrate checks. A one-time emulator app-data reset also demonstrated delayed MediaProvider PACKAGE_DATA_CLEARED cleanup revoking a newly created test URI; the final run began after that cleanup completed and performed no further reset. These attempts are not counted as release passes.

The local bounded runner monitors logs against an 8 MiB threshold, has a 15-minute run limit, bounds diagnostic snapshot subprocesses by size/time and always tears down its emulator. Finite snapshots can finish between monitor checks; the final complete system snapshot is about 9.4 MB, retained in full. Emulator stdout stayed about 12 KB. The local guard now also checks snapshot size after process completion. No logs were truncated or deleted.

Emulator evidence does not establish physical Samsung S22, all hardware encoder/provider conditions, future Android compatibility or Google Play Protect clearance. Restart still requires Android confirmation for the new session; the previous video has already been permanently deleted when that prompt appears.

---

## Historical 1.2.3 verification

# ScreenOhRecord 1.2.3 verification

Validated on 2026-10-27 using only the task-owned ScreenOhRecord16 Android 16/API 36 emulator, port 5564. No physical phone was modified.

- Package com.screenohrecord.app; version 1.2.3/build 11; min SDK 29, target 36.
- Signed APK: artifacts/ScreenOhRecord-1.2.3.apk, 125,448 bytes.
- SHA-256: 84aa3be1ec9f3825c92dd8efd0f79dcb36358cff4f4d66916013d17ba7210ba8.
- Preserved certificate SHA-256: 7d1a8bb17518df543a9e193289f4dc379f63748de2b6920986f4432e42c8dbc8. Signature v2 verification passed; signed emulator upgrade succeeded.
- Build/lint: artifacts/build-1.2.3-final.log; zero errors, 32 warnings. Harness build: artifacts/build-test-1.2.3-final.log.
- Runtime: artifacts/instrumentation-release-1.2.3-final.log; 24 unique passing groups, no FAIL/crash markers, harness process exit 0. Duplicate progress/summary lines do not count as extra tests.
- Packaged permissions are identical to 1.2.2. No runtime dependencies or native libraries added. Artifact identity and full group list: artifacts/verification-1.2.3.json.

## New behavior verified

With overlay permission granted, returning to the main screen restores the bubble without pressing Show. Hide removes it and it stays hidden while the screen remains open, including across UI timer refreshes; leaving and returning restores it. Automatic appearance leaves recording idle. Launch with denied permission and return with revoked permission keep the bubble absent. Existing permission grant flow, drag behavior, capture-consent cancellation, floating recording/pause/save/discard, permission revocation, saved-video cleanup/thumbnails, low bitrates/AV1, and updater validation all passed.

## Limits

This is emulator evidence, not physical Samsung S22 or future-Android validation. No Google Play Protect clearance is claimed. Overlay permission must still be enabled by the user. Controls appear on main-screen resume; no recording or permission-settings screen starts automatically, and Hide lasts until that screen resumes again.

---

## Historical 1.2.2 verification

# ScreenOhRecord 1.2.2 verification

Validated on 2026-10-27 using only the task-owned ScreenOhRecord16 Android 16/API 36 emulator, port 5564. No physical phone was modified.

- Package com.screenohrecord.app; version 1.2.2/build 10; min SDK 29, target 36.
- Signed APK: artifacts/ScreenOhRecord-1.2.2.apk, 125,328 bytes.
- SHA-256: df4c958f8c4fdf10aec1aee1be3c5f49a9e5dcd14ed445248086dcfd49071714.
- Preserved certificate SHA-256: 7d1a8bb17518df543a9e193289f4dc379f63748de2b6920986f4432e42c8dbc8. Signature v2 verification passed; signed emulator upgrade succeeded.
- Release build/lint passed: artifacts/build-1.2.2-final.log. Zero errors, 32 warnings. Harness build: artifacts/build-test-1.2.2-final.log.
- Final runtime log: artifacts/instrumentation-release-1.2.2-final.log. All 23 unique groups passed with no FAIL/crash markers. Progress and summary duplicates are not extra tests. Evidence/identity: artifacts/verification-1.2.2.json.
- Packaged permissions match 1.2.1 exactly. No new runtime dependencies, native libraries, test code or ad SDKs introduced.

## New behavior verified

For both MediaStore and the local document-provider destination, an outside deletion while the Delete confirmation is open clears the history entry and row when confirmed. Deleting another fixture while the app is away causes resume reconciliation to remove it, while an intact fixture remains available. Revoking the custom-folder grant produces UNAVAILABLE, preserves history during pruning, and preserves it after failed deletion. Existing recording thumbnails, deletion/cache cancellation, floating controls, recording/audio/AV1/low-bitrate and updater validation groups also passed.

The first runtime run caught an assertion racing the asynchronous UI refresh; the harness now waits for the row to disappear. Its complete log and crash output are retained, along with the initial sandbox Gradle-cache denial and emulator attempts. No failed attempt is counted as release verification.

## Limits

These emulator tests do not establish physical Samsung S22 behavior, all removable-storage/provider failure modes, future Android compatibility or Google Play Protect clearance. The previously reported Play Protect classification remains unexplained. Unknown availability retains history; reconciliation runs on resume rather than monitoring storage continuously. Cleanup changes history only and does not remove intact video files.

---

## Historical 1.2.1 verification

# Verification - ScreenOhRecord 1.2.1

Executed 2026-10-27 on the task-owned ScreenOhRecord16 Android 16/API 36 x86_64 emulator. This change did not modify the physical Galaxy S22 or its recordings.

## Artifact

- Package com.screenohrecord.app; version 1.2.1/code 9; minimum API 29, compile/target API 36.
- Signed APK artifacts/ScreenOhRecord-1.2.1.apk; 122,136 bytes.
- SHA-256 a1ca624b83a3b2a1c337554ba09e7b1ce962a6094f2ad2d44cb40d1627a31c39.
- Certificate SHA-256 7d1a8bb17518df543a9e193289f4dc379f63748de2b6920986f4432e42c8dbc8, unchanged. Signature v2 verification passed; signed in-place emulator upgrade succeeded without uninstalling.
- Release build/lint passed in artifacts/build-1.2.1-final.log: zero errors, 32 non-blocking warnings retained in app/build/reports/lint-results-release.txt. Test build: artifacts/build-test-1.2.1-final.log.
- Final runtime log: artifacts/instrumentation-release-1.2.1-final.log; twenty unique passing groups. Repeated progress/summary lines are not additional runs. Machine-readable identity/checks: artifacts/verification-1.2.1.json.

## New behavior verified

1. An actual custom-folder video thumbnail loads next to its filename/duration/size. The installed version/build displayed in the footer matches the running package and is the final root-layout element.
2. Confirming Delete through the history UI removes that recording row and clears its bound image immediately. The history entry disappears. Rebinding the deleted URI does not retrieve an old image from the memory cache.
3. A separate decode is started and cancelled before completion; the cleared image cannot be repopulated by a late result. The same test runs for local document-provider and default MediaStore recordings, including an actual AV1 recording when available on the emulator.
4. The floating control label is exactly Stop and Delete. It permanently discards the active output with no history entry or reopenable file and no recycle-bin operation. The bubble AND expanded menu remain open, with Start visible, for both custom-folder and MediaStore recordings. The test explicitly hides it afterward only for isolation.
5. Background preview decoding uses scaled MediaMetadataRetriever frames and a bounded 4 MiB memory cache. No app-managed thumbnail files are written. Provider thumbnail APIs were deliberately avoided to prevent invoking their separate image caches. No extra media permissions or dependencies are introduced. Missing/unreadable video keeps a placeholder rather than crashing the UI.

The prior eighteen regression groups also passed: updater metadata/checksum/version/signing validation and weekly opt-in/cancellation; capture-update exclusion; default/migration UI; consent cancellation; silent and microphone MP4 decoding; pause/resume/stop and screen-off; floating permission/drag/background capture/hide/revocation; actual local folder picker and persistence; recovery/deletion/revoked access; restoring Movies; 500/350/250kbps recordings; and actual AV1 output/decoding. Their modified discard groups verify the new keep-open behavior rather than the former close-on-discard behavior.

## Package/privacy and visual inspection

Evidence: apk-permissions-1.2.1.txt, apk-badging-1.2.1.txt and apk-signature-1.2.1.txt under artifacts. The same eleven permissions remain: seven recording/notification/overlay permissions plus the four approved GitHub updater/network/install/persisted-job permissions. No broad storage/media, camera, ad identifier or accessibility-service permission was added. No native libraries, test runner, advertising SDK or third-party runtime dependency was found in the release. Recording and preview code remain local; updater requests are isolated to GitHub, with no recording uploads.

Visual inspection of artifacts/ScreenOhRecord-footer-1.2.1-Android16.png confirms complete rows with a small thumbnail on the left, filename/duration/size beside it, unchanged Play/Delete actions below and the centred ScreenOhRecord 1.2.1 / build 9 footer as the last line. The MediaStore/custom-folder history preview images are retained alongside it. Early integration screenshots show partly scrolled rows; the final footer screenshot shows the complete layout.

## History and limitations

Initial candidate tests passed; preview decoding was then tightened to use direct frame extraction exclusively, and the final signed APK above passed all twenty groups again. All candidate build/test logs, previous release reports and screenshots remain local; no diagnostic logs were removed. Historical 1.2.0 installer/network end-to-end evidence is in artifacts/TESTING-1.2.0.md. Updater network/install implementation is unchanged in this release; repeating its entire install integration was unnecessary.

Actual H.264 and AV1 capture/preview were exercised on the emulator. Device/codec performance, older supported Android versions and removable physical storage are not proven by emulator coverage. The physical S22's earlier package error was separately traced to insufficient internal storage; no package-parsing workaround or unrelated change was introduced. The app still needs Android installation confirmation and sufficient free storage.

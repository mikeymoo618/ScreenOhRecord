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

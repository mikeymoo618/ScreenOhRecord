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

# Verification - ScreenOhRecord 1.2.0

Core release verification completed 2026-10-27 on the task-owned ScreenOhRecord16 Android 16/API 36 x86_64 emulator. No physical phone was modified. Live public-release installer verification follows initial publication and will be recorded separately.

## Delivered artifact

- Package com.screenohrecord.app; version 1.2.0/code 8; minimum API 29; target/compile API 36.
- artifacts/ScreenOhRecord-1.2.0.apk: 115,108 bytes.
- SHA-256 d8308b4cb35446d29e852ffe0eb59d00cc6bd3f93f40253813c3871343d865d7.
- Certificate SHA-256 7d1a8bb17518df543a9e193289f4dc379f63748de2b6920986f4432e42c8dbc8, unchanged. APK v2 signature verification and in-place emulator installation passed.
- Build/lint: artifacts/build-1.2.0-final.log; zero errors, 29 non-blocking warnings (translations, existing UI/platform notices and AV1's guarded API-33 constant). Runtime code checks API 33 before using AV1. Full lint details retained in app/build/reports.
- Final tests: artifacts/instrumentation-release-1.2.0-final.log, eighteen unique passing groups. The separate test APK is signed with the local release identity and checks installed version. Duplicate progress/final-summary lines are not extra runs.

## Runtime coverage

The existing fourteen groups cover capture consent cancellation, default/migration UI, dimensions/timer, silent/AAC MP4 decoding, notification pause/resume/stop, screen-off, floating permission/drag/background start/cancel/hide/revocation, actual local-folder picker and persistence, saved/unfinished recovery/deletion/revoked access, restoring Movies and low-bitrate recordings at 500/350/250kbps. The privacy assertions now explicitly expect the approved updater permissions rather than the obsolete OS socket-denial guarantee.

Four added groups cover:

1. Weekly opt-in scheduling/cancellation, protected system JobService, capture/update exclusion, fixed-repository URL validation, cleartext/lookalike/credential URL rejection, same-version rejection, corrupt checksum rejection and installed signing identity. A real same-package/version APK signed with the debug key is rejected as an alternate signer.
2. Delete and Close on an active custom-folder video: no history entry, descriptor cannot reopen, bubble closes and no unfinished marker remains. Existing saved video stays decodable.
3. Delete and Close on an active MediaStore video, with the same permanent-removal and history checks. Update operations cannot acquire the network gate during recording.
4. AV1 was selected through Settings and actually encoded/decoded as video/av01 in a 720x1280 MP4 with microphone audio. The emulator achieved variable fps below the requested 15fps; physical device performance remains to be checked.

## Permissions/privacy/package

Evidence is stored locally as apk-permissions-1.2.0.txt, apk-badging-1.2.0.txt, apk-manifest-1.2.0.txt, apk-signature-1.2.0.txt and verification-1.2.0.json. Eleven permissions: the seven prior foreground/microphone/notification/overlay permissions plus INTERNET, ACCESS_NETWORK_STATE, REQUEST_INSTALL_PACKAGES and RECEIVE_BOOT_COMPLETED. No camera/advertising identifier/accessibility-service/broad storage/media-read permission. System job binding requires BIND_JOB_SERVICE. No third-party runtime libraries, test runner or advertising SDK is bundled in the release.

The old no-INTERNET guarantee is intentionally superseded by the user-approved updater. Its HTTPS GET client is isolated from recording/storage code and uses only fixed public GitHub release endpoints. It checks size/hash/package/version/signing identity and hands installation to Android's confirming installer. Weekly requests are metadata-only and bound to unmetered Wi-Fi; they default off and cannot run during capture. Recordings are never read by this updater. Other gallery/backup apps may sync shared video under their own permissions.

## Limits and retained diagnostics

All intermediate logs and earlier release reports remain local. This includes a quoted-Gradle-property test-fixture invocation correction and the publisher's pre-execution syntax-check correction. The final APK differs from the first candidate by tightened Wi-Fi binding, installation-operation exclusion and explicit AV1 platform guarding; the final eighteen-group run tests that exact signed artifact.

Actual H.264 and AV1 playback were exercised; full-resolution HEVC is unavailable on this emulator (512x512 encoder limit). Removable/SD media and physical-phone codec/microphone performance are not established. Frame rate is a target and size is a bitrate estimate, not a guarantee. Android can delay weekly jobs and user installation approval is always required.

# Approved 1.2.0 design and decisions

Approved by the user on 2026-10-27: AV1 compression option; Delete and Close for the active recording; GitHub release publishing and opt-in weekly update checks. Personal phone/testing use, small release assets, framework-only Java retained. Existing signing identity and recorded files must be preserved on upgrade.

AV1 is selected explicitly and checked against device encoder capabilities for the selected dimensions/fps/bitrate. An unavailable or failing encoder gives an error; no misleading successful recording or silent codec substitution.

Delete and Close stops and discards only the active output, bypassing MediaStore trash and omitting history publication, then hides the floating controls. Report deletion failures and retain a recovery marker rather than claiming deletion succeeded. Existing saved videos are untouched. No extra confirmation is added to this explicitly named destructive control.

Update approach: public GitHub Releases with versioned signed APK, checksum, release notes and update.json. This is preferred to committing binary APKs to Git history or requiring GitHub credentials on the phone. Source and version tag correspond to the delivered build. Signing keys/credentials/test files stay private and local.

Manual checks fetch metadata only; an approved download is checked for exact URL/size/SHA-256/package/version and matching installed signing identity before PackageInstaller receives it. Android installation consent remains required. Weekly JobScheduler checks default off, require unmetered Wi-Fi, persist across reboot and may be delayed by Android. They never download an APK. Recording and updater network activity are mutually exclusive. No advertisements/analytics or recording uploads are introduced. INTERNET, ACCESS_NETWORK_STATE, REQUEST_INSTALL_PACKAGES and the scheduler's RECEIVE_BOOT_COMPLETED permission are required; the old no-INTERNET guarantee is explicitly superseded by the approved updater.

Test recording/delete in MediaStore and a selected local folder; encoder selection and failure; metadata parsing and malicious URLs/checksums/package/signature/version rejection; optional scheduling and recording/network exclusion; actual public GitHub check/download and Android installer update on the isolated emulator. Keep all failure logs. Physical-device AV1/HEVC behavior remains device dependent.


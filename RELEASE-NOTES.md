# ScreenOhRecord 1.2.0

- Adds AV1 compression selection with device capability checks. Existing H.264/HEVC, resolution, fps and bitrate settings remain.
- Adds Delete and Close to the floating recording menu: stop and permanently discard the current recording without history/trash, then hide the controls. Previous saved videos remain untouched.
- Adds App updates from this public GitHub repository: check release notes/version, approve an APK download and use Android's installation confirmation. Package/version/checksum and installed signing identity are checked before installation.
- Adds optional weekly metadata-only checks over unmetered Wi-Fi; disabled initially. No automatic APK download or silent install.
- Keeps recording local, with no ads, analytics or recording uploads. The updater introduces internet/network-state, installation and persisted-job permissions; the old no-INTERNET guarantee no longer applies.
- Uses the existing signing identity for in-place updates. Install this version manually once; later published updates can be obtained inside the app.

Android 16 emulator verification includes actual AV1 recording and permanent deletion in default/custom local storage. AV1/HEVC performance remains device dependent. See TESTING.md for the final validation record.

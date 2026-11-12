# ScreenOhRecord 1.2.7

- Adds support for Android 9 with a minimum SDK of 28.
- Android 9 requests storage access when recording to Movies/ScreenOhRecord. Local folders can be selected in Settings through Android's folder picker.
- Adds Android 9-compatible file finalization, interrupted-recording cleanup, missing-file detection and updater certificate verification.
- Fits Android 9 video dimensions within the chosen encoder's limits and the selected resolution cap. This fitting is restricted to Android 9.
- Uses the existing signing identity for upgrades. Compile and target SDK remain Android 16/API 36.

Install the signed release APK. Validation coverage and device-specific limits are recorded in TESTING.md.

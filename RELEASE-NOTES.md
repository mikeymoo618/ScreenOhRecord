# ScreenOhRecord 1.3.0

- Adds optional SMB and plain FTP automatic moves for completed recordings over Wi-Fi. Disabled by default.
- Configure server, port, share/folder, credentials and delay under Settings > Automatic video moves. Default delay is 60 minutes after recording finishes.
- Test connection checks login, upload, checksum read-back, rename and cleanup.
- Uploads to a temporary remote filename, verifies the content through SHA-256 read-back and checks the finalized file before permanently deleting the local recording and removing its list entry/thumbnail.
- Retains local files on failed or interrupted transfers. Reuses verified remote copies on retry. Remote filenames include a unique suffix to prevent collisions.
- Tracks completed recordings independently of the 50-row recent list and discovers older recognized recordings in the current folder. Pauses transfers during recording or capture consent.
- Stores transfer passwords encrypted with Android Keystore. Plain FTP network traffic is unencrypted. SMB uses SMB2 on servers accepting SMB2 connections.
- Checks about every 15 minutes. Android may defer background jobs beyond the selected delay.
- Preserves Android 9 support, Android 16 recording settings and the existing signing identity.

Validation coverage and physical-device limits are recorded in TESTING.md.
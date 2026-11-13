# ScreenOhRecord 1.3.1

- Completed-video SMB and FTP transfers can run while another screen recording is in progress, including capture consent.
- Keeps the current unfinished recording excluded from transfer and deletion, with an additional check before local deletion.
- Moves completed files only after destination size and SHA-256 read-back verification. Successful moves permanently remove the local video and its list entry/thumbnail.
- Retains Wi-Fi requirements, the configured completion delay, interruption recovery and protection of the recording's recovery marker.
- Preserves saved recording settings, Android 9 support and the existing signing identity.

Validation coverage and device-specific limits are recorded in TESTING.md.
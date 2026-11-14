# ScreenOhRecord 1.3.4

- Move videos now restarts inactive delayed jobs while preserving active transfers and resumable progress.
- Displays current Wi-Fi state and Android scheduling reasons, including retry delay, quota and background restrictions.
- Keeps connection-test results separate from live queue status and retains the last interruption with its timestamp.
- Supports Android 9 and newer with the existing recording controls, transfer verification and signing identity.

## Previous 1.3.3 behavior retained

- Processes the eligible queue continuously with a visible notification, filename, file count, transfer phase and byte progress.
- Removes the eight-minute batch cutoff. Saving enabled settings starts the queue. Move videos now starts an existing saved queue.
- Uses user-initiated transfer jobs on Android 14+ and a foreground transfer service on older Android versions. Periodic automatic discovery remains enabled.
- Resumes FTP and SMB uploads from remote offsets and persists SHA-256 verification progress across interruptions. Remote size/timestamp changes invalidate saved verification state.
- Handles temporary FTP stalls with longer response/read timeouts and an inactivity watchdog. FTP transfers use short commands to keep control connections active.
- Preserves Wi-Fi-only operation, active-recording exclusion, both complete remote checksum verification passes and final local checks before permanent deletion.

## Previous 1.3.2 behavior retained

- Rescans the selected video folder on every transfer check, including files absent from the app's recent list.
- Accepts any filename and video MIME type or recognized video extension, including MP4, MKV, MOV, WebM and AVI. The configured delay uses last-modified time and defaults to 60 minutes.
- Android 10 and newer require selecting the source folder once in Automatic video moves. This grants full folder access and sets the recording destination.
- Preserves original extensions. A failed file remains on the device while other eligible files continue transferring.
- Keeps active recordings excluded. Remote size and SHA-256 read-back verification and a local content/timestamp recheck precede permanent deletion.

## Previous 1.3.1 behavior retained

- Completed-video SMB and FTP transfers can run while another screen recording is in progress, including capture consent.
- Keeps the current unfinished recording excluded from transfer and deletion, with an additional check before local deletion.
- Moves completed files only after destination size and SHA-256 read-back verification. Successful moves permanently remove the local video and its list entry/thumbnail.
- Retains Wi-Fi requirements, the configured completion delay, interruption recovery and protection of the recording's recovery marker.
- Preserves saved recording settings, Android 9 support and the existing signing identity.

Validation coverage and device-specific limits are recorded in TESTING.md.

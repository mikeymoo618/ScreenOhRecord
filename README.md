# ScreenOhRecord 1.3.3

Version 1.3.3 runs eligible videos sequentially through the whole queue with a visible transfer notification and live filename/byte progress. Save enabled transfer settings or tap **Move videos now** in **Settings > Automatic video moves**. Android 14+ uses a user-initiated transfer job. Older Android versions use a foreground data-transfer service. The app's eight-minute batch cutoff has been removed.

Interrupted FTP uploads resume using REST/STOR and SMB uploads resume using file offsets. SHA-256 read-back verification stores private continuation checkpoints, reused when the remote size and modification timestamp match. Servers without a usable modification timestamp receive a fresh verification pass. Local files are permanently removed after both remote verification passes and the final local content/timestamp check.

Recoverable socket failures and premature connection closure receive two brief retries within the current file. Repeated start requests share the existing continuous job. A user request can hand an ordinary scheduled transfer over to continuous execution, preserving its saved progress.

Automatic discovery still checks about every 15 minutes. Android can defer background starts and interrupted work. **Move videos now** requests continuous queue execution while the app is visible. Once a run starts, successful files lead directly to the next eligible file. Wi-Fi loss, Android interruption, disabled moves or changed settings can pause it. Temporary FTP stalls receive a 60-second response/read allowance, and a 90-second inactivity watchdog closes stalled connections. Short FTP command segments keep the control connection active while transferring large files.

Version 1.3.2 scans the selected folder on every transfer check. Any filename is eligible when its MIME type or extension identifies a video and its last-modified time is older than the configured delay, which defaults to 60 minutes. Android 10 and newer require a one-time folder selection in Automatic video moves to grant access to all videos in that folder. This also sets the recording destination. Files directly in that folder are scanned. Unknown timestamps start the delay at first discovery. Active recordings stay on the device.

An independent Android screen recorder: local MP4 files, no ads, analytics, livestreaming or screen-text analysis. Optional SMB/FTP moves transfer completed videos to your configured server over Wi-Fi. AZ Recorder was a behavioral reference; its code, resources and signing identity are not reused.

**Version 1.2.7 adds Android 9 support. Saving in Movies on Android 9 requests storage access. Android 9 can fit video dimensions to its encoder limits within the selected resolution cap.**

**Version 1.2.6 adds Open app to the idle floating menu. Tap it to bring the main screen into view and collapse the menu.**

**Version 1.2.5 makes Stop and Restart red, matching the other stop actions.**

**Version 1.2.4 adds Stop and Restart to the floating recording menu. It permanently deletes the current recording and immediately opens fresh Android screen-sharing consent using your current settings. If deletion fails, it reports the error and does not restart.**

**Version 1.2.3 shows floating controls automatically when opening or returning to the main screen, once Android's Display over other apps permission is enabled. Hide lasts until you return to the app. Recording still requires your action and Android capture consent.**

**Version 1.2.2 removes confirmed missing recordings from history when opening or returning to the app. Delete also clears an already-missing recording. Temporary storage or permission failures preserve history.** Version 1.2.1 added recording thumbnails, a final installed-version footer, and Stop and Delete that keeps floating controls open. Version 1.2.0 introduced AV1 and the GitHub updater. The updater requires internet access; the earlier no-INTERNET permission guarantee no longer applies. Recording defaults to local storage. Network access serves public GitHub update metadata/APK downloads and user-enabled SMB/FTP video moves.

## Install and update

Install the signed APK from [GitHub Releases](https://github.com/mikeymoo618/ScreenOhRecord/releases/latest). Install it over the existing ScreenOhRecord without uninstalling; the original signing identity is retained. Versions before 1.2.0 require a manual APK installation; versions with App updates can use that screen for subsequent releases. AZ Recorder may remain installed as a separate app.

Minimum Android 9/API 28, with compile/target Android 16/API 36. Validation coverage is recorded in TESTING.md. Install the signed release APK linked above.

Open **App updates** from the main screen or Settings. **Check for updates** fetches small public GitHub release information and shows installed/latest versions, download size and release notes. After you approve a download, the app checks its size, SHA-256, package, version and installed signing identity, then requests Android's installation confirmation. Android validates the APK signature during installation. You may need to enable Android's **Allow from this source** setting for ScreenOhRecord, then return to the app. Installations are not silent; recordings/settings are retained on an ordinary signed upgrade.

**Check weekly over Wi-Fi** starts off. If enabled, Android schedules an approximately weekly metadata-only check on unmetered Wi-Fi. The request is bound to that Wi-Fi network, so loss of Wi-Fi does not fall back to mobile data. A check can be delayed by Android. It may notify you when an update is found but never automatically downloads or installs the APK. Manual checks/downloads use your active connection and may use mobile data. No update requests run during recording/capture consent; finish an update operation before starting a recording.

## Recording settings

Settings apply to the next recording, including floating-control starts. Resolution/microphone also have quick controls on the main screen.

| Setting | Choices |
|---|---|
| Resolution | 720p or 1080p short-edge cap; aspect ratio preserved without upscaling |
| Target frame rate | 15 or 30 fps |
| Compression | H.264; H.265/HEVC; AV1 |
| Video bitrate | 250, 350 or 500 kbps; 1, 2, 4 or 6 Mbps |
| Audio | Microphone AAC at 96 kbps/44.1 kHz, or silent; no internal app audio/mixing |
| Destination | Movies/ScreenOhRecord, or an Android-picker local phone/removable-storage folder |

Defaults remain 720p, 15fps, H.264, 1Mbps, microphone enabled. Your later selections are remembered. Android microphone permission is required when enabled; denial requires granting it or turning microphone off. Screen capture always needs new Android consent. On Android 14+, the app requests the entire display; cancelling records nothing.

HEVC and H.265 are the same standard. AV1/HEVC encoder support depends on the device, dimensions, fps and bitrate; Settings labels unavailable combinations and starting an unsupported codec gives an error. AV1 requires Android 13+ in this app. A software encoder can be slow or battery intensive. Hardware/device tests are still needed on your phone, even if a configuration is advertised as supported. The Android 16 emulator produced a decodable 720p AV1 MP4 with microphone audio during verification.

At the same bitrate, file sizes are roughly the same across codecs. More efficient compression may retain quality at a lower bitrate. Microphone-inclusive estimates are about 8.2 MB/min at 1Mbps, 4.5 at 500kbps, 3.3 at 350kbps and 2.6 at 250kbps. Actual file size/achieved fps vary with content and performance. Small text/motion may blur at low rates; 15/30fps are targets rather than guaranteed constant delivery.

## Floating controls and deletion

If needed, tap **Show floating controls**, grant **Display over other apps**, and return. With that permission, opening or returning to the main screen automatically shows the bubble. Drag it; tap it for recording controls. Android consent is still required before every new recording. The bubble hides during consent and may appear in entire-screen recordings.

- **Stop & save** finalizes the current local video and adds it to history.
- **Stop and Delete** stops and permanently deletes only the current recording, without publishing it to history or moving it to a recycle bin. The floating menu stays open, ready to start another recording. This explicitly named action has no extra confirmation. Previously saved recordings are not affected.
- **Stop and Restart** is available while recording or paused. It permanently deletes the current recording, then opens Android's screen-sharing confirmation for a fresh recording using your current settings. Cancel leaves recording stopped. Deletion failure keeps the menu open and prevents restart; restore folder access to retry cleanup. Previously saved recordings are unaffected.
- **Open app** appears between Start recording and Hide bubble while idle. It brings the main screen into view and collapses the menu.
- **Hide bubble** hides controls until you return to the main screen, without stopping an active recording. Use the app or notification to stop it.

Deletion failures are reported; the floating menu remains open. The app retains an unfinished-file marker for cleanup when folder access is restored and the app is reopened. The bubble never automatically records or starts at boot. It can be restored after process death when you open the main screen. Revoking overlay permission closes it. An enabled idle bubble has a visible foreground notification.

Pause/resume/stop also work through the main app and recording notification. If notification permission is denied, use the app/bubble. Screen-off stops and saves recording. Keep orientation steady; the fixed recording canvas can letterbox after rotation. Failed/too-short recordings are discarded. Recent history is limited to 50; older saved files remain on storage. No editor, captions or livestream feature is included.

## Saved recordings and version footer

Saved recordings show a small local thumbnail beside their name, duration and size. Frames are decoded in the background with a bounded memory-only cache; no preview image files are saved. Deleting a recording removes its row and thumbnail immediately and cancels pending preview work. Missing/unreadable videos use a placeholder. Existing Play/Delete controls remain.

The last line of the page shows the installed package version and build number, currently ScreenOhRecord 1.3.3, build 19. It is refreshed from Android package information.

## Local folders and privacy

Only Android's external-storage document provider is accepted by the folder picker; cloud providers cannot become recording destinations. Android restricts certain roots/app-data directories, so choose a permitted subfolder. Removing storage/revoking access reports failure rather than silently changing destination. **Use Movies / ScreenOhRecord** restores the default. Existing recordings stay in place; old folder grants are retained for their playback/deletion. Play launches a selected player with a URI read grant; Delete for saved files requires confirmation.

Android 9 uses READ_EXTERNAL_STORAGE and WRITE_EXTERNAL_STORAGE for the default Movies folder. Both declarations have maxSdkVersion 28. Android 10 and newer use scoped MediaStore access. The updater adds INTERNET, ACCESS_NETWORK_STATE, REQUEST_INSTALL_PACKAGES and RECEIVE_BOOT_COMPLETED (for the optional persisted job). The system-only job service requires BIND_JOB_SERVICE; it does not start recording at boot. App-data backup/device transfer is disabled. Other gallery, player or backup apps have their own permissions and may sync shared videos if you configure them to do so.

Whole-screen video can contain private messages, notifications and sensitive data displayed during recording. Hide private notifications and avoid exposing sensitive content. Completed recordings move to your configured SMB/FTP destination when automatic moves are enabled.

## Build and publish

Requires JDK 17+ (built with Android Studio JDK 21), Android SDK 36/build tools 36.0.0, and the SDK location in untracked local.properties. Gradle 8.11.1/AGP 8.10.1 are pinned. No third-party runtime library is bundled.

```powershell
.\build.ps1 -Online  # first dependency download
.\build.ps1          # cached/offline build and lint
```

Outputs artifacts/ScreenOhRecord-1.2.1.apk and its checksum. Preserve both private .local-signing files securely; never publish them. Losing the original key prevents ordinary in-place upgrades.

The project AGENTS.md requires verified completed upgrades to be published to this GitHub repository. Update the version/code, updates.md, RELEASE-NOTES.md and verification report, test the signed release, then run scripts/publish-release.ps1. The publisher validates the exact project Git root, source allowlist and artifact identity; preserves remote history; pushes source and a version tag; and publishes a GitHub Release only after the APK, checksum and update.json are uploaded. Artifacts stay out of Git history. Existing release versions are never overwritten. GitHub credentials are read from the local credential manager and never embedded in the APK or saved in this repository.

See TESTING.md, DESIGN.md, RELEASE-PLAN-1.2.0.md and updates.md for evidence, decisions and limitations. Historical artifacts and diagnostic logs remain local under artifacts.

## Automatic video moves

Version 1.3.0 adds optional SMB and plain FTP destinations under Settings > Automatic video moves. The feature starts disabled. Enter the server IPv4 address or hostname, port, credentials and destination folder. SMB additionally uses a share name and optional domain. For `//192.168.1.1/video files/videos`, enter server `192.168.1.1`, share `video files` and folder `videos`. FTP paths are relative to the login directory unless an absolute path is entered.

Use Test connection to check login, write, checksum read-back, rename and deletion of a small test file. Enable automatic moves and save. The default delay is 60 minutes after the video was last modified. Android checks about every 15 minutes and may defer background work for battery management. Transfers require active Wi-Fi and can run during recording or capture consent. Only completed videos are eligible. VPN connections are excluded. Passwords are encrypted in device-local storage using Android Keystore.

Videos directly in the selected folder are tracked independently of the recent list and rediscovered on every check, including videos from older versions and other apps. Last-modified time determines eligibility. Active recordings stay on the device. Remote filenames preserve the original extension and include a unique suffix. Uploads use a `.uploading` suffix, then size and full SHA-256 read-back verification, rename and final verification. Local content and modification time are rechecked before permanent deletion. Failed files remain for retry while other eligible files continue. Verification adds Wi-Fi traffic because the remote file is read back.

SMB supports SMB2.02/2.1 on SMB2-enabled SMB3 servers. SMB1 and servers requiring SMB3 encryption are unsupported. Plain FTP sends credentials and videos unencrypted. The destination folder must already exist and permit upload, read, rename and delete operations.

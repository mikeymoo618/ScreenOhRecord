# ScreenOhRecord 1.2.2

- Refreshes saved-recording history against local storage when opening or returning to the app. Confirmed missing files and their thumbnails disappear from the list.
- Delete clears an already-missing recording instead of leaving an unremovable entry, including when a file disappears while its Delete confirmation is open.
- Keeps history when a folder is inaccessible, storage is unavailable, or folder permission is revoked. Cleanup never deletes an existing video.
- Preserves signing identity, recording settings, floating controls and updater. No permissions or dependencies added.

Install over the previous version without uninstalling. This release does not establish the cause of the previously reported Google Play Protect warning or provide Play Protect clearance. See TESTING.md for evidence and limitations.

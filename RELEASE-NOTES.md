# ScreenOhRecord 1.2.1

- Shows the installed version and build number on the final line of the main screen, refreshed automatically from Android package information.
- Adds small thumbnails beside saved recordings. Preview generation runs in the background and uses memory only; no extra thumbnail files accumulate on storage.
- Deleting a saved recording immediately removes its row and preview, evicts its cached frame, and cancels pending preview loads so they cannot reappear.
- Renames the floating action to Stop and Delete. It permanently discards the active recording without recycling it and keeps the floating menu open for another recording.
- Preserves existing settings, recordings, signing identity, AV1/HEVC options and GitHub updater. Weekly checks remain optional and off until enabled. No new permissions, ads, analytics or uploads are introduced.

Install over the previous version. Devices with App updates can obtain the release there. Android still asks for installation confirmation and needs sufficient free internal storage. Tests and limitations are documented in TESTING.md.

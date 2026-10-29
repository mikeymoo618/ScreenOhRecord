# ScreenOhRecord 1.2.4

- Adds Stop and Restart to the floating recording menu, including while paused.
- Permanently deletes the active recording and immediately opens Android screen-sharing confirmation for a new recording with the current settings. No extra bubble/Start tap is needed.
- Cancelling Android confirmation leaves recording stopped. If deletion fails, the app reports it, keeps the menu open and does not restart.
- Existing saved recordings, signing identity, settings and updater are preserved. No new permissions or dependencies.

Install over the previous version without uninstalling. Android's capture confirmation remains required for each new session. Verification evidence and device limitations are recorded in TESTING.md.

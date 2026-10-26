# ScreenOhRecord project rules

Follow the parent repository instructions and communicate in English. Obtain approval for a short plan before substantive changes; once approved, complete the work without asking again for the same scope.

- Increment the app version/versionCode and update updates.md for each released code change.
- Keep recordings local. Network access is solely for the explicit GitHub updater; no ads, analytics, telemetry or recording uploads. Do not run update requests during a recording or capture-consent flow.
- Preserve the project-local signing identity. Never publish .local-signing, local.properties, credentials, phone recordings, Android test images, caches or analysis files.
- Publish every completed, verified upgraded build to https://github.com/mikeymoo618/ScreenOhRecord as a versioned GitHub Release. Include the signed APK, SHA-256 checksum, update.json and release notes. Push the matching project source/version and annotated release tag; preserve existing remote history. This standing instruction authorises publishing completed upgrades without a separate publication approval.
- Before publishing, build/lint, test relevant behavior on the task-owned Android emulator, inspect packaged permissions/signature, and record exact artifact identity and limitations. Never publish a failed or unverified build as completed.
- Use scripts/publish-release.ps1 from this project folder. It must verify the exact repository root, use a source allowlist, refuse duplicate release versions and upload only explicit release assets. Do not stage or push the parent C:\Users\Mike repository.
- Keep all diagnostic logs locally, including failed attempts. Do not claim that an emulator establishes physical-phone or future-Android compatibility.
- Update downloads must come only from this repository's HTTPS GitHub releases, pass size/hash/package/version/signature validation, and use Android's installation confirmation. Weekly checks are optional, metadata-only and Wi-Fi-only; never silently download or install an update.


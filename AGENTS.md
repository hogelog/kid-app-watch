# Kid App Watch agent notes

This repository is public; keep code, comments, commits, issues, and PRs in English.

## Product shape

- The core value is the watch page recent-event view.
- The watch page is scoped to one logged-in device and should stay minimal.
- Device login is done by opening `/?device_id=<device-id>`; the server stores it in a long-lived cookie.
- Admin UI is for setup and maintenance only.
- `/admin*` is expected to be protected by deployment infrastructure, not by in-app Basic auth.

## Authentication model

- Do not reintroduce an internal API token unless the application requirements change.
- Android API calls may include deployment-specific gateway headers.
- The Android settings screen stores extra headers as one multi-line text field.

## Android behavior

- Device ID is generated from `Settings.Secure.ANDROID_ID` and prefixed with `android-`.
- Usage access is required to read app foreground/background events.
- Periodic checks use WorkManager and are intentionally silent.
- Manual `Check Now` uses one-time WorkManager work and is the only path that updates the visible check-status message.
- WorkManager is not real time: Android enforces a minimum 15-minute periodic interval and power management may delay work.
- Usage duration is approximate, estimated from foreground/background usage events.

## Server behavior

- Devices are registered automatically on first API communication.
- `devices.last_seen_at` is updated by config, event, and heartbeat API calls.
- Watch packages are managed from the admin device page.
- Package labels and icons are fetched from Google Play metadata when possible.
- Store fetched metadata as UTF-8; Japanese app names are expected.
- Keep `source` in the database but do not show it in normal admin UI unless multiple sources exist.

## UI preferences

- Avoid redundant headings when the page has a single obvious purpose.
- Prefer list/card layouts for mobile admin screens; avoid dense editable tables on small screens.
- Hide edit/add forms behind disclosure controls by default.
- All destructive buttons must require confirmation.
- Show event times in JST on the web UI, with minute precision.
- Group event lists by date.

## Release flow

- Main-branch debug APKs are published to the `main-debug` GitHub Release.
- Release notes should be written via `--notes-file` or equivalent so newlines render correctly.
- The debug keystore is committed intentionally so CI APKs can be installed as updates.

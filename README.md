# Kid App Watch

Kid App Watch records when selected Android apps are opened on a child device and shows the recent events in a small web UI.

It is designed for this flow:

1. Install the Android app on the child device.
2. Configure the server URL and gateway headers in the Android app settings.
3. Grant Android usage access.
4. Open the watch page from the Android app to log in with the device ID.
5. Manage watched apps from the admin UI.

## Web UI

- `/` shows recent events for the logged-in device.
- Login is device-based: open `/?device_id=<device-id>` once, and the device ID is stored in a long-lived cookie.
- `/admin` is the management UI and should be protected by an external gateway such as Cloudflare Access.
- The admin UI shows device last-seen time, watched apps, and recent events.

## Android app

The Android app:

- generates a stable device ID from `Settings.Secure.ANDROID_ID`
- uses Android Usage Access to read foreground app usage events
- checks periodically with WorkManager
- supports a manual `Check Now` action
- links to the logged-in watch page for the current device

WorkManager periodic checks are not real-time. Android enforces a minimum periodic interval of 15 minutes, and device power management may delay execution. Manual `Check Now` is available when immediate checking is needed.

Usage duration is approximate. The app estimates a session from foreground/background usage events; this is intended as a useful hint, not an exact measurement.

## Server

```sh
cd server
bundle install
PORT=9292 ./bin/server
```

Useful environment variables:

- `DATABASE_PATH`: SQLite path; defaults to `server/data/kid_app_watch.sqlite3`
- `HOST`: bind address; defaults to `127.0.0.1`
- `PORT`: bind port; defaults to `9292`

## Gateway headers

If the server is behind Cloudflare Access or another gateway, configure the Android app with extra request headers, one per line:

```http
CF-Access-Client-Id: <client-id>
CF-Access-Client-Secret: <client-secret>
```

The app does not use an internal API token. API endpoints rely on the external gateway for authentication.

## API

```http
GET /api/devices/:id/heartbeat
POST /api/devices/:id/heartbeat
GET /api/devices/:id/config
POST /api/devices/:id/app_launch_events
```

Event body:

```json
{
  "package_name": "com.google.android.youtube",
  "app_label": "YouTube",
  "detected_at": "2026-06-30T21:30:00+09:00",
  "duration_seconds": 180,
  "source": "usage_stats"
}
```

All events are stored. ntfy notifications are suppressed per device and package within the configured cooldown.

## Debug APK release

A debug APK is published from the `main` branch GitHub Actions workflow:

<https://github.com/hogelog/kid-app-watch/releases/tag/main-debug>

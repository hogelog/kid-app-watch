# Kid App Watch

Detect selected Android app launches on a child device, store events in SQLite, and optionally notify a parent through ntfy.

The server is intended to run behind Cloudflare Access. The parent uses the web admin UI and ntfy; there is no parent Android app.

## Components

- `server/`: Sinatra, SQLite, ERB admin UI
- `android/`: Kotlin, Jetpack Compose, UsageStatsManager, WorkManager

## Server

```sh
cd server
bundle install
ADMIN_AUTH_MODE=none bundle exec rackup -o 127.0.0.1 -p 9292
```

Use `ADMIN_AUTH_MODE=none` when Cloudflare Access protects `/admin*`. For local-only testing without Access, omit it and set `ADMIN_USER` / `ADMIN_PASSWORD`.

Useful environment variables:

- `DATABASE_PATH`: SQLite path; defaults to `server/data/kid_app_watch.sqlite3`
- `ADMIN_AUTH_MODE`: `basic` or `none`
- `ADMIN_USER`, `ADMIN_PASSWORD`: Basic auth credentials when `ADMIN_AUTH_MODE=basic`

## Cloudflare Access

Recommended policies:

- `/admin*`: interactive parent login
- `/api/*`: service token for child devices

The Android app sends these service token headers when configured:

- `CF-Access-Client-Id`
- `CF-Access-Client-Secret`

The app-level device token is still required:

```http
Authorization: Bearer <device-token>
```

## Setup Flow

1. Open `/admin`.
2. Create a device and copy its API token.
3. Add watch packages for that device.
4. Set the device ntfy topic URL if notifications are needed.
5. Install the Android app on the child device.
6. Enter server URL, device ID, API token, and Cloudflare Access service token values.
7. Grant Android usage access.

## API

```http
GET /api/devices/:id/config
POST /api/devices/:id/app_launch_events
```

Event body:

```json
{
  "package_name": "com.google.android.youtube",
  "app_label": "YouTube",
  "detected_at": "2026-06-30T21:30:00+09:00",
  "source": "usage_stats"
}
```

All events are stored. ntfy notifications are suppressed per device and package within the configured cooldown.

## Android Notes

WorkManager checks usage events periodically. Android enforces a minimum periodic interval of 15 minutes, so this MVP is not real-time. A foreground service can be added later if tighter detection is needed.

Open `android/` in Android Studio and run the `app` configuration.

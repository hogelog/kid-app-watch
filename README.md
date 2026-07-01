# Kid App Watch

Detect selected Android app launches on a child device, store events in SQLite, and optionally notify a parent through ntfy.

The parent uses the web admin UI and ntfy; there is no parent Android app.

## Components

- `server/`: Sinatra, SQLite, ERB admin UI
- `android/`: Kotlin, Jetpack Compose, UsageStatsManager, WorkManager

## Server

```sh
cd server
bundle install
ADMIN_AUTH_MODE=none PORT=9292 ./bin/server
```

Use `ADMIN_AUTH_MODE=none` only when another gateway already protects `/admin*`. For local-only testing, omit it and set `ADMIN_USER` / `ADMIN_PASSWORD`.

Useful environment variables:

- `DATABASE_PATH`: SQLite path; defaults to `server/data/kid_app_watch.sqlite3`
- `HOST`: bind address; defaults to `127.0.0.1`
- `PORT`: bind port; defaults to `9292`
- `ADMIN_AUTH_MODE`: `basic` or `none`
- `ADMIN_USER`, `ADMIN_PASSWORD`: Basic auth credentials when `ADMIN_AUTH_MODE=basic`

## Optional Gateway Headers

If the server is behind an auth gateway, configure the Android app with extra request headers. For example, a gateway may require:

- header name
- header value

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
6. Enter server URL, device ID, API token, and any gateway headers.
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

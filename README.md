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
PORT=9292 ./bin/server
```

Protect `/admin*` with an external gateway such as Cloudflare Access when exposing the server outside localhost.

Useful environment variables:

- `DATABASE_PATH`: SQLite path; defaults to `server/data/kid_app_watch.sqlite3`
- `HOST`: bind address; defaults to `127.0.0.1`
- `PORT`: bind port; defaults to `9292`

## Optional Gateway Headers

If the server is behind an auth gateway, configure the Android app with extra request headers, one per line:

```http
CF-Access-Client-Id: <client-id>
CF-Access-Client-Secret: <client-secret>
```

The app generates its device ID and app-level bearer token automatically.

## Setup Flow

1. Install the Android app on the child device.
2. Enter server URL and any gateway headers.
3. Grant Android usage access.
4. Open `/admin` after the first device config request or event. The device is registered automatically.
5. Add watch packages for that device.
6. Set the device ntfy topic URL if notifications are needed.

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

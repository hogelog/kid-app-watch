# Kid App Watch

Kid App Watch is an MVP for detecting selected app launches on a child Android device and sending those events to a small self-hosted web server.

The parent side uses only the web admin UI and an ntfy subscription. There is no parent Android app, no Firebase dependency, and no server-specific secrets committed to the repository.

## Architecture

- Child device: native Android app written in Kotlin and Jetpack Compose
- Server: Ruby, Sinatra, SQLite, and Puma
- Notification: ntfy topic URL configured per device
- Parent side: Basic-auth protected web admin UI and ntfy app

## Repository Layout

- `server/`: Sinatra API, admin UI, SQLite schema
- `android/`: Android app project

## Server Setup

Requirements:

- Ruby 3.1 or newer
- Bundler
- SQLite development library

Install dependencies:

```sh
cd server
bundle install
```

Start the server:

```sh
ADMIN_USER=admin ADMIN_PASSWORD=replace-this bundle exec rackup -o 0.0.0.0 -p 9292
```

Open the admin UI:

```text
http://localhost:9292/admin
```

The SQLite database is created automatically at `server/data/kid_app_watch.sqlite3`. Set `DATABASE_PATH` to use a different path.

## Server Configuration

Environment variables:

- `ADMIN_USER`: Basic auth user for `/admin`
- `ADMIN_PASSWORD`: Basic auth password for `/admin`
- `DATABASE_PATH`: SQLite database path

Do not commit production values for these settings.

## Admin Flow

1. Open `/admin`.
2. Add a device with a stable device ID, display name, and API token. Leave token empty to generate one.
3. Open the device page.
4. Set an ntfy topic URL, for example an HTTPS topic endpoint.
5. Add watch packages such as `com.google.android.youtube`.
6. Configure the child Android app with server URL, device ID, and API token.

## API

Fetch device config:

```http
GET /api/devices/:id/config
Authorization: Bearer <token>
```

Create app launch event:

```http
POST /api/devices/:id/app_launch_events
Authorization: Bearer <token>
Content-Type: application/json

{
  "package_name": "com.google.android.youtube",
  "app_label": "YouTube",
  "detected_at": "2026-06-30T21:30:00+09:00",
  "source": "usage_stats"
}
```

Events are always stored. ntfy notification is skipped when the same `device_id` and `package_name` already notified within the package cooldown window.

## Android Setup

Requirements:

- Android Studio with a recent Android Gradle Plugin
- Android SDK 35
- Kotlin support

Open `android/` in Android Studio and run the `app` configuration on the child device.

The MVP app stores these settings with DataStore:

- Server URL
- Device ID
- API token
- Last sent event summary

Usage access is required. In the app, tap `Open usage access`, find Kid App Watch, and allow usage access.

## Android Behavior

- WorkManager runs `LaunchMonitorWorker` periodically.
- The worker fetches watch packages from the server.
- It reads foreground app resume events through `UsageStatsManager`.
- It posts matching package launch events to the server.
- It advances a local scan cursor and only sends one event per watched package per scan window.

WorkManager's minimum periodic interval is 15 minutes. A foreground service can be added later if near real-time detection is required.

## Manual Verification

Create a device in the admin UI, then verify the API with curl:

```sh
curl -s \
  -H "Authorization: Bearer <token>" \
  http://localhost:9292/api/devices/<device-id>/config
```

Post a sample launch event:

```sh
curl -s -X POST \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"package_name":"com.google.android.youtube","app_label":"YouTube","detected_at":"2026-06-30T21:30:00+09:00","source":"usage_stats"}' \
  http://localhost:9292/api/devices/<device-id>/app_launch_events
```

Reload the device admin page and confirm the event appears in recent events.

## Production Notes

- Put the server behind HTTPS before using it outside a private network.
- Use strong per-device API tokens.
- Use strong Basic auth credentials.
- Keep `server/data/` out of git.
- Treat package launch history as sensitive data.

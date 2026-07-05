require "base64"
require "cgi"
require "digest"
require "json"
require "fileutils"
require "net/http"
require "sinatra/base"
require "sqlite3"
require "time"
require "uri"

module KidAppWatch
  class App < Sinatra::Base
    DB_PATH = ENV.fetch("DATABASE_PATH", File.expand_path("data/kid_app_watch.sqlite3", __dir__))
    SCHEMA_PATH = File.expand_path("schema.sql", __dir__)
    ICON_PNG_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAYAAABS3GwHAAACt0lEQVR42u3cwRFAMBBA0XTgqAcVaF5fbpTADJFd+zLzC4jsOwVtmpdDqlrzEASABIAEgASABIAEgASABIAEgASABIAEgASABIBUFMC67dKwABAAAAgAAAQAAAIAAAEAgAAAQACMBeCyRZHnCwABAIAAAEAAACAAABAAAAgAAAQAAAIAAAEAgAAAQAAAIAAAEAAACAAABAAAmXq6AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIBKAt9ffBxwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMOAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAEHAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACASgAMOAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAOQFYMABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADgPz+SMuAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADggxhAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIC4AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAKAuAEAAAAAAAAAAAAAAAAAAgC8AXJ3X3QAAAAAAAAAAAAAAAACAHPsBAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPK/TAYAAAAAAAAAAAAAAAAAAAAAAAAAAEBlANn2DwAAAACgaEAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHgEAWABIAEgASABIAEgASABIAEgASABIAEgAAAQAAAIAByApB6BoAAAEAAACAAABAAAAgAAAQAAAKgLwApcwAIAAkACQAJAAkACQAJAAkACQAJAAkACQAJAAkAKWknbbH4SK8kLnsAAAAASUVORK5CYII="
    ICON_PNG_DIGEST = Digest::SHA256.hexdigest(Base64.decode64(ICON_PNG_BASE64))[0, 16]

    configure do
      set :show_exceptions, false
      set :raise_errors, false
      set :erb, escape_html: true
      set :inline_templates, true
      enable :method_override
    end

    helpers do
      def db
        Thread.current[:kid_app_watch_db] ||= self.class.open_database
      end

      def now_iso
        Time.now.iso8601
      end

      def json_body
        JSON.parse(request.body.read)
      rescue JSON::ParserError
        halt_json 400, error: "invalid_json"
      end

      def halt_json(status, payload)
        content_type :json
        halt status, JSON.generate(payload)
      end

      def find_or_register_api_device!
        id = params.fetch("id").to_s.strip
        halt_json 422, error: "device_id_required" if id.empty?

        device = db.get_first_row("SELECT * FROM devices WHERE id = ?", [id])
        return device if device

        db.execute(<<~SQL, [id, id, now_iso, now_iso])
          INSERT INTO devices (id, name, created_at, updated_at)
          VALUES (?, ?, ?, ?)
        SQL
        db.get_first_row("SELECT * FROM devices WHERE id = ?", [id])
      rescue SQLite3::ConstraintException
        db.get_first_row("SELECT * FROM devices WHERE id = ?", [id])
      end

      def bool_param(name)
        params[name].to_s == "1" ? 1 : 0
      end

      def redirect_back_to_device(device_id)
        redirect "/admin/devices/#{Rack::Utils.escape_path(device_id)}"
      end

      def current_watch_device_id!
        device_id = params["device_id"].to_s.strip
        if device_id.empty?
          device_id = request.cookies["kid_app_watch_device_id"].to_s.strip
        else
          response.set_cookie(
            "kid_app_watch_device_id",
            value: device_id,
            path: "/",
            max_age: 60 * 60 * 24 * 365 * 100,
            same_site: :lax,
            httponly: true,
          )
        end

        if device_id.empty?
          status 401
          return nil
        end
        device_id
      end

      def load_watch_overview!(device_id)
        @devices = db.execute(<<~SQL, [device_id])
          SELECT d.id,
                 d.name,
                 COUNT(DISTINCT w.id) AS watch_package_count,
                 MAX(e.detected_at) AS last_detected_at
          FROM devices d
          LEFT JOIN watch_packages w ON w.device_id = d.id AND w.enabled = 1
          LEFT JOIN app_launch_events e ON e.device_id = d.id
          WHERE d.id = ?
          GROUP BY d.id
          ORDER BY d.name COLLATE NOCASE, d.id COLLATE NOCASE
        SQL
        @events = db.execute(<<~SQL, [device_id])
          SELECT e.*, d.name AS device_name, w.icon_url
          FROM app_launch_events e
          JOIN devices d ON d.id = e.device_id
          LEFT JOIN watch_packages w
            ON w.device_id = e.device_id AND w.package_name = e.package_name
          WHERE e.device_id = ?
          ORDER BY e.detected_at DESC, e.id DESC
          LIMIT 100
        SQL
      end
    end

    get "/" do
      device_id = current_watch_device_id!
      return erb :login_required unless device_id

      load_watch_overview!(device_id)
      erb :watch
    end

    get "/health" do
      content_type :json
      JSON.generate(ok: true)
    end

    get "/manifest.webmanifest" do
      response["Cache-Control"] = "no-store, max-age=0"
      content_type "application/manifest+json"
      JSON.pretty_generate(
        name: "Kid App Watch",
        short_name: "KWatch",
        start_url: "/",
        scope: "/",
        display: "standalone",
        background_color: "#0f172a",
        theme_color: "#0f172a",
        icons: [
          {
            src: "/icon.png?v=#{ICON_PNG_DIGEST}",
            sizes: "192x192",
            type: "image/png",
            purpose: "any maskable"
          }
        ]
      )
    end

    get "/service-worker.js" do
      response["Cache-Control"] = "no-store, max-age=0"
      content_type "application/javascript"
      <<~JS
        self.addEventListener("install", (event) => {
          event.waitUntil(self.skipWaiting());
        });

        self.addEventListener("activate", (event) => {
          event.waitUntil(self.clients.claim());
        });
      JS
    end

    get "/icon.png" do
      response["Cache-Control"] = "no-store, max-age=0"
      content_type "image/png"
      Base64.decode64(ICON_PNG_BASE64)
    end

    get "/api/devices/:id/config" do
      device = find_or_register_api_device!
      packages = db.execute(<<~SQL, [device.fetch("id")])
        SELECT package_name, app_label, icon_url, cooldown_seconds
        FROM watch_packages
        WHERE device_id = ? AND enabled = 1
        ORDER BY app_label COLLATE NOCASE, package_name COLLATE NOCASE
      SQL

      content_type :json
      JSON.generate(
        device: { id: device.fetch("id"), name: device.fetch("name") },
        watch_packages: packages
      )
    end

    post "/api/devices/:id/app_launch_events" do
      device = find_or_register_api_device!
      payload = json_body

      package_name = payload.fetch("package_name", "").to_s.strip
      app_label = payload.fetch("app_label", package_name).to_s.strip
      detected_at = payload.fetch("detected_at", now_iso).to_s
      source = payload.fetch("source", "unknown").to_s.strip

      halt_json 422, error: "package_name_required" if package_name.empty?
      halt_json 422, error: "source_required" if source.empty?

      package_config = db.get_first_row(<<~SQL, [device.fetch("id"), package_name])
        SELECT *
        FROM watch_packages
        WHERE device_id = ? AND package_name = ?
      SQL

      notified = should_notify?(device, package_name, package_config, detected_at)
      db.execute(<<~SQL, [device.fetch("id"), package_name, app_label, detected_at, source, notified ? 1 : 0, now_iso])
        INSERT INTO app_launch_events
          (device_id, package_name, app_label, detected_at, source, notified, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
      SQL

      notify_ntfy(device, package_name, app_label, detected_at) if notified

      status 201
      content_type :json
      JSON.generate(ok: true, notified: notified)
    rescue KeyError => error
      halt_json 422, error: "missing_field", field: error.key
    end

    get "/devices/:id" do
      halt 403, "Forbidden\n" unless params[:id] == current_watch_device_id!

      @device = db.get_first_row("SELECT id, name FROM devices WHERE id = ?", [params[:id]])
      halt 404, "Device not found" unless @device

      @watch_packages = db.execute(<<~SQL, [@device.fetch("id")])
        SELECT package_name, app_label, icon_url, cooldown_seconds
        FROM watch_packages
        WHERE device_id = ? AND enabled = 1
        ORDER BY app_label COLLATE NOCASE, package_name COLLATE NOCASE
      SQL

      @events = db.execute(<<~SQL, [@device.fetch("id")])
        SELECT e.*, w.icon_url
        FROM app_launch_events e
        LEFT JOIN watch_packages w
          ON w.device_id = e.device_id AND w.package_name = e.package_name
        WHERE e.device_id = ?
        ORDER BY e.detected_at DESC, e.id DESC
        LIMIT 100
      SQL

      erb :watch_device
    end

    get "/admin" do
      @devices = db.execute(<<~SQL)
        SELECT d.*,
               COUNT(w.id) AS watch_package_count,
               MAX(e.detected_at) AS last_detected_at
        FROM devices d
        LEFT JOIN watch_packages w ON w.device_id = d.id
        LEFT JOIN app_launch_events e ON e.device_id = d.id
        GROUP BY d.id
        ORDER BY d.created_at DESC
      SQL
      erb :admin
    end

    get "/admin/devices/:id" do
      @device = db.get_first_row("SELECT * FROM devices WHERE id = ?", [params[:id]])
      halt 404, "Device not found" unless @device

      @watch_packages = db.execute(<<~SQL, [@device.fetch("id")])
        SELECT *
        FROM watch_packages
        WHERE device_id = ?
        ORDER BY enabled DESC, app_label COLLATE NOCASE, package_name COLLATE NOCASE
      SQL

      @events = db.execute(<<~SQL, [@device.fetch("id")])
        SELECT e.*, w.icon_url
        FROM app_launch_events e
        LEFT JOIN watch_packages w
          ON w.device_id = e.device_id AND w.package_name = e.package_name
        WHERE e.device_id = ?
        ORDER BY e.detected_at DESC, e.id DESC
        LIMIT 100
      SQL

      erb :device
    end

    post "/admin/devices/:id/watch_packages" do
      device = db.get_first_row("SELECT * FROM devices WHERE id = ?", [params[:id]])
      halt 404, "Device not found" unless device

      package_name = params.fetch("package_name", "").strip
      app_label = params.fetch("app_label", "").strip
      cooldown_seconds = Integer(params.fetch("cooldown_seconds", "300"))
      halt 422, "Package name is required" if package_name.empty?

      existing_package = db.get_first_row(<<~SQL, [device.fetch("id"), package_name])
        SELECT *
        FROM watch_packages
        WHERE device_id = ? AND package_name = ?
      SQL
      metadata = fetch_play_store_metadata(package_name)
      app_label = metadata.fetch(:label, "") if app_label.empty?
      app_label = existing_package.fetch("app_label", "") if app_label.empty? && existing_package
      app_label = package_name if app_label.empty?
      icon_url = metadata.fetch(:icon_url, "")
      icon_url = existing_package.fetch("icon_url", "") if icon_url.empty? && existing_package

      db.execute(<<~SQL, [device.fetch("id"), package_name, app_label, icon_url, bool_param("enabled"), cooldown_seconds, now_iso, now_iso])
        INSERT INTO watch_packages
          (device_id, package_name, app_label, icon_url, enabled, cooldown_seconds, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(device_id, package_name) DO UPDATE SET
          app_label = excluded.app_label,
          icon_url = excluded.icon_url,
          enabled = excluded.enabled,
          cooldown_seconds = excluded.cooldown_seconds,
          updated_at = excluded.updated_at
      SQL

      redirect_back_to_device(device.fetch("id"))
    end

    post "/admin/devices/:id/name" do
      device = db.get_first_row("SELECT * FROM devices WHERE id = ?", [params[:id]])
      halt 404, "Device not found" unless device

      name = params.fetch("name", "").strip
      halt 422, "Name is required" if name.empty?

      db.execute(<<~SQL, [name, now_iso, device.fetch("id")])
        UPDATE devices
        SET name = ?, updated_at = ?
        WHERE id = ?
      SQL

      redirect_back_to_device(device.fetch("id"))
    end

    post "/admin/devices/:id/ntfy" do
      device = db.get_first_row("SELECT * FROM devices WHERE id = ?", [params[:id]])
      halt 404, "Device not found" unless device

      db.execute(<<~SQL, [params.fetch("ntfy_topic_url", "").strip, now_iso, device.fetch("id")])
        UPDATE devices
        SET ntfy_topic_url = ?, updated_at = ?
        WHERE id = ?
      SQL

      redirect_back_to_device(device.fetch("id"))
    end

    post "/admin/devices/:id/delete" do
      device = db.get_first_row("SELECT * FROM devices WHERE id = ?", [params[:id]])
      halt 404, "Device not found" unless device

      db.execute("DELETE FROM devices WHERE id = ?", [device.fetch("id")])
      redirect "/admin"
    end

    error do
      status 500
      "Internal server error\n"
    end

    not_found do
      "Not found\n"
    end

    def self.initialize_database!
      FileUtils.mkdir_p(File.dirname(DB_PATH))
      db = open_database
      db.execute_batch(File.read(SCHEMA_PATH))
      db.close
    end

    def self.open_database
      SQLite3::Database.new(DB_PATH).tap do |database|
        database.results_as_hash = true
        database.busy_timeout = 5_000
        database.execute("PRAGMA foreign_keys = ON")
        database.execute("PRAGMA journal_mode = WAL")
      end
    end

    def should_notify?(device, package_name, package_config, detected_at)
      return false if device.fetch("ntfy_topic_url", "").to_s.empty?
      return false if package_config && package_config.fetch("enabled").to_i != 1

      cooldown = package_config ? package_config.fetch("cooldown_seconds").to_i : 300
      return true if cooldown <= 0

      last_notified = db.get_first_value(<<~SQL, [device.fetch("id"), package_name])
        SELECT detected_at
        FROM app_launch_events
        WHERE device_id = ? AND package_name = ? AND notified = 1
        ORDER BY detected_at DESC, id DESC
        LIMIT 1
      SQL
      return true unless last_notified

      Time.parse(detected_at) - Time.parse(last_notified) >= cooldown
    rescue ArgumentError
      false
    end

    def fetch_play_store_metadata(package_name)
      uri = URI("https://play.google.com/store/apps/details")
      uri.query = URI.encode_www_form(id: package_name, hl: "ja", gl: "JP")
      response = Net::HTTP.get_response(uri)
      return {} unless response.is_a?(Net::HTTPSuccess)

      html = response.body
      label = html[/<meta\s+property="og:title"\s+content="([^"]+)"/i, 1]
      icon_url = html[/<meta\s+property="og:image"\s+content="([^"]+)"/i, 1]
      label = CGI.unescapeHTML(label.to_s).sub(/\s+-\s+[^-]+\z/, "").strip
      icon_url = CGI.unescapeHTML(icon_url.to_s).strip
      { label: label, icon_url: icon_url }
    rescue StandardError => error
      warn "Play Store metadata fetch failed for #{package_name}: #{error.class}: #{error.message}"
      {}
    end

    def notify_ntfy(device, package_name, app_label, detected_at)
      uri = URI(device.fetch("ntfy_topic_url"))
      request = Net::HTTP::Post.new(uri)
      request["Title"] = "#{app_label} opened"
      request.body = [
        "Device: #{device.fetch("name")}",
        "Package: #{package_name}",
        "Time: #{detected_at}"
      ].join("\n")

      Net::HTTP.start(uri.hostname, uri.port, use_ssl: uri.scheme == "https") do |http|
        http.request(request)
      end
    rescue StandardError => error
      warn "ntfy notification failed: #{error.class}: #{error.message}"
    end
  end
end

KidAppWatch::App.initialize_database!

__END__

@@ layout
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Kid App Watch</title>
  <link rel="manifest" href="/manifest.webmanifest">
  <meta name="theme-color" content="#0f172a">
  <link rel="icon" href="/icon.png?v=<%= KidAppWatch::App::ICON_PNG_DIGEST %>">
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/@picocss/pico@2/css/pico.min.css">
  <style>
    :root {
      --pico-font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    body {
      background: linear-gradient(180deg, color-mix(in srgb, var(--pico-primary-background) 12%, transparent), transparent 280px), var(--pico-background-color);
      overflow-x: hidden;
    }
    body > main > header {
      align-items: center;
      flex-wrap: wrap;
      margin-bottom: 1.5rem;
    }
    body > main > header h1 {
      font-size: clamp(2.2rem, 10vw, 4rem);
      line-height: 1.05;
      margin: 0;
    }
    section {
      background: var(--pico-card-background-color);
      border: 1px solid var(--pico-muted-border-color);
      border-radius: var(--pico-border-radius);
      box-shadow: var(--pico-card-box-shadow);
      margin: 1.25rem 0;
      overflow-x: auto;
      padding: 1.25rem;
    }
    table {
      margin-bottom: 0;
      table-layout: auto;
      width: 100%;
    }
    th, td {
      overflow-wrap: anywhere;
      white-space: normal;
    }
    @media (max-width: 700px) {
      body > main {
        padding-inline: 0.75rem;
      }
      section {
        padding: 0.9rem;
      }
      table {
        font-size: 0.82rem;
      }
      th, td {
        padding: 0.45rem 0.35rem;
      }
      .optional-mobile {
        display: none;
      }
    }
    form {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px;
      align-items: end;
      margin-top: 12px;
    }
    label {
      display: grid;
      gap: 4px;
      font-size: 0.92rem;
    }
    input, button {
      font: inherit;
      padding: 8px 10px;
    }
    button {
      cursor: pointer;
    }
    .muted {
      color: color-mix(in srgb, CanvasText 62%, transparent);
    }
    .token {
      font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
      overflow-wrap: anywhere;
    }
    .nav {
      display: flex;
      gap: 12px;
      align-items: center;
    }
    .app-icon {
      border-radius: 20%;
      height: 1.75rem;
      margin-right: 0.5rem;
      vertical-align: middle;
      width: 1.75rem;
    }
    .pill {
      display: inline-block;
      border: 1px solid var(--pico-muted-border-color);
      border-radius: 999px;
      padding: 2px 8px;
      font-size: 0.86rem;
    }
    footer {
      margin-top: 2rem;
      text-align: center;
    }
  </style>
</head>
<body>
  <main class="container">
    <header>
      <h1><a href="/">Kid App Watch</a></h1>
      <% if request.path_info.start_with?("/admin") %>
        <span class="nav"><a href="/admin">Admin</a></span>
      <% end %>
    </header>
    <%= yield %>
    <footer>
      <small><a href="https://github.com/hogelog/kid-app-watch/releases/tag/main-debug">Android APK</a></small>
    </footer>
  </main>
  <script>
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register("/service-worker.js");
    }
  </script>
</body>
</html>

@@ login_required
<section>
  <h2>Device login required</h2>
  <p>Open this page from the Android app first.</p>
</section>

@@ watch
<section>
  <h2>Recent events</h2>
  <table>
    <thead>
      <tr>
        <th>Detected at</th>
        <th>Device</th>
        <th>App</th>
        <th class="optional-mobile">Package</th>
      </tr>
    </thead>
    <tbody>
      <% @events.each do |event| %>
        <tr>
          <td><%= event.fetch("detected_at") %></td>
          <td><a href="/devices/<%= Rack::Utils.escape_path(event.fetch("device_id")) %>"><%= event.fetch("device_name") %></a></td>
          <td>
            <% unless event.fetch("icon_url", "").to_s.empty? %>
              <img class="app-icon" src="<%= event.fetch("icon_url") %>" alt="">
            <% end %>
            <%= event.fetch("app_label") %>
          </td>
          <td class="token optional-mobile"><%= event.fetch("package_name") %></td>
        </tr>
      <% end %>
    </tbody>
  </table>
</section>



<section>
  <h2>Devices</h2>
  <table>
    <thead>
      <tr>
        <th>Device</th>
        <th>Watch packages</th>
        <th>Last event</th>
      </tr>
    </thead>
    <tbody>
      <% @devices.each do |device| %>
        <tr>
          <td><a href="/devices/<%= Rack::Utils.escape_path(device.fetch("id")) %>"><%= device.fetch("name") %></a></td>
          <td><%= device.fetch("watch_package_count") %></td>
          <td><%= device.fetch("last_detected_at") || "-" %></td>
        </tr>
      <% end %>
    </tbody>
  </table>
</section>

@@ watch_device
<section>
  <h2><%= @device.fetch("name") %></h2>
  <p><a href="/">← Back to watch</a></p>
</section>

<section>
  <h2>Watched apps</h2>
  <table>
    <thead>
      <tr>
        <th>App</th>
        <th>Package</th>
        <th>Cooldown</th>
      </tr>
    </thead>
    <tbody>
      <% @watch_packages.each do |watch_package| %>
        <tr>
          <td>
            <% unless watch_package.fetch("icon_url", "").to_s.empty? %>
              <img class="app-icon" src="<%= watch_package.fetch("icon_url") %>" alt="">
            <% end %>
            <%= watch_package.fetch("app_label") %>
          </td>
          <td class="token"><%= watch_package.fetch("package_name") %></td>
          <td><%= watch_package.fetch("cooldown_seconds") %>s</td>
        </tr>
      <% end %>
    </tbody>
  </table>
</section>



<section>
  <h2>Recent events</h2>
  <table>
    <thead>
      <tr>
        <th>Detected at</th>
        <th>App</th>
        <th class="optional-mobile">Package</th>
        <th class="optional-mobile">Source</th>
      </tr>
    </thead>
    <tbody>
      <% @events.each do |event| %>
        <tr>
          <td><%= event.fetch("detected_at") %></td>
          <td>
            <% unless event.fetch("icon_url", "").to_s.empty? %>
              <img class="app-icon" src="<%= event.fetch("icon_url") %>" alt="">
            <% end %>
            <%= event.fetch("app_label") %>
          </td>
          <td class="token optional-mobile"><%= event.fetch("package_name") %></td>
          <td class="optional-mobile"><%= event.fetch("source") %></td>
        </tr>
      <% end %>
    </tbody>
  </table>
</section>

@@ admin
<section>
  <h2>Devices</h2>
  <table>
    <thead>
      <tr>
        <th>ID</th>
        <th>Name</th>
        <th>Watch packages</th>
        <th>Last event</th>
      </tr>
    </thead>
    <tbody>
      <% @devices.each do |device| %>
        <tr>
          <td><a href="/admin/devices/<%= Rack::Utils.escape_path(device.fetch("id")) %>"><%= device.fetch("id") %></a></td>
          <td><%= device.fetch("name") %></td>
          <td><%= device.fetch("watch_package_count") %></td>
          <td><%= device.fetch("last_detected_at") || "-" %></td>
        </tr>
      <% end %>
    </tbody>
  </table>
</section>

@@ device
<section>
  <h2><%= @device.fetch("name") %></h2>
  <p class="muted">Device ID: <span class="token"><%= @device.fetch("id") %></span></p>
  <form method="post" action="/admin/devices/<%= Rack::Utils.escape_path(@device.fetch("id")) %>/name">
    <label>
      Name
      <input name="name" value="<%= @device.fetch("name") %>" required>
    </label>
    <button type="submit">Save name</button>
  </form>
  <form method="post" action="/admin/devices/<%= Rack::Utils.escape_path(@device.fetch("id")) %>/delete" onsubmit="return confirm('Delete this device and all events?')">
    <button type="submit" class="secondary">Delete device</button>
  </form>
</section>

<section>
  <h2>ntfy topic</h2>
  <form method="post" action="/admin/devices/<%= Rack::Utils.escape_path(@device.fetch("id")) %>/ntfy">
    <label>
      Topic URL
      <input name="ntfy_topic_url" value="<%= @device.fetch("ntfy_topic_url") %>" placeholder="https://ntfy.sh/example-topic">
    </label>
    <button type="submit">Save</button>
  </form>
</section>

<section>
  <h2>Watch packages</h2>
  <table>
    <thead>
      <tr>
        <th>Package</th>
        <th>Label</th>
        <th>Enabled</th>
        <th>Cooldown</th>
      </tr>
    </thead>
    <tbody>
      <% @watch_packages.each do |watch_package| %>
        <tr>
          <td class="token"><%= watch_package.fetch("package_name") %></td>
          <td>
            <% unless watch_package.fetch("icon_url", "").to_s.empty? %>
              <img class="app-icon" src="<%= watch_package.fetch("icon_url") %>" alt="">
            <% end %>
            <%= watch_package.fetch("app_label") %>
          </td>
          <td><%= watch_package.fetch("enabled").to_i == 1 ? "yes" : "no" %></td>
          <td><%= watch_package.fetch("cooldown_seconds") %>s</td>
        </tr>
      <% end %>
    </tbody>
  </table>

  <form method="post" action="/admin/devices/<%= Rack::Utils.escape_path(@device.fetch("id")) %>/watch_packages">
    <label>
      Package name
      <input name="package_name" placeholder="com.google.android.youtube" required>
    </label>
    <label>
      App label
      <input name="app_label" placeholder="YouTube">
    </label>
    <label>
      Cooldown seconds
      <input name="cooldown_seconds" type="number" min="0" value="300">
    </label>
    <label>
      Enabled
      <input name="enabled" type="checkbox" value="1" checked>
    </label>
    <button type="submit">Save package</button>
  </form>
</section>

<section>
  <h2>Recent events</h2>
  <table>
    <thead>
      <tr>
        <th>Detected at</th>
        <th>Package</th>
        <th>Label</th>
        <th>Source</th>
      </tr>
    </thead>
    <tbody>
      <% @events.each do |event| %>
        <tr>
          <td><%= event.fetch("detected_at") %></td>
          <td class="token"><%= event.fetch("package_name") %></td>
          <td>
            <% unless event.fetch("icon_url", "").to_s.empty? %>
              <img class="app-icon" src="<%= event.fetch("icon_url") %>" alt="">
            <% end %>
            <%= event.fetch("app_label") %>
          </td>
          <td><%= event.fetch("source") %></td>
        </tr>
      <% end %>
    </tbody>
  </table>
</section>

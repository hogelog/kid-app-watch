require "json"
require "fileutils"
require "net/http"
require "securerandom"
require "sinatra/base"
require "sqlite3"
require "time"
require "uri"

module KidAppWatch
  class App < Sinatra::Base
    DB_PATH = ENV.fetch("DATABASE_PATH", File.expand_path("data/kid_app_watch.sqlite3", __dir__))
    SCHEMA_PATH = File.expand_path("schema.sql", __dir__)

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

      def require_api_device!
        device = db.get_first_row("SELECT * FROM devices WHERE id = ?", params[:id])
        halt_json 404, error: "device_not_found" unless device

        expected = "Bearer #{device.fetch("token")}"
        provided = request.env["HTTP_AUTHORIZATION"].to_s
        halt_json 401, error: "unauthorized" unless Rack::Utils.secure_compare(provided, expected)

        device
      end

      def protected_admin!
        return if ENV.fetch("ADMIN_AUTH_MODE", "basic") == "none"

        user = ENV.fetch("ADMIN_USER", "admin")
        password = ENV.fetch("ADMIN_PASSWORD", "change-me")

        @auth ||= Rack::Auth::Basic::Request.new(request.env)
        return if @auth.provided? && @auth.basic? && @auth.credentials == [user, password]

        response["WWW-Authenticate"] = %(Basic realm="Kid App Watch Admin")
        halt 401, "Authorization required\n"
      end

      def bool_param(name)
        params[name].to_s == "1" ? 1 : 0
      end

      def redirect_back_to_device(device_id)
        redirect "/admin/devices/#{Rack::Utils.escape_path(device_id)}"
      end
    end

    before "/admin*" do
      protected_admin!
    end

    get "/" do
      redirect "/watch"
    end

    get "/health" do
      content_type :json
      JSON.generate(ok: true)
    end

    get "/api/devices/:id/config" do
      device = require_api_device!
      packages = db.execute(<<~SQL, device.fetch("id"))
        SELECT package_name, app_label, cooldown_seconds
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
      device = require_api_device!
      payload = json_body

      package_name = payload.fetch("package_name", "").to_s.strip
      app_label = payload.fetch("app_label", package_name).to_s.strip
      detected_at = payload.fetch("detected_at", now_iso).to_s
      source = payload.fetch("source", "unknown").to_s.strip

      halt_json 422, error: "package_name_required" if package_name.empty?
      halt_json 422, error: "source_required" if source.empty?

      package_config = db.get_first_row(<<~SQL, device.fetch("id"), package_name)
        SELECT *
        FROM watch_packages
        WHERE device_id = ? AND package_name = ?
      SQL

      notified = should_notify?(device, package_name, package_config, detected_at)
      db.execute(<<~SQL, device.fetch("id"), package_name, app_label, detected_at, source, notified ? 1 : 0, now_iso)
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

    get "/watch" do
      @devices = db.execute(<<~SQL)
        SELECT d.id,
               d.name,
               COUNT(DISTINCT w.id) AS watch_package_count,
               MAX(e.detected_at) AS last_detected_at
        FROM devices d
        LEFT JOIN watch_packages w ON w.device_id = d.id AND w.enabled = 1
        LEFT JOIN app_launch_events e ON e.device_id = d.id
        GROUP BY d.id
        ORDER BY d.name COLLATE NOCASE, d.id COLLATE NOCASE
      SQL
      @events = db.execute(<<~SQL)
        SELECT e.*, d.name AS device_name
        FROM app_launch_events e
        JOIN devices d ON d.id = e.device_id
        ORDER BY e.detected_at DESC, e.id DESC
        LIMIT 100
      SQL
      erb :watch
    end

    get "/watch/devices/:id" do
      @device = db.get_first_row("SELECT id, name FROM devices WHERE id = ?", params[:id])
      halt 404, "Device not found" unless @device

      @watch_packages = db.execute(<<~SQL, @device.fetch("id"))
        SELECT package_name, app_label, cooldown_seconds
        FROM watch_packages
        WHERE device_id = ? AND enabled = 1
        ORDER BY app_label COLLATE NOCASE, package_name COLLATE NOCASE
      SQL

      @events = db.execute(<<~SQL, @device.fetch("id"))
        SELECT *
        FROM app_launch_events
        WHERE device_id = ?
        ORDER BY detected_at DESC, id DESC
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

    post "/admin/devices" do
      id = params.fetch("id", "").strip
      name = params.fetch("name", "").strip
      token = params.fetch("token", "").strip

      halt 422, "Device ID is required" if id.empty?
      halt 422, "Name is required" if name.empty?
      token = SecureRandom.hex(24) if token.empty?

      db.execute(<<~SQL, id, name, token, now_iso, now_iso)
        INSERT INTO devices (id, name, token, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?)
      SQL
      redirect_back_to_device(id)
    rescue SQLite3::ConstraintException
      halt 409, "Device already exists"
    end

    get "/admin/devices/:id" do
      @device = db.get_first_row("SELECT * FROM devices WHERE id = ?", params[:id])
      halt 404, "Device not found" unless @device

      @watch_packages = db.execute(<<~SQL, @device.fetch("id"))
        SELECT *
        FROM watch_packages
        WHERE device_id = ?
        ORDER BY enabled DESC, app_label COLLATE NOCASE, package_name COLLATE NOCASE
      SQL

      @events = db.execute(<<~SQL, @device.fetch("id"))
        SELECT *
        FROM app_launch_events
        WHERE device_id = ?
        ORDER BY detected_at DESC, id DESC
        LIMIT 100
      SQL

      erb :device
    end

    post "/admin/devices/:id/watch_packages" do
      device = db.get_first_row("SELECT * FROM devices WHERE id = ?", params[:id])
      halt 404, "Device not found" unless device

      package_name = params.fetch("package_name", "").strip
      app_label = params.fetch("app_label", package_name).strip
      cooldown_seconds = Integer(params.fetch("cooldown_seconds", "300"))
      halt 422, "Package name is required" if package_name.empty?

      db.execute(<<~SQL, device.fetch("id"), package_name, app_label, bool_param("enabled"), cooldown_seconds, now_iso, now_iso)
        INSERT INTO watch_packages
          (device_id, package_name, app_label, enabled, cooldown_seconds, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(device_id, package_name) DO UPDATE SET
          app_label = excluded.app_label,
          enabled = excluded.enabled,
          cooldown_seconds = excluded.cooldown_seconds,
          updated_at = excluded.updated_at
      SQL

      redirect_back_to_device(device.fetch("id"))
    end

    post "/admin/devices/:id/ntfy" do
      device = db.get_first_row("SELECT * FROM devices WHERE id = ?", params[:id])
      halt 404, "Device not found" unless device

      db.execute(<<~SQL, params.fetch("ntfy_topic_url", "").strip, now_iso, device.fetch("id"))
        UPDATE devices
        SET ntfy_topic_url = ?, updated_at = ?
        WHERE id = ?
      SQL

      redirect_back_to_device(device.fetch("id"))
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

      last_notified = db.get_first_value(<<~SQL, device.fetch("id"), package_name)
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
  <style>
    :root {
      color-scheme: light dark;
      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    }
    body {
      margin: 0;
      background: Canvas;
      color: CanvasText;
    }
    main {
      max-width: 1080px;
      margin: 0 auto;
      padding: 24px;
    }
    header {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 16px;
      margin-bottom: 24px;
    }
    h1, h2 {
      margin: 0 0 12px;
    }
    section {
      margin: 28px 0;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      margin-top: 12px;
    }
    th, td {
      border-bottom: 1px solid color-mix(in srgb, CanvasText 18%, transparent);
      padding: 8px;
      text-align: left;
      vertical-align: top;
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
    .pill {
      display: inline-block;
      border: 1px solid color-mix(in srgb, CanvasText 24%, transparent);
      border-radius: 999px;
      padding: 2px 8px;
      font-size: 0.86rem;
    }
  </style>
</head>
<body>
  <main>
    <header>
      <h1><a href="/watch">Kid App Watch</a></h1>
      <span class="nav">
        <a href="/watch">Watch</a>
        <a href="/admin">Admin</a>
      </span>
    </header>
    <%= yield %>
  </main>
</body>
</html>

@@ watch
<section>
  <h2>Devices</h2>
  <p class="muted">Read-only launch history. Use Admin only for configuration changes.</p>
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
          <td><a href="/watch/devices/<%= Rack::Utils.escape_path(device.fetch("id")) %>"><%= device.fetch("name") %></a></td>
          <td><%= device.fetch("watch_package_count") %></td>
          <td><%= device.fetch("last_detected_at") || "-" %></td>
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
        <th>Device</th>
        <th>App</th>
        <th>Package</th>
        <th>Notified</th>
      </tr>
    </thead>
    <tbody>
      <% @events.each do |event| %>
        <tr>
          <td><%= event.fetch("detected_at") %></td>
          <td><a href="/watch/devices/<%= Rack::Utils.escape_path(event.fetch("device_id")) %>"><%= event.fetch("device_name") %></a></td>
          <td><%= event.fetch("app_label") %></td>
          <td class="token"><%= event.fetch("package_name") %></td>
          <td><span class="pill"><%= event.fetch("notified").to_i == 1 ? "notified" : "stored" %></span></td>
        </tr>
      <% end %>
    </tbody>
  </table>
</section>

@@ watch_device
<section>
  <h2><%= @device.fetch("name") %></h2>
  <p><a href="/watch">← Back to watch</a></p>
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
          <td><%= watch_package.fetch("app_label") %></td>
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
        <th>Package</th>
        <th>Source</th>
        <th>Notified</th>
      </tr>
    </thead>
    <tbody>
      <% @events.each do |event| %>
        <tr>
          <td><%= event.fetch("detected_at") %></td>
          <td><%= event.fetch("app_label") %></td>
          <td class="token"><%= event.fetch("package_name") %></td>
          <td><%= event.fetch("source") %></td>
          <td><span class="pill"><%= event.fetch("notified").to_i == 1 ? "notified" : "stored" %></span></td>
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

<section>
  <h2>Add device</h2>
  <form method="post" action="/admin/devices">
    <label>
      Device ID
      <input name="id" placeholder="child-pixel" required>
    </label>
    <label>
      Name
      <input name="name" placeholder="Child Pixel" required>
    </label>
    <label>
      API token
      <input name="token" placeholder="Leave empty to generate">
    </label>
    <button type="submit">Create</button>
  </form>
</section>

@@ device
<section>
  <h2><%= @device.fetch("name") %></h2>
  <p class="muted">Device ID: <span class="token"><%= @device.fetch("id") %></span></p>
  <p class="muted">API token: <span class="token"><%= @device.fetch("token") %></span></p>
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
          <td><%= watch_package.fetch("app_label") %></td>
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
        <th>Notified</th>
      </tr>
    </thead>
    <tbody>
      <% @events.each do |event| %>
        <tr>
          <td><%= event.fetch("detected_at") %></td>
          <td class="token"><%= event.fetch("package_name") %></td>
          <td><%= event.fetch("app_label") %></td>
          <td><%= event.fetch("source") %></td>
          <td><%= event.fetch("notified").to_i == 1 ? "yes" : "no" %></td>
        </tr>
      <% end %>
    </tbody>
  </table>
</section>

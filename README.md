# Ligan Bas – live bus alerts (Rapid KL)

Pick a bus route and stop, set a schedule (e.g. Mon–Fri 07:00–09:00, every 2 min), and get **Web Push**
notifications with an estimated arrival ("3 stops away, ~4 min"). Works on Android (Chrome) and iPhone
(Safari 16.4+, after "Add to Home Screen"). No accounts, no personal data stored.

Built with **Spring Boot 4 / Java 21**, vanilla JS PWA frontend, PostgreSQL.

> **Heads-up:** ETAs are *estimates*. Malaysia's open API only publishes bus **vehicle positions**
> (GTFS Realtime, refreshed every ~30 s); there is no official ETA feed yet and no live feed for LRT/MRT.
> This service estimates from distance along the route at an assumed average speed (`AVG_BUS_SPEED_KMH`, default 20).

## How it works

```
 browser (PWA) ──► /api/routes, /api/stops, /api/eta, /api/subscriptions
                         │
   static GTFS (daily) ──┤  NetworkService  routes, stops, stop patterns
   live positions (30s) ─┤  LiveFeedService one cached fetch per feed, shared by all users
                         │  EtaService      nearest stop → distance along pattern → minutes
   every 30 s ───────────┘  AlertScheduler  who is "on" right now? → PushService (VAPID Web Push)
```

* **Static data:** `api.data.gov.my/gtfs-static/prasarana` (feeds `rapid-bus-mrtfeeder`, `rapid-bus-kl`), cached per day.
* **Realtime data:** `api.data.gov.my/gtfs-realtime/vehicle-position/prasarana`, protobuf, cached ~25 s.
* **Push:** Web Push with a VAPID key pair generated on first start (`data/vapid.properties`, **back it up, never regenerate**).
* **Public-facing safeguards:** per-IP rate limits, caps on total/per-IP subscriptions, max 4 h window, push endpoint
  allow-list (FCM, Apple, Mozilla, Windows) to prevent SSRF, random unsubscribe token per subscription.

## Run locally

```bash
# needs JDK 21 + Maven + Docker   (macOS: brew install openjdk@21 maven)
docker compose up -d   # local PostgreSQL (db/user/password: ligan_bas)
mvn test               # unit tests for the ETA logic (no database needed)
mvn spring-boot:run
# open http://localhost:8080
```

Web Push needs HTTPS, except on `localhost` (so local testing works in Chrome). iPhone requires the installed
Home-Screen app over real HTTPS, so test iOS on the VPS.

The first start downloads the static GTFS (can take a minute). `/api/health` shows `ready: true` when done.

## Deploy on a VPS

```bash
mvn -DskipTests package
scp target/ligan-bas-0.1.0.jar you@vps:/opt/busalert/
```

`/etc/systemd/system/busalert.service`
```ini
[Unit]
Description=Bus Alert
After=network.target

[Service]
User=busalert
WorkingDirectory=/opt/busalert
Environment=DATA_DIR=/opt/busalert/data
Environment=VAPID_SUBJECT=mailto:you@yourdomain.com
Environment=PORT=8080
Environment=DB_HOST=localhost
Environment=DB_NAME=ligan_bas
Environment=DB_USER=ligan_bas
Environment=DB_PASSWORD=change-me
ExecStart=/usr/bin/java -Xmx384m -jar /opt/busalert/ligan-bas-0.1.0.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

Caddy (automatic HTTPS) `/etc/caddy/Caddyfile`
```
bus.yourdomain.com {
    reverse_proxy 127.0.0.1:8080
}
```

PostgreSQL on the VPS (once):
```bash
sudo apt install postgresql
sudo -u postgres psql -c "CREATE USER ligan_bas WITH PASSWORD 'change-me';"
sudo -u postgres psql -c "CREATE DATABASE ligan_bas OWNER ligan_bas;"
```
Tables are created on first start (`spring.jpa.hibernate.ddl-auto=update`). For a real production setup
switch to Flyway migrations. Back up the database **and** `data/vapid.properties`.

Keep Spring bound behind the proxy (firewall port 8080 from outside). Client IPs come from `X-Forwarded-For`
set by Caddy (trusted only from loopback/private addresses), which is what the rate limiter uses.

Memory note: the `rapid-bus-kl` static feed is large. If the VPS is small, give the JVM ~512 MB.

## API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/health` | `{ready, routes}` |
| GET | `/api/config` | VAPID public key |
| GET | `/api/routes` | `[{key, code, label}]` |
| GET | `/api/stops?route=KEY` | `[{id, name}]` |
| GET | `/api/eta?route=KEY&stop=ID` | `{etas:[{vehicle, stops_away, km, minutes}], note}` (30 req/min/IP) |
| POST | `/api/subscriptions` | body: `subscription, route, stop_id, days[0=Mon..6], start, end, interval_min(1/2/5/10)` → `{id, token}` |
| DELETE | `/api/subscriptions/{id}?token=…` | unsubscribe |

## Known limitations / ideas

* Estimates only (see above). LRT/MRT timetable support could be added from the `rapid-rail-kl` static feed.
* Realtime `trip_id`s don't always match the static ones, so a bus's direction is inferred from its position.
* Single instance (in-memory rate limiter and cache). To scale out: move those to Redis.
* Notification wording is in Malay; extract strings for i18n if needed.

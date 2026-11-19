# Ligan Bas – live bus alerts (Rapid KL)

Pick a bus route and stop, set a schedule (e.g. Mon–Fri 07:00–09:00, every 2 min), and get **Web Push**
notifications with an estimated arrival ("3 stops away, ~4 min"). Works on Android (Chrome) and iPhone
(Safari 16.4+, after "Add to Home Screen"). No accounts, no personal data stored.

Built with **Spring Boot 4 / Java 21** backend, **React + Vite + TypeScript + shadcn/ui** frontend, PostgreSQL.

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

* **Static data:** all 14 `api.data.gov.my` GTFS feeds — Prasarana (`rapid-bus-kl`, `rapid-bus-mrtfeeder`,
  `rapid-bus-penang`, `rapid-rail-kl`) and BAS.MY (9 cities) — cached per day.
* **Realtime data:** `api.data.gov.my/gtfs-realtime/vehicle-position/…`, protobuf, cached ~25 s. `rapid-rail-kl`
  has no realtime feed (static schedule only).
* **Push:** Web Push with a VAPID key pair generated on first start (`data/vapid.properties`, **back it up, never regenerate**).
* **Public-facing safeguards:** per-IP rate limits, caps on total/per-IP subscriptions, max 4 h window, push endpoint
  allow-list (FCM, Apple, Mozilla, Windows) to prevent SSRF, random unsubscribe token per subscription.

## Run locally

```bash
# needs JDK 21 + Maven + Docker + Node 20+   (macOS: brew install openjdk@21 maven node)
docker compose up -d                  # local PostgreSQL (db/user/password: ligan_bas)
mvn test                              # unit tests for the ETA logic (no database needed)
cd frontend && npm install && npm run build && cd ..   # builds into src/main/resources/static
mvn spring-boot:run
# open http://localhost:8080
```

For frontend-only iteration, `cd frontend && npm run dev` serves on its own port with `/api` proxied to `:8080`,
but **Web Push testing needs the production build** served from `:8080` directly — the service worker (`sw.js`)
must be at the same origin/scope as the API.

Web Push needs HTTPS, except on `localhost` (so local testing works in Chrome). iPhone requires the installed
Home-Screen app over real HTTPS, so test iOS on the VPS.

The first start downloads the static GTFS (can take a minute). `/api/health` shows `ready: true` when done.

## Deploy on a VPS

Runs as a Docker container (`Dockerfile` builds the frontend + jar in one multi-stage build) alongside a
Postgres container, both on an `app` Docker network that a shared Caddy container (handling HTTPS + other
projects on the same box) is already attached to.

```bash
# once, on the VPS
mkdir -p ~/projects/ligan-bas && cd ~/projects/ligan-bas
git clone https://github.com/bisyairi/ligan-bas.git .
docker network create app   # skip if it already exists (e.g. Caddy already uses it)
```

Create `~/projects/ligan-bas/.env.production` (not committed — holds real secrets):
```bash
DB_HOST=db
DB_PORT=5432
DB_NAME=ligan_bas
DB_USER=ligan_bas
DB_PASSWORD=change-me-strong
POSTGRES_DB=ligan_bas
POSTGRES_USER=ligan_bas
POSTGRES_PASSWORD=change-me-strong
VAPID_SUBJECT=mailto:you@yourdomain.com
PORT=8080
```
(`DB_PASSWORD` and `POSTGRES_PASSWORD` must match — one is read by the app, the other by the Postgres image.)

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

Add a site block to the shared Caddyfile (reverse-proxies by container name over the `app` network, not a
host port — `ligan-bas-api-1` is the default `<project folder>-<service>-1` name, confirm with
`docker compose ps`):
```
bus.yourdomain.com {
    reverse_proxy ligan-bas-api-1:8080
}
```
then reload Caddy (e.g. `docker exec caddy caddy reload --config /etc/caddy/Caddyfile`, or whatever the
existing Caddy container's reload process is).

**Redeploy after a code change:**
```bash
cd ~/projects/ligan-bas && git pull && docker compose -f docker-compose.prod.yml up -d --build
```

**Back up** the `data` named volume (holds `vapid.properties` — losing it invalidates every push subscription)
and the `pgdata` named volume (the database), e.g. `docker run --rm -v ligan-bas_data:/data -v $PWD:/backup
alpine tar czf /backup/data-backup.tgz /data`.

Tables are created on first start (`spring.jpa.hibernate.ddl-auto=update`). For a real production setup
switch to Flyway migrations.

Memory note: the `rapid-bus-kl` static feed is large. If the VPS is small, give the JVM more than the
Dockerfile's default `-Xmx384m`.

## API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/health` | `{ready, routes}` |
| GET | `/api/config` | VAPID public key |
| GET | `/api/routes` | `[{key, code, label}]` |
| GET | `/api/stops?route=KEY` | `[{id, name}]` |
| GET | `/api/eta?route=KEY&stop=ID` | `{etas:[{vehicle, stops_away, km, minutes}], note}` (30 req/min/IP) |
| GET | `/api/schedule?route=KEY&stop=ID&day=0-6` | `{day, times, note}` — static GTFS schedule, `day` defaults to today |
| GET | `/api/stops/search?q=name` | `[{id, name, routes:[{key, code, label}]}]` — find a stop by name across all providers |
| POST | `/api/subscriptions` | body: `subscription, route, stop_id, days[0=Mon..6], start, end, interval_min(1/2/5/10)` → `{id, token}` |
| DELETE | `/api/subscriptions/{id}?token=…` | unsubscribe |

## Known limitations / ideas

* Estimates only (see above). LRT/MRT timetable support could be added from the `rapid-rail-kl` static feed.
* Realtime `trip_id`s don't always match the static ones, so a bus's direction is inferred from its position.
* Single instance (in-memory rate limiter and cache). To scale out: move those to Redis.
* Notification wording is in Malay; extract strings for i18n if needed.

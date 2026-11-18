# CLAUDE.md – ligan-bas (handoff from a claude.ai chat)

Owner: Ri (Malaysian developer). **Reply in casual Manglish (Malay–English mix)**; formal written outputs (README, docs) in English.

## What this is
Public portfolio project: pick Rapid KL / Penang / BAS.MY bus routes + stops, set a schedule
(days, time window, interval), and receive **Web Push** notifications with an *estimated* arrival
("3 stop lagi, ~4 min"). No accounts, no personal data. Works on Android Chrome and iPhone
(installed PWA, iOS 16.4+, HTTPS only). Hosted on the owner's own Malaysian VPS + domain.

## Decisions already made (don't re-litigate)
- **Spring Boot 4.1.2**, **Java 21**, Maven, package `dev.bisyairi.liganbas`, artifact `ligan-bas`.
- **PostgreSQL** (docker-compose for dev). `ddl-auto=update` for now; Flyway later.
- **Web Push (VAPID)** straight from the VPS, no ntfy. VAPID keys auto-generated into `data/vapid.properties`
  (must be backed up, never regenerated).
- Boot 4 migration done per official guide: `spring-boot-starter-webmvc` + `spring-boot-starter-jackson`,
  Jackson 3 (`tools.jackson.databind.json.JsonMapper` in `PushService`; `@JsonProperty` annotations unchanged).
- Frontend currently vanilla JS PWA in `src/main/resources/static`. **Plan: rewrite in React + Vite + TypeScript**
  (`frontend/`, Vite proxy `/api` → :8080, build output copied into `static/`, keep a hand-written `public/sw.js`
  at root scope for Web Push).
- Public safeguards: per-IP rate limits, caps on subscriptions, max 4 h window, push endpoint host allow-list (anti-SSRF).
- ETA is an **estimate** from GPS + average speed (`busalert.avg-speed-kmh`, default 20). There is no official ETA feed.
  Never present it as official.

## STATUS: NEVER COMPILED
The whole Java code base was written without a compiler (the authoring sandbox could not reach Maven Central).
**First job: `mvn test`, fix compile errors, then `docker compose up -d && mvn spring-boot:run`, check `/api/health`.**
Highest-risk spots: `push/PushService.java` (library `nl.martijndwars:web-push` API calls, BouncyCastle key generation,
Jackson 3), `gtfs/NetworkService.java` (commons-csv parsing, memory), anything touched by Boot 4.0→4.1 differences
(the migration guide that was read was for 4.0).

## Data facts learned from the live API (api.data.gov.my)
- Static: `GET /gtfs-static/prasarana?category=<rapid-bus-kl|rapid-bus-mrtfeeder|rapid-bus-penang|rapid-rail-kl>`;
  BAS.MY: `/gtfs-static/mybas-<kangar|alor-setar|kota-bharu|kuala-terengganu|ipoh|melaka|johor|kuching>`,
  Seremban has two feeds `mybas-seremban-a` and `-b`.
- Realtime (vehicle positions only, protobuf, updated every 30 s):
  `GET /gtfs-realtime/vehicle-position/prasarana?category=<rapid-bus-kl|rapid-bus-mrtfeeder|rapid-bus-penang>`,
  `GET /gtfs-realtime/vehicle-position/mybas-<city>` (same city names as above, Seremban a/b).
- `rapid-rail-kl` has static only, **no stable realtime**; trip updates/alerts are not available yet.
- In Prasarana routes.txt `route_short_name` is empty; the code (e.g. `T811`) is in `route_long_name`.
- Static zips contain macOS junk (`__MACOSX/._*.txt`): skip those entries.
- `rapid-bus-kl`: route `T780` has static route_id `T7800`, while other realtime route_ids look like `U7720`.
  Realtime `trip_id` often does not exist in static (`trip_in_static=False`), so direction is inferred from position:
  nearest stop on the route pattern vs. the user's stop index.
- Penang: realtime trip_ids lack the service prefix (`weekday_`, `weekend_`, `<date>_`); match on the part after the prefix.
- ~2 % of `rapid-bus-kl` trips were removed from stop_times upstream.
- Test stop "BAYU PUTERI APARTMENT": stop_id `12000840` (mrtfeeder, route T811) and `1000236` (rapid-bus-kl, route T780).
  Late at night (≈23:00) there are almost no buses; test in the morning rush.

## Next features (agreed with the owner)
1. **Provider/route picker like a "Filter buses" panel**: provider cards with route count and a green "N live" badge
   (or "None now"), drill into routes, tick several routes ("2 of 201 routes picked"), search providers/routes.
   Providers: **Rapid KL** (= `rapid-bus-kl` + `rapid-bus-mrtfeeder` merged), **Rapid Penang**, **BAS.MY** (9 cities).
   Nadi Putra / PJ City Bus / Smart Selangor are **not** in this API, so out of scope.
2. Backend: `GET /api/providers` (name, route count, live vehicle count), `GET /api/routes?provider=` with live counts,
   generalise feeds (config table: static URL + realtime URL per feed), **lazy-load** static feeds (14 feeds is heavy;
   load on demand, cache), Penang trip-id matching.
3. **Multi-target subscriptions**: one subscription holds several (route, stop) pairs sharing one schedule.
   Stop IDs differ per feed, so the user picks a stop per route (suggest same-name stops automatically).
4. **One combined notification** per tick (single tag so it replaces the previous one), soonest buses first, max 3–4 lines.
5. React frontend (see above). Rename UI text from "Bus Alert" when the final public name is settled
   (candidate names discussed: Kejar Bas, Ngam-Ngam, Bila Bas?, BasKu; the owner is using `ligan-bas` technically).

## Operational notes
- Deploy: JAR + systemd + Caddy (HTTPS) on the VPS, PostgreSQL via apt. See README.md.
- Single-instance design (in-memory rate limiter and live-feed cache). Redis only if it ever needs to scale out.

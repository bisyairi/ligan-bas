package dev.bisyairi.liganbas.gtfs;

import dev.bisyairi.liganbas.AppProperties;
import dev.bisyairi.liganbas.gtfs.Gtfs.Pattern;
import dev.bisyairi.liganbas.gtfs.Gtfs.Route;
import dev.bisyairi.liganbas.gtfs.Gtfs.RouteRef;
import dev.bisyairi.liganbas.gtfs.Gtfs.Stop;
import dev.bisyairi.liganbas.gtfs.Gtfs.StopHit;
import dev.bisyairi.liganbas.gtfs.Gtfs.StopRef;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Loads Prasarana static GTFS (routes, stops, stop patterns) and answers lookups. */
@Service
public class NetworkService {
    public static final ZoneId KL = ZoneId.of("Asia/Kuala_Lumpur");
    public static final List<String> CATEGORIES = List.of(
            "rapid-bus-mrtfeeder", "rapid-bus-kl", "rapid-bus-penang", "rapid-rail-kl",
            "mybas-kangar", "mybas-alor-setar", "mybas-kota-bharu", "mybas-kuala-terengganu",
            "mybas-ipoh", "mybas-melaka", "mybas-johor", "mybas-kuching",
            "mybas-seremban-a", "mybas-seremban-b");

    /** BAS.MY feeds are served at a direct path; Prasarana feeds go through the shared "prasarana" endpoint. */
    static String staticUrl(String apiBase, String cat) {
        return cat.startsWith("mybas-")
                ? apiBase + "/gtfs-static/" + cat
                : apiBase + "/gtfs-static/prasarana?category=" + cat;
    }
    private static final Logger log = LoggerFactory.getLogger(NetworkService.class);

    record Snapshot(Map<String, Route> routes,
                    Map<String, Map<String, Stop>> stops,
                    Map<String, Map<String, String>> codeToId,
                    Map<String, Map<String, String>> tripRoute,
                    Map<String, Map<String, List<RouteRef>>> stopRoutes,
                    LocalDate loadedOn) {
    }

    private record SeqStop(int seq, String stopId, String time) {
    }

    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile Snapshot snap;

    public NetworkService(AppProperties props) {
        this.props = props;
    }

    // ---------- lifecycle ----------
    @EventListener(ApplicationReadyEvent.class)
    public void loadOnStartup() {
        CompletableFuture.runAsync(this::load);  // don't block startup on a big download
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Kuala_Lumpur")
    public void dailyRefresh() {
        load();
    }

    public boolean isReady() {
        return snap != null;
    }

    public int routeCount() {
        Snapshot s = snap;
        return s == null ? 0 : s.routes().size();
    }

    /** Test hook. */
    void install(Snapshot s) {
        this.snap = s;
    }

    public synchronized void load() {
        Map<String, Route> routes = new HashMap<>();
        Map<String, Map<String, Stop>> stops = new HashMap<>();
        Map<String, Map<String, String>> codeToId = new HashMap<>();
        Map<String, Map<String, String>> tripRoute = new HashMap<>();
        int ok = 0;
        for (String cat : CATEGORIES) {
            try {
                loadCategory(cat, routes, stops, codeToId, tripRoute);
                ok++;
            } catch (Exception e) {
                log.error("Failed to load static GTFS for {}", cat, e);
            }
        }
        if (ok > 0) {
            Map<String, Map<String, List<RouteRef>>> stopRoutes = new HashMap<>();
            for (Route r : routes.values()) {
                Map<String, List<RouteRef>> byStop = stopRoutes.computeIfAbsent(r.category(), k -> new HashMap<>());
                RouteRef ref = new RouteRef(r.key(), r.code(), r.label());
                for (Pattern p : r.patterns()) {
                    for (String id : p.ids) {
                        List<RouteRef> list = byStop.computeIfAbsent(id, k -> new ArrayList<>());
                        if (!list.contains(ref)) {
                            list.add(ref);
                        }
                    }
                }
            }
            snap = new Snapshot(routes, stops, codeToId, tripRoute, stopRoutes, LocalDate.now(KL));
            log.info("Loaded {} routes from {} feed(s)", routes.size(), ok);
        }
    }

    // ---------- download + parse ----------
    private Path download(String cat) throws IOException, InterruptedException {
        Path dir = Path.of(props.dataDir());
        Files.createDirectories(dir);
        Path target = dir.resolve(cat + "-" + LocalDate.now(KL) + ".zip");
        if (!Files.exists(target)) {
            log.info("Downloading static GTFS {}", cat);
            Path tmp = dir.resolve(target.getFileName() + ".part");
            HttpRequest req = HttpRequest.newBuilder(URI.create(staticUrl(props.apiBase(), cat)))
                    .timeout(Duration.ofMinutes(3)).GET().build();
            HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
            if (resp.statusCode() != 200) {
                Files.deleteIfExists(tmp);
                throw new IOException("HTTP " + resp.statusCode() + " for " + cat);
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            try (Stream<Path> old = Files.list(dir)) {  // drop older caches of this category
                for (Path p : old.toList()) {
                    String n = p.getFileName().toString();
                    if (n.startsWith(cat + "-") && n.endsWith(".zip") && !p.equals(target)) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
        return target;
    }

    /** GTFS times are "H:MM:SS" or "HH:MM:SS", hour can exceed 23 for a trip past midnight. Returns "HH:MM". */
    private static String normalizeTime(String raw) {
        String[] parts = raw.split(":");
        if (parts.length < 2) {
            return raw;
        }
        String hour = parts[0].length() == 1 ? "0" + parts[0] : parts[0];
        return hour + ":" + parts[1];
    }

    private static int parseTimeSeconds(String raw) {
        String[] p = raw.split(":");
        int h = Integer.parseInt(p[0]);
        int m = Integer.parseInt(p[1]);
        int s = p.length > 2 ? Integer.parseInt(p[2]) : 0;
        return h * 3600 + m * 60 + s;
    }

    private static String formatSeconds(int totalSec) {
        int h = totalSec / 3600;
        int m = (totalSec % 3600) / 60;
        return String.format("%02d:%02d", h, m);
    }

    private static ZipEntry find(ZipFile z, String name) {
        for (var en = z.entries(); en.hasMoreElements(); ) {
            ZipEntry e = en.nextElement();
            String base = e.getName().substring(e.getName().lastIndexOf('/') + 1);
            if (base.equals(name) && !e.getName().startsWith("__MACOSX") && !base.startsWith(".")) {
                return e;
            }
        }
        return null;
    }

    /** Opens a CSV file inside the zip (skipping a UTF-8 BOM if present). */
    private static CSVParser open(ZipFile z, String name) throws IOException {
        ZipEntry e = find(z, name);
        if (e == null) {
            throw new IOException(name + " missing from GTFS zip");
        }
        PushbackReader r = new PushbackReader(new InputStreamReader(z.getInputStream(e), StandardCharsets.UTF_8), 1);
        int c = r.read();
        if (c != 0xFEFF && c != -1) {
            r.unread(c);
        }
        Reader reader = new BufferedReader(r);
        return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader);
    }

    private void loadCategory(String cat, Map<String, Route> routes, Map<String, Map<String, Stop>> stopsByCat,
                              Map<String, Map<String, String>> codeToId,
                              Map<String, Map<String, String>> tripRoute) throws Exception {
        try (ZipFile z = new ZipFile(download(cat).toFile())) {
            Map<String, Stop> stops = new HashMap<>();
            try (CSVParser p = open(z, "stops.txt")) {
                for (CSVRecord r : p) {
                    stops.put(r.get("stop_id"), new Stop(r.get("stop_id"), r.get("stop_name"),
                            Double.parseDouble(r.get("stop_lat")), Double.parseDouble(r.get("stop_lon"))));
                }
            }
            stopsByCat.put(cat, stops);

            Map<String, String[]> routeInfo = new HashMap<>();  // route_id -> [code, label]
            Map<String, String> c2i = new HashMap<>();
            try (CSVParser p = open(z, "routes.txt")) {
                for (CSVRecord r : p) {
                    String shortName = r.isMapped("route_short_name") ? r.get("route_short_name").trim() : "";
                    String longName = r.isMapped("route_long_name") ? r.get("route_long_name").trim() : "";
                    String code = (shortName.isEmpty() ? longName : shortName).toUpperCase();
                    String label = !shortName.isEmpty() && !longName.isEmpty() && !longName.equalsIgnoreCase(code)
                            ? code + " – " + longName : code;
                    routeInfo.put(r.get("route_id"), new String[]{code, label});
                    c2i.put(code, r.get("route_id"));
                }
            }
            codeToId.put(cat, c2i);

            Map<String, String> t2r = new HashMap<>();
            Map<String, String> tripService = new HashMap<>();
            try (CSVParser p = open(z, "trips.txt")) {
                for (CSVRecord r : p) {
                    t2r.put(r.get("trip_id"), r.get("route_id"));
                    if (r.isMapped("service_id")) {
                        tripService.put(r.get("trip_id"), r.get("service_id"));
                    }
                }
            }
            tripRoute.put(cat, t2r);

            /* calendar.txt: which days each service_id runs. Used to filter the schedule down to today's
               trips only (calendar_dates.txt exceptions, e.g. holidays, are not accounted for). */
            Map<String, boolean[]> calendarByService = new HashMap<>();
            if (find(z, "calendar.txt") != null) {
                try (CSVParser p = open(z, "calendar.txt")) {
                    for (CSVRecord r : p) {
                        calendarByService.put(r.get("service_id"), new boolean[]{
                                "1".equals(r.get("monday")), "1".equals(r.get("tuesday")),
                                "1".equals(r.get("wednesday")), "1".equals(r.get("thursday")),
                                "1".equals(r.get("friday")), "1".equals(r.get("saturday")),
                                "1".equals(r.get("sunday"))});
                    }
                }
            }

            Map<String, List<SeqStop>> byTrip = new HashMap<>();
            try (CSVParser p = open(z, "stop_times.txt")) {
                for (CSVRecord r : p) {
                    byTrip.computeIfAbsent(r.get("trip_id"), k -> new ArrayList<>())
                            .add(new SeqStop(Integer.parseInt(r.get("stop_sequence")), r.get("stop_id").intern(),
                                    r.get("arrival_time")));
                }
            }

            /* Some feeds (e.g. rapid-bus-kl) express headway-based service via frequencies.txt: stop_times.txt
               then holds only one template trip per time block, and the real departures must be expanded. */
            Map<String, List<int[]>> freqByTrip = new HashMap<>();  // trip_id -> [startSec, endSec, headwaySec]
            if (find(z, "frequencies.txt") != null) {
                try (CSVParser p = open(z, "frequencies.txt")) {
                    for (CSVRecord r : p) {
                        int headway = Integer.parseInt(r.get("headway_secs"));
                        if (headway <= 0) {
                            continue;
                        }
                        freqByTrip.computeIfAbsent(r.get("trip_id"), k -> new ArrayList<>())
                                .add(new int[]{parseTimeSeconds(r.get("start_time")), parseTimeSeconds(r.get("end_time")), headway});
                    }
                }
            }

            Map<String, Set<List<String>>> patterns = new HashMap<>();
            // routeId -> day (0=Mon..6=Sun) -> stopId -> times
            Map<String, Map<Integer, Map<String, Set<String>>>> schedule = new HashMap<>();
            for (var e : byTrip.entrySet()) {
                String tripId = e.getKey();
                String routeId = t2r.get(tripId);
                if (routeId == null) {
                    continue;
                }
                List<SeqStop> list = e.getValue();
                list.sort(Comparator.comparingInt(SeqStop::seq));
                patterns.computeIfAbsent(routeId, k -> new LinkedHashSet<>())
                        .add(list.stream().map(SeqStop::stopId).toList());

                Map<String, Set<String>> tripTimes = new HashMap<>();  // stopId -> times, for this trip
                List<int[]> freqs = freqByTrip.get(tripId);
                if (freqs == null) {
                    for (SeqStop ss : list) {
                        if (ss.time() != null && !ss.time().isBlank()) {
                            tripTimes.computeIfAbsent(ss.stopId(), k -> new TreeSet<>()).add(normalizeTime(ss.time()));
                        }
                    }
                } else {
                    int tripStart = parseTimeSeconds(list.get(0).time());
                    for (SeqStop ss : list) {
                        if (ss.time() == null || ss.time().isBlank()) {
                            continue;
                        }
                        int offset = parseTimeSeconds(ss.time()) - tripStart;
                        Set<String> times = tripTimes.computeIfAbsent(ss.stopId(), k -> new TreeSet<>());
                        for (int[] block : freqs) {
                            for (int t = block[0]; t <= block[1]; t += block[2]) {
                                times.add(formatSeconds(t + offset));
                            }
                        }
                    }
                }

                boolean[] svcDays = calendarByService.get(tripService.get(tripId));
                Map<Integer, Map<String, Set<String>>> byDay = schedule.computeIfAbsent(routeId, k -> new HashMap<>());
                for (int day = 0; day < 7; day++) {
                    if (svcDays != null && !svcDays[day]) {  // unknown service: include on every day rather than hide
                        continue;
                    }
                    Map<String, Set<String>> byStop = byDay.computeIfAbsent(day, k -> new HashMap<>());
                    for (var te : tripTimes.entrySet()) {
                        byStop.computeIfAbsent(te.getKey(), k -> new TreeSet<>()).addAll(te.getValue());
                    }
                }
            }
            byTrip.clear();

            for (var e : patterns.entrySet()) {
                String[] info = routeInfo.get(e.getKey());
                if (info == null) {
                    continue;
                }
                List<Pattern> pats = e.getValue().stream()
                        .sorted((a, b) -> Integer.compare(b.size(), a.size()))
                        .map(ids -> new Pattern(ids, stops))
                        .filter(pt -> pt.ids.length > 0)
                        .toList();
                if (!pats.isEmpty()) {
                    String key = cat + ":" + e.getKey();
                    Map<Integer, Map<String, List<String>>> scheduleByDay = new HashMap<>();
                    for (var de : schedule.getOrDefault(e.getKey(), Map.of()).entrySet()) {
                        Map<String, List<String>> timesByStop = new HashMap<>();
                        for (var se : de.getValue().entrySet()) {
                            timesByStop.put(se.getKey(), List.copyOf(se.getValue()));
                        }
                        scheduleByDay.put(de.getKey(), timesByStop);
                    }
                    routes.put(key, new Route(key, cat, e.getKey(), info[0], info[1], pats, scheduleByDay));
                }
            }
        }
    }

    // ---------- lookups ----------
    public List<RouteRef> routeList() {
        Snapshot s = snap;
        return s.routes().values().stream()
                .map(r -> new RouteRef(r.key(), r.code(), r.label()))
                .sorted(Comparator.comparing(RouteRef::label))
                .toList();
    }

    public Route route(String key) {
        Snapshot s = snap;
        return s == null ? null : s.routes().get(key);
    }

    public List<StopRef> stopList(String routeKey) {
        Snapshot s = snap;
        Route r = s.routes().get(routeKey);
        if (r == null) {
            return null;
        }
        Map<String, StopRef> out = new LinkedHashMap<>();
        Map<String, Stop> stops = s.stops().get(r.category());
        for (Pattern p : r.patterns()) {  // longest pattern first keeps the order sensible
            for (String id : p.ids) {
                out.computeIfAbsent(id, k -> new StopRef(k, stops.get(k).name()));
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(out.values()));
    }

    /** Finds stops by name (any provider) and lists which routes serve each — for users who know the stop, not the route. */
    public List<StopHit> searchStops(String query, int limit) {
        Snapshot s = snap;
        if (s == null || query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.trim().toLowerCase();
        List<StopHit> out = new ArrayList<>();
        for (var catEntry : s.stops().entrySet()) {
            Map<String, List<RouteRef>> byStop = s.stopRoutes().getOrDefault(catEntry.getKey(), Map.of());
            for (Stop stop : catEntry.getValue().values()) {
                if (!stop.name().toLowerCase().contains(q)) {
                    continue;
                }
                List<RouteRef> served = byStop.get(stop.id());
                if (served == null || served.isEmpty()) {
                    continue;
                }
                out.add(new StopHit(stop.id(), stop.name(), served));
                if (out.size() >= limit) {
                    return out;
                }
            }
        }
        return out;
    }

    public boolean routeServesStop(String routeKey, String stopId) {
        Route r = route(routeKey);
        return r != null && r.patterns().stream().anyMatch(p -> p.indexOf(stopId) >= 0);
    }

    /** Static schedule (HH:MM) for a route at a stop on the given day (0=Monday .. 6=Sunday). */
    public List<String> scheduledTimes(String routeKey, String stopId, int dayOfWeek) {
        Route r = route(routeKey);
        if (r == null) {
            return List.of();
        }
        return r.scheduleByDay().getOrDefault(dayOfWeek, Map.of()).getOrDefault(stopId, List.of());
    }

    public String stopName(String routeKey, String stopId) {
        Route r = route(routeKey);
        if (r == null) {
            return stopId;
        }
        Stop s = snap.stops().get(r.category()).get(stopId);
        return s == null ? stopId : s.name();
    }

    /** Maps a route code (e.g. "T811") to the static route_id within a category, or null. */
    String routeIdForCode(String category, String code) {
        Snapshot s = snap;
        return s.codeToId().getOrDefault(category, Map.of()).get(code.toUpperCase());
    }

    /** Maps a realtime trip_id to its static route_id, or null. */
    String routeIdForTrip(String category, String tripId) {
        Snapshot s = snap;
        return s.tripRoute().getOrDefault(category, Map.of()).get(tripId);
    }
}

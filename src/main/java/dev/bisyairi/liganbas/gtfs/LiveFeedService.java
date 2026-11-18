package dev.bisyairi.liganbas.gtfs;

import com.google.transit.realtime.GtfsRealtime;
import dev.bisyairi.liganbas.AppProperties;
import dev.bisyairi.liganbas.gtfs.Gtfs.Vehicle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches GTFS Realtime vehicle positions. The upstream feed refreshes every ~30s, so results are cached
 * per category: 1000 subscribers still cost one upstream request per 30s per category.
 */
@Service
public class LiveFeedService {
    private static final Logger log = LoggerFactory.getLogger(LiveFeedService.class);
    private static final long FRESH_MS = 25_000;
    private static final long STALE_LIMIT_MS = 180_000;
    /** No stable realtime feed exists yet for these categories (see CLAUDE.md). */
    private static final Set<String> NO_REALTIME = Set.of("rapid-rail-kl");

    private record Cached(long at, List<Vehicle> vehicles) {
    }

    private final AppProperties props;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public LiveFeedService(AppProperties props) {
        this.props = props;
    }

    public List<Vehicle> live(String category) throws IOException {
        if (NO_REALTIME.contains(category)) {
            return List.of();
        }
        Object lock = locks.computeIfAbsent(category, k -> new Object());
        synchronized (lock) {  // one fetch at a time per category
            Cached c = cache.get(category);
            long now = System.currentTimeMillis();
            if (c != null && now - c.at() < FRESH_MS) {
                return c.vehicles();
            }
            try {
                List<Vehicle> fresh = fetch(category);
                cache.put(category, new Cached(now, fresh));
                return fresh;
            } catch (IOException | RuntimeException e) {
                if (c != null && now - c.at() < STALE_LIMIT_MS) {
                    log.warn("Live fetch failed for {} ({}), serving slightly stale data", category, e.toString());
                    return c.vehicles();
                }
                throw e instanceof IOException io ? io : new IOException(e);
            }
        }
    }

    private List<Vehicle> fetch(String category) throws IOException {
        String url = category.startsWith("mybas-")
                ? props.apiBase() + "/gtfs-realtime/vehicle-position/" + category
                : props.apiBase() + "/gtfs-realtime/vehicle-position/prasarana?category=" + category;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60)).GET().build();
        try {
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                if (resp.statusCode() != 200) {
                    throw new IOException("HTTP " + resp.statusCode() + " from realtime feed");
                }
                GtfsRealtime.FeedMessage feed = GtfsRealtime.FeedMessage.parseFrom(in);
                return feed.getEntityList().stream()
                        .filter(GtfsRealtime.FeedEntity::hasVehicle)
                        .map(GtfsRealtime.FeedEntity::getVehicle)
                        .map(v -> new Vehicle(
                                v.getTrip().getRouteId(), v.getTrip().getTripId(),
                                v.getPosition().getLatitude(), v.getPosition().getLongitude(),
                                v.getVehicle().hasLabel() ? v.getVehicle().getLabel() : v.getVehicle().getId()))
                        .toList();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}

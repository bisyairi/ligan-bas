package dev.bisyairi.liganbas.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.bisyairi.liganbas.AppProperties;
import dev.bisyairi.liganbas.gtfs.EtaService;
import dev.bisyairi.liganbas.gtfs.Gtfs.Eta;
import dev.bisyairi.liganbas.gtfs.Gtfs.Route;
import dev.bisyairi.liganbas.gtfs.Gtfs.RouteRef;
import dev.bisyairi.liganbas.gtfs.Gtfs.StopHit;
import dev.bisyairi.liganbas.gtfs.Gtfs.StopRef;
import dev.bisyairi.liganbas.gtfs.NetworkService;
import dev.bisyairi.liganbas.push.PushService;
import dev.bisyairi.liganbas.sub.Subscription;
import dev.bisyairi.liganbas.sub.SubscriptionRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class ApiController {
    static final int MAX_WINDOW_MIN = 240;  // one alert window: at most 4 hours
    static final Set<Integer> INTERVALS = Set.of(1, 2, 5, 10);
    /** Only genuine browser push services: stops a fake "endpoint" turning this server into a request relay (SSRF). */
    static final List<String> PUSH_HOSTS = List.of("fcm.googleapis.com", "push.apple.com",
            "push.services.mozilla.com", "notify.windows.com");

    public record SubIn(
            @NotNull Map<String, Object> subscription,
            @NotBlank String route,
            @JsonProperty("stop_id") @NotBlank String stopId,
            @NotEmpty @Size(max = 7) List<Integer> days,
            @NotBlank String start,
            @NotBlank String end,
            @JsonProperty("interval_min") int intervalMin) {
    }

    private final NetworkService network;
    private final EtaService etas;
    private final PushService push;
    private final SubscriptionRepository repo;
    private final RateLimiter limiter;
    private final AppProperties props;
    private final SecureRandom random = new SecureRandom();

    public ApiController(NetworkService network, EtaService etas, PushService push, SubscriptionRepository repo,
                         RateLimiter limiter, AppProperties props) {
        this.network = network;
        this.etas = etas;
        this.push = push;
        this.repo = repo;
        this.limiter = limiter;
        this.props = props;
    }

    private void needReady() {
        if (!network.isReady()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Data GTFS sedang dimuat turun, cuba lagi dalam seminit.");
        }
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ready", network.isReady(), "routes", network.routeCount());
    }

    @GetMapping("/config")
    public Map<String, String> config() {
        return Map.of("vapidPublicKey", push.publicKey());
    }

    @GetMapping("/routes")
    public List<RouteRef> routes() {
        needReady();
        return network.routeList();
    }

    @GetMapping("/stops")
    public List<StopRef> stops(@RequestParam String route) {
        needReady();
        List<StopRef> out = network.stopList(route);
        if (out == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Route tak jumpa");
        }
        return out;
    }

    @GetMapping("/stops/search")
    public List<StopHit> stopSearch(@RequestParam String q) {
        needReady();
        return network.searchStops(q, 20);
    }

    @GetMapping("/eta")
    public Map<String, Object> eta(@RequestParam String route, @RequestParam String stop, HttpServletRequest req) {
        needReady();
        limiter.check("eta", req.getRemoteAddr(), 30, 60);
        if (!network.routeServesStop(route, stop)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Route/stop tak sah");
        }
        try {
            List<Eta> list = etas.eta(route, stop, 3);
            return Map.of("etas", list, "note", "Anggaran dari GPS bas, bukan ETA rasmi.");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Tak dapat ambil data live dari api.data.gov.my");
        }
    }

    @GetMapping("/schedule")
    public Map<String, Object> schedule(@RequestParam String route, @RequestParam String stop,
                                        @RequestParam(required = false) Integer day) {
        needReady();
        if (!network.routeServesStop(route, stop)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Route/stop tak sah");
        }
        int dayOfWeek = day != null && day >= 0 && day <= 6 ? day
                : LocalDate.now(NetworkService.KL).getDayOfWeek().getValue() - 1;  // Monday=0 .. Sunday=6
        return Map.of(
                "day", dayOfWeek,
                "times", network.scheduledTimes(route, stop, dayOfWeek),
                "note", "Jadual statik GTFS, bukan masa sebenar.");
    }

    @PostMapping("/subscriptions")
    public Map<String, String> subscribe(@Valid @RequestBody SubIn body, HttpServletRequest req) {
        needReady();
        String ip = req.getRemoteAddr();
        limiter.check("sub", ip, 5, 3600);

        Object ep = body.subscription().get("endpoint");
        String endpoint = ep instanceof String s ? s : "";
        Object keysObj = body.subscription().get("keys");
        @SuppressWarnings("unchecked")
        Map<String, Object> keys = keysObj instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String p256dh = keys.get("p256dh") instanceof String s ? s : "";
        String auth = keys.get("auth") instanceof String s ? s : "";
        if (!validPushEndpoint(endpoint)) {
            throw unprocessable("Push endpoint tak sah");
        }
        if (p256dh.isBlank() || auth.isBlank() || p256dh.length() > 200 || auth.length() > 100) {
            throw unprocessable("Push keys tak lengkap");
        }
        if (!network.routeServesStop(body.route(), body.stopId())) {
            throw unprocessable("Route/stop tak sah");
        }
        if (body.days().stream().anyMatch(d -> d == null || d < 0 || d > 6)) {
            throw unprocessable("Hari tak sah");
        }
        if (!INTERVALS.contains(body.intervalMin())) {
            throw unprocessable("Interval mesti salah satu dari " + new TreeSet<>(INTERVALS));
        }
        int start = minutes(body.start()), end = minutes(body.end());
        if (end - start <= 0 || end - start > MAX_WINDOW_MIN) {
            throw unprocessable("Tetingkap masa mesti 1 hingga " + MAX_WINDOW_MIN / 60 + " jam, dalam hari yang sama");
        }
        if (repo.count() >= props.maxSubsTotal() || repo.countByIp(ip) >= props.maxSubsPerIp()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Had subscription dicapai");
        }
        if (repo.existsByEndpoint(endpoint)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Peranti ni dah ada subscription. Padam yang lama dulu.");
        }

        // Send a welcome push first: proves the subscription works before we store it.
        Route r = network.route(body.route());
        String stopName = network.stopName(body.route(), body.stopId());
        PushService.Result res = push.send(endpoint, p256dh, auth, "Bus Alert aktif ✅",
                r.code() + " · " + Text.title(stopName) + "\nNotifikasi akan dihantar ikut jadual kau.", "welcome");
        if (res != PushService.Result.OK) {
            throw unprocessable("Subscription push dah tak sah atau tak dapat dihubungi");
        }

        String id = token(8), secret = token(24);
        String days = body.days().stream().distinct().sorted().map(String::valueOf).collect(Collectors.joining(","));
        try {
            repo.save(new Subscription(id, secret, endpoint, p256dh, auth, body.route(), body.stopId(), days,
                    start, end, body.intervalMin(), ip));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Peranti ni dah ada subscription. Padam yang lama dulu.");
        }
        return Map.of("id", id, "token", secret);
    }

    @DeleteMapping("/subscriptions/{id}")
    public Map<String, Boolean> unsubscribe(@PathVariable String id, @RequestParam String token) {
        Subscription s = repo.findById(id).orElse(null);
        boolean match = s != null && MessageDigest.isEqual(
                s.getToken().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        if (!match) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tak jumpa");
        }
        repo.delete(s);
        return Map.of("ok", true);
    }

    // ---------- helpers ----------
    static boolean validPushEndpoint(String endpoint) {
        if (endpoint.length() > 600 || !endpoint.startsWith("https://")) {
            return false;
        }
        try {
            String host = URI.create(endpoint).getHost();
            return host != null && PUSH_HOSTS.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static int minutes(String hhmm) {
        try {
            String[] p = hhmm.split(":");
            int v = Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
            if (p.length != 2 || v < 0 || v >= 24 * 60) {
                throw new IllegalArgumentException();
            }
            return v;
        } catch (RuntimeException e) {
            throw unprocessable("Masa tak sah: " + hhmm);
        }
    }

    static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    private String token(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}

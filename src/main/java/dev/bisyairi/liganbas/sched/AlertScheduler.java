package dev.bisyairi.liganbas.sched;

import dev.bisyairi.liganbas.gtfs.EtaService;
import dev.bisyairi.liganbas.gtfs.Gtfs.Eta;
import dev.bisyairi.liganbas.gtfs.Gtfs.Route;
import dev.bisyairi.liganbas.gtfs.NetworkService;
import dev.bisyairi.liganbas.push.PushService;
import dev.bisyairi.liganbas.sub.Subscription;
import dev.bisyairi.liganbas.sub.SubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Every 30s: for each subscription whose schedule is "on" right now, compute ETAs and push them. */
@Component
public class AlertScheduler {
    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);

    private final SubscriptionRepository repo;
    private final NetworkService network;
    private final EtaService etas;
    private final PushService push;

    public AlertScheduler(SubscriptionRepository repo, NetworkService network, EtaService etas, PushService push) {
        this.repo = repo;
        this.network = network;
        this.etas = etas;
        this.push = push;
    }

    @Scheduled(fixedRate = 30_000, initialDelay = 20_000)
    public void tick() {
        if (!network.isReady()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(NetworkService.KL);
        int day = now.getDayOfWeek().getValue() - 1;  // Monday = 0
        int minuteOfDay = now.getHour() * 60 + now.getMinute();
        long ts = System.currentTimeMillis();
        Map<String, List<Eta>> cache = new HashMap<>();  // same route+stop is computed once per tick

        for (Subscription s : repo.findAll()) {
            try {
                if (!activeDays(s).contains(day) || minuteOfDay < s.getStartMin() || minuteOfDay >= s.getEndMin()) {
                    continue;
                }
                if (ts - s.getLastSent() < s.getIntervalMin() * 60_000L - 20_000L) {
                    continue;
                }
                Route route = network.route(s.getRouteKey());
                if (route == null) {
                    continue;
                }
                List<Eta> list = cache.get(s.getRouteKey() + "|" + s.getStopId());
                if (list == null) {
                    list = etas.eta(s.getRouteKey(), s.getStopId(), 3);
                    cache.put(s.getRouteKey() + "|" + s.getStopId(), list);
                }
                if (list.isEmpty()) {
                    continue;  // nothing approaching: stay quiet
                }
                PushService.Result res = push.send(s.getEndpoint(), s.getP256dh(), s.getAuth(),
                        route.code() + " · " + dev.bisyairi.liganbas.web.Text.title(network.stopName(s.getRouteKey(), s.getStopId())),
                        body(list, now), "bus-eta");
                switch (res) {
                    case OK -> {
                        s.setLastSent(ts);
                        repo.save(s);
                    }
                    case GONE -> repo.delete(s);  // user revoked permission or the subscription expired
                    case ERROR -> { /* temporary problem: retry next tick */ }
                }
            } catch (Exception e) {
                log.warn("Alert for subscription {} failed: {}", s.getId(), e.toString());
            }
        }
    }

    private static Set<Integer> activeDays(Subscription s) {
        return Arrays.stream(s.getDays().split(",")).filter(x -> !x.isBlank()).map(Integer::parseInt).collect(Collectors.toSet());
    }

    private static String body(List<Eta> list, ZonedDateTime now) {
        StringBuilder sb = new StringBuilder();
        for (Eta e : list) {
            sb.append(e.stopsAway() <= 0 ? "hampir sampai" : e.stopsAway() + " stop lagi")
                    .append(" · ~").append(e.minutes()).append(" min\n");
        }
        return sb.append("(anggaran, ").append(String.format("%02d:%02d", now.getHour(), now.getMinute())).append(")").toString();
    }
}

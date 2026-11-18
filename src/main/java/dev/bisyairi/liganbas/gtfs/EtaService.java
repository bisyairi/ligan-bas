package dev.bisyairi.liganbas.gtfs;

import dev.bisyairi.liganbas.AppProperties;
import dev.bisyairi.liganbas.gtfs.Gtfs.Eta;
import dev.bisyairi.liganbas.gtfs.Gtfs.Pattern;
import dev.bisyairi.liganbas.gtfs.Gtfs.Route;
import dev.bisyairi.liganbas.gtfs.Gtfs.Vehicle;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Estimates when buses reach a stop. There is no official ETA feed yet, so this is an estimate:
 * for each live bus on the route, find the nearest stop on the route pattern, add up the distance
 * along the stops to the target stop, and divide by an assumed average speed.
 */
@Service
public class EtaService {
    private final NetworkService network;
    private final LiveFeedService live;
    private final AppProperties props;

    public EtaService(NetworkService network, LiveFeedService live, AppProperties props) {
        this.network = network;
        this.live = live;
        this.props = props;
    }

    public List<Eta> eta(String routeKey, String stopId, int limit) throws IOException {
        Route route = network.route(routeKey);
        if (route == null) {
            return List.of();
        }
        List<Pattern> serving = route.patterns().stream().filter(p -> p.indexOf(stopId) >= 0).toList();
        if (serving.isEmpty()) {
            return List.of();
        }
        List<Eta> out = new ArrayList<>();
        for (Vehicle v : live.live(route.category())) {
            if (!onRoute(v, route)) {
                continue;
            }
            double bestKm = Double.MAX_VALUE;
            int bestStops = 0;
            for (Pattern p : serving) {
                int me = p.indexOf(stopId);
                int bus = nearestIndex(p, v.lat(), v.lon());
                if (bus > me) {
                    continue;  // already passed this stop
                }
                double km = Gtfs.haversineKm(v.lat(), v.lon(), p.lat[bus], p.lon[bus]);
                for (int i = bus; i < me; i++) {
                    km += Gtfs.haversineKm(p.lat[i], p.lon[i], p.lat[i + 1], p.lon[i + 1]);
                }
                if (km < bestKm) {
                    bestKm = km;
                    bestStops = me - bus;
                }
            }
            if (bestKm != Double.MAX_VALUE) {
                int minutes = (int) Math.max(1, Math.round(bestKm / props.avgSpeedKmh() * 60));
                out.add(new Eta(v.label(), bestStops, Math.round(bestKm * 100) / 100.0, minutes));
            }
        }
        out.sort(Comparator.comparingDouble(Eta::km));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** The realtime feed is not always consistent with static IDs, so try route_id, route code, then trip_id. */
    private boolean onRoute(Vehicle v, Route route) {
        if (route.routeId().equals(v.routeId())) {
            return true;
        }
        if (v.routeId() != null && route.routeId().equals(network.routeIdForCode(route.category(), v.routeId()))) {
            return true;
        }
        return route.routeId().equals(network.routeIdForTrip(route.category(), v.tripId()));
    }

    private static int nearestIndex(Pattern p, double lat, double lon) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < p.ids.length; i++) {
            double d = Gtfs.haversineKm(lat, lon, p.lat[i], p.lon[i]);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }
}

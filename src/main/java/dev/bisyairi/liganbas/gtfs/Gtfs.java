package dev.bisyairi.liganbas.gtfs;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Plain data types shared by the GTFS services. */
public final class Gtfs {
    private Gtfs() {
    }

    public record Stop(String id, String name, double lat, double lon) {
    }

    public record StopRef(String id, String name) {
    }

    public record RouteRef(String key, String code, String label) {
    }

    /** A stop found by name search, with the routes that serve it. */
    public record StopHit(String id, String name, List<RouteRef> routes) {
    }

    /** A live bus position from the GTFS Realtime feed. */
    public record Vehicle(String routeId, String tripId, double lat, double lon, String label) {
    }

    /** One upcoming bus for a given stop. */
    public record Eta(String vehicle,
                      @JsonProperty("stops_away") int stopsAway,
                      double km,
                      int minutes) {
    }

    /** An ordered list of stops that some trips of a route follow. */
    public static final class Pattern {
        public final String[] ids;
        public final double[] lat;
        public final double[] lon;
        private final Map<String, Integer> index = new HashMap<>();

        public Pattern(List<String> stopIds, Map<String, Stop> stops) {
            List<String> known = stopIds.stream().filter(stops::containsKey).toList();
            this.ids = known.toArray(new String[0]);
            this.lat = new double[ids.length];
            this.lon = new double[ids.length];
            for (int i = 0; i < ids.length; i++) {
                Stop s = stops.get(ids[i]);
                lat[i] = s.lat();
                lon[i] = s.lon();
                index.putIfAbsent(ids[i], i);
            }
        }

        /** Index of the stop in this pattern, or -1 if the pattern does not serve it. */
        public int indexOf(String stopId) {
            return index.getOrDefault(stopId, -1);
        }
    }

    /** scheduleByDay: day of week (0=Monday .. 6=Sunday) -> stop id -> sorted scheduled times ("HH:MM"). */
    public record Route(String key, String category, String routeId, String code, String label,
                        List<Pattern> patterns, Map<Integer, Map<String, List<String>>> scheduleByDay) {
    }

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6371 * Math.asin(Math.sqrt(h));
    }
}

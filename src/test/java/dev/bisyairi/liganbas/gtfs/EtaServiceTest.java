package dev.bisyairi.liganbas.gtfs;

import dev.bisyairi.liganbas.AppProperties;
import dev.bisyairi.liganbas.gtfs.Gtfs.Eta;
import dev.bisyairi.liganbas.gtfs.Gtfs.Pattern;
import dev.bisyairi.liganbas.gtfs.Gtfs.Route;
import dev.bisyairi.liganbas.gtfs.Gtfs.Stop;
import dev.bisyairi.liganbas.gtfs.Gtfs.Vehicle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Stops A-B-C-D are ~1.1 km apart along a line of latitude... well, longitude. Target stop is C. */
class EtaServiceTest {
    private static final String CAT = "rapid-bus-mrtfeeder";
    private EtaService service;
    private LiveFeedService live;

    @BeforeEach
    void setUp() throws IOException {
        Map<String, Stop> stops = Map.of(
                "A", new Stop("A", "Stop A", 3.1000, 101.6000),
                "B", new Stop("B", "Stop B", 3.1000, 101.6100),
                "C", new Stop("C", "Stop C", 3.1000, 101.6200),
                "D", new Stop("D", "Stop D", 3.1000, 101.6300));
        Route route = new Route(CAT + ":R1", CAT, "R1", "T811", "T811",
                List.of(new Pattern(List.of("A", "B", "C", "D"), stops)), Map.of());
        AppProperties props = new AppProperties("./data", "mailto:x@example.com", 300, 5, 20, "https://example.invalid");
        NetworkService network = new NetworkService(props);
        network.install(new NetworkService.Snapshot(
                Map.of(route.key(), route), Map.of(CAT, stops),
                Map.of(CAT, Map.of("T811", "R1")), Map.of(CAT, Map.of("trip-1", "R1")), Map.of(), LocalDate.now()));
        live = mock(LiveFeedService.class);
        service = new EtaService(network, live, props);
    }

    @Test
    void busBeforeStopIsCountedInStops() throws IOException {
        // right next to stop A: two stops (A->B->C) before reaching C
        when(live.live(CAT)).thenReturn(List.of(new Vehicle("R1", "t", 3.1000, 101.6001, "BUS1")));
        List<Eta> out = service.eta(CAT + ":R1", "C", 3);
        assertEquals(1, out.size());
        assertEquals(2, out.get(0).stopsAway());
        assertTrue(out.get(0).km() > 2.0 && out.get(0).km() < 2.5, "km was " + out.get(0).km());
        assertTrue(out.get(0).minutes() >= 5);
    }

    @Test
    void busThatAlreadyPassedIsIgnored() throws IOException {
        when(live.live(CAT)).thenReturn(List.of(new Vehicle("R1", "t", 3.1000, 101.6299, "BUS2")));  // near D
        assertTrue(service.eta(CAT + ":R1", "C", 3).isEmpty());
    }

    @Test
    void matchesByRouteCodeAndTripAndSortsByDistance() throws IOException {
        when(live.live(CAT)).thenReturn(List.of(
                new Vehicle("T811", "x", 3.1000, 101.6001, "FAR"),     // feed uses the route code
                new Vehicle("zzz", "trip-1", 3.1000, 101.6199, "NEAR"), // matched through trip_id
                new Vehicle("OTHER", "other", 3.1000, 101.6150, "NOT-MINE")));
        List<Eta> out = service.eta(CAT + ":R1", "C", 3);
        assertEquals(List.of("NEAR", "FAR"), out.stream().map(Eta::vehicle).toList());
        assertEquals(0, out.get(0).stopsAway());
    }
}

package dev.bisyairi.liganbas.web;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Tiny in-memory sliding-window limiter (per bucket + client IP). Fine for a single-instance deployment. */
@Component
public class RateLimiter {
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public void check(String bucket, String ip, int limit, int perSeconds) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(bucket + "|" + ip, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > perSeconds * 1000L) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Terlalu banyak request, cuba lagi sebentar.");
            }
            q.addLast(now);
        }
    }

    @Scheduled(fixedRate = 600_000)
    void cleanup() {  // forget idle clients so the map cannot grow forever
        long now = System.currentTimeMillis();
        hits.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return e.getValue().isEmpty() || now - e.getValue().peekLast() > 3_600_000L;
            }
        });
    }
}

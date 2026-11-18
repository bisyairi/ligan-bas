package dev.bisyairi.liganbas.sub;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One alert schedule for one browser/device. */
@Entity
@Table(name = "subs")
public class Subscription {
    @Id
    private String id;
    @Column(nullable = false)
    private String token;
    @Column(nullable = false, unique = true, length = 700)
    private String endpoint;
    @Column(nullable = false, length = 200)
    private String p256dh;
    @Column(nullable = false, length = 100)
    private String auth;
    @Column(nullable = false)
    private String routeKey;
    @Column(nullable = false)
    private String stopId;
    /** Comma separated, Monday = 0 (same as the UI). */
    @Column(nullable = false)
    private String days;
    private int startMin;
    private int endMin;
    private int intervalMin;
    private String ip;
    private long created;
    private long lastSent;

    protected Subscription() {
    }

    public Subscription(String id, String token, String endpoint, String p256dh, String auth, String routeKey,
                        String stopId, String days, int startMin, int endMin, int intervalMin, String ip) {
        this.id = id;
        this.token = token;
        this.endpoint = endpoint;
        this.p256dh = p256dh;
        this.auth = auth;
        this.routeKey = routeKey;
        this.stopId = stopId;
        this.days = days;
        this.startMin = startMin;
        this.endMin = endMin;
        this.intervalMin = intervalMin;
        this.ip = ip;
        this.created = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public String getToken() { return token; }
    public String getEndpoint() { return endpoint; }
    public String getP256dh() { return p256dh; }
    public String getAuth() { return auth; }
    public String getRouteKey() { return routeKey; }
    public String getStopId() { return stopId; }
    public String getDays() { return days; }
    public int getStartMin() { return startMin; }
    public int getEndMin() { return endMin; }
    public int getIntervalMin() { return intervalMin; }
    public String getIp() { return ip; }
    public long getLastSent() { return lastSent; }
    public void setLastSent(long lastSent) { this.lastSent = lastSent; }
}

package dev.bisyairi.liganbas;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "busalert")
public record AppProperties(
        @DefaultValue("./data") String dataDir,
        @DefaultValue("mailto:admin@example.com") String vapidSubject,
        @DefaultValue("300") int maxSubsTotal,
        @DefaultValue("5") int maxSubsPerIp,
        @DefaultValue("20") double avgSpeedKmh,
        @DefaultValue("https://api.data.gov.my") String apiBase) {
}

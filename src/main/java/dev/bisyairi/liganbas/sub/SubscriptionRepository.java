package dev.bisyairi.liganbas.sub;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, String> {
    long countByIp(String ip);
}

package com.igirepay.service;

import com.igirepay.model.IdempotencyRecord;
import com.igirepay.model.PaymentRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IdempotencyStore {

    private final ConcurrentHashMap<String, IdempotencyRecord> store =
            new ConcurrentHashMap<>();

    @Value("${idempotency.ttl-hours:24}")
    private int ttlHours;

    public IdempotencyRecord putIfAbsentOrExpired(String key, PaymentRequest request) {
        Instant now = Instant.now();
        IdempotencyRecord newRecord = new IdempotencyRecord(
                key, request, now, now.plus(Duration.ofHours(ttlHours)));

        return store.compute(key, (k, existing) -> {
            if (existing == null || existing.isExpired()) {
                return newRecord;
            }
            return existing;
        });
    }

    public IdempotencyRecord get(String key) {
        IdempotencyRecord record = store.get(key);
        return (record != null && !record.isExpired()) ? record : null;
    }

    @Scheduled(fixedDelay = 600_000)
    public void evictExpiredEntries() {
        int before = store.size();
        store.entrySet().removeIf(e -> e.getValue().isExpired());
        int removed = before - store.size();
        if (removed > 0) {
            System.out.printf("[IdempotencyStore] Evicted %d expired record(s). Store size: %d%n",
                    removed, store.size());
        }
    }

    public int size() { return store.size(); }
    public void clear() { store.clear(); }
}
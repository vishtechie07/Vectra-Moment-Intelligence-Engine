package com.vectramoment.metrics;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ApiMetricsService {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public void increment(String key) {
        if (key == null || key.isBlank()) return;
        counters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }

    public Map<String, Long> snapshot() {
        return counters.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get()));
    }
}


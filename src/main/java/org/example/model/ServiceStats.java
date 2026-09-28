package org.example.model;

import java.util.Map;

/** Statistics calculated from HTTP request log entries for one service. */
public record ServiceStats(
        String service,
        long requestCount,
        long serverErrorCount,
        double serverErrorRate,
        Long p95LatencyMs,
        Map<Integer, Long> hourlyDistribution
) {
}

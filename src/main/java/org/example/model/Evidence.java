package org.example.model;

import java.time.Instant;

/** A small, concrete sample that supports a detected anomaly. */
public record Evidence(
        Instant timestamp,
        String message,
        Integer statusCode,
        String clientIp,
        String userId,
        String sku,
        Long latencyMs,
        String traceId
) {
}

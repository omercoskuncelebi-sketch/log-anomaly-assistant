package org.example.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogEntry(
        Instant timestamp,
        String service,
        String level,
        String message,
        String traceId,
        String userId,
        String clientIp,
        Long latencyMs,
        Integer statusCode,
        String userEmail,
        String sku,
        String jobName,
        Long durationMs
) {
}

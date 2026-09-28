package org.example.model;

import java.time.Instant;
import java.util.List;

/** A deterministic pattern found in the available log entries. */
public record Anomaly(
        String type,
        String service,
        String description,
        Instant startTime,
        Instant endTime,
        long count,
        List<Evidence> evidence,
        List<String> exampleTraceIds
) {
}

package org.example.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import org.example.model.LogEntry;
import org.example.model.ServiceStats;
import org.example.repository.LogRepository;
import org.junit.jupiter.api.Test;

class LogAnalysisServiceTest {

    @Test
    void calculatesRequestStatisticsAndDoesNotTreat4xxAsServerErrors() {
        LogRepository repository = mock(LogRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                entry("2026-09-21T00:00:00Z", 200, 10L),
                entry("2026-09-21T00:10:00Z", 404, 20L),
                entry("2026-09-21T01:00:00Z", 500, 30L),
                entry("2026-09-21T23:00:00Z", 201, 100L),
                entry("2026-09-21T02:00:00Z", null, 999L)
        ));

        ServiceStats stats = new LogAnalysisService(repository).getServiceStats().get(0);

        assertEquals("catalog-service", stats.service());
        assertEquals(4, stats.requestCount());
        assertEquals(1, stats.serverErrorCount());
        assertEquals(0.25, stats.serverErrorRate());
        assertEquals(Long.valueOf(100L), stats.p95LatencyMs());
        assertEquals(2L, stats.hourlyDistribution().get(0));
        assertEquals(1L, stats.hourlyDistribution().get(1));
        assertEquals(0L, stats.hourlyDistribution().get(2));
        assertEquals(1L, stats.hourlyDistribution().get(23));
    }

    @Test
    void returnsNullP95WhenRequestsHaveNoLatencyValues() {
        LogRepository repository = mock(LogRepository.class);
        when(repository.findAll()).thenReturn(List.of(
                entry("2026-09-21T00:00:00Z", 200, null),
                entry("2026-09-21T01:00:00Z", 500, null)
        ));

        ServiceStats stats = new LogAnalysisService(repository).getServiceStats().get(0);

        assertNull(stats.p95LatencyMs());
    }

    private LogEntry entry(String timestamp, Integer statusCode, Long latencyMs) {
        return new LogEntry(
                Instant.parse(timestamp),
                "catalog-service",
                "INFO",
                "request completed",
                "trace-1",
                "u1",
                "10.0.0.1",
                latencyMs,
                statusCode,
                null,
                null,
                null,
                null
        );
    }
}

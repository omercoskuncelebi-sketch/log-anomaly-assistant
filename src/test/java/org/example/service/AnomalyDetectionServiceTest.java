package org.example.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.example.model.Anomaly;
import org.example.model.LogEntry;
import org.example.repository.LogRepository;
import org.junit.jupiter.api.Test;

class AnomalyDetectionServiceTest {

    @Test
    void detectsAuthenticationBurstFromOneIpAcrossMultipleUsers() {
        List<LogEntry> entries = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            entries.add(entry(
                    "2026-09-21T03:%02d:00Z".formatted(12 + index),
                    "auth-service",
                    "POST /auth/login failed",
                    401,
                    70L,
                    "198.51.100.20",
                    "u" + (index % 3),
                    null,
                    "auth-trace-" + index,
                    null
            ));
        }

        Anomaly anomaly = anomalyOf(detect(entries), "AUTHENTICATION_BURST");

        assertEquals("auth-service", anomaly.service());
        assertEquals(10, anomaly.count());
        assertEquals(401, anomaly.evidence().get(0).statusCode());
        assertEquals("198.51.100.20", anomaly.evidence().get(0).clientIp());
    }

    @Test
    void detectsSameMessageServerErrorBurst() {
        List<LogEntry> entries = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            entries.add(entry(
                    Instant.parse("2026-09-21T15:40:00Z").plusSeconds(index * 300L).toString(),
                    "checkout-service",
                    "POST /checkout/confirm failed: dependency error",
                    500,
                    200L,
                    "10.0.0.1",
                    "u" + index,
                    null,
                    "error-trace-" + index,
                    null
            ));
        }

        Anomaly anomaly = anomalyOf(detect(entries), "SERVER_ERROR_BURST");

        assertEquals("checkout-service", anomaly.service());
        assertEquals(5, anomaly.count());
        assertTrue(anomaly.description().contains("dependency error"));
    }

    @Test
    void detectsAConcentratedHighLatencyGroupAgainstServiceBaseline() {
        List<LogEntry> entries = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            entries.add(entry(
                    "2026-09-21T00:%02d:00Z".formatted(index),
                    "payment-service",
                    "POST /payments/authorize completed",
                    200,
                    100L,
                    "10.0.0.1",
                    "u" + index,
                    null,
                    "normal-trace-" + index,
                    null
            ));
        }
        for (int index = 0; index < 5; index++) {
            entries.add(entry(
                    "2026-09-21T01:%02d:00Z".formatted(index),
                    "payment-service",
                    "POST /payments/authorize slow response",
                    200,
                    2_000L,
                    "10.0.0.1",
                    "u" + index,
                    null,
                    "slow-trace-" + index,
                    null
            ));
        }

        Anomaly anomaly = anomalyOf(detect(entries), "HIGH_LATENCY");

        assertEquals("payment-service", anomaly.service());
        assertEquals(5, anomaly.count());
        assertTrue(anomaly.description().contains("1000 ms"));
    }

    @Test
    void detectsRepeatedNegativeStockConflictsWithoutClassifyingThemAsServerErrors() {
        List<LogEntry> entries = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            entries.add(entry(
                    "2026-09-21T12:%02d:00Z".formatted(index),
                    "inventory-service",
                    "POST /stock/reserve conflict: stock went negative",
                    409,
                    50L,
                    "10.0.0.1",
                    "u" + index,
                    "SKU-42",
                    "stock-trace-" + index,
                    null
            ));
        }

        Anomaly anomaly = anomalyOf(detect(entries), "NEGATIVE_STOCK_CONFLICT");

        assertEquals("inventory-service", anomaly.service());
        assertEquals(5, anomaly.count());
        assertEquals(409, anomaly.evidence().get(0).statusCode());
    }

    @Test
    void reportsAnExplicitlyAnnouncedJobRunOnlyWhenNoExecutionIsPresent() {
        List<LogEntry> entries = List.of(
                entry(
                        "2026-09-21T02:04:00Z",
                        "order-service",
                        "scheduler: next run of order-reconciliation at 2026-09-21T14:00:00Z",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "order-reconciliation"
                ),
                entry(
                        "2026-09-21T14:20:00Z",
                        "catalog-service",
                        "GET /products completed",
                        200,
                        20L,
                        "10.0.0.1",
                        "u1",
                        null,
                        "later-trace",
                        null
                )
        );

        Anomaly anomaly = anomalyOf(detect(entries), "MISSING_SCHEDULED_JOB");

        assertEquals("order-service", anomaly.service());
        assertEquals(Instant.parse("2026-09-21T14:00:00Z"), anomaly.startTime());
        assertEquals(1, anomaly.count());
    }

    @Test
    void doesNotReportMissingJobWhenExecutionFallsInTheExpectedWindow() {
        List<LogEntry> entries = List.of(
                entry(
                        "2026-09-21T02:04:00Z",
                        "order-service",
                        "scheduler: next run of order-reconciliation at 2026-09-21T14:00:00Z",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "order-reconciliation"
                ),
                entry(
                        "2026-09-21T14:05:00Z",
                        "order-service",
                        "job order-reconciliation completed",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "order-reconciliation"
                ),
                entry(
                        "2026-09-21T14:20:00Z",
                        "catalog-service",
                        "GET /products completed",
                        200,
                        20L,
                        "10.0.0.1",
                        "u1",
                        null,
                        "later-trace",
                        null
                )
        );

        assertFalse(detect(entries).stream().anyMatch(anomaly -> anomaly.type().equals("MISSING_SCHEDULED_JOB")));
    }

    @Test
    void doesNotTreatASameNamedJobInAnotherServiceAsTheExpectedExecution() {
        List<LogEntry> entries = List.of(
                entry(
                        "2026-09-21T02:04:00Z",
                        "order-service",
                        "scheduler: next run of order-reconciliation at 2026-09-21T14:00:00Z",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "order-reconciliation"
                ),
                entry(
                        "2026-09-21T14:05:00Z",
                        "other-service",
                        "job order-reconciliation completed",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "order-reconciliation"
                ),
                entry(
                        "2026-09-21T14:20:00Z",
                        "catalog-service",
                        "GET /products completed",
                        200,
                        20L,
                        "10.0.0.1",
                        "u1",
                        null,
                        "later-trace",
                        null
                )
        );

        assertTrue(detect(entries).stream().anyMatch(anomaly -> anomaly.type().equals("MISSING_SCHEDULED_JOB")));
    }

    private List<Anomaly> detect(List<LogEntry> entries) {
        LogRepository repository = mock(LogRepository.class);
        when(repository.findAll()).thenReturn(entries);
        return new AnomalyDetectionService(repository).detectAnomalies();
    }

    private Anomaly anomalyOf(List<Anomaly> anomalies, String type) {
        return anomalies.stream()
                .filter(anomaly -> anomaly.type().equals(type))
                .findFirst()
                .orElseThrow();
    }

    private LogEntry entry(
            String timestamp,
            String service,
            String message,
            Integer statusCode,
            Long latencyMs,
            String clientIp,
            String userId,
            String sku,
            String traceId,
            String jobName
    ) {
        return new LogEntry(
                Instant.parse(timestamp),
                service,
                "INFO",
                message,
                traceId,
                userId,
                clientIp,
                latencyMs,
                statusCode,
                null,
                sku,
                jobName,
                null
        );
    }
}

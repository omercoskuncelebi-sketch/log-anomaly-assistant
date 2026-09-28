package org.example.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import org.example.model.LogEntry;
import org.junit.jupiter.api.Test;

class LogRepositoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void parsesNormalLogEntry() throws Exception {
        LogEntry entry = objectMapper.readValue("""
                {
                  "timestamp": "2026-09-21T00:00:43.806Z",
                  "service": "catalog-service",
                  "level": "INFO",
                  "message": "GET /products/{id} completed",
                  "traceId": "3dbe508043dd",
                  "userId": "u1071",
                  "clientIp": "10.16.236.1",
                  "latencyMs": 61,
                  "statusCode": 200,
                  "userEmail": "user1071@example.com",
                  "sku": "SKU-123",
                  "jobName": "stock-sync",
                  "durationMs": 1948
                }
                """, LogEntry.class);

        assertEquals(Instant.parse("2026-09-21T00:00:43.806Z"), entry.timestamp());
        assertEquals("catalog-service", entry.service());
        assertEquals("INFO", entry.level());
        assertEquals("GET /products/{id} completed", entry.message());
        assertEquals("3dbe508043dd", entry.traceId());
        assertEquals("u1071", entry.userId());
        assertEquals("10.16.236.1", entry.clientIp());
        assertEquals(Long.valueOf(61), entry.latencyMs());
        assertEquals(Integer.valueOf(200), entry.statusCode());
        assertEquals("user1071@example.com", entry.userEmail());
        assertEquals("SKU-123", entry.sku());
        assertEquals("stock-sync", entry.jobName());
        assertEquals(Long.valueOf(1948), entry.durationMs());
    }

    @Test
    void parsesEntryWhenOptionalFieldsAreMissing() throws Exception {
        LogEntry entry = objectMapper.readValue("""
                {
                  "timestamp": "2026-09-21T00:00:14.181Z",
                  "service": "inventory-service",
                  "level": "INFO",
                  "message": "job completed"
                }
                """, LogEntry.class);

        assertNull(entry.traceId());
        assertNull(entry.userId());
        assertNull(entry.clientIp());
        assertNull(entry.latencyMs());
        assertNull(entry.statusCode());
        assertNull(entry.userEmail());
        assertNull(entry.sku());
        assertNull(entry.jobName());
        assertNull(entry.durationMs());
    }

    @Test
    void ignoresUnknownJsonFields() throws Exception {
        LogEntry entry = objectMapper.readValue("""
                {
                  "timestamp": "2026-09-21T00:00:14.181Z",
                  "service": "inventory-service",
                  "level": "INFO",
                  "message": "job completed",
                  "recordsProcessed": 3672,
                  "futureField": true
                }
                """, LogEntry.class);

        assertEquals("inventory-service", entry.service());
        assertEquals("job completed", entry.message());
    }

    @Test
    void loadsRealClasspathLogFile() {
        List<LogEntry> entries = repository().findAll();

        assertFalse(entries.isEmpty());
        assertEquals(Instant.parse("2026-09-21T00:00:14.181Z"), entries.get(0).timestamp());
        assertEquals("inventory-service", entries.get(0).service());
    }

    @Test
    void loadsExpectedNumberOfLogEntries() {
        assertEquals(2_000, repository().findAll().size());
    }

    private LogRepository repository() {
        return new LogRepository(objectMapper);
    }
}

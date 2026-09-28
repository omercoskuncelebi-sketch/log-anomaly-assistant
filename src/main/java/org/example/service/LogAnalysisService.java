package org.example.service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.example.model.LogEntry;
import org.example.model.ServiceStats;
import org.example.repository.LogRepository;
import org.springframework.stereotype.Service;

@Service
public class LogAnalysisService {

    private final LogRepository logRepository;

    public LogAnalysisService(LogRepository logRepository) {
        this.logRepository = logRepository;
    }

    public List<ServiceStats> getServiceStats() {
        Map<String, ServiceAccumulator> byService = new TreeMap<>();

        for (LogEntry entry : logRepository.findAll()) {
            ServiceAccumulator accumulator = byService.computeIfAbsent(serviceName(entry), ignored -> new ServiceAccumulator());
            accumulator.add(entry);
        }

        List<ServiceStats> result = new ArrayList<>();
        for (Map.Entry<String, ServiceAccumulator> entry : byService.entrySet()) {
            result.add(entry.getValue().toServiceStats(entry.getKey()));
        }
        return List.copyOf(result);
    }

    private String serviceName(LogEntry entry) {
        if (entry.service() == null || entry.service().isBlank()) {
            return "unknown-service";
        }
        return entry.service();
    }

    private static class ServiceAccumulator {

        private long requestCount;
        private long serverErrorCount;
        private final List<Long> latencies = new ArrayList<>();
        private final Map<Integer, Long> hourlyDistribution = emptyHourlyDistribution();

        private void add(LogEntry entry) {
            if (entry.statusCode() == null) {
                return;
            }

            requestCount++;
            if (entry.statusCode() >= 500 && entry.statusCode() < 600) {
                serverErrorCount++;
            }
            if (entry.latencyMs() != null) {
                latencies.add(entry.latencyMs());
            }
            if (entry.timestamp() != null) {
                int hour = entry.timestamp().atZone(ZoneOffset.UTC).getHour();
                hourlyDistribution.merge(hour, 1L, Long::sum);
            }
        }

        private ServiceStats toServiceStats(String service) {
            Long p95Latency = latencies.isEmpty() ? null : percentile95(latencies);
            double errorRate = requestCount == 0 ? 0.0 : (double) serverErrorCount / requestCount;

            return new ServiceStats(
                    service,
                    requestCount,
                    serverErrorCount,
                    errorRate,
                    p95Latency,
                    Collections.unmodifiableMap(new LinkedHashMap<>(hourlyDistribution))
            );
        }

        private static Map<Integer, Long> emptyHourlyDistribution() {
            Map<Integer, Long> distribution = new LinkedHashMap<>();
            for (int hour = 0; hour < 24; hour++) {
                distribution.put(hour, 0L);
            }
            return distribution;
        }

        private static long percentile95(List<Long> values) {
            List<Long> sorted = new ArrayList<>(values);
            sorted.sort(Comparator.naturalOrder());
            int index = (int) Math.ceil(0.95 * sorted.size()) - 1;
            return sorted.get(index);
        }
    }
}

package org.example.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.example.model.Anomaly;
import org.example.model.Evidence;
import org.example.model.LogEntry;
import org.example.repository.LogRepository;
import org.springframework.stereotype.Service;

@Service
public class AnomalyDetectionService {

    private static final String AUTHENTICATION_BURST = "AUTHENTICATION_BURST";
    private static final String SERVER_ERROR_BURST = "SERVER_ERROR_BURST";
    private static final String HIGH_LATENCY = "HIGH_LATENCY";
    private static final String NEGATIVE_STOCK_CONFLICT = "NEGATIVE_STOCK_CONFLICT";
    private static final String MISSING_SCHEDULED_JOB = "MISSING_SCHEDULED_JOB";

    private static final int MIN_AUTH_FAILURES = 10;
    private static final int MIN_AUTH_USERS = 3;
    private static final int MIN_SERVER_ERRORS = 5;
    private static final int MIN_HIGH_LATENCY_REQUESTS = 5;
    private static final int MIN_STOCK_CONFLICTS = 5;
    private static final int MAX_EVIDENCE_ENTRIES = 3;

    private static final Duration AUTH_WINDOW = Duration.ofMinutes(15);
    private static final Duration SERVER_ERROR_WINDOW = Duration.ofMinutes(30);
    private static final Duration HIGH_LATENCY_WINDOW = Duration.ofMinutes(30);
    private static final Duration STOCK_CONFLICT_WINDOW = Duration.ofHours(1);
    private static final Duration JOB_EXECUTION_TOLERANCE = Duration.ofMinutes(15);
    private static final Duration JOB_EARLY_EXECUTION_TOLERANCE = Duration.ofMinutes(5);

    private static final Pattern EXPECTED_RUN_TIME = Pattern.compile(
            "\\b(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)\\b"
    );

    private final LogRepository logRepository;

    public AnomalyDetectionService(LogRepository logRepository) {
        this.logRepository = logRepository;
    }

    public List<Anomaly> detectAnomalies() {
        List<LogEntry> entries = logRepository.findAll();
        List<Anomaly> anomalies = new ArrayList<>();

        anomalies.addAll(findAuthenticationBursts(entries));
        anomalies.addAll(findServerErrorBursts(entries));
        anomalies.addAll(findHighLatencyBursts(entries));
        anomalies.addAll(findNegativeStockConflicts(entries));
        anomalies.addAll(findMissingScheduledJobs(entries));

        anomalies.sort(Comparator.comparing(Anomaly::startTime).thenComparing(Anomaly::type));
        return List.copyOf(anomalies);
    }

    private List<Anomaly> findAuthenticationBursts(List<LogEntry> entries) {
        Map<AuthKey, List<LogEntry>> candidates = new LinkedHashMap<>();
        for (LogEntry entry : entries) {
            if (!isAuthenticationFailure(entry)) {
                continue;
            }
            AuthKey key = new AuthKey(serviceName(entry), entry.clientIp());
            candidates.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }

        List<Anomaly> anomalies = new ArrayList<>();
        for (Map.Entry<AuthKey, List<LogEntry>> candidate : candidates.entrySet()) {
            List<LogEntry> burst = largestWindow(candidate.getValue(), AUTH_WINDOW);
            long distinctUsers = burst.stream()
                    .map(this::accountIdentifier)
                    .filter(userId -> userId != null && !userId.isBlank())
                    .distinct()
                    .count();
            if (burst.size() < MIN_AUTH_FAILURES || distinctUsers < MIN_AUTH_USERS) {
                continue;
            }

            anomalies.add(anomaly(
                    AUTHENTICATION_BURST,
                    candidate.getKey().service(),
                    "Detected " + burst.size() + " HTTP 401 authentication failures from one client IP across "
                            + distinctUsers + " user accounts in a concentrated time window. This is a correlation "
                            + "in the logs and does not establish a cause.",
                    burst
            ));
        }
        return anomalies;
    }

    private List<Anomaly> findServerErrorBursts(List<LogEntry> entries) {
        Map<MessageKey, List<LogEntry>> candidates = new LinkedHashMap<>();
        for (LogEntry entry : entries) {
            if (!isServerError(entry) || entry.timestamp() == null || entry.message() == null || entry.message().isBlank()) {
                continue;
            }
            MessageKey key = new MessageKey(serviceName(entry), messagePattern(entry.message()));
            candidates.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }

        List<Anomaly> anomalies = new ArrayList<>();
        for (Map.Entry<MessageKey, List<LogEntry>> candidate : candidates.entrySet()) {
            List<LogEntry> burst = largestWindow(candidate.getValue(), SERVER_ERROR_WINDOW);
            if (burst.size() < MIN_SERVER_ERRORS) {
                continue;
            }

            anomalies.add(anomaly(
                    SERVER_ERROR_BURST,
                    candidate.getKey().service(),
                    "Detected " + burst.size() + " 5xx responses with the same message pattern: "
                            + candidate.getKey().messagePattern() + " (status codes: " + statusCodes(burst) + "). "
                            + "The shared pattern is evidence of correlation, not definite causation.",
                    burst
            ));
        }
        return anomalies;
    }

    private List<Anomaly> findHighLatencyBursts(List<LogEntry> entries) {
        Map<String, List<LogEntry>> requestsByService = new LinkedHashMap<>();
        for (LogEntry entry : entries) {
            if (entry.statusCode() == null || entry.latencyMs() == null || entry.latencyMs() < 0 || entry.timestamp() == null) {
                continue;
            }
            requestsByService.computeIfAbsent(serviceName(entry), ignored -> new ArrayList<>()).add(entry);
        }

        List<Anomaly> anomalies = new ArrayList<>();
        for (Map.Entry<String, List<LogEntry>> serviceRequests : requestsByService.entrySet()) {
            long medianLatency = medianLatency(serviceRequests.getValue());
            long threshold = Math.max(1_000L, medianLatency * 3);
            List<LogEntry> slowRequests = serviceRequests.getValue().stream()
                    .filter(entry -> entry.latencyMs() >= threshold)
                    .toList();
            List<LogEntry> burst = largestWindow(slowRequests, HIGH_LATENCY_WINDOW);
            if (burst.size() < MIN_HIGH_LATENCY_REQUESTS) {
                continue;
            }

            anomalies.add(anomaly(
                    HIGH_LATENCY,
                    serviceRequests.getKey(),
                    "Detected " + burst.size() + " requests at or above " + threshold + " ms, which is at least "
                            + "three times the service median latency of " + medianLatency + " ms. The logs show "
                            + "elevated latency in this window but do not establish a cause.",
                    burst
            ));
        }
        return anomalies;
    }

    private List<Anomaly> findNegativeStockConflicts(List<LogEntry> entries) {
        Map<StockKey, List<LogEntry>> candidates = new LinkedHashMap<>();
        for (LogEntry entry : entries) {
            if (!isNegativeStockConflict(entry) || entry.timestamp() == null) {
                continue;
            }
            StockKey key = new StockKey(serviceName(entry), skuName(entry));
            candidates.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
        }

        List<Anomaly> anomalies = new ArrayList<>();
        for (Map.Entry<StockKey, List<LogEntry>> candidate : candidates.entrySet()) {
            List<LogEntry> burst = largestWindow(candidate.getValue(), STOCK_CONFLICT_WINDOW);
            if (burst.size() < MIN_STOCK_CONFLICTS) {
                continue;
            }

            anomalies.add(anomaly(
                    NEGATIVE_STOCK_CONFLICT,
                    candidate.getKey().service(),
                    "Detected " + burst.size() + " repeated inventory conflicts for " + candidate.getKey().sku()
                            + " that indicate negative stock (status codes: " + statusCodes(burst) + "). "
                            + "The records show a conflict pattern, not its root cause.",
                    burst
            ));
        }
        return anomalies;
    }

    private List<Anomaly> findMissingScheduledJobs(List<LogEntry> entries) {
        Instant lastLogTime = entries.stream()
                .map(LogEntry::timestamp)
                .filter(timestamp -> timestamp != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (lastLogTime == null) {
            return List.of();
        }

        Map<JobScheduleKey, LogEntry> announcements = new LinkedHashMap<>();
        for (LogEntry entry : entries) {
            Instant expectedTime = expectedRunTime(entry);
            if (expectedTime != null) {
                announcements.putIfAbsent(new JobScheduleKey(serviceName(entry), entry.jobName(), expectedTime), entry);
            }
        }

        List<Anomaly> anomalies = new ArrayList<>();
        for (Map.Entry<JobScheduleKey, LogEntry> scheduledJob : announcements.entrySet()) {
            JobScheduleKey key = scheduledJob.getKey();
            if (lastLogTime.isBefore(key.expectedTime().plus(JOB_EXECUTION_TOLERANCE))) {
                continue;
            }
            if (hasJobExecution(entries, key.service(), key.jobName(), key.expectedTime())) {
                continue;
            }

            LogEntry evidence = scheduledJob.getValue();
            anomalies.add(new Anomaly(
                    MISSING_SCHEDULED_JOB,
                    key.service(),
                    "No start, completion, or execution log for job " + key.jobName() + " was found within "
                            + JOB_EXECUTION_TOLERANCE.toMinutes() + " minutes of its explicitly announced scheduled "
                            + "time " + key.expectedTime() + ". This is an absence in the available logs, not a cause.",
                    key.expectedTime(),
                    key.expectedTime(),
                    1,
                    List.of(toEvidence(evidence)),
                    traceIds(List.of(evidence))
            ));
        }
        return anomalies;
    }

    private boolean isAuthenticationFailure(LogEntry entry) {
        return entry.timestamp() != null
                && entry.statusCode() != null
                && entry.statusCode() == 401
                && entry.clientIp() != null
                && !entry.clientIp().isBlank()
                && serviceName(entry).toLowerCase(Locale.ROOT).contains("auth");
    }

    private boolean isServerError(LogEntry entry) {
        return entry.statusCode() != null && entry.statusCode() >= 500 && entry.statusCode() < 600;
    }

    private boolean isNegativeStockConflict(LogEntry entry) {
        String message = entry.message() == null ? "" : entry.message().toLowerCase(Locale.ROOT);
        boolean mentionsNegativeStock = message.contains("negative")
                && (message.contains("stock") || message.contains("inventory"));
        boolean inventoryConflict = serviceName(entry).toLowerCase(Locale.ROOT).contains("inventory")
                && entry.statusCode() != null
                && entry.statusCode() == 409
                && message.contains("conflict");
        return mentionsNegativeStock || inventoryConflict;
    }

    private Instant expectedRunTime(LogEntry entry) {
        if (entry.jobName() == null || entry.jobName().isBlank() || entry.message() == null) {
            return null;
        }
        String message = entry.message().toLowerCase(Locale.ROOT);
        if (!message.contains("next run") && !message.contains("schedul")) {
            return null;
        }

        Matcher matcher = EXPECTED_RUN_TIME.matcher(entry.message());
        if (!matcher.find()) {
            return null;
        }
        try {
            return Instant.parse(matcher.group(1));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private boolean hasJobExecution(List<LogEntry> entries, String service, String jobName, Instant expectedTime) {
        Instant earliest = expectedTime.minus(JOB_EARLY_EXECUTION_TOLERANCE);
        Instant latest = expectedTime.plus(JOB_EXECUTION_TOLERANCE);
        return entries.stream().anyMatch(entry -> jobName.equals(entry.jobName())
                && service.equals(serviceName(entry))
                && entry.timestamp() != null
                && !entry.timestamp().isBefore(earliest)
                && !entry.timestamp().isAfter(latest)
                && isJobExecutionMessage(entry.message()));
    }

    private boolean isJobExecutionMessage(String message) {
        if (message == null) {
            return false;
        }
        String lowerCaseMessage = message.toLowerCase(Locale.ROOT);
        return lowerCaseMessage.contains(" started")
                || lowerCaseMessage.contains(" completed")
                || lowerCaseMessage.contains(" executed")
                || lowerCaseMessage.contains(" finished");
    }

    private List<LogEntry> largestWindow(List<LogEntry> events, Duration window) {
        if (events.isEmpty()) {
            return List.of();
        }

        List<LogEntry> sorted = events.stream()
                .filter(entry -> entry.timestamp() != null)
                .sorted(Comparator.comparing(LogEntry::timestamp))
                .toList();
        int bestStart = 0;
        int bestEnd = 0;
        int end = 0;
        for (int start = 0; start < sorted.size(); start++) {
            Instant latest = sorted.get(start).timestamp().plus(window);
            while (end < sorted.size() && !sorted.get(end).timestamp().isAfter(latest)) {
                end++;
            }
            if (end - start > bestEnd - bestStart) {
                bestStart = start;
                bestEnd = end;
            }
        }
        return List.copyOf(sorted.subList(bestStart, bestEnd));
    }

    private Anomaly anomaly(String type, String service, String description, List<LogEntry> events) {
        return new Anomaly(
                type,
                service,
                description,
                events.get(0).timestamp(),
                events.get(events.size() - 1).timestamp(),
                events.size(),
                events.stream().limit(MAX_EVIDENCE_ENTRIES).map(this::toEvidence).toList(),
                traceIds(events)
        );
    }

    private Evidence toEvidence(LogEntry entry) {
        return new Evidence(
                entry.timestamp(),
                entry.message(),
                entry.statusCode(),
                entry.clientIp(),
                entry.userId(),
                entry.sku(),
                entry.latencyMs(),
                entry.traceId()
        );
    }

    private List<String> traceIds(List<LogEntry> entries) {
        return entries.stream()
                .map(LogEntry::traceId)
                .filter(traceId -> traceId != null && !traceId.isBlank())
                .distinct()
                .limit(MAX_EVIDENCE_ENTRIES)
                .toList();
    }

    private long medianLatency(List<LogEntry> entries) {
        List<Long> latencies = entries.stream()
                .map(LogEntry::latencyMs)
                .sorted()
                .toList();
        return latencies.get((latencies.size() - 1) / 2);
    }

    private String statusCodes(List<LogEntry> entries) {
        return entries.stream()
                .map(LogEntry::statusCode)
                .filter(statusCode -> statusCode != null)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
    }

    private String serviceName(LogEntry entry) {
        return entry.service() == null || entry.service().isBlank() ? "unknown-service" : entry.service();
    }

    private String skuName(LogEntry entry) {
        return entry.sku() == null || entry.sku().isBlank() ? "unspecified SKU" : entry.sku();
    }

    private String accountIdentifier(LogEntry entry) {
        if (entry.userId() != null && !entry.userId().isBlank()) {
            return entry.userId();
        }
        return entry.userEmail();
    }

    private String messagePattern(String message) {
        return message.trim().replaceAll("(?i)\\b[0-9a-f]{8,}\\b", "{id}");
    }

    private record AuthKey(String service, String clientIp) {
    }

    private record MessageKey(String service, String messagePattern) {
    }

    private record StockKey(String service, String sku) {
    }

    private record JobScheduleKey(String service, String jobName, Instant expectedTime) {
    }
}

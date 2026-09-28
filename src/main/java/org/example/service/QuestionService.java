package org.example.service;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.example.llm.LlmClient;
import org.example.model.Anomaly;
import org.example.model.Evidence;
import org.example.model.ServiceStats;
import org.example.privacy.PiiMasker;
import org.springframework.stereotype.Service;

@Service
public class QuestionService {

    public static final String UNKNOWN_INFORMATION_ANSWER = "Bu bilgi log verisinde mevcut değil.";

    private final LogAnalysisService logAnalysisService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final PiiMasker piiMasker;
    private final LlmClient llmClient;

    public QuestionService(
            LogAnalysisService logAnalysisService,
            AnomalyDetectionService anomalyDetectionService,
            PiiMasker piiMasker,
            LlmClient llmClient
    ) {
        this.logAnalysisService = logAnalysisService;
        this.anomalyDetectionService = anomalyDetectionService;
        this.piiMasker = piiMasker;
        this.llmClient = llmClient;
    }

    public String ask(String question) {
        if (question == null || question.isBlank()) {
            return UNKNOWN_INFORMATION_ANSWER;
        }

        List<ServiceStats> allStats = emptyIfNull(logAnalysisService.getServiceStats());
        List<Anomaly> allAnomalies = emptyIfNull(anomalyDetectionService.detectAnomalies());
        String normalizedQuestion = normalize(question);
        List<String> mentionedServices = findMentionedServices(normalizedQuestion, allStats, allAnomalies);
        QuestionTopic topic = findTopic(removeMentionedServices(normalizedQuestion, mentionedServices));

        if (topic == QuestionTopic.UNKNOWN) {
            return UNKNOWN_INFORMATION_ANSWER;
        }

        List<ServiceStats> relevantStats = selectStats(allStats, topic, mentionedServices);
        List<Anomaly> relevantAnomalies = selectAnomalies(allAnomalies, topic, mentionedServices);
        relevantAnomalies = selectNightAnomaliesWhenRequested(relevantAnomalies, normalizedQuestion);
        if (relevantStats.isEmpty() && relevantAnomalies.isEmpty()) {
            return UNKNOWN_INFORMATION_ANSWER;
        }

        String answerDraft = buildAnswerDraft(relevantStats, relevantAnomalies);
        String prompt = buildPrompt(question, answerDraft, relevantStats, relevantAnomalies);
        String answer = llmClient.ask(piiMasker.mask(prompt));

        return answer == null || answer.isBlank() ? UNKNOWN_INFORMATION_ANSWER : answer;
    }

    private QuestionTopic findTopic(String question) {
        boolean asksAboutLatency = containsAny(question, "latency", "gecikme", "yavaş", "yavas", "slow", "p95");
        boolean asksAboutServerErrors = containsAny(
                question,
                "5xx", "server error", "sunucu hat", "error rate", "hata oran", "hata ver", "hata al"
        );

        if (asksAboutLatency && asksAboutServerErrors) {
            return QuestionTopic.ANOMALY;
        }
        if (containsAny(question, "login", "auth", "giriş", "oturum", "kimlik")) {
            return QuestionTopic.AUTHENTICATION;
        }
        if (asksAboutLatency) {
            return QuestionTopic.LATENCY;
        }
        if (containsAny(question, "inventory", "stok", "stock", "envanter", "negative", "negatif", "conflict")) {
            return QuestionTopic.INVENTORY;
        }
        if (containsAny(
                question,
                "scheduled", "schedule", "job", "zamanlan", "görev", "gorev", "çalışmayan", "calismayan",
                "çalışması gereken", "calismasi gereken"
        )) {
            return QuestionTopic.SCHEDULED_JOB;
        }
        if (asksAboutServerErrors) {
            return QuestionTopic.SERVER_ERROR;
        }
        if (containsAny(question, "anomaly", "anomali", "şüpheli", "supheli", "incident", "sorun", "problem")) {
            return QuestionTopic.ANOMALY;
        }
        if (containsAny(question, "stats", "stat", "istatistik", "request", "istek", "status code")) {
            return QuestionTopic.STATISTICS;
        }
        return QuestionTopic.UNKNOWN;
    }

    private String removeMentionedServices(String question, List<String> services) {
        String topicText = question;
        for (String service : services) {
            for (String alias : serviceAliases(service)) {
                topicText = topicText.replace(alias, " ");
            }
        }
        return topicText;
    }

    private List<String> findMentionedServices(
            String question,
            List<ServiceStats> stats,
            List<Anomaly> anomalies
    ) {
        List<String> services = new ArrayList<>();
        for (ServiceStats stat : stats) {
            addIfMentioned(services, stat.service(), question);
        }
        for (Anomaly anomaly : anomalies) {
            addIfMentioned(services, anomaly.service(), question);
        }
        return services;
    }

    private void addIfMentioned(List<String> services, String service, String question) {
        if (service != null && mentionsService(question, service) && !services.contains(service)) {
            services.add(service);
        }
    }

    private boolean mentionsService(String question, String service) {
        for (String alias : serviceAliases(service)) {
            if (question.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    private List<String> serviceAliases(String service) {
        String normalizedService = normalize(service);
        if (normalizedService.endsWith("-service")) {
            return List.of(normalizedService, normalizedService.substring(0, normalizedService.length() - "-service".length()));
        }
        return List.of(normalizedService);
    }

    private List<ServiceStats> selectStats(
            List<ServiceStats> allStats,
            QuestionTopic topic,
            List<String> mentionedServices
    ) {
        List<ServiceStats> selected = new ArrayList<>();
        for (ServiceStats stat : allStats) {
            if (matchesStats(stat, topic, mentionedServices)) {
                selected.add(stat);
            }
        }
        return selected;
    }

    private boolean matchesStats(ServiceStats stat, QuestionTopic topic, List<String> mentionedServices) {
        if (!mentionedServices.isEmpty()) {
            return mentionedServices.contains(stat.service());
        }

        return switch (topic) {
            case AUTHENTICATION -> containsAny(normalize(stat.service()), "auth", "login");
            case INVENTORY -> containsAny(normalize(stat.service()), "inventory", "stock");
            case SCHEDULED_JOB, ANOMALY -> false;
            case SERVER_ERROR, LATENCY, STATISTICS -> true;
            case UNKNOWN -> false;
        };
    }

    private List<Anomaly> selectAnomalies(
            List<Anomaly> allAnomalies,
            QuestionTopic topic,
            List<String> mentionedServices
    ) {
        List<Anomaly> selected = new ArrayList<>();
        for (Anomaly anomaly : allAnomalies) {
            if (matchesAnomaly(anomaly, topic, mentionedServices)) {
                selected.add(anomaly);
            }
        }
        return selected;
    }

    private List<Anomaly> selectNightAnomaliesWhenRequested(List<Anomaly> anomalies, String question) {
        if (!containsAny(question, "gece")) {
            return anomalies;
        }
        return anomalies.stream()
                .filter(anomaly -> anomaly.startTime() != null)
                .filter(anomaly -> {
                    int hour = anomaly.startTime().atZone(ZoneOffset.UTC).getHour();
                    return hour >= 22 || hour < 6;
                })
                .toList();
    }

    private boolean matchesAnomaly(Anomaly anomaly, QuestionTopic topic, List<String> mentionedServices) {
        if (!mentionedServices.isEmpty() && !mentionedServices.contains(anomaly.service())) {
            return false;
        }

        String details = normalize(anomaly.type() + " " + anomaly.service() + " " + anomaly.description());
        return switch (topic) {
            case AUTHENTICATION -> containsAny(details, "auth", "login", "401");
            case SERVER_ERROR -> containsAny(details, "server", "error", "5xx", "500", "502", "503", "504");
            case LATENCY -> containsAny(details, "latency", "slow", "high_latency", "p95");
            case INVENTORY -> containsAny(details, "inventory", "stock", "negative", "conflict", "409");
            case SCHEDULED_JOB -> containsAny(details, "scheduled", "schedule", "job", "missing");
            case ANOMALY -> true;
            case STATISTICS, UNKNOWN -> false;
        };
    }

    private String buildAnswerDraft(List<ServiceStats> stats, List<Anomaly> anomalies) {
        if (!anomalies.isEmpty()) {
            StringBuilder answer = new StringBuilder("Loglarda ")
                    .append(anomalies.size())
                    .append(" ilgili bulgu var. ");
            for (Anomaly anomaly : anomalies) {
                answer.append(anomalySummary(anomaly)).append(' ');
            }
            return answer.append("Bu bulgular korelasyon gösterir; kesin nedensellik göstermez.").toString();
        }

        ServiceStats first = stats.get(0);
        return first.service() + " için " + first.requestCount() + " HTTP isteği, "
                + first.serverErrorCount() + " adet 5xx hata ve %"
                + String.format(Locale.ROOT, "%.2f", first.serverErrorRate() * 100)
                + " hata oranı görüldü.";
    }

    private String anomalySummary(Anomaly anomaly) {
        String summary = anomaly.type() + " / " + anomaly.service() + ": " + anomaly.count()
                + " olay, zaman aralığı " + anomaly.startTime() + " - " + anomaly.endTime();
        List<String> traceIds = firstItems(anomaly.exampleTraceIds(), 1);
        if (!traceIds.isEmpty()) {
            return summary + ", örnek traceId " + traceIds.get(0) + ".";
        }
        return summary + ".";
    }

    private String buildPrompt(
            String question,
            String answerDraft,
            List<ServiceStats> stats,
            List<Anomaly> anomalies
    ) {
        StringBuilder evidence = new StringBuilder();
        appendStats(evidence, stats);
        appendAnomalies(evidence, anomalies);

        return "Kullanıcı sorusu: " + question + "\n"
                + "Yalnızca aşağıdaki doğrulanmış yerel log kanıtlarını kullanarak kısa, Türkçe bir yanıt ver. "
                + "Bulguları kesin nedensellik olarak sunma. Anomali yanıtlarında servis, olay sayısı, zaman aralığı "
                + "ve mevcutsa örnek traceId belirt.\n"
                + "Yanıt taslağı:\n" + answerDraft + "\n"
                + "Kanıt:\n" + evidence;
    }

    private void appendStats(StringBuilder evidence, List<ServiceStats> stats) {
        int limit = Math.min(3, stats.size());
        for (int index = 0; index < limit; index++) {
            ServiceStats stat = stats.get(index);
            evidence.append("istatistik: service=").append(stat.service())
                    .append(", requestCount=").append(stat.requestCount())
                    .append(", serverErrorCount=").append(stat.serverErrorCount())
                    .append(", serverErrorRate=").append(stat.serverErrorRate())
                    .append(", p95LatencyMs=").append(stat.p95LatencyMs())
                    .append(", hourlyDistribution=").append(stat.hourlyDistribution())
                    .append('\n');
        }
    }

    private void appendAnomalies(StringBuilder evidence, List<Anomaly> anomalies) {
        int anomalyLimit = Math.min(3, anomalies.size());
        for (int index = 0; index < anomalyLimit; index++) {
            Anomaly anomaly = anomalies.get(index);
            evidence.append("anomali: type=").append(anomaly.type())
                    .append(", service=").append(anomaly.service())
                    .append(", description=").append(anomaly.description())
                    .append(", startTime=").append(anomaly.startTime())
                    .append(", endTime=").append(anomaly.endTime())
                    .append(", count=").append(anomaly.count())
                    .append(", traceIds=").append(firstItems(anomaly.exampleTraceIds(), 3))
                    .append('\n');
            appendEvidence(evidence, anomaly.evidence());
        }
    }

    private void appendEvidence(StringBuilder evidence, List<Evidence> entries) {
        if (entries == null) {
            return;
        }
        int limit = Math.min(2, entries.size());
        for (int index = 0; index < limit; index++) {
            Evidence entry = entries.get(index);
            evidence.append("  evidence: timestamp=").append(entry.timestamp())
                    .append(", message=").append(entry.message())
                    .append(", statusCode=").append(entry.statusCode())
                    .append(", clientIp=").append(entry.clientIp())
                    .append(", sku=").append(entry.sku())
                    .append(", latencyMs=").append(entry.latencyMs())
                    .append(", traceId=").append(entry.traceId())
                    .append('\n');
        }
    }

    private List<String> firstItems(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.subList(0, Math.min(limit, values.size()));
    }

    private <T> List<T> emptyIfNull(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private enum QuestionTopic {
        AUTHENTICATION,
        SERVER_ERROR,
        LATENCY,
        INVENTORY,
        SCHEDULED_JOB,
        ANOMALY,
        STATISTICS,
        UNKNOWN
    }
}

package org.example.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.example.llm.FallbackLlmClient;
import org.example.llm.LlmClient;
import org.example.model.Anomaly;
import org.example.model.Evidence;
import org.example.model.ServiceStats;
import org.example.privacy.PiiMasker;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuestionServiceTest {

    @Test
    void returnsExactUnknownAnswerWithoutCallingLlmWhenQuestionHasNoSupportingEvidence() {
        LogAnalysisService logAnalysisService = mock(LogAnalysisService.class);
        AnomalyDetectionService anomalyDetectionService = mock(AnomalyDetectionService.class);
        LlmClient llmClient = mock(LlmClient.class);
        QuestionService questionService = new QuestionService(
                logAnalysisService,
                anomalyDetectionService,
                new PiiMasker(),
                llmClient
        );

        String answer = questionService.ask("Hangi veritabanı kullanılıyor?");

        assertEquals(QuestionService.UNKNOWN_INFORMATION_ANSWER, answer);
        verifyNoInteractions(llmClient);
    }

    @Test
    void returnsUnknownWhenAnUnsupportedQuestionOnlyMentionsAKnownService() {
        LogAnalysisService logAnalysisService = mock(LogAnalysisService.class);
        AnomalyDetectionService anomalyDetectionService = mock(AnomalyDetectionService.class);
        LlmClient llmClient = mock(LlmClient.class);
        when(logAnalysisService.getServiceStats()).thenReturn(List.of(
                new ServiceStats("auth-service", 120, 0, 0.0, 90L, Map.of())
        ));
        when(anomalyDetectionService.detectAnomalies()).thenReturn(List.of());
        QuestionService questionService = new QuestionService(
                logAnalysisService,
                anomalyDetectionService,
                new PiiMasker(),
                llmClient
        );

        String answer = questionService.ask("auth-service hangi veritabanını kullanıyor?");

        assertEquals(QuestionService.UNKNOWN_INFORMATION_ANSWER, answer);
        verifyNoInteractions(llmClient);
    }

    @Test
    void sendsOnlyMaskedSelectedEvidenceToLlm() {
        LogAnalysisService logAnalysisService = mock(LogAnalysisService.class);
        AnomalyDetectionService anomalyDetectionService = mock(AnomalyDetectionService.class);
        LlmClient llmClient = mock(LlmClient.class);
        when(logAnalysisService.getServiceStats()).thenReturn(List.of(
                new ServiceStats("auth-service", 120, 0, 0.0, 90L, Map.of(10, 120L))
        ));
        when(anomalyDetectionService.detectAnomalies()).thenReturn(List.of(
                new Anomaly(
                        "AUTHENTICATION_BURST",
                        "auth-service",
                        "Repeated failed logins for user1071@example.com",
                        Instant.parse("2026-09-21T10:00:00Z"),
                        Instant.parse("2026-09-21T10:04:00Z"),
                        12,
                        List.of(new Evidence(
                                Instant.parse("2026-09-21T10:01:00Z"),
                                "POST /auth/login returned 401 for user1071@example.com",
                                401,
                                "185.220.101.47",
                                "u1071",
                                null,
                                80L,
                                "trace-1"
                        )),
                        List.of("trace-1")
                )
        ));
        when(llmClient.ask(anyString())).thenReturn("Şüpheli giriş denemeleri bulundu.");
        QuestionService questionService = new QuestionService(
                logAnalysisService,
                anomalyDetectionService,
                new PiiMasker(),
                llmClient
        );

        String answer = questionService.ask("Şüpheli login aktivitesi var mı?");

        assertEquals("Şüpheli giriş denemeleri bulundu.", answer);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).ask(prompt.capture());
        assertTrue(prompt.getValue().contains("u***@example.com"));
        assertTrue(prompt.getValue().contains("185.220.xxx.xxx"));
        assertFalse(prompt.getValue().contains("user1071@example.com"));
        assertFalse(prompt.getValue().contains("185.220.101.47"));
    }

    @Test
    void fallbackAnswerIncludesTimeRangeAndTraceIdForAnomalyEvidence() {
        LogAnalysisService logAnalysisService = mock(LogAnalysisService.class);
        AnomalyDetectionService anomalyDetectionService = mock(AnomalyDetectionService.class);
        when(logAnalysisService.getServiceStats()).thenReturn(List.of());
        when(anomalyDetectionService.detectAnomalies()).thenReturn(List.of(anomaly(
                "AUTHENTICATION_BURST",
                "auth-service",
                "2026-09-21T03:12:00Z",
                "2026-09-21T03:26:00Z",
                85,
                "auth-trace-1"
        )));

        QuestionService questionService = new QuestionService(
                logAnalysisService,
                anomalyDetectionService,
                new PiiMasker(),
                new FallbackLlmClient()
        );

        String answer = questionService.ask("Şüpheli login aktivitesi var mı?");

        assertTrue(answer.contains("auth-service"));
        assertTrue(answer.contains("85 olay"));
        assertTrue(answer.contains("2026-09-21T03:12:00Z"));
        assertTrue(answer.contains("2026-09-21T03:26:00Z"));
        assertTrue(answer.contains("auth-trace-1"));
    }

    @Test
    void selectsCheckoutErrorEvidenceForMixedTurkishLatencyAndErrorQuestion() {
        LogAnalysisService logAnalysisService = mock(LogAnalysisService.class);
        AnomalyDetectionService anomalyDetectionService = mock(AnomalyDetectionService.class);
        LlmClient llmClient = mock(LlmClient.class);
        when(logAnalysisService.getServiceStats()).thenReturn(List.of(
                new ServiceStats("checkout-service", 279, 46, 0.16, 260L, Map.of()),
                new ServiceStats("payment-service", 277, 2, 0.01, 7_180L, Map.of())
        ));
        when(anomalyDetectionService.detectAnomalies()).thenReturn(List.of(
                anomaly("SERVER_ERROR_BURST", "checkout-service", "2026-09-21T15:40:00Z", "2026-09-21T16:10:00Z", 45, "checkout-trace"),
                anomaly("HIGH_LATENCY", "payment-service", "2026-09-21T21:05:00Z", "2026-09-21T21:35:00Z", 71, "payment-trace")
        ));
        when(llmClient.ask(anyString())).thenReturn("Checkout bulgusu.");
        QuestionService questionService = new QuestionService(
                logAnalysisService,
                anomalyDetectionService,
                new PiiMasker(),
                llmClient
        );

        String answer = questionService.ask(
                "checkout servisinin yavaşladığı veya hata verdiği bir zaman aralığı var mı, nedeni ne olabilir?"
        );

        assertEquals("Checkout bulgusu.", answer);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llmClient).ask(prompt.capture());
        assertTrue(prompt.getValue().contains("checkout-service"));
        assertTrue(prompt.getValue().contains("SERVER_ERROR_BURST"));
        assertFalse(prompt.getValue().contains("payment-service"));
    }

    @Test
    void recognizesTurkishMissingScheduledJobQuestion() {
        LogAnalysisService logAnalysisService = mock(LogAnalysisService.class);
        AnomalyDetectionService anomalyDetectionService = mock(AnomalyDetectionService.class);
        when(logAnalysisService.getServiceStats()).thenReturn(List.of());
        when(anomalyDetectionService.detectAnomalies()).thenReturn(List.of(anomaly(
                "MISSING_SCHEDULED_JOB",
                "order-service",
                "2026-09-21T14:00:00Z",
                "2026-09-21T14:00:00Z",
                1,
                null
        )));
        QuestionService questionService = new QuestionService(
                logAnalysisService,
                anomalyDetectionService,
                new PiiMasker(),
                new FallbackLlmClient()
        );

        String answer = questionService.ask("Bugün çalışması gereken ancak çalışmayan bir iş var mı?");

        assertFalse(answer.equals(QuestionService.UNKNOWN_INFORMATION_ANSWER));
        assertTrue(answer.contains("MISSING_SCHEDULED_JOB"));
        assertTrue(answer.contains("order-service"));
    }

    @Test
    void narrowsGenericNightQuestionToNightAnomalies() {
        LogAnalysisService logAnalysisService = mock(LogAnalysisService.class);
        AnomalyDetectionService anomalyDetectionService = mock(AnomalyDetectionService.class);
        when(logAnalysisService.getServiceStats()).thenReturn(List.of());
        when(anomalyDetectionService.detectAnomalies()).thenReturn(List.of(
                anomaly("AUTHENTICATION_BURST", "auth-service", "2026-09-21T03:12:00Z", "2026-09-21T03:26:00Z", 85, "auth-trace"),
                anomaly("SERVER_ERROR_BURST", "checkout-service", "2026-09-21T15:40:00Z", "2026-09-21T16:10:00Z", 45, "checkout-trace")
        ));
        QuestionService questionService = new QuestionService(
                logAnalysisService,
                anomalyDetectionService,
                new PiiMasker(),
                new FallbackLlmClient()
        );

        String answer = questionService.ask("Dün gece hangi serviste sorun vardı?");

        assertTrue(answer.contains("auth-service"));
        assertFalse(answer.contains("checkout-service"));
    }

    private Anomaly anomaly(
            String type,
            String service,
            String startTime,
            String endTime,
            long count,
            String traceId
    ) {
        return new Anomaly(
                type,
                service,
                "Test anomaly",
                Instant.parse(startTime),
                Instant.parse(endTime),
                count,
                List.of(),
                traceId == null ? List.of() : List.of(traceId)
        );
    }
}

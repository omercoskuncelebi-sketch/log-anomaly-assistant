package org.example.controller;

import java.util.List;
import org.example.model.Anomaly;
import org.example.model.ServiceStats;
import org.example.service.AnomalyDetectionService;
import org.example.service.LogAnalysisService;
import org.example.service.QuestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LogAnalysisController {

    private final LogAnalysisService logAnalysisService;
    private final AnomalyDetectionService anomalyDetectionService;
    private final QuestionService questionService;

    public LogAnalysisController(
            LogAnalysisService logAnalysisService,
            AnomalyDetectionService anomalyDetectionService,
            QuestionService questionService
    ) {
        this.logAnalysisService = logAnalysisService;
        this.anomalyDetectionService = anomalyDetectionService;
        this.questionService = questionService;
    }

    @GetMapping("/stats")
    public List<ServiceStats> stats() {
        return logAnalysisService.getServiceStats();
    }

    @GetMapping("/anomalies")
    public List<Anomaly> anomalies() {
        return anomalyDetectionService.detectAnomalies();
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        return new AskResponse(questionService.ask(request.question()));
    }
}

package com.vectramoment.web;

import com.vectramoment.metrics.ApiMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class MetricsController {

    private final ApiMetricsService metricsService;

    public MetricsController(ApiMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Long>> metrics() {
        return ResponseEntity.ok(metricsService.snapshot());
    }
}


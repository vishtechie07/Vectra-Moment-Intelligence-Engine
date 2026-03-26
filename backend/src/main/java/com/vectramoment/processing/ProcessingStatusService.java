package com.vectramoment.processing;

import com.vectramoment.metrics.ApiMetricsService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProcessingStatusService {

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_EXTRACTING = "extracting";
    private static final String STATUS_EMBEDDING = "embedding";
    private static final String STATUS_READY = "ready";
    private static final String STATUS_FAILED = "failed";

    private final Map<String, StatusSnapshot> statusByVideo = new ConcurrentHashMap<>();
    private final ApiMetricsService metricsService;

    public ProcessingStatusService(ApiMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    public record StatusSnapshot(String status, long framesIndexed, String message) {}

    public void markQueued(String videoId) {
        upsert(videoId, STATUS_QUEUED, 0, "Queued for processing", "processing.queued");
    }

    public void markExtracting(String videoId) {
        upsert(videoId, STATUS_EXTRACTING, 0, "Extracting frames", "processing.extracting");
    }

    public void markEmbedding(String videoId) {
        long existing = statusByVideo.getOrDefault(videoId, new StatusSnapshot(STATUS_EMBEDDING, 0, "")).framesIndexed();
        upsert(videoId, STATUS_EMBEDDING, existing, "Analyzing and indexing frames", "processing.embedding");
    }

    public void markReady(String videoId, long framesIndexed) {
        upsert(videoId, STATUS_READY, Math.max(framesIndexed, 0), "Processing complete", "processing.ready");
    }

    public void markFailed(String videoId, String message) {
        String msg = message == null || message.isBlank() ? "Processing failed" : message;
        long existing = statusByVideo.getOrDefault(videoId, new StatusSnapshot(STATUS_FAILED, 0, msg)).framesIndexed();
        upsert(videoId, STATUS_FAILED, existing, msg, "processing.failed");
    }

    public StatusSnapshot getStatus(String videoId, long indexedFallback) {
        StatusSnapshot snapshot = statusByVideo.get(videoId);
        if (snapshot != null) {
            long frames = Math.max(snapshot.framesIndexed(), indexedFallback);
            if (STATUS_READY.equals(snapshot.status())) {
                return new StatusSnapshot(STATUS_READY, frames, snapshot.message());
            }
            return new StatusSnapshot(snapshot.status(), frames, snapshot.message());
        }
        if (indexedFallback > 0) {
            return new StatusSnapshot(STATUS_READY, indexedFallback, "Processing complete");
        }
        return new StatusSnapshot("processing", 0, "Processing");
    }

    private void upsert(String videoId, String status, long framesIndexed, String message, String metricKey) {
        if (videoId == null || videoId.isBlank()) return;
        statusByVideo.put(videoId, new StatusSnapshot(status, framesIndexed, message));
        metricsService.increment(metricKey);
    }
}


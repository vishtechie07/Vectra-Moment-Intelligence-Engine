package com.vectramoment.ingestion;

import com.vectramoment.domain.VideoMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import com.vectramoment.processing.ProcessingStatusService;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final KafkaTemplate<String, VideoIngestedEvent> kafkaTemplate;
    private final String videoIngestedTopic;
    private final Path localDir;
    private final ProcessingStatusService processingStatusService;

    public IngestionService(KafkaTemplate<String, VideoIngestedEvent> kafkaTemplate,
                            ProcessingStatusService processingStatusService,
                            @Value("${vectramoment.kafka.video-ingested-topic:video.ingested}") String videoIngestedTopic,
                            @Value("${vectramoment.storage.local-dir:}") String localDir) {
        this.kafkaTemplate = kafkaTemplate;
        this.processingStatusService = processingStatusService;
        this.videoIngestedTopic = videoIngestedTopic;
        this.localDir = (localDir != null && !localDir.isBlank()) ? Path.of(localDir) : null;
    }

    public VideoMetadata ingest(String fileName, long contentLength, InputStream inputStream, String openAiKey) throws Exception {
        var videoId = UUID.randomUUID().toString();
        processingStatusService.markQueued(videoId);
        var s3Key = "videos/" + videoId + "/" + fileName;
        if (localDir == null) {
            throw new IllegalStateException("Local storage directory not configured");
        }
        Path dir = localDir.resolve("videos").resolve(videoId);
        Files.createDirectories(dir);
        Path file = dir.resolve(sanitizeFileName(fileName));
        Files.copy(inputStream, file);
        var event = new VideoIngestedEvent(videoId, s3Key, openAiKey, file.toAbsolutePath().toString());
        log.info("Sending to topic={} videoId={} localPath={} hasKey={}", videoIngestedTopic, videoId, event.localPath() != null, openAiKey != null && !openAiKey.isBlank());
        try {
            kafkaTemplate.send(videoIngestedTopic, videoId, event).get(10, TimeUnit.SECONDS);
            log.info("Kafka send completed videoId={}", videoId);
        } catch (Exception e) {
            log.warn("Kafka send failed (video saved locally): {}", e.getMessage());
        }
        return new VideoMetadata(videoId, s3Key, fileName, contentLength, Instant.now());
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "video";
        return name.replaceAll("[:\\\\/*?\"<>|]", "_");
    }
}

package com.vectramoment.processing;

import com.vectramoment.config.KafkaConfig;
import com.vectramoment.vision.OpenAIVisionService;
import com.vectramoment.vision.SemanticFrameResult;
import com.vectramoment.search.OpenSearchVectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class VisionEmbedConsumer {

    private static final Logger log = LoggerFactory.getLogger(VisionEmbedConsumer.class);
    private final OpenAIVisionService visionService;
    private final OpenSearchVectorService searchService;
    private final ProcessingStatusService processingStatusService;

    public VisionEmbedConsumer(OpenAIVisionService visionService,
                               OpenSearchVectorService searchService,
                               ProcessingStatusService processingStatusService) {
        this.visionService = visionService;
        this.searchService = searchService;
        this.processingStatusService = processingStatusService;
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_FRAMES_READY, containerFactory = "frameReadyListenerFactory")
    public void onFramesReady(FrameReadyEvent event) {
        log.info("Received frames.ready videoId={} frames={}", event.videoId(), event.frames() != null ? event.frames().size() : 0);
        processingStatusService.markEmbedding(event.videoId());
        if (event.openAiKey() == null || event.openAiKey().isBlank()) {
            processingStatusService.markFailed(event.videoId(), "Missing OpenAI API key");
            log.warn("frames.ready for videoId={} has no OpenAI key, skipping", event.videoId());
            return;
        }
        searchService.ensureIndex();
        if (event.frames() == null || event.frames().isEmpty()) {
            processingStatusService.markFailed(event.videoId(), "No frames extracted");
            log.warn("frames.ready for videoId={} has no frames", event.videoId());
            return;
        }
        int indexed = 0;
        for (FrameEntry frame : event.frames()) {
            try {
                if (frame.localPath() == null || frame.localPath().isBlank()) {
                    log.warn("Skipping frame with missing local path videoId={} t={}", event.videoId(), frame.timestampSeconds());
                    continue;
                }
                byte[] imageBytes = Files.readAllBytes(Path.of(frame.localPath()));
                SemanticFrameResult result = visionService.analyzeFrame(imageBytes, event.openAiKey());
                searchService.indexFrame(event.videoId(), frame.timestampSeconds(), result.description(), result.embedding());
                indexed++;
            } catch (Exception e) {
                log.error("Vision/embed failed for videoId={} frame t={}", event.videoId(), frame.timestampSeconds(), e);
            }
        }
        String vid = event.videoId();
        if (indexed > 0) {
            processingStatusService.markReady(vid, indexed);
        } else {
            processingStatusService.markFailed(vid, "No frames indexed");
        }
        log.info("Indexed {} frames for videoId={}", indexed, vid);
    }
}

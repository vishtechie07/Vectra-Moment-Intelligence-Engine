package com.vectramoment.processing;

import com.vectramoment.config.KafkaConfig;
import com.vectramoment.vision.OpenAIVisionService;
import com.vectramoment.vision.SemanticFrameResult;
import com.vectramoment.search.OpenSearchVectorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import org.springframework.beans.factory.annotation.Value;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class VisionEmbedConsumer {

    private static final Logger log = LoggerFactory.getLogger(VisionEmbedConsumer.class);
    private final S3Client s3Client;
    private final OpenAIVisionService visionService;
    private final OpenSearchVectorService searchService;
    private final String framesBucket;

    public VisionEmbedConsumer(S3Client s3Client,
                               OpenAIVisionService visionService,
                               OpenSearchVectorService searchService,
                               @Value("${vectramoment.aws.s3.frames-bucket}") String framesBucket) {
        this.s3Client = s3Client;
        this.visionService = visionService;
        this.searchService = searchService;
        this.framesBucket = framesBucket;
    }

    @KafkaListener(topics = KafkaConfig.TOPIC_FRAMES_READY, containerFactory = "frameReadyListenerFactory")
    public void onFramesReady(FrameReadyEvent event) {
        log.info("Received frames.ready videoId={} frames={}", event.videoId(), event.frames() != null ? event.frames().size() : 0);
        if (event.openAiKey() == null || event.openAiKey().isBlank()) {
            log.warn("frames.ready for videoId={} has no OpenAI key, skipping", event.videoId());
            return;
        }
        searchService.ensureIndex();
        if (event.frames() == null || event.frames().isEmpty()) {
            log.warn("frames.ready for videoId={} has no frames", event.videoId());
            return;
        }
        int indexed = 0;
        for (FrameEntry frame : event.frames()) {
            try {
                byte[] imageBytes = frame.localPath() != null && !frame.localPath().isBlank()
                        ? Files.readAllBytes(Path.of(frame.localPath()))
                        : s3Client.getObject(GetObjectRequest.builder().bucket(framesBucket).key(frame.s3Key()).build()).readAllBytes();
                SemanticFrameResult result = visionService.analyzeFrame(imageBytes, event.openAiKey());
                searchService.indexFrame(event.videoId(), frame.timestampSeconds(), result.description(), result.embedding());
                indexed++;
            } catch (Exception e) {
                log.error("Vision/embed failed for videoId={} frame t={}", event.videoId(), frame.timestampSeconds(), e);
            }
        }
        String vid = event.videoId();
        log.info("Indexed {} frames for videoId={}", indexed, vid);
    }
}

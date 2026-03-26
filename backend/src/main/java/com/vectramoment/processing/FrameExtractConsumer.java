package com.vectramoment.processing;

import com.vectramoment.ingestion.VideoIngestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class FrameExtractConsumer {

    private static final Logger log = LoggerFactory.getLogger(FrameExtractConsumer.class);
    private final FrameExtractService frameExtractService;
    private final ProcessingStatusService processingStatusService;

    public FrameExtractConsumer(FrameExtractService frameExtractService, ProcessingStatusService processingStatusService) {
        this.frameExtractService = frameExtractService;
        this.processingStatusService = processingStatusService;
    }

    @KafkaListener(topics = "${vectramoment.kafka.video-ingested-topic:video.ingested}", containerFactory = "videoIngestedListenerFactory")
    public void onVideoIngested(VideoIngestedEvent event) {
        log.info("Received video.ingested videoId={} local={} hasKey={}", event.videoId(), event.localPath() != null, event.openAiKey() != null && !event.openAiKey().isBlank());
        try {
            processingStatusService.markExtracting(event.videoId());
            if (event.localPath() != null && !event.localPath().isBlank()) {
                frameExtractService.processVideoFromLocal(event.videoId(), event.localPath(), event.openAiKey());
            } else {
                frameExtractService.processVideo(event.videoId(), event.s3Key(), event.openAiKey());
            }
        } catch (Exception e) {
            processingStatusService.markFailed(event.videoId(), "Frame extraction failed");
            log.error("Frame extraction failed for videoId={}", event.videoId(), e);
        }
    }
}

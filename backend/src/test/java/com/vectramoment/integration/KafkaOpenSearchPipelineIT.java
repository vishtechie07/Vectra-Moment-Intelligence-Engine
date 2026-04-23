package com.vectramoment.integration;

import com.vectramoment.config.KafkaConfig;
import com.vectramoment.ingestion.VideoIngestedEvent;
import com.vectramoment.processing.FrameEntry;
import com.vectramoment.processing.FrameReadyEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "vectramoment.storage.local-dir=${java.io.tmpdir}/vectramoment-test",
        "vectramoment.opensearch.endpoint=http://localhost:9200"
})
@EmbeddedKafka(partitions = 1, topics = { KafkaConfig.TOPIC_VIDEO_INGESTED, KafkaConfig.TOPIC_FRAMES_READY }, brokerProperties = { "listeners=PLAINTEXT://localhost:9092", "port=9092" })
@ActiveProfiles("test")
class KafkaOpenSearchPipelineIT {

    @Autowired
    private KafkaTemplate<String, VideoIngestedEvent> videoIngestedTemplate;

    @Autowired
    private KafkaTemplate<String, FrameReadyEvent> framesReadyTemplate;

    @Test
    void canProduceVideoIngestedEvent() {
        videoIngestedTemplate.send(KafkaConfig.TOPIC_VIDEO_INGESTED, "v1",
                new VideoIngestedEvent("v1", "videos/v1/test.mp4", null));
        await().atMost(5, TimeUnit.SECONDS).until(() -> true);
    }

    @Test
    void canProduceFrameReadyEvent() {
        framesReadyTemplate.send(KafkaConfig.TOPIC_FRAMES_READY, "v1",
                new FrameReadyEvent("v1", List.of(new FrameEntry(0, "frames/v1/0.jpg")), null));
        await().atMost(5, TimeUnit.SECONDS).until(() -> true);
    }
}

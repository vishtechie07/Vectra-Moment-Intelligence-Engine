package com.vectramoment.config;

import com.vectramoment.ingestion.VideoIngestedEvent;
import com.vectramoment.processing.FrameReadyEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.lang.NonNull;

import java.util.Map;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);
    public static final String TOPIC_VIDEO_INGESTED = "video.ingested";
    public static final String TOPIC_FRAMES_READY = "frames.ready";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;
    @Value("${vectramoment.kafka.frame-extract-group:frame-extract}")
    private String frameExtractGroupId;
    @Value("${vectramoment.kafka.vision-embed-group:vision-embed}")
    private String visionEmbedGroupId;
    @Value("${vectramoment.kafka.video-ingested-topic:video.ingested}")
    private String videoIngestedTopicName;

    @Bean
    public NewTopic videoIngestedTopic() {
        return TopicBuilder.name(videoIngestedTopicName).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic framesReadyTopic() {
        return TopicBuilder.name(TOPIC_FRAMES_READY).partitions(3).replicas(1).build();
    }

    /** Ensure topics exist, then start listener containers. Retries allow Kafka to be ready after healthcheck. */
    @Bean
    public ApplicationRunner kafkaTopicInitializer(KafkaAdmin admin, KafkaListenerEndpointRegistry registry) {
        return args -> {
            for (int i = 0; i < 20; i++) {
                try {
                    admin.initialize();
                    log.info("Kafka topics initialized successfully");
                    registry.start();
                    return;
                } catch (Exception e) {
                    log.warn("Kafka topic creation attempt {} failed: {}", i + 1, e.getMessage());
                    if (i < 19) Thread.sleep(3000);
                    else throw e;
                }
            }
        };
    }

    @Bean
    public ProducerFactory<String, VideoIngestedEvent> videoIngestedProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000));
    }

    @Bean
    public KafkaTemplate<String, VideoIngestedEvent> videoIngestedKafkaTemplate(
            ProducerFactory<String, VideoIngestedEvent> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public ProducerFactory<String, FrameReadyEvent> frameReadyProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class));
    }

    @Bean
    public KafkaTemplate<String, FrameReadyEvent> framesReadyKafkaTemplate(
            ProducerFactory<String, FrameReadyEvent> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public ConsumerFactory<String, VideoIngestedEvent> videoIngestedConsumerFactory() {
        var deserializer = new JsonDeserializer<>(VideoIngestedEvent.class);
        deserializer.addTrustedPackages("com.vectramoment.*");
        deserializer.setRemoveTypeHeaders(true);
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, frameExtractGroupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 0,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10,
                ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 60_000,
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 900_000,
                ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 20_000),
                new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, VideoIngestedEvent> videoIngestedListenerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, VideoIngestedEvent>();
        factory.setConsumerFactory(videoIngestedConsumerFactory());
        factory.setConcurrency(1);
        factory.setAutoStartup(false);
        factory.setRecordInterceptor(new RecordInterceptor<String, VideoIngestedEvent>() {
            @Override
            @NonNull
            public ConsumerRecord<String, VideoIngestedEvent> intercept(@NonNull ConsumerRecord<String, VideoIngestedEvent> record, @NonNull Consumer<String, VideoIngestedEvent> consumer) {
                log.info("Container received record topic={} partition={} offset={}", record.topic(), record.partition(), record.offset());
                return record;
            }
        });
        return factory;
    }

    @Bean
    public ConsumerFactory<String, FrameReadyEvent> frameReadyConsumerFactory() {
        var deserializer = new JsonDeserializer<>(FrameReadyEvent.class);
        deserializer.addTrustedPackages("com.vectramoment.*");
        deserializer.setRemoveTypeHeaders(true);
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, visionEmbedGroupId,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 60_000,
                ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 900_000,
                ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 20_000),
                new StringDeserializer(), deserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, FrameReadyEvent> frameReadyListenerFactory() {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, FrameReadyEvent>();
        factory.setConsumerFactory(frameReadyConsumerFactory());
        factory.setConcurrency(2);
        factory.setAutoStartup(false);
        return factory;
    }
}

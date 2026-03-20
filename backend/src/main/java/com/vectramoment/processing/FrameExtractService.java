package com.vectramoment.processing;

import com.vectramoment.config.KafkaConfig;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class FrameExtractService {

    private static final Logger log = LoggerFactory.getLogger(FrameExtractService.class);
    private final S3Client s3Client;
    private final KafkaTemplate<String, FrameReadyEvent> framesReadyKafkaTemplate;
    private final String rawBucket;
    private final String framesBucket;
    private final String storageMode;
    private final Path localDir;

    public FrameExtractService(S3Client s3Client,
                               KafkaTemplate<String, FrameReadyEvent> framesReadyKafkaTemplate,
                               @Value("${vectramoment.aws.s3.raw-bucket}") String rawBucket,
                               @Value("${vectramoment.aws.s3.frames-bucket}") String framesBucket,
                               @Value("${vectramoment.storage.mode:s3}") String storageMode,
                               @Value("${vectramoment.storage.local-dir:}") String localDir) {
        this.s3Client = s3Client;
        this.framesReadyKafkaTemplate = framesReadyKafkaTemplate;
        this.rawBucket = rawBucket;
        this.framesBucket = framesBucket;
        this.storageMode = storageMode != null ? storageMode : "s3";
        this.localDir = (localDir != null && !localDir.isBlank()) ? Path.of(localDir) : null;
    }

    public void processVideo(String videoId, String s3Key, String openAiKey) throws Exception {
        Path tempVideo = Files.createTempFile("vectramoment-video-", ".mp4");
        try {
            ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(
                    GetObjectRequest.builder().bucket(rawBucket).key(s3Key).build());
            Files.write(tempVideo, stream.readAllBytes());

            List<FrameEntry> frames = new ArrayList<>();
            var converter = new Java2DFrameConverter();
            try (var grabber = new FFmpegFrameGrabber(tempVideo.toFile())) {
                grabber.start();
                long lengthMicros = grabber.getLengthInTime();
                // Use ceiling so 4.2s video yields 5 frames (1/sec), not 4
                int lengthInSeconds = lengthMicros > 0 ? (int) Math.ceil((double) lengthMicros / 1_000_000.0) : 1;
                if (lengthInSeconds <= 0) lengthInSeconds = 1;
                for (int t = 0; t < lengthInSeconds; t++) {
                    grabber.setTimestamp(t * 1_000_000L);
                    var frame = grabber.grabImage();
                    if (frame == null) continue;
                    BufferedImage img = converter.convert(frame);
                    if (img == null) continue;
                    String frameS3Key = "frames/" + videoId + "/" + t + ".jpg";
                    byte[] jpeg = toJpeg(img);
                    s3Client.putObject(PutObjectRequest.builder()
                                    .bucket(framesBucket)
                                    .key(frameS3Key)
                                    .contentType("image/jpeg")
                                    .build(),
                            RequestBody.fromBytes(jpeg));
                    frames.add(new FrameEntry(t, frameS3Key));
                }
            }
            converter.close();
            if (!frames.isEmpty()) {
                framesReadyKafkaTemplate.send(KafkaConfig.TOPIC_FRAMES_READY, videoId, new FrameReadyEvent(videoId, frames, openAiKey));
            }
        } finally {
            Files.deleteIfExists(tempVideo);
        }
    }

    /** Local mode: extract frames from local file and write to local dir; publish frames.ready with localPath. */
    public void processVideoFromLocal(String videoId, String localVideoPath, String openAiKey) throws Exception {
        log.info("Starting frame extraction videoId={} path={}", videoId, localVideoPath);
        if (localDir == null || !"local".equalsIgnoreCase(storageMode)) {
            log.warn("processVideoFromLocal skipped: videoId={} localDir={} mode={}", videoId, localDir != null, storageMode);
            return;
        }
        Path videoFile = Path.of(localVideoPath);
        if (!Files.isRegularFile(videoFile)) {
            log.warn("processVideoFromLocal skipped: video file not found videoId={} path={}", videoId, localVideoPath);
            return;
        }
        Path framesDir = localDir.resolve("frames").resolve(videoId);
        Files.createDirectories(framesDir);
        List<FrameEntry> frames = new ArrayList<>();
        var converter = new Java2DFrameConverter();
        try (var grabber = new FFmpegFrameGrabber(videoFile.toFile())) {
            try {
                grabber.start();
            } catch (Exception e) {
                log.error("FFmpegFrameGrabber.start() failed videoId={} path={}", videoId, localVideoPath, e);
                throw e;
            }
            long lengthMicros = grabber.getLengthInTime();
            // Use ceiling so 4.2s video yields 5 frames (1/sec), not 4
            int lengthInSeconds = lengthMicros > 0 ? (int) Math.ceil((double) lengthMicros / 1_000_000.0) : 1;
            if (lengthInSeconds <= 0) lengthInSeconds = 1;
            log.info("Video length {}s ({} micros), extracting {} frames videoId={}", lengthInSeconds, lengthMicros, lengthInSeconds, videoId);
            for (int t = 0; t < lengthInSeconds; t++) {
                grabber.setTimestamp(t * 1_000_000L);
                var frame = grabber.grabImage();
                if (frame == null) continue;
                BufferedImage img = converter.convert(frame);
                if (img == null) continue;
                Path frameFile = framesDir.resolve(t + ".jpg");
                ImageIO.write(img, "jpg", frameFile.toFile());
                frames.add(new FrameEntry(t, "frames/" + videoId + "/" + t + ".jpg", frameFile.toAbsolutePath().toString()));
            }
        } catch (Exception e) {
            log.error("Frame extraction error videoId={} path={}", videoId, localVideoPath, e);
            throw e;
        } finally {
            converter.close();
        }
        if (!frames.isEmpty()) {
            log.info("Extracted {} frames for videoId={}, sending to frames.ready", frames.size(), videoId);
            try {
                framesReadyKafkaTemplate.send(KafkaConfig.TOPIC_FRAMES_READY, videoId, new FrameReadyEvent(videoId, frames, openAiKey));
            } catch (Exception e) {
                log.error("Kafka send frames.ready failed videoId={}", videoId, e);
            }
        } else {
            log.warn("No frames extracted for videoId={} path={}", videoId, localVideoPath);
        }
    }

    private static byte[] toJpeg(BufferedImage img) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}

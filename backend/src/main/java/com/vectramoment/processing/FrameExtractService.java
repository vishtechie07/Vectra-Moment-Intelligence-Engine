package com.vectramoment.processing;

import com.vectramoment.config.KafkaConfig;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class FrameExtractService {

    private static final Logger log = LoggerFactory.getLogger(FrameExtractService.class);
    private final KafkaTemplate<String, FrameReadyEvent> framesReadyKafkaTemplate;
    private final Path localDir;

    public FrameExtractService(KafkaTemplate<String, FrameReadyEvent> framesReadyKafkaTemplate,
                               @Value("${vectramoment.storage.local-dir:}") String localDir) {
        this.framesReadyKafkaTemplate = framesReadyKafkaTemplate;
        this.localDir = (localDir != null && !localDir.isBlank()) ? Path.of(localDir) : null;
    }

    /** Extract ~1 fps JPEGs to disk, emit frames.ready with local paths. */
    public void processVideoFromLocal(String videoId, String localVideoPath, String openAiKey) throws Exception {
        log.info("Starting frame extraction videoId={} path={}", videoId, localVideoPath);
        if (localDir == null) {
            log.warn("processVideoFromLocal skipped: videoId={} localDirConfigured={}", videoId, false);
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

}

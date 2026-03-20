package com.vectramoment.web;

import com.vectramoment.domain.VideoMetadata;
import com.vectramoment.ingestion.IngestionService;
import com.vectramoment.search.OpenSearchVectorService;
import com.vectramoment.web.dto.VideoUploadResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/videos")
public class VideoUploadController {

    private static final Logger log = LoggerFactory.getLogger(VideoUploadController.class);
    private final IngestionService ingestionService;
    private final S3Client s3Client;
    private final OpenSearchVectorService searchService;
    private final String rawBucket;
    private final String storageMode;
    private final Path localDir;

    public VideoUploadController(IngestionService ingestionService,
                                 S3Client s3Client,
                                 OpenSearchVectorService searchService,
                                 @Value("${vectramoment.aws.s3.raw-bucket}") String rawBucket,
                                 @Value("${vectramoment.storage.mode:s3}") String storageMode,
                                 @Value("${vectramoment.storage.local-dir:}") String localDir) {
        this.ingestionService = ingestionService;
        this.s3Client = s3Client;
        this.searchService = searchService;
        this.rawBucket = rawBucket;
        this.storageMode = storageMode != null ? storageMode : "s3";
        this.localDir = (localDir != null && !localDir.isBlank()) ? Path.of(localDir) : null;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VideoUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-OpenAI-Key", required = false) String openAiKey) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            VideoMetadata meta = ingestionService.ingest(
                    file.getOriginalFilename(),
                    file.getSize(),
                    file.getInputStream(),
                    openAiKey);
            return ResponseEntity.ok(new VideoUploadResponse(
                    meta.videoId(),
                    meta.s3Key(),
                    "ingested"));
        } catch (Exception e) {
            log.error("Upload failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{videoId}/playback-url")
    public ResponseEntity<Map<String, String>> playbackUrl(@PathVariable String videoId) {
        if ("local".equalsIgnoreCase(storageMode) && localDir != null) {
            Path dir = localDir.resolve("videos").resolve(videoId);
            if (!Files.isDirectory(dir)) return ResponseEntity.notFound().build();
            try {
                Path first = Files.list(dir).filter(p -> Files.isRegularFile(p)).findFirst().orElse(null);
                if (first == null) return ResponseEntity.notFound().build();
                return ResponseEntity.ok(Map.of("url", "/api/videos/" + videoId + "/stream"));
            } catch (Exception e) {
                return ResponseEntity.notFound().build();
            }
        }
        try {
            var list = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(rawBucket)
                    .prefix("videos/" + videoId + "/")
                    .maxKeys(1)
                    .build());
            String key = list.contents().stream().findFirst().map(o -> o.key()).orElse(null);
            if (key == null) return ResponseEntity.notFound().build();
            var getReq = GetObjectRequest.builder().bucket(rawBucket).key(key).build();
            var presigner = S3Presigner.create();
            PresignedGetObjectRequest presigned = presigner.presignGetObject(
                    GetObjectPresignRequest.builder().signatureDuration(Duration.ofHours(1)).getObjectRequest(getReq).build());
            return ResponseEntity.ok(Map.of("url", presigned.url().toString()));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{videoId}/processing-status")
    public ResponseEntity<Map<String, Object>> processingStatus(@PathVariable String videoId) {
        long framesIndexed = searchService.countFramesByVideoId(videoId);
        String status = framesIndexed > 0 ? "ready" : "processing";
        return ResponseEntity.ok(Map.of("status", status, "framesIndexed", framesIndexed));
    }

    @GetMapping("/{videoId}/stream")
    public ResponseEntity<Resource> stream(@PathVariable String videoId) {
        if (localDir == null || !"local".equalsIgnoreCase(storageMode))
            return ResponseEntity.notFound().build();
        try {
            Path dir = localDir.resolve("videos").resolve(videoId);
            Path file = Files.list(dir).filter(Files::isRegularFile).findFirst().orElse(null);
            if (file == null) return ResponseEntity.notFound().build();
            Resource resource = new PathResource(file);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("video/mp4"))
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}

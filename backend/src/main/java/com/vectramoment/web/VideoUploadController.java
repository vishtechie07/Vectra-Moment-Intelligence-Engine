package com.vectramoment.web;

import com.vectramoment.domain.VideoMetadata;
import com.vectramoment.ingestion.IngestionService;
import com.vectramoment.processing.ProcessingStatusService;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/videos")
public class VideoUploadController {

    private static final Logger log = LoggerFactory.getLogger(VideoUploadController.class);
    private final IngestionService ingestionService;
    private final OpenSearchVectorService searchService;
    private final ProcessingStatusService processingStatusService;
    private final Path localDir;

    public VideoUploadController(IngestionService ingestionService,
                                 OpenSearchVectorService searchService,
                                 ProcessingStatusService processingStatusService,
                                 @Value("${vectramoment.storage.local-dir:}") String localDir) {
        this.ingestionService = ingestionService;
        this.searchService = searchService;
        this.processingStatusService = processingStatusService;
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
        if (localDir == null) return ResponseEntity.notFound().build();
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

    @GetMapping("/{videoId}/processing-status")
    public ResponseEntity<Map<String, Object>> processingStatus(@PathVariable String videoId) {
        long framesIndexed = searchService.countFramesByVideoId(videoId);
        var snapshot = processingStatusService.getStatus(videoId, framesIndexed);
        return ResponseEntity.ok(Map.of(
                "status", snapshot.status(),
                "framesIndexed", snapshot.framesIndexed(),
                "message", snapshot.message()));
    }

    @GetMapping("/{videoId}/stream")
    public ResponseEntity<Resource> stream(@PathVariable String videoId) {
        if (localDir == null) return ResponseEntity.notFound().build();
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

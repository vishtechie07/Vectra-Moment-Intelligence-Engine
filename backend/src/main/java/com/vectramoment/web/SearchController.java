package com.vectramoment.web;

import com.vectramoment.domain.IndexedFrame;
import com.vectramoment.domain.SearchHit;
import com.vectramoment.search.OpenSearchVectorService;
import com.vectramoment.vision.OpenAIVisionService;
import com.vectramoment.web.dto.SearchHitDto;
import com.vectramoment.web.dto.SearchResultDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private final OpenSearchVectorService searchService;
    private final OpenAIVisionService visionService;

    public SearchController(OpenSearchVectorService searchService, OpenAIVisionService visionService) {
        this.searchService = searchService;
        this.visionService = visionService;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam("q") String query,
            @RequestParam(value = "videoId", required = false) String videoIdFilter,
            @RequestHeader(value = "X-OpenAI-Key", required = false) String openAiKey) throws Exception {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        if (openAiKey == null || openAiKey.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "missing_api_key", "message", "Set your OpenAI API key first (Update API Key)."));
        }
        try {
            List<SearchHit> hits;
            if (videoIdFilter != null && !videoIdFilter.isBlank()) {
                List<IndexedFrame> frames = searchService.listFramesByVideoId(videoIdFilter);
                List<IndexedFrame> matching = visionService.selectMatchingFrames(query, frames, openAiKey);
                hits = matching.stream()
                        .map(f -> new SearchHit(f.videoId(), f.timestampSeconds(), f.description(), 1.0))
                        .toList();
                log.info("Search (AI comparison) q={} videoId={} hits={}", query, videoIdFilter, hits.size());
            } else {
                float[] embedding = visionService.embedQuery(query, openAiKey);
                hits = searchService.search(embedding, null);
                log.info("Search (vector) q={} hits={}", query, hits.size());
            }
            List<SearchHitDto> dtos = hits.stream()
                    .map(h -> new SearchHitDto(h.videoId(), h.timestampSeconds(), h.snippet(), h.score()))
                    .toList();
            return ResponseEntity.ok(new SearchResultDto(dtos));
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof WebClientResponseException.Unauthorized) {
                return ResponseEntity.status(401).body(Map.of("error", "invalid_api_key", "message", "Invalid OpenAI API key. Update it and try again."));
            }
            if (cause instanceof java.io.IOException || cause.getClass().getName().contains("ConnectionClosed")) {
                return ResponseEntity.status(503).body(Map.of("error", "search_unavailable", "message", "Search service unavailable. Start Docker (OpenSearch) and try again."));
            }
            throw e;
        }
    }
}

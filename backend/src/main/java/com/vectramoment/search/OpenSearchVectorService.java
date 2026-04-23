package com.vectramoment.search;

import com.vectramoment.domain.IndexedFrame;
import com.vectramoment.domain.SearchHit;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.core.IndexRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.indices.CreateIndexRequest;
import org.opensearch.client.opensearch._types.FieldValue;
import org.opensearch.client.opensearch._types.Refresh;
import org.opensearch.client.opensearch.indices.ExistsRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OpenSearchVectorService {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchVectorService.class);
    private static final String EMBEDDING_FIELD = "embedding";
    private static final int K = 20;
    private static final double MIN_SCORE_THRESHOLD = 0.45;

    private final OpenSearchClient client;

    public OpenSearchVectorService(OpenSearchClient client) {
        this.client = client;
    }

    public void ensureIndex() {
        try {
            if (client.indices().exists(ExistsRequest.of(e -> e.index(IndexName.FRAMES))).value()) {
                return;
            }
            var mapping = TypeMapping.of(m -> m
                    .properties("videoId", p -> p.keyword(k -> k))
                    .properties("timestampSeconds", p -> p.integer(i -> i))
                    .properties("description", p -> p.text(t -> t))
                    .properties(EMBEDDING_FIELD, p -> p.knnVector(v -> v.dimension(1536))));
            client.indices().create(CreateIndexRequest.of(c -> c
                    .index(IndexName.FRAMES)
                    .settings(s -> s.knn(true))
                    .mappings(mapping)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to create index", e);
        }
    }

    public void indexFrame(String videoId, int timestampSeconds, String description, float[] embedding) {
        try {
            List<Double> vec = new ArrayList<>(embedding.length);
            for (float v : embedding) {
                vec.add((double) v);
            }
            var doc = Map.<String, Object>of(
                    "videoId", videoId,
                    "timestampSeconds", timestampSeconds,
                    "description", description,
                    EMBEDDING_FIELD, vec);
            client.index(IndexRequest.of(i -> i.index(IndexName.FRAMES).document(doc).refresh(Refresh.WaitFor)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to index frame", e);
        }
    }

    public List<SearchHit> search(float[] queryEmbedding, String videoIdFilter) throws IOException {
        if (!client.indices().exists(ExistsRequest.of(e -> e.index(IndexName.FRAMES))).value()) {
            log.debug("Search skipped: index does not exist");
            return List.of();
        }
        boolean filterByVideo = videoIdFilter != null && !videoIdFilter.isBlank();
        var sr = SearchRequest.of(s -> {
            s.index(IndexName.FRAMES).size(filterByVideo ? K * 2 : K);
            s.query(q -> {
                if (filterByVideo) {
                    return q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("videoId").value(FieldValue.of(videoIdFilter))))
                            .must(m -> m.knn(k -> k.field(EMBEDDING_FIELD).vector(queryEmbedding).k(K))));
                }
                return q.knn(k -> k.field(EMBEDDING_FIELD).vector(queryEmbedding).k(K));
            });
            return s;
        });
        @SuppressWarnings("unchecked")
        SearchResponse<Map<String, Object>> response = (SearchResponse<Map<String, Object>>) (SearchResponse<?>) client.search(sr, Map.class);
        List<SearchHit> hits = parseHits(response);
        if (filterByVideo && hits.isEmpty()) {
            final String vIdFilter = videoIdFilter != null ? videoIdFilter : "";
            var fallback = SearchRequest.of(s -> s.index(IndexName.FRAMES).size(100)
                    .query(q -> q.knn(k -> k.field(EMBEDDING_FIELD).vector(queryEmbedding).k(100))));
            @SuppressWarnings("unchecked")
            SearchResponse<Map<String, Object>> fallbackResp = (SearchResponse<Map<String, Object>>) (SearchResponse<?>) client.search(fallback, Map.class);
            List<SearchHit> all = parseHits(fallbackResp);
            hits = all.stream().filter(h -> vIdFilter.equals(h.videoId())).limit(K).toList();
            log.info("Search fallback: bool+knn returned 0 hits, knn+inMemory filter returned {} hits videoId={}", hits.size(), vIdFilter);
        }
        List<SearchHit> filtered = hits.stream()
                .filter(h -> h.score() >= MIN_SCORE_THRESHOLD)
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed())
                .collect(Collectors.toList());
        if (!hits.isEmpty() && filtered.size() < hits.size()) {
            var scores = hits.stream().mapToDouble(SearchHit::score).summaryStatistics();
            log.debug("Search score filter: rawHits={} aboveThreshold={} (score range {}-{}, threshold={})",
                    hits.size(), filtered.size(), scores.getMin(), scores.getMax(), MIN_SCORE_THRESHOLD);
        }
        log.debug("Search query videoIdFilter={} rawHits={} aboveThreshold={}", videoIdFilter, hits.size(), filtered.size());
        return filtered;
    }

    private List<SearchHit> parseHits(SearchResponse<Map<String, Object>> response) {
        List<SearchHit> hits = new ArrayList<>();
        for (Hit<Map<String, Object>> hit : response.hits().hits()) {
            Map<String, Object> src = hit.source();
            if (src == null) continue;
            String vId = (String) src.get("videoId");
            Number ts = (Number) src.get("timestampSeconds");
            String desc = (String) src.get("description");
            Double scoreVal = hit.score();
            double score = scoreVal == null ? 0 : scoreVal;
            hits.add(new SearchHit(vId, ts != null ? ts.intValue() : 0, desc != null ? desc : "", score));
        }
        return hits;
    }

    public List<IndexedFrame> listFramesByVideoId(String videoId) throws IOException {
        if (videoId == null || videoId.isBlank()) return List.of();
        if (!client.indices().exists(ExistsRequest.of(e -> e.index(IndexName.FRAMES))).value()) {
            return List.of();
        }
        var sr = SearchRequest.of(s -> s
                .index(IndexName.FRAMES)
                .size(500)
                .query(q -> q.term(t -> t.field("videoId").value(FieldValue.of(videoId))))
                .sort(so -> so.field(f -> f.field("timestampSeconds").order(org.opensearch.client.opensearch._types.SortOrder.Asc))));
        @SuppressWarnings("unchecked")
        SearchResponse<Map<String, Object>> response = (SearchResponse<Map<String, Object>>) (SearchResponse<?>) client.search(sr, Map.class);
        List<IndexedFrame> out = new ArrayList<>();
        for (Hit<Map<String, Object>> hit : response.hits().hits()) {
            Map<String, Object> src = hit.source();
            if (src == null) continue;
            String vId = (String) src.get("videoId");
            Number ts = (Number) src.get("timestampSeconds");
            String desc = (String) src.get("description");
            out.add(new IndexedFrame(vId != null ? vId : videoId, ts != null ? ts.intValue() : 0, desc != null ? desc : ""));
        }
        return out;
    }

    public long countFramesByVideoId(String videoId) {
        if (videoId == null || videoId.isBlank()) return 0;
        try {
            if (!client.indices().exists(ExistsRequest.of(e -> e.index(IndexName.FRAMES))).value()) {
                return 0;
            }
            var sr = SearchRequest.of(s -> s
                    .index(IndexName.FRAMES)
                    .size(0)
                    .trackTotalHits(t -> t.count(10000))
                    .query(q -> q.term(t -> t.field("videoId").value(FieldValue.of(videoId)))));
            SearchResponse<?> response = client.search(sr, Map.class);
            var total = response.hits().total();
            return total == null ? 0 : total.value();
        } catch (Exception e) {
            return 0;
        }
    }
}

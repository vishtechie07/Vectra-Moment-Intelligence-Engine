package com.vectramoment.vision;

import com.vectramoment.domain.IndexedFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vectramoment.metrics.ApiMetricsService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OpenAIVisionService {

    private static final String VISION_MODEL = "gpt-4o";
    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final int EMBEDDING_DIM = 1536;
    private static final int MAX_API_RETRIES = 3;
    private static final Duration API_TIMEOUT = Duration.ofSeconds(45);
    private static final String METRIC_OPENAI_REQUEST_TOTAL = "openai.request.total";
    private static final String METRIC_OPENAI_RETRY_TOTAL = "openai.retry.total";
    private static final String METRIC_OPENAI_TIMEOUT_TOTAL = "openai.timeout.total";
    private static final String METRIC_OPENAI_FAILURE_TOTAL = "openai.failure.total";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ApiMetricsService metricsService;

    public OpenAIVisionService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper, ApiMetricsService metricsService) {
        this.webClient = webClientBuilder.baseUrl("https://api.openai.com/v1").build();
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
    }

    public SemanticFrameResult analyzeFrame(byte[] imageBytes, String openAiKey) {
        if (openAiKey == null || openAiKey.isBlank()) {
            throw new IllegalArgumentException("OpenAI API key required");
        }
        try {
            String description = describeImage(imageBytes, openAiKey);
            float[] embedding = embedText(description, openAiKey);
            return new SemanticFrameResult(description, embedding);
        } catch (Exception e) {
            throw new RuntimeException("OpenAI vision/embed failed", e);
        }
    }

    private String describeImage(byte[] imageBytes, String apiKey) throws Exception {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        var body = Map.of(
                "model", VISION_MODEL,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text", "text", "Describe this video frame in one concise sentence for semantic search. Focus on actions, objects, people, and setting."),
                                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/jpeg;base64," + base64))
                        )
                )),
                "max_tokens", 150
        );
        String json = postWithRetry("/chat/completions", body, apiKey);
        JsonNode root = objectMapper.readTree(json);
        return extractMessageContent(root);
    }

    public float[] embedQuery(String text, String apiKey) {
        try {
            return embedText(text, apiKey);
        } catch (Exception e) {
            throw new RuntimeException("Embed failed", e);
        }
    }

    /** LLM picks matching frame timestamps from descriptions. */
    public List<IndexedFrame> selectMatchingFrames(String query, List<IndexedFrame> frames, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("OpenAI API key required");
        if (frames == null || frames.isEmpty()) return List.of();
        try {
            StringBuilder frameList = new StringBuilder();
            for (IndexedFrame f : frames) {
                frameList.append(String.format("[%d] %s%n", f.timestampSeconds(), f.description()));
            }
            String systemPrompt = "You are a precise assistant. Given a user search query and a list of video frame descriptions (each prefixed with [timestamp in seconds]), output ONLY a JSON object with a single key \"timestamps\" whose value is an array of the timestamp(s) (as integers) of frames that match the search query. Match by meaning: e.g. 'publish' matches a frame describing a Publish button, 'finger' matches a frame describing a finger or hand. If no frame matches, return {\"timestamps\": []}. Do not include any other text or explanation.";
            String userContent = "Search query: \"" + query + "\"\n\nFrame descriptions:\n" + frameList;
            String raw = chatCompletion(systemPrompt, userContent, apiKey);
            Set<Integer> matching = extractTimestamps(raw);
            return frames.stream().filter(f -> matching.contains(f.timestampSeconds())).toList();
        } catch (Exception e) {
            throw new RuntimeException("AI frame selection failed", e);
        }
    }

    private Set<Integer> extractTimestamps(String raw) {
        Set<Integer> matching = new HashSet<>();
        if (raw == null || raw.isBlank()) return matching;
        String cleaned = raw.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
        try {
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode timestampsNode = root.has("timestamps") ? root.get("timestamps") : root;
            if (timestampsNode.isArray()) {
                for (JsonNode n : timestampsNode) {
                    if (n.isNumber()) matching.add(n.asInt());
                }
                return matching;
            }
        } catch (Exception ignored) { /* parse numbers from text */ }
        Matcher matcher = Pattern.compile("\\b\\d+\\b").matcher(cleaned);
        while (matcher.find()) {
            matching.add(Integer.parseInt(matcher.group()));
        }
        return matching;
    }

    private String chatCompletion(String systemPrompt, String userContent, String apiKey) throws Exception {
        var body = Map.of(
                "model", VISION_MODEL,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userContent)
                ),
                "max_tokens", 300
        );
        String json = postWithRetry("/chat/completions", body, apiKey);
        JsonNode root = objectMapper.readTree(json);
        return extractMessageContent(root);
    }

    private float[] embedText(String text, String apiKey) throws Exception {
        var body = Map.of(
                "model", EMBEDDING_MODEL,
                "input", text
        );
        String json = postWithRetry("/embeddings", body, apiKey);
        JsonNode root = objectMapper.readTree(json);
        JsonNode embeddingNode = root.path("data").get(0).path("embedding");
        float[] embedding = new float[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM && i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }

    private String postWithRetry(String uri, Object body, String apiKey) throws Exception {
        Exception last = null;
        metricsService.increment(METRIC_OPENAI_REQUEST_TOTAL);
        for (int attempt = 1; attempt <= MAX_API_RETRIES; attempt++) {
            try {
                return webClient.post()
                        .uri(uri)
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(API_TIMEOUT);
            } catch (Exception e) {
                last = e;
                if (isTimeout(e)) metricsService.increment(METRIC_OPENAI_TIMEOUT_TOTAL);
                if (!isRetryable(e) || attempt == MAX_API_RETRIES) break;
                metricsService.increment(METRIC_OPENAI_RETRY_TOTAL);
                sleepWithJitter(attempt);
            }
        }
        metricsService.increment(METRIC_OPENAI_FAILURE_TOTAL);
        throw last != null ? last : new RuntimeException("OpenAI request failed");
    }

    private boolean isRetryable(Exception e) {
        if (e instanceof WebClientResponseException w) {
            int status = w.getStatusCode().value();
            return status == 429 || status >= 500;
        }
        return true;
    }

    private void sleepWithJitter(int attempt) throws InterruptedException {
        long baseMs = Math.min(2000L * attempt, 5000L);
        long jitterMs = ThreadLocalRandom.current().nextLong(100, 500);
        Thread.sleep(baseMs + jitterMs);
    }

    private boolean isTimeout(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof TimeoutException) return true;
            cur = cur.getCause();
        }
        return false;
    }

    private static String extractMessageContent(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) return "";
        return choices.get(0).path("message").path("content").asText("");
    }
}

package com.vectramoment.vision;

import com.vectramoment.domain.IndexedFrame;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OpenAIVisionService {

    private static final String VISION_MODEL = "gpt-4o";
    private static final String EMBEDDING_MODEL = "text-embedding-3-small";
    private static final int EMBEDDING_DIM = 1536;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public OpenAIVisionService(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.baseUrl("https://api.openai.com/v1").build();
        this.objectMapper = objectMapper;
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
        String json = webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        JsonNode root = objectMapper.readTree(json);
        return root.path("choices").get(0).path("message").path("content").asText();
    }

    public float[] embedQuery(String text, String apiKey) {
        try {
            return embedText(text, apiKey);
        } catch (Exception e) {
            throw new RuntimeException("Embed failed", e);
        }
    }

    /**
     * Uses the LLM to select which frames match the search query (AI comparison).
     * Sends the query and frame descriptions; returns only frames the model considers relevant.
     */
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
            String json = raw.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("\\s*```$", "").trim();
            JsonNode root = objectMapper.readTree(json);
            JsonNode timestampsNode = root.has("timestamps") ? root.get("timestamps") : root;
            if (!timestampsNode.isArray()) return List.of();
            Set<Integer> matching = new HashSet<>();
            for (JsonNode n : timestampsNode) {
                if (n.isInt()) matching.add(n.asInt());
                else if (n.isNumber()) matching.add(n.asInt());
            }
            return frames.stream().filter(f -> matching.contains(f.timestampSeconds())).toList();
        } catch (Exception e) {
            throw new RuntimeException("AI frame selection failed", e);
        }
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
        String json = webClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        JsonNode root = objectMapper.readTree(json);
        return root.path("choices").get(0).path("message").path("content").asText();
    }

    private float[] embedText(String text, String apiKey) throws Exception {
        var body = Map.of(
                "model", EMBEDDING_MODEL,
                "input", text
        );
        String json = webClient.post()
                .uri("/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        JsonNode root = objectMapper.readTree(json);
        JsonNode embeddingNode = root.path("data").get(0).path("embedding");
        float[] embedding = new float[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM && i < embeddingNode.size(); i++) {
            embedding[i] = (float) embeddingNode.get(i).asDouble();
        }
        return embedding;
    }
}

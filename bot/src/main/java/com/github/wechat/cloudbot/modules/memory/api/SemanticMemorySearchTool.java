package com.github.wechat.cloudbot.modules.memory.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolArgs;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import com.github.wechat.cloudbot.platform.persistence.ChatMemoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent semantic-memory tool backed by DashScope text embeddings.
 *
 * <p>Vectors are computed on demand from the retained chat history. This keeps
 * the existing JSON persistence format compatible; a later repository can
 * persist vectors without changing the Function Calling contract.
 */
@Component
public class SemanticMemorySearchTool implements AgentTool {

    private final RestClient restClient;
    private final ChatMemoryRepository memoryStore;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public SemanticMemorySearchTool(
            RestClient.Builder builder,
            ChatMemoryRepository memoryStore,
            ObjectMapper objectMapper,
            @Value("${embedding.api-key:${qwen.api-key:${DASHSCOPE_API_KEY:}}}") String apiKey,
            @Value("${embedding.model:text-embedding-v3}") String model) {
        // 显式配置 connect/read 超时：默认 JdkClientHttpRequestFactory 读超时无限，
        // embedding 接口挂起时该工具会无限阻塞 Agent 线程
        this.restClient = builder
                .requestFactory(requestFactoryWithTimeouts())
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .build();
        this.memoryStore = memoryStore;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    private static ClientHttpRequestFactory requestFactoryWithTimeouts() {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    /**
     * Registers semantic_memory_search for Agent Function Calling.
     *
     * @return tool definition and JSON schema
     */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "semantic_memory_search",
                "按语义相似度检索当前用户的历史对话。用户换一种说法询问过去内容时调用。",
                """
                {"type":"object","properties":{"query":{"type":"string","description":"要检索的历史问题或内容"},"topK":{"type":"integer","minimum":1,"maximum":10,"default":5}},"required":["query"]}
                """);
    }

    /**
     * Embeds the query and retained history, then returns the nearest messages.
     *
     * @param call Function Calling request; userId is injected by the Agent layer
     * @return ranked semantic matches or a diagnostic failure
     */
    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String query = ToolArgs.text(args, "query");
            // 编排层注入字段为 user_id；兼容 userId / ToolCall.userId
            String userId = ToolArgs.resolveUserId(call, args);
            int topK = Math.max(1, Math.min(10, ToolArgs.intValue(args, "topK", 5)));
            if (query.isBlank()) return ToolResult.fail(call.id(), call.name(), "query empty");
            if (userId.isBlank()) return ToolResult.fail(call.id(), call.name(), "userId missing");
            if (apiKey == null || apiKey.isBlank()) {
                return ToolResult.fail(call.id(), call.name(), "DASHSCOPE_API_KEY 未配置");
            }

            List<ChatMessage> history = memoryStore.getHistory(userId);
            List<ChatMessage> candidates = history.stream()
                    .filter(m -> m.content() instanceof String text && !text.isBlank())
                    .toList();
            if (candidates.isEmpty()) return ToolResult.ok(call.id(), call.name(), "没有可检索的历史对话。");

            List<String> inputs = new ArrayList<>(candidates.size() + 1);
            inputs.add(query);
            candidates.forEach(m -> inputs.add((String) m.content()));
            List<double[]> vectors = embed(inputs);
            double[] queryVector = vectors.get(0);
            List<Match> matches = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                double similarity = cosine(queryVector, vectors.get(i + 1));
                matches.add(new Match(candidates.get(i).role(), (String) candidates.get(i).content(), similarity));
            }
            matches.sort(Comparator.comparingDouble(Match::similarity).reversed());
            String result = matches.stream().limit(topK)
                    .map(m -> String.format("[%.3f][%s] %s", m.similarity(), m.role(), m.content()))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("没有找到相关历史对话。");
            return ToolResult.ok(call.id(), call.name(), result);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "semantic memory search failed: " + e.getMessage());
        }
    }

    private List<double[]> embed(List<String> inputs) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("input", inputs);
        body.put("encoding_format", "float");
        JsonNode root = restClient.post()
                .uri("/embeddings")
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (root == null || !root.path("data").isArray()) {
            throw new IllegalStateException("embedding API returned no data");
        }
        List<double[]> vectors = new ArrayList<>();
        for (JsonNode row : root.path("data")) {
            JsonNode values = row.path("embedding");
            double[] vector = new double[values.size()];
            for (int i = 0; i < values.size(); i++) vector[i] = values.get(i).asDouble();
            vectors.add(vector);
        }
        if (vectors.size() != inputs.size()) throw new IllegalStateException("embedding result count mismatch");
        return vectors;
    }

    private static double cosine(double[] left, double[] right) {
        if (left.length != right.length || left.length == 0) return -1;
        double dot = 0, leftNorm = 0, rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private record Match(String role, String content, double similarity) {}
}

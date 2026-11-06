package com.github.wechat.cloudbot.modules.chat.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.chat.api.LlmGateway;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolRegistry;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.platform.config.Strings;
import com.github.wechat.cloudbot.platform.model.ChatMessage;
import com.github.wechat.cloudbot.platform.model.LlmTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DeepSeek Chat Completions（OpenAI 兼容）。tools 定义来自 {@link ToolRegistry}。
 */
@Component
public class DeepSeekClient implements LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final RestClient restClient;
    private final DeepSeekProperties properties;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;

    /** 工具参数 Schema 缓存：名称 → JsonNode，避免每次 LLM 调用都解析（Phase 1 优化） */
    private final ConcurrentHashMap<String, com.fasterxml.jackson.databind.JsonNode> schemaCache =
            new ConcurrentHashMap<>();

    public DeepSeekClient(
            @Qualifier("deepSeekRestClient") RestClient restClient,
            DeepSeekProperties properties,
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        LlmTurn turn = chatTurn(messages, false);
        if (!turn.hasText()) {
            throw new IllegalStateException("expected text reply from DeepSeek");
        }
        return turn.content().trim();
    }

    @Override
    public LlmTurn chatTurn(List<ChatMessage> messages, boolean enableTools) {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("DeepSeek is disabled");
        }
        if (!properties.hasApiKey()) {
            throw new IllegalStateException("DeepSeek api-key is empty (set DEEPSEEK_API_KEY)");
        }
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getModel());
        // 思考模式（V4/Reasoner 系列）不支持 temperature，传了会被 API 拒绝或忽略
        if (!isThinkingModel()) {
            body.put("temperature", properties.getTemperature());
        }
        body.put("messages", toApiMessages(messages));
        if (enableTools) {
            List<Map<String, Object>> tools = toApiTools(toolRegistry.definitions());
            if (!tools.isEmpty()) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }
        }

        try {
            String raw =
                    restClient
                            .post()
                            .uri("/chat/completions")
                            .body(body)
                            .retrieve()
                            .body(String.class);

            return parseTurn(raw);
        } catch (RestClientResponseException e) {
            log.error("{}DeepSeek HTTP {} : {}",
                    ErrorCode.CHAT_API_ERROR.prefix(),
                    e.getStatusCode().value(),
                   Strings.truncate(e.getResponseBodyAsString(), 300));
            throw new IllegalStateException("DeepSeek API error: " + e.getStatusCode().value(), e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("DeepSeek parse failed: " + e.getMessage(), e);
        }
    }

    private LlmTurn parseTurn(String raw) throws Exception {
        JsonNode root = objectMapper.readTree(raw);
        JsonNode message = root.path("choices").path(0).path("message");
        String content = message.path("content").asText(null);
        if (content != null) {
            content = content.isBlank() ? null : content.trim();
        }

        // 捕获 reasoning_content（DeepSeek 思考模式返回的推理过程）
        // 回传 API 时必须带上，否则会报 400 错误
        // 注意：即使 reasoning_content 为空字符串也不能丢弃，
        // 因为 API 检查的是字段是否存在（而非内容是否为空）
        String reasoningContent = message.path("reasoning_content").asText(null);
        if (log.isDebugEnabled()) {
            log.debug("parseTurn: reasoning_content present={}, length={}",
                    reasoningContent != null, reasoningContent != null ? reasoningContent.length() : 0);
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isArray()) {
            for (JsonNode call : toolCallsNode) {
                String id = call.path("id").asText("");
                String name = call.path("function").path("name").asText("");
                String argsJson = call.path("function").path("arguments").asText("{}");
                if (name.isBlank()) {
                    continue;
                }
                if (id.isBlank()) {
                    id = "call_" + toolCalls.size();
                }
                toolCalls.add(new ToolCall(id, name, argsJson));
            }
        }

        if (!toolCalls.isEmpty()) {
            return LlmTurn.of(content, toolCalls, reasoningContent);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("DeepSeek returned empty content");
        }
        return LlmTurn.of(content, List.of(), reasoningContent);
    }

    private List<Map<String, Object>> toApiTools(List<ToolDefinition> definitions) {
        List<Map<String, Object>> tools = new ArrayList<>(definitions.size());
        for (ToolDefinition def : definitions) {
            if (def == null || def.name() == null || def.name().isBlank()) {
                continue;
            }
            Object parameters = schemaCache.get(def.name());
            if (parameters == null) {
                try {
                    String schema = def.parametersJsonSchema() == null
                            || def.parametersJsonSchema().isBlank()
                            ? "{\"type\":\"object\",\"properties\":{}}"
                            : def.parametersJsonSchema();
                    parameters = objectMapper.readTree(schema);
                    schemaCache.put(def.name(), (com.fasterxml.jackson.databind.JsonNode) parameters);
                } catch (Exception e) {
                    log.warn("tool schema parse failed name={}: {}", def.name(), e.getMessage());
                    parameters = Map.of("type", "object", "properties", Map.of());
                }
            }

            Map<String, Object> function = new HashMap<>();
            function.put("name", def.name());
            function.put(
                    "description", def.description() == null ? def.name() : def.description());
            function.put("parameters", parameters);

            Map<String, Object> tool = new HashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            tools.add(tool);
        }
        return tools;
    }

    private static List<Map<String, Object>> toApiMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> list = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            Map<String, Object> item = new HashMap<>();
            item.put("role", m.role());
            if ("tool".equals(m.role())) {
                item.put("tool_call_id", m.toolCallId());
                item.put("content", m.content() == null ? "" : m.content());
            } else if (m.hasToolCalls()) {
                if (m.content() != null) {
                    item.put("content", m.content());
                } else {
                    item.put("content", null);
                }
                // 思考模式：带 tool_calls 的 assistant 消息必须回传 reasoning_content，否则 API 报 400
                // 即使 reasoning_content 为空字符串也必须传回（API 检查字段存在性）
                if (m.reasoningContent() != null) {
                    item.put("reasoning_content", m.reasoningContent());
                }
                List<Map<String, Object>> calls = new ArrayList<>(m.toolCalls().size());
                for (ChatMessage.FunctionCall fc : m.toolCalls()) {
                    Map<String, Object> fn = new HashMap<>();
                    fn.put("name", fc.name());
                    fn.put(
                            "arguments",
                            fc.argumentsJson() == null || fc.argumentsJson().isBlank()
                                    ? "{}"
                                    : fc.argumentsJson());
                    Map<String, Object> call = new HashMap<>();
                    call.put("id", fc.id());
                    call.put("type", "function");
                    call.put("function", fn);
                    calls.add(call);
                }
                item.put("tool_calls", calls);
            } else {
                item.put("content", m.content());
                // DeepSeek 思考模式：非 tool_calls 的 assistant 消息不能包含 reasoning_content，
                // 否则 API 会报 400 错误。
                // 历史消息（从 appendTurn 加载）本身就不带 reasoning_content，无需处理。
            }
            list.add(item);
        }
        return list;
    }

    /** 判断当前模型是否为思考模式（V4 系列或 Reasoner 系列）。
     *  思考模式下 API 不支持 temperature 参数，且要求 tool_calls 消息回传 reasoning_content。 */
    private boolean isThinkingModel() {
        String model = properties.getModel();
        return model != null && (model.contains("v4") || model.contains("reasoner"));
    }

}

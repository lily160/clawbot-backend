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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent memory tool: searches the current user's persisted conversation history.
 * This is intentionally lexical; a vector store can replace it later without
 * changing the Function Calling contract.
 */
@Component
public class MemorySearchTool implements AgentTool {

    private final ChatMemoryRepository memoryStore;
    private final ObjectMapper objectMapper;

    public MemorySearchTool(ChatMemoryRepository memoryStore, ObjectMapper objectMapper) {
        this.memoryStore = memoryStore;
        this.objectMapper = objectMapper;
    }

    /**
     * 向 Agent 注册 memory_search Function Calling 工具。
     *
     * @return 工具名称、用途描述和参数 JSON Schema
     */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "memory_search",
                "检索当前用户过去的对话内容。当用户询问之前聊过什么、历史决定或曾提到的信息时调用；不能用来检索其他用户。",
                """
                {"type":"object","properties":{"query":{"type":"string","description":"要检索的历史内容或关键词"},"topK":{"type":"integer","minimum":1,"maximum":10,"default":5,"description":"最多返回的相关消息数量"}},"required":["query"]}
                """);
    }

    /**
     * 执行一次历史记忆检索。
     *
     * <p>工具会根据当前用户 ID 查询其保存的对话历史，按关键词匹配度排序后返回结果。
     *
     * @param call Agent 发起的工具调用，参数包括 query、topK 和由编排层注入的 userId
     * @return 匹配到的历史消息，或包含失败原因的工具结果
     */
    @Override
    public ToolResult execute(ToolCall call) {
        try {
            JsonNode args = ToolArgs.parse(objectMapper, call);
            String query = ToolArgs.text(args, "query");
            if (query.isBlank()) return ToolResult.fail(call.id(), call.name(), "query empty");
            // 编排层注入字段为 user_id；兼容 userId / ToolCall.userId
            String userId = ToolArgs.resolveUserId(call, args);
            if (userId.isBlank()) return ToolResult.fail(call.id(), call.name(), "userId missing");
            int topK = Math.max(1, Math.min(10, ToolArgs.intValue(args, "topK", 5)));

            Set<String> terms = terms(query);
            List<Match> matches = new ArrayList<>();
            for (ChatMessage message : memoryStore.getHistory(userId)) {
                if (!(message.content() instanceof String text) || text.isBlank()) continue;
                int score = score(text, terms);
                if (score > 0) matches.add(new Match(message.role(), text, score));
            }
            matches.sort(Comparator.comparingInt(Match::score).reversed());
            if (matches.isEmpty()) return ToolResult.ok(call.id(), call.name(), "没有找到相关历史对话。");

            String result = matches.stream().limit(topK)
                    .map(m -> "[" + m.role() + "] " + m.content())
                    .collect(Collectors.joining("\n"));
            return ToolResult.ok(call.id(), call.name(), result);
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "memory search failed: " + e.getMessage());
        }
    }

    private static Set<String> terms(String query) {
        Set<String> result = new HashSet<>();
        for (String part : query.toLowerCase(Locale.ROOT).split("[\\s，。！？、,.!?]+")) {
            if (!part.isBlank()) result.add(part);
        }
        // Chinese has no spaces; use short overlapping fragments for useful lexical matching.
        String compact = query.replaceAll("[\\s，。！？、,.!?]", "");
        for (int i = 0; i + 1 < compact.length(); i++) result.add(compact.substring(i, i + 2));
        return result;
    }

    private static int score(String content, Set<String> terms) {
        String normalized = content.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) if (normalized.contains(term)) score++;
        return score;
    }

    private record Match(String role, String content, int score) {}
}

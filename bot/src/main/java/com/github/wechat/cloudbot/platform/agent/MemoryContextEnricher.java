package com.github.wechat.cloudbot.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.cloudbot.platform.persistence.ChatMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 记忆搜索工具上下文注入器：向 {@code memory_search} 工具参数中注入对话历史概况。
 *
 * <p>让 LLM 在搜索记忆前能感知当前对话的长度和大致范围，辅助生成更精准的搜索关键词。
 */
@Component
public class MemoryContextEnricher implements ToolContextEnricher {

    private static final Logger log = LoggerFactory.getLogger(MemoryContextEnricher.class);

    private final ChatMemoryRepository memoryStore;

    public MemoryContextEnricher(ChatMemoryRepository memoryStore) {
        this.memoryStore = memoryStore;
    }

    @Override
    public boolean supports(String toolName) {
        return "memory_search".equals(toolName);
    }

    @Override
    public String enrich(String argumentsJson, String userId, String userContent) {
        return argumentsJson;
    }

    @Override
    public ObjectNode enrichNode(ObjectNode args, String userId, String userContent,
                                  ObjectMapper mapper) {
        int historyCount = memoryStore.getHistory(userId).size();
        args.put("_history_messages", historyCount);
        if (userContent != null && !userContent.isBlank()) {
            args.put("_user_intent", userContent);
        }
        return args;
    }
}

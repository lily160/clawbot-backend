package com.github.wechat.cloudbot.platform.agent.plan;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 组长阶段先用内存实现，后续再替换为持久化仓储。
 */
@Component
public class InMemoryPlanRepository implements PlanRepository {

    private final Map<String, PlanDraft> storage = new ConcurrentHashMap<>();

    @Override
    public void save(String userId, PlanDraft draft) {
        if (userId == null || userId.isBlank() || draft == null) {
            return;
        }
        storage.put(userId, draft);
    }

    @Override
    public Optional<PlanDraft> load(String userId) {
        return Optional.ofNullable(storage.get(userId));
    }

    @Override
    public void clear(String userId) {
        if (userId != null) {
            storage.remove(userId);
        }
    }
}

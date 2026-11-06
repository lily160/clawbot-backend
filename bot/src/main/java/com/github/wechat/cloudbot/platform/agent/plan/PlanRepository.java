package com.github.wechat.cloudbot.platform.agent.plan;

import java.util.Optional;

/**
 * 暂存 WAIT_USER 等待点的最小仓储契约。
 */
public interface PlanRepository {

    void save(String userId, PlanDraft draft);

    Optional<PlanDraft> load(String userId);

    void clear(String userId);
}

package com.github.wechat.cloudbot.platform.agent.plan;

/**
 * 主流程编排中的单步动作类型。
 */
public enum PlanAction {
    CALL_TOOL,
    WAIT_USER,
    REPLY,
    END
}

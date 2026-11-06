package com.github.wechat.cloudbot.platform.skill;

import com.github.wechat.cloudbot.platform.agent.AgentTool;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolDefinition;
import com.github.wechat.cloudbot.platform.agent.ToolResult;

/**
 * 将 Skill 接入现有 AgentTool 执行链的适配器。
 */
final class SkillAgentToolAdapter implements AgentTool {

    private final Skill skill;

    SkillAgentToolAdapter(Skill skill) {
        this.skill = skill;
    }

    @Override
    public ToolDefinition definition() {
        return skill.definition();
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            return ToolResult.ok(
                    call.id(),
                    call.name(),
                    skill.execute(call.argumentsJson(), call.userId()));
        } catch (Exception e) {
            return ToolResult.fail(call.id(), call.name(), "Skill 执行失败：" + e.getMessage());
        }
    }
}

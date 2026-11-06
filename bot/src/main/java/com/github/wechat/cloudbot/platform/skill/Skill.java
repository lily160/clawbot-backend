package com.github.wechat.cloudbot.platform.skill;

import com.github.wechat.cloudbot.platform.agent.ToolDefinition;

/**
 * 可由 Agent 动态调用的 Skill 协议。
 *
 * <p>Skill 只暴露 Function Calling 定义和一次执行方法。加载位置、文件解析、
 * 注册、重载以及错误转换均由 {@link SkillManager} 隐藏，调用方不需要知道具体
 * Skill 的实现类型。
 */
public interface Skill {

    /**
     * 返回给大模型的 Function Calling 定义。
     *
     * @return 名称、用途说明和参数 JSON Schema
     */
    ToolDefinition definition();

    /**
     * 执行 Skill。
     *
     * @param argumentsJson 大模型产生的 JSON 参数
     * @param userId 当前用户标识，可能为空
     * @return 需要交还给 Agent 循环继续处理的结果
     */
    String execute(String argumentsJson, String userId);
}

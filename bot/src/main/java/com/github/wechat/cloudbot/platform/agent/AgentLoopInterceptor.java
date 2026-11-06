package com.github.wechat.cloudbot.platform.agent;

import com.github.wechat.cloudbot.platform.model.LlmTurn;

/**
 * Agent 循环拦截器。在循环的关键阶段插入自定义逻辑。
 *
 * <p>所有方法均为 default，实现者只需覆盖关心的阶段。
 */
public interface AgentLoopInterceptor {

    /** LLM 调用前执行。返回 false 则跳过本次 LLM 调用。 */
    default boolean preLlmCall(AgentLoopContext ctx) { return true; }

    /** LLM 调用后执行。 */
    default void postLlmCall(AgentLoopContext ctx, LlmTurn turn) {}

    /** 工具分发前执行。返回 false 则跳过该工具。 */
    default boolean preToolDispatch(AgentLoopContext ctx, ToolCall call) { return true; }

    /** 工具分发后执行。 */
    default void postToolDispatch(AgentLoopContext ctx, ToolCall call, ToolResult result) {}

    /** 整个循环成功完成时执行。 */
    default void onComplete(AgentLoopContext ctx, String reply) {}

    /** 循环抛出异常时执行。 */
    default void onError(AgentLoopContext ctx, Throwable error) {}
}

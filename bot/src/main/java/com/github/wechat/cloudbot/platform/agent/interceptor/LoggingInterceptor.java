package com.github.wechat.cloudbot.platform.agent.interceptor;

import com.github.wechat.cloudbot.platform.agent.AgentLoopContext;
import com.github.wechat.cloudbot.platform.agent.AgentLoopInterceptor;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import com.github.wechat.cloudbot.platform.agent.ToolResult;
import com.github.wechat.cloudbot.platform.model.LlmTurn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 记录 Agent 循环每个环节的耗时和关键参数。
 */
@Component
public class LoggingInterceptor implements AgentLoopInterceptor {

    private static final Logger log = LoggerFactory.getLogger(LoggingInterceptor.class);
    private final ThreadLocal<Instant> llmStart = new ThreadLocal<>();
    private final ThreadLocal<Instant> toolStart = new ThreadLocal<>();

    @Override
    public boolean preLlmCall(AgentLoopContext ctx) {
        llmStart.set(Instant.now());
        log.debug("[Agent] Round {}/{} LLM 调用开始 userId={}",
                ctx.getCurrentRound(), ctx.getMaxRounds(), ctx.getUserId());
        return true;
    }

    @Override
    public void postLlmCall(AgentLoopContext ctx, LlmTurn turn) {
        Instant start = llmStart.get();
        if (start != null) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            boolean hasTools = turn != null && turn.hasToolCalls();
            log.info("[Agent] Round {}/{} LLM 完成耗时={}ms hasTools={} userId={}",
                    ctx.getCurrentRound(), ctx.getMaxRounds(),
                    ms, hasTools, ctx.getUserId());
        }
        llmStart.remove();
    }

    @Override
    public boolean preToolDispatch(AgentLoopContext ctx, ToolCall call) {
        toolStart.set(Instant.now());
        log.debug("[Agent] Round {}/{} 工具调用 name={} userId={}",
                ctx.getCurrentRound(), ctx.getMaxRounds(), call.name(), ctx.getUserId());
        return true;
    }

    @Override
    public void postToolDispatch(AgentLoopContext ctx, ToolCall call, ToolResult result) {
        Instant start = toolStart.get();
        if (start != null) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            log.info("[Agent] Round {}/{} 工具完成 name={} success={}耗时={}ms userId={}",
                    ctx.getCurrentRound(), ctx.getMaxRounds(),
                    call.name(), result.success(), ms, ctx.getUserId());
        }
        toolStart.remove();
    }

    @Override
    public void onComplete(AgentLoopContext ctx, String reply) {
        if (ctx == null) {
            log.info("[Agent] 循环完成，但上下文为空");
            return;
        }
        long totalMs = Duration.between(ctx.getStartTime(), Instant.now()).toMillis();
        log.info("[Agent] 循环完成 rounds={} 总耗时={}ms userId={}",
                ctx.getCurrentRound(), totalMs, ctx.getUserId());
    }

    @Override
    public void onError(AgentLoopContext ctx, Throwable error) {
        if (ctx == null) {
            log.error("[Agent] 循环异常，但上下文为空: {}", error == null ? "unknown" : error.getMessage());
            return;
        }
        long totalMs = Duration.between(ctx.getStartTime(), Instant.now()).toMillis();
        log.error("[Agent] 循环异常 userId={} rounds={} 耗时={}ms: {}",
                ctx.getUserId(), ctx.getCurrentRound(), totalMs,
                error == null ? "unknown" : error.getMessage());
    }
}

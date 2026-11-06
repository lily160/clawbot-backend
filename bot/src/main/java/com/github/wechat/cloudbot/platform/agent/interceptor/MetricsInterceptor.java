package com.github.wechat.cloudbot.platform.agent.interceptor;

import com.github.wechat.cloudbot.platform.agent.AgentLoopContext;
import com.github.wechat.cloudbot.platform.agent.AgentLoopInterceptor;
import com.github.wechat.cloudbot.platform.agent.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 采集 Agent 循环的运行指标：轮次、工具调用次数、LLM 耗时等。
 */
@Component
public class MetricsInterceptor implements AgentLoopInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MetricsInterceptor.class);

    private final ThreadLocal<Integer> toolCount = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Instant> loopStart = new ThreadLocal<>();

    @Override
    public boolean preLlmCall(AgentLoopContext ctx) {
        if (ctx.getCurrentRound() == 1) {
            loopStart.set(Instant.now());
        }
        return true;
    }

    @Override
    public boolean preToolDispatch(AgentLoopContext ctx, ToolCall call) {
        toolCount.set(toolCount.get() + 1);
        return true;
    }

    @Override
    public void onComplete(AgentLoopContext ctx, String reply) {
        Instant start = loopStart.get();
        if (start != null) {
            long totalMs = Duration.between(start, Instant.now()).toMillis();
            log.info("[Metrics] userId={} rounds={} tools={} totalMs={}",
                    ctx.getUserId(), ctx.getCurrentRound(), toolCount.get(), totalMs);
        }
        toolCount.remove();
        loopStart.remove();
    }

    @Override
    public void onError(AgentLoopContext ctx, Throwable error) {
        toolCount.remove();
        loopStart.remove();
    }
}

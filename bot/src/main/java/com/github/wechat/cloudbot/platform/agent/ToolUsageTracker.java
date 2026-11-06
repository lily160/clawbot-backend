package com.github.wechat.cloudbot.platform.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用统计：记录每个工具的调用次数、成功/失败、总耗时。
 *
 * <p>通过 {@link ToolRegistry#setUsageTracker(ToolUsageTracker)} 注入。
 * 数据可通过 {@link #allStats()} 导出，用于 {@code /tool stats} 命令。
 */
@Component
public class ToolUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(ToolUsageTracker.class);

    private final ConcurrentHashMap<String, ToolStats> stats = new ConcurrentHashMap<>();

    /**
     * 记录一次工具调用。
     *
     * @param name       工具名称
     * @param success    是否成功
     * @param durationMs 执行耗时（毫秒）
     */
    public void recordCall(String name, boolean success, long durationMs) {
        stats.compute(name, (key, existing) -> {
            if (existing == null) {
                return new ToolStats(1, success ? 0 : 1, durationMs);
            }
            return new ToolStats(
                    existing.calls() + 1,
                    existing.failures() + (success ? 0 : 1),
                    existing.totalDurationMs() + durationMs);
        });
    }

    /**
     * 获取指定工具的统计。
     *
     * @param name 工具名称
     * @return 统计快照（没有调用记录时返回空的 Stats）
     */
    public ToolStats getStats(String name) {
        return stats.getOrDefault(name, new ToolStats(0, 0, 0));
    }

    /**
     * 返回全部工具的统计快照。
     *
     * @return 不可变的名称→统计映射
     */
    public Map<String, ToolStats> allStats() {
        return Map.copyOf(stats);
    }

    /**
     * 重置所有统计计数。
     */
    public void reset() {
        stats.clear();
        log.info("工具调用统计已重置");
    }

    /**
     * 工具统计快照。
     *
     * @param calls           总调用次数
     * @param failures        失败次数
     * @param totalDurationMs 总耗时（毫秒）
     */
    public record ToolStats(long calls, long failures, long totalDurationMs) {

        /** 平均耗时（毫秒），无调用时返回 0。 */
        public double avgDurationMs() {
            return calls == 0 ? 0.0 : (double) totalDurationMs / calls;
        }

        /** 成功率（0.0 ~ 1.0），无调用时返回 1.0。 */
        public double successRate() {
            return calls == 0 ? 1.0 : (double) (calls - failures) / calls;
        }

        /** 格式化摘要输出。 */
        public String summary() {
            return String.format("调用=%d, 失败=%d, 成功率=%.1f%%, 总耗时=%dms, 平均=%.0fms",
                    calls, failures, successRate() * 100, totalDurationMs, avgDurationMs());
        }
    }
}

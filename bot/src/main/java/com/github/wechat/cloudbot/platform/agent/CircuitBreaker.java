package com.github.wechat.cloudbot.platform.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按用户隔离的熔断器。状态机：CLOSED → OPEN → HALF_OPEN → CLOSED。
 *
 * <p>CLOSED：正常状态。连续失败达到阈值后进入 OPEN。
 * OPEN：拒绝所有请求，等待 halfOpenAfterMs 后进入 HALF_OPEN 做探针。
 * HALF_OPEN：允许一次探针调用。成功则回到 CLOSED，失败则回到 OPEN。
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    private volatile boolean enabled = true;
    private volatile int failureThreshold = 3;
    private volatile long halfOpenAfterMs = 30_000;

    public void configure(AgentProperties.CircuitBreakerConfig config) {
        this.enabled = config.isEnabled();
        this.failureThreshold = config.getFailureThreshold();
        this.halfOpenAfterMs = config.getHalfOpenAfterMs();
    }

    /** 检查指定用户是否允许调用。返回 false 表示熔断中。 */
    public boolean allowRequest(String userId) {
        if (!enabled) return true;

        State state = states.get(userId);
        if (state == null) {
            states.putIfAbsent(userId, new State());
            state = states.get(userId);
        }

        return switch (state.status) {
            case CLOSED -> true;
            case OPEN -> {
                if (Duration.between(state.lastFailureTime, Instant.now()).toMillis() >= halfOpenAfterMs) {
                    // 用 CAS 抢占探针名额：仅一个并发请求能把 probeUsed 置 true 进入 HALF_OPEN，
                    // 其余并发请求直接拒绝，突破"仅一次探针"的限制
                    if (state.probeUsed.compareAndSet(false, true)) {
                        state.status = Status.HALF_OPEN;
                        log.info("[CB] 用户 {} 进入 HALF_OPEN，允许探针调用", userId);
                        yield true;
                    }
                    log.warn("[CB] 用户 {} 探针已被并发请求占用，拒绝 (OPEN)", userId);
                    yield false;
                }
                log.warn("[CB] 用户 {} 熔断中 (OPEN)，请求被拒绝", userId);
                yield false;
            }
            case HALF_OPEN -> {
                // 只有一次探针机会
                if (state.probeUsed.compareAndSet(false, true)) {
                    yield true;
                }
                log.warn("[CB] 用户 {} 探针已使用，请求被拒绝 (HALF_OPEN)", userId);
                yield false;
            }
        };
    }

    /** 记录一次成功调用。 */
    public void onSuccess(String userId) {
        State state = states.get(userId);
        if (state == null) return;

        if (state.status == Status.HALF_OPEN) {
            log.info("[CB] 用户 {} 探针成功，回到 CLOSED", userId);
        }
        state.failureCount.set(0);
        state.status = Status.CLOSED;
        state.probeUsed.set(false);
        state.lastFailureTime = null;
    }

    /** 记录一次失败调用。 */
    public void onFailure(String userId) {
        State state = states.computeIfAbsent(userId, k -> new State());

        int count = state.failureCount.incrementAndGet();
        state.lastFailureTime = Instant.now();

        if (state.status == Status.HALF_OPEN) {
            log.warn("[CB] 用户 {} 探针失败，回到 OPEN", userId);
            state.status = Status.OPEN;
            state.probeUsed.set(false);
        } else if (count >= failureThreshold && state.status == Status.CLOSED) {
            log.warn("[CB] 用户 {} 连续失败 {} 次，进入 OPEN", userId, count);
            state.status = Status.OPEN;
        }
    }

    /** 重置指定用户的熔断状态。 */
    public void reset(String userId) {
        states.remove(userId);
    }

    /** 重置所有用户的熔断状态。 */
    public void resetAll() {
        states.clear();
    }

    /** 获取指定用户当前状态名称。 */
    public String getStatus(String userId) {
        State s = states.get(userId);
        return s == null ? "CLOSED" : s.status.name();
    }

    private enum Status { CLOSED, OPEN, HALF_OPEN }

    private static class State {
        private volatile Status status = Status.CLOSED;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicBoolean probeUsed = new AtomicBoolean(false);
        private volatile Instant lastFailureTime;
    }
}

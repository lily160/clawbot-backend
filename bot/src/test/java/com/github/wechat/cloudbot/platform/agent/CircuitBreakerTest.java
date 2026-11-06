package com.github.wechat.cloudbot.platform.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new CircuitBreaker();
        AgentProperties.CircuitBreakerConfig config = new AgentProperties.CircuitBreakerConfig();
        config.setEnabled(true);
        config.setFailureThreshold(3);
        config.setHalfOpenAfterMs(100);
        breaker.configure(config);
    }

    @Test
    @DisplayName("默认 CLOSED 放行")
    void initiallyClosedAllowsAll() {
        assertTrue(breaker.allowRequest("u1"));
        assertEquals("CLOSED", breaker.getStatus("u1"));
    }

    @Test
    @DisplayName("连续失败达阈值进入 OPEN 并拒绝")
    void failuresOpenCircuit() {
        breaker.onFailure("u1");
        breaker.onFailure("u1");
        assertTrue(breaker.allowRequest("u1"), "未达阈值前仍放行");
        breaker.onFailure("u1");
        assertEquals("OPEN", breaker.getStatus("u1"));
        assertFalse(breaker.allowRequest("u1"), "OPEN 状态应拒绝请求");
    }

    @Test
    @DisplayName("成功调用会重置失败计数")
    void successResetsFailureCount() {
        breaker.onFailure("u1");
        breaker.onFailure("u1");
        breaker.onSuccess("u1");
        breaker.onFailure("u1");
        breaker.onFailure("u1");
        assertTrue(breaker.allowRequest("u1"), "成功重置后未达阈值应放行");
        assertEquals("CLOSED", breaker.getStatus("u1"));
    }

    @Test
    @DisplayName("OPEN 冷却后仅放行一次探针，并发第二个请求被拒（CAS 修复）")
    void openAfterHalfOpenAllowsSingleProbe() throws Exception {
        breaker.onFailure("u1");
        breaker.onFailure("u1");
        breaker.onFailure("u1");
        assertFalse(breaker.allowRequest("u1"), "刚 OPEN 时应拒绝");

        Thread.sleep(150); // 超过 halfOpenAfterMs=100

        // 并发抢占探针：仅一个能放行
        int probeThreads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(probeThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(probeThreads);
        AtomicBoolean allowed = new AtomicBoolean();
        List<Boolean> results = new ArrayList<>();
        for (int i = 0; i < probeThreads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    boolean ok = breaker.allowRequest("u1");
                    synchronized (results) {
                        results.add(ok);
                    }
                    if (ok) {
                        allowed.set(true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "探针并发应 10s 内完成");
        pool.shutdownNow();

        long allowedCount = results.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, allowedCount, "HALF_OPEN 应只放行一次探针，其余并发请求被拒");
        assertTrue(allowed.get());
        assertEquals("HALF_OPEN", breaker.getStatus("u1"));
    }

    @Test
    @DisplayName("探针成功回到 CLOSED，探针失败回到 OPEN")
    void probeResultDrivesRecovery() {
        breaker.onFailure("u1");
        breaker.onFailure("u1");
        breaker.onFailure("u1");
        assertEquals("OPEN", breaker.getStatus("u1"));

        // 冷却后探针失败 → 回到 OPEN
        sleepQuiet(150);
        assertTrue(breaker.allowRequest("u1"));
        assertEquals("HALF_OPEN", breaker.getStatus("u1"));
        breaker.onFailure("u1");
        assertEquals("OPEN", breaker.getStatus("u1"));

        // 再冷却后探针并成功 → CLOSED
        sleepQuiet(150);
        assertTrue(breaker.allowRequest("u1"));
        assertEquals("HALF_OPEN", breaker.getStatus("u1"));
        breaker.onSuccess("u1");
        assertEquals("CLOSED", breaker.getStatus("u1"));
        assertTrue(breaker.allowRequest("u1"));
    }

    @Test
    @DisplayName("reset 清理单用户状态")
    void resetClearsState() {
        breaker.onFailure("u1");
        breaker.onFailure("u1");
        breaker.onFailure("u1");
        assertEquals("OPEN", breaker.getStatus("u1"));
        breaker.reset("u1");
        assertEquals("CLOSED", breaker.getStatus("u1"));
        assertTrue(breaker.allowRequest("u1"));
    }

    @Test
    @DisplayName("不同用户隔离，互不影响")
    void usersAreIsolated() {
        breaker.onFailure("u1");
        breaker.onFailure("u1");
        breaker.onFailure("u1");
        assertEquals("OPEN", breaker.getStatus("u1"));
        assertEquals("CLOSED", breaker.getStatus("u2"));
        assertTrue(breaker.allowRequest("u2"), "u2 不应受 u1 熔断影响");
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

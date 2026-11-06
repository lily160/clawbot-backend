package com.github.wechat.cloudbot.platform.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    private Loop loop = new Loop();
    private Llm llm = new Llm();
    private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();
    private RetryConfig retry = new RetryConfig();

    public Loop getLoop() { return loop; }
    public void setLoop(Loop loop) { this.loop = loop; }
    public Llm getLlm() { return llm; }
    public void setLlm(Llm llm) { this.llm = llm; }
    public CircuitBreakerConfig getCircuitBreaker() { return circuitBreaker; }
    public void setCircuitBreaker(CircuitBreakerConfig circuitBreaker) { this.circuitBreaker = circuitBreaker; }
    public RetryConfig getRetry() { return retry; }
    public void setRetry(RetryConfig retry) { this.retry = retry; }

    public static class Loop {
        private int maxRounds = 5;
        private long timeoutMs = 120_000;

        public int getMaxRounds() { return maxRounds; }
        public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class Llm {
        private long timeoutMs = 60_000;

        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }

    public static class CircuitBreakerConfig {
        private boolean enabled = true;
        private int failureThreshold = 3;
        private long halfOpenAfterMs = 30_000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getFailureThreshold() { return failureThreshold; }
        public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
        public long getHalfOpenAfterMs() { return halfOpenAfterMs; }
        public void setHalfOpenAfterMs(long halfOpenAfterMs) { this.halfOpenAfterMs = halfOpenAfterMs; }
    }

    public static class RetryConfig {
        private int maxAttempts = 2;
        private long baseDelayMs = 1_000;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getBaseDelayMs() { return baseDelayMs; }
        public void setBaseDelayMs(long baseDelayMs) { this.baseDelayMs = baseDelayMs; }
    }
}

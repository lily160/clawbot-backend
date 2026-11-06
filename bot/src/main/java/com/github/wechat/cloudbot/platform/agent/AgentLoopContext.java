package com.github.wechat.cloudbot.platform.agent;

import com.github.wechat.cloudbot.platform.model.ChatMessage;
import com.github.wechat.ilink.sdk.ILinkClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class AgentLoopContext {

    private final ILinkClient client;
    private final String userId;
    private final String userContent;
    private final List<ChatMessage> messages;
    private final int maxRounds;
    private final long timeoutMs;
    private int currentRound;
    private final Instant startTime;

    public AgentLoopContext(ILinkClient client, String userId, String userContent,
                            List<ChatMessage> messages, int maxRounds, long timeoutMs) {
        this.client = client;
        this.userId = userId;
        this.userContent = userContent;
        this.messages = messages;
        this.maxRounds = maxRounds;
        this.timeoutMs = timeoutMs;
        this.currentRound = 1;
        this.startTime = Instant.now();
    }

    public ILinkClient getClient() { return client; }
    public String getUserId() { return userId; }
    public String getUserContent() { return userContent; }
    public List<ChatMessage> getMessages() { return messages; }
    public int getMaxRounds() { return maxRounds; }
    public long getTimeoutMs() { return timeoutMs; }
    public int getCurrentRound() { return currentRound; }
    public void setCurrentRound(int currentRound) { this.currentRound = currentRound; }
    public Instant getStartTime() { return startTime; }

    public boolean isTimeout() {
        return Duration.between(startTime, Instant.now()).toMillis() > timeoutMs;
    }

    @Override
    public String toString() {
        return "AgentLoopContext{" +
                "userId='" + userId + '\'' +
                ", round=" + currentRound + '/' + maxRounds +
                ", elapsed=" + Duration.between(startTime, Instant.now()).toMillis() + "ms" +
                '}';
    }
}

package com.github.wechat.cloudbot.modules.fun.service;

import com.github.wechat.cloudbot.modules.fun.infra.FunProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 猜数字游戏状态管理。
 */
@Service
public class GameService {

    private static final Logger log = LoggerFactory.getLogger(GameService.class);

    private final FunProperties properties;
    private final ConcurrentHashMap<String, GameSession> sessions = new ConcurrentHashMap<>();

    public GameService(FunProperties properties) { this.properties = properties; }

    public String startGuessNumber(String userId) {
        int target = ThreadLocalRandom.current().nextInt(1, 101);
        sessions.put(userId, new GameSession(userId, target, 0, Instant.now()));
        return "🎯 猜数字游戏开始！我想好了一个 1-100 之间的整数，你有 10 次机会，猜猜是多少？";
    }

    public String guess(String userId, int value) {
        GameSession s = sessions.get(userId);
        if (s == null) return "没有进行中的游戏，发「猜数字」开始一局吧！";
        if (Duration.between(s.createdAt, Instant.now()).toMinutes() >= properties.getGameTimeoutMinutes()) {
            sessions.remove(userId);
            return "⏰ 游戏已超时，发「猜数字」重新开始！";
        }
        s.attempts.incrementAndGet();
        if (value < s.target) return "📈 小了！" + remaining(s);
        if (value > s.target) return "📉 大了！" + remaining(s);
        sessions.remove(userId);
        return "🎉 猜对了！就是 " + s.target + "，你用了 " + s.attempts.get() + " 次。";
    }

    private String remaining(GameSession s) {
        if (s.attempts.get() >= 10) { sessions.remove(s.userId); return " 机会用完了！数字是 " + s.target + "。发「猜数字」再来一局。"; }
        return " 还剩 " + (10 - s.attempts.get()) + " 次机会。";
    }

    /** 定期清理超时未互动的游戏会话，防止长期运行内存无限增长。 */
    @Scheduled(cron = "0 23 * * * *")
    public void sweepExpiredSessions() {
        try {
            Instant now = Instant.now();
            long before = sessions.size();
            sessions.values().removeIf(s ->
                    Duration.between(s.createdAt, now).toMinutes() >= properties.getGameTimeoutMinutes());
            if (sessions.size() < before) {
                log.info("清理超时游戏会话，移除 {} 个", before - sessions.size());
            }
        } catch (Exception e) {
            log.warn("清理超时游戏会话失败: {}", e.getMessage());
        }
    }

    private static class GameSession {
        final String userId; final int target; final AtomicInteger attempts; final Instant createdAt;
        GameSession(String userId, int target, int attempts, Instant createdAt) {
            this.userId = userId; this.target = target; this.attempts = new AtomicInteger(attempts); this.createdAt = createdAt;
        }
    }
}

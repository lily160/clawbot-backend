package com.github.wechat.cloudbot.platform.persistence.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.persistence.AuthStorage;
import com.github.wechat.cloudbot.platform.persistence.StoredLogin;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis 版 {@link AuthStorage}：登录凭证写入 Redis，重启后可恢复免扫码。
 * {@code cloudbot.persistence.enabled=true} 且 {@code auth=redis} 时启用。
 */
@Component
@ConditionalOnExpression(
        "'${cloudbot.persistence.enabled:true}'.equalsIgnoreCase('true')"
                + " && '${cloudbot.persistence.auth:file}'.equalsIgnoreCase('redis')")
public class RedisAuthStorage implements AuthStorage {

    private static final Logger log = LoggerFactory.getLogger(RedisAuthStorage.class);
    static final String KEY = "cloudbot:auth:login-context";

    private final AtomicReference<LoginContext> holder = new AtomicReference<>();
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisAuthStorage(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        loadFromRedis().ifPresent(holder::set);
    }

    @Override
    public void save(LoginContext context) {
        holder.set(context);
        if (context == null) {
            clearRedis();
            return;
        }
        try {
            StoredLogin stored =
                    new StoredLogin(
                            context.getBotToken(),
                            context.getUserId(),
                            context.getBotId(),
                            context.getBaseUrl());
            redis.opsForValue().set(KEY, objectMapper.writeValueAsString(stored));
            log.info("登录凭证已写入 Redis key={}, botId={}", KEY, context.getBotId());
        } catch (Exception e) {
            log.warn("写入 Redis 登录凭证失败: {}", e.getMessage());
        }
    }

    @Override
    public Optional<LoginContext> load() {
        LoginContext cached = holder.get();
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<LoginContext> fromRedis = loadFromRedis();
        fromRedis.ifPresent(holder::set);
        return fromRedis;
    }

    @Override
    public void clear() {
        holder.set(null);
        clearRedis();
    }

    private Optional<LoginContext> loadFromRedis() {
        try {
            String json = redis.opsForValue().get(KEY);
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            StoredLogin stored = objectMapper.readValue(json, StoredLogin.class);
            if (stored == null
                    || stored.botToken() == null
                    || stored.botToken().isBlank()
                    || stored.botId() == null
                    || stored.botId().isBlank()) {
                return Optional.empty();
            }
            LoginContext ctx =
                    new LoginContext(
                            stored.botToken(),
                            stored.userId(),
                            stored.botId(),
                            stored.baseUrl());
            log.info("已从 Redis 加载登录凭证 botId={}", ctx.getBotId());
            return Optional.of(ctx);
        } catch (Exception e) {
            log.warn("读取 Redis 登录凭证失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void clearRedis() {
        try {
            redis.delete(KEY);
        } catch (Exception e) {
            log.warn("删除 Redis 登录凭证失败: {}", e.getMessage());
        }
    }
}

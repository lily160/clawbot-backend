package com.github.wechat.cloudbot.platform.persistence.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.persistence.StoredLogin;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisAuthStorageTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RedisAuthStorage storage;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(RedisAuthStorage.KEY)).thenReturn(null);
        storage = new RedisAuthStorage(redis, objectMapper);
    }

    @Test
    void saveWritesJsonToRedis() throws Exception {
        LoginContext ctx = new LoginContext("token", "uid", "bot-1", "https://example.com");
        storage.save(ctx);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(RedisAuthStorage.KEY), jsonCaptor.capture());
        StoredLogin stored = objectMapper.readValue(jsonCaptor.getValue(), StoredLogin.class);
        assertEquals("token", stored.botToken());
        assertEquals("bot-1", stored.botId());
    }

    @Test
    void loadReadsFromRedisWhenCacheEmpty() throws Exception {
        StoredLogin stored = new StoredLogin("t2", "u2", "b2", "https://x");
        when(valueOps.get(RedisAuthStorage.KEY)).thenReturn(objectMapper.writeValueAsString(stored));

        // 新实例，内存为空，强制走 Redis
        RedisAuthStorage fresh = new RedisAuthStorage(redis, objectMapper);
        Optional<LoginContext> loaded = fresh.load();

        assertTrue(loaded.isPresent());
        assertEquals("b2", loaded.get().getBotId());
        assertEquals("t2", loaded.get().getBotToken());
    }

    @Test
    void clearDeletesRedisKey() {
        storage.clear();
        verify(redis).delete(RedisAuthStorage.KEY);
    }
}

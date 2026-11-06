package com.github.wechat.cloudbot.platform.persistence;

import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内存版 {@link AuthStorage}：进程内有效，重启后丢失。
 *
 * <p>仅在 {@code cloudbot.persistence.enabled=false} 时启用。
 */
@Component
@ConditionalOnProperty(prefix = "cloudbot.persistence", name = "enabled", havingValue = "false")
public class InMemoryAuthStorage implements AuthStorage {

    private final AtomicReference<LoginContext> holder = new AtomicReference<>();

    @Override
    public void save(LoginContext context) {
        holder.set(context);
    }

    @Override
    public Optional<LoginContext> load() {
        return Optional.ofNullable(holder.get());
    }

    @Override
    public void clear() {
        holder.set(null);
    }
}

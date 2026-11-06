package com.github.wechat.cloudbot.platform.persistence.file;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.platform.persistence.AuthStorage;
import com.github.wechat.cloudbot.platform.persistence.PersistencePaths;
import com.github.wechat.cloudbot.platform.persistence.StoredLogin;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 文件版 {@link AuthStorage}：登录凭证落盘到 {@code ./data/auth/login-context.json}，
 * 重启后可供 {@code ILinkClient.loginContext(...)} 恢复。
 */
@Component
@ConditionalOnExpression(
        "'${cloudbot.persistence.enabled:true}'.equalsIgnoreCase('true')"
                + " && '${cloudbot.persistence.auth:file}'.equalsIgnoreCase('file')")
public class FileAuthStorage implements AuthStorage {

    private static final Logger log = LoggerFactory.getLogger(FileAuthStorage.class);

    private final AtomicReference<LoginContext> holder = new AtomicReference<>();
    private final CloudbotProperties cloudbotProperties;
    private final ObjectMapper objectMapper;

    public FileAuthStorage(CloudbotProperties cloudbotProperties, ObjectMapper objectMapper) {
        this.cloudbotProperties = cloudbotProperties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        loadFromDisk().ifPresent(holder::set);
    }

    @Override
    public void save(LoginContext context) {
        holder.set(context);
        if (context == null) {
            clearFile();
            return;
        }
        Path file = PersistencePaths.authFile(cloudbotProperties);
        try {
            Files.createDirectories(file.getParent());
            StoredLogin stored =
                    new StoredLogin(
                            context.getBotToken(),
                            context.getUserId(),
                            context.getBotId(),
                            context.getBaseUrl());
            objectMapper.writeValue(file.toFile(), stored);
            log.info("登录凭证已落盘 file={}", file);
        } catch (Exception e) {
            log.warn("写入登录凭证失败: {}", e.getMessage());
        }
    }

    @Override
    public Optional<LoginContext> load() {
        LoginContext cached = holder.get();
        if (cached != null) {
            return Optional.of(cached);
        }
        Optional<LoginContext> fromDisk = loadFromDisk();
        fromDisk.ifPresent(holder::set);
        return fromDisk;
    }

    @Override
    public void clear() {
        holder.set(null);
        clearFile();
    }

    private Optional<LoginContext> loadFromDisk() {
        Path file = PersistencePaths.authFile(cloudbotProperties);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            StoredLogin stored = objectMapper.readValue(file.toFile(), StoredLogin.class);
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
            log.info("已从本地加载登录凭证 botId={}, file={}", ctx.getBotId(), file);
            return Optional.of(ctx);
        } catch (Exception e) {
            log.warn("读取登录凭证失败 {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private void clearFile() {
        Path file = PersistencePaths.authFile(cloudbotProperties);
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.warn("删除登录凭证失败: {}", e.getMessage());
        }
    }
}

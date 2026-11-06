package com.github.wechat.cloudbot.platform.persistence;

import com.github.wechat.ilink.sdk.core.login.LoginContext;

import java.util.Optional;

/**
 * 登录凭证存储抽象。
 *
 * <p>Day1 不做持久化；后续可实现 Redis / 文件 / DB 版本，在进程重启后恢复
 * {@link LoginContext}（配合 SDK 的 {@code resumeContext}）。
 */
public interface AuthStorage {

    /** 保存登录成功后的凭证（botToken / botId / baseUrl 等）。 */
    void save(LoginContext context);

    /** 读取已缓存的登录凭证；不存在则返回 empty。 */
    Optional<LoginContext> load();

    /** 清除凭证（登出或凭证失效时调用）。 */
    void clear();
}

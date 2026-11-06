package com.github.wechat.cloudbot.platform.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 登录凭证可序列化快照（文件 / Redis 共用）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredLogin(String botToken, String userId, String botId, String baseUrl) {}

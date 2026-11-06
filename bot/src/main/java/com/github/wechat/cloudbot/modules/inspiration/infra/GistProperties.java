package com.github.wechat.cloudbot.modules.inspiration.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cloudbot.gist")
public class GistProperties {
    /**
     * GitHub Personal Access Token (需勾选 gist 权限)
     */
    private String token;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
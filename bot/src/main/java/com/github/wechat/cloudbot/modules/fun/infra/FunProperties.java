package com.github.wechat.cloudbot.modules.fun.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 娱乐互动模块配置。
 */
@ConfigurationProperties(prefix = "cloudbot.fun")
public class FunProperties {

    private boolean jokeEnabled = true;
    private boolean gameEnabled = true;
    private boolean luckEnabled = true;
    private int gameTimeoutMinutes = 30;

    public boolean isJokeEnabled() { return jokeEnabled; }
    public void setJokeEnabled(boolean v) { this.jokeEnabled = v; }
    public boolean isGameEnabled() { return gameEnabled; }
    public void setGameEnabled(boolean v) { this.gameEnabled = v; }
    public boolean isLuckEnabled() { return luckEnabled; }
    public void setLuckEnabled(boolean v) { this.luckEnabled = v; }
    public int getGameTimeoutMinutes() { return gameTimeoutMinutes; }
    public void setGameTimeoutMinutes(int v) { this.gameTimeoutMinutes = v; }
}

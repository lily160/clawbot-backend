package com.github.wechat.cloudbot.platform.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Skill 动态加载配置。
 */
@ConfigurationProperties(prefix = "cloudbot.skills")
public class SkillProperties {

    private boolean enabled = true;
    private String directory = "src/skills";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }
}

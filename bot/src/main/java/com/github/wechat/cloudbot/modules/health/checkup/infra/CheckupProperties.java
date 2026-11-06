package com.github.wechat.cloudbot.modules.health.checkup.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 体检套餐模板模块配置属性（前缀 cloudbot.health.checkup）。
 */
@ConfigurationProperties(prefix = "cloudbot.health.checkup")
public class CheckupProperties {

    /** 是否启用体检模板模块。 */
    private boolean enabled = true;

    /** 模板数据文件路径（classpath）。 */
    private String templatesDataPath = "health/checkup/templates.json";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getTemplatesDataPath() { return templatesDataPath; }
    public void setTemplatesDataPath(String templatesDataPath) { this.templatesDataPath = templatesDataPath; }
}

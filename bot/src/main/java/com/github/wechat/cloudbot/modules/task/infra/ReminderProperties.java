package com.github.wechat.cloudbot.modules.task.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 待办提醒模块配置属性（前缀 task）。
 */
@ConfigurationProperties(prefix = "task")
public class ReminderProperties {

    /** 是否启用提醒模块。 */
    private boolean enabled = true;

    /** 扫描到期提醒的间隔秒数。 */
    private int checkIntervalSeconds = 30;

    /** 每个用户最多保存多少条提醒。 */
    private int maxRemindersPerUser = 50;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getCheckIntervalSeconds() {
        return checkIntervalSeconds;
    }

    public void setCheckIntervalSeconds(int checkIntervalSeconds) {
        this.checkIntervalSeconds = checkIntervalSeconds;
    }

    public int getMaxRemindersPerUser() {
        return maxRemindersPerUser;
    }

    public void setMaxRemindersPerUser(int maxRemindersPerUser) {
        this.maxRemindersPerUser = maxRemindersPerUser;
    }
}

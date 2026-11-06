package com.github.wechat.cloudbot.modules.health.checkin.api;

import com.github.wechat.cloudbot.modules.health.common.HealthToolNames;

/**
 * 健康打卡模块工具名（引用组长维护的 {@code HealthToolNames} 全局登记表，
 * 避免工具名散落导致撞名）。
 */
public final class CheckinToolNames {

    public static final String CHECKIN_ADD = HealthToolNames.CHECKIN_ADD;
    public static final String CHECKIN_TODAY = HealthToolNames.CHECKIN_TODAY;
    public static final String REMINDER_LIST = HealthToolNames.REMINDER_LIST;
    public static final String REMINDER_CANCEL = HealthToolNames.REMINDER_CANCEL;

    private CheckinToolNames() {
    }
}

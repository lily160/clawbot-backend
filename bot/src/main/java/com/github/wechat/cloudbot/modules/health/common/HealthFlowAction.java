package com.github.wechat.cloudbot.modules.health.common;

/**
 * 健康主流程当前阶段标记，同时用于阶段图的观测和快照恢复。
 */
public enum HealthFlowAction {
    LOAD_PATIENT,
    TRIAGE,
    BOOKING,
    REPORT_SUMMARY,
    FOLLOW_UP,
    CHECKUP,
    MEDICATION,
    CHECKIN,
    COMPLETED
}

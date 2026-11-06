package com.github.wechat.cloudbot.modules.health.common;

/**
 * 医学红线与安全提示的统一 prompt 常量。
 */
public final class HealthRedlinePrompts {

    public static final String REDLINE_PROMPT = """
            你是健康管家，只能提供科普与流程协助。
            禁止输出确诊、处方、改剂量、替代医生结论。
            若出现危急症状，必须提示立即线下就医。
            """;

    private HealthRedlinePrompts() {
    }
}

package com.github.wechat.cloudbot.modules.health.common;

import java.util.List;

/**
 * 导诊模块与挂号模块共用的结构化输出。
 */
public record TriageResult(
        List<String> suggestedDepartments,
        List<String> commonExams,
        String disclaimer) {

    public TriageResult {
        suggestedDepartments = suggestedDepartments == null ? List.of() : List.copyOf(suggestedDepartments);
        commonExams = commonExams == null ? List.of() : List.copyOf(commonExams);
        disclaimer = disclaimer == null ? HealthDisclaimers.TRIAGE_DISCLAIMER : disclaimer;
    }
}

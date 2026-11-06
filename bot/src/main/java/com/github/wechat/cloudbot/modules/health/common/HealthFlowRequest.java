package com.github.wechat.cloudbot.modules.health.common;

import java.util.List;

/**
 * 健康流程的不可变输入契约。编排层只接收这个对象，不接收工具调用 JSON 或领域实体。
 */
public record HealthFlowRequest(
        String userId,
        String patientId,
        String symptomText,
        String reportPath,
        String reportFilename,
        int followUpRound,
        List<String> previouslyAsked) {

    public HealthFlowRequest {
        previouslyAsked = previouslyAsked == null ? List.of() : List.copyOf(previouslyAsked);
        followUpRound = Math.max(0, followUpRound);
    }

    public HealthFlowRequest(String userId, String symptomText) {
        this(userId, userId, symptomText, null, null, 0, List.of());
    }

    public boolean hasReport() {
        return reportPath != null && !reportPath.isBlank()
                && reportFilename != null && !reportFilename.isBlank();
    }
}

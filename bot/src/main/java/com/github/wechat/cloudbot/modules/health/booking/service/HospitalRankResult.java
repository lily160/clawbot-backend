package com.github.wechat.cloudbot.modules.health.booking.service;

import java.util.List;

/**
 * 科室下的医院推荐排序结果。
 */
public record HospitalRankResult(String department, List<HospitalRankItem> items, String rationale) {

    public HospitalRankResult {
        items = items == null ? List.of() : List.copyOf(items);
        if (department == null || department.isBlank()) {
            department = "未指定科室";
        }
        if (rationale == null) {
            rationale = "";
        }
    }

    public record HospitalRankItem(int rank, String hospitalName, String miniProgramName, String miniProgramPath, double score, String reason) {
        public HospitalRankItem {
            if (hospitalName == null || hospitalName.isBlank()) {
                hospitalName = "未指定医院";
            }
            if (miniProgramName == null || miniProgramName.isBlank()) {
                miniProgramName = hospitalName;
            }
            if (miniProgramPath == null) {
                miniProgramPath = "";
            }
            if (reason == null) {
                reason = "";
            }
        }
    }
}

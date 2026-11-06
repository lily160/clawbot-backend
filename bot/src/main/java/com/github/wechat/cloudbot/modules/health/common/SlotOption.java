package com.github.wechat.cloudbot.modules.health.common;

import java.time.LocalDateTime;

/**
 * 挂号候选号源。
 */
public record SlotOption(
        String hospitalName,
        String department,
        LocalDateTime visitTime,
        String doctorName,
        String bookingLink) {

    public SlotOption {
        if (hospitalName == null || hospitalName.isBlank()) {
            hospitalName = "未指定医院";
        }
        if (department == null || department.isBlank()) {
            department = "未指定科室";
        }
    }
}

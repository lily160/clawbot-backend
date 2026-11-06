package com.github.wechat.cloudbot.modules.health.medication.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用药记录领域模型。
 *
 * @param id          唯一标识
 * @param userId      微信用户ID
 * @param drugName    药名
 * @param dosage      每次用量，如「1片」「10ml」
 * @param frequency   频次，如「每日一次」「每日两次」
 * @param courseDays  疗程天数
 * @param notes       备注（可选）
 * @param reminderTimes 自定义每日提醒时间（HH:mm，可选；与频次提醒数量对应）
 * @param createdAt   创建时间
 * @param enabled     是否启用
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MedicationRecord(
        String id,
        String userId,
        String drugName,
        String dosage,
        String frequency,
        int courseDays,
        String notes,
        List<String> reminderTimes,
        LocalDateTime createdAt,
        boolean enabled
) {

    public MedicationRecord {
        reminderTimes = reminderTimes == null ? List.of() : List.copyOf(reminderTimes);
    }

    public MedicationRecord disable() {
        return new MedicationRecord(id, userId, drugName, dosage, frequency, courseDays, notes,
                reminderTimes, createdAt, false);
    }
}

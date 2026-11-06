package com.github.wechat.cloudbot.modules.health.checkin.infra;

import java.time.LocalDate;

/**
 * 健康打卡记录：每天每类一条，同一天多次打卡累加到 {@code count}。
 *
 * @param id          主键（新增前为 null）
 * @param userId      微信用户 ID（隔离标识，绝不串库）
 * @param kind        打卡类型：drink_water / exercise / sleep_early / med_take（服药）
 * @param checkinDate 打卡日期
 * @param count       当日累计次数/量（如喝水杯数）
 * @param remark      备注（med_take 时为当日已服药品名，、分隔）
 */
public record HealthCheckin(
        Long id,
        String userId,
        String kind,
        LocalDate checkinDate,
        int count,
        String remark) {
}

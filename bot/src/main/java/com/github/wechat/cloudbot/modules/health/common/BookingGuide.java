package com.github.wechat.cloudbot.modules.health.common;

import java.util.List;

/**
 * 导诊完成后的医院推荐与小程序入口，不代表已经完成挂号。
 */
public record BookingGuide(
        String department,
        List<HospitalEntry> hospitals,
        String disclaimer) {

    public BookingGuide {
        hospitals = hospitals == null ? List.of() : List.copyOf(hospitals);
        disclaimer = disclaimer == null || disclaimer.isBlank()
                ? "以上为医院和科室推荐，不代表已完成挂号，请在对应小程序内查看实时号源并预约。"
                : disclaimer;
    }

    public record HospitalEntry(
            int rank,
            String hospitalName,
            String reason,
            String miniProgramName,
            String miniProgramEntry) {
    }
}

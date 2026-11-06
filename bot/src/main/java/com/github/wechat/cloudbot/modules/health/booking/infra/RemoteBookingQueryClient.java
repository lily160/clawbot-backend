package com.github.wechat.cloudbot.modules.health.booking.infra;

import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.common.SlotOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 远程医院平台实现骨架。
 * <p>
 * 当前仅保留协议入口，等你提供 A+ 抓包信息后再补齐真实 HTTP 调用与字段映射。
 */
@Component
@ConditionalOnProperty(prefix = "health.booking", name = "enabled", havingValue = "true")
public class RemoteBookingQueryClient implements BookingQueryClient {

    @Override
    public List<SlotOption> recommend(PatientRef patientRef, String preferredDepartment, int limit) {
        throw new UnsupportedOperationException("远程挂号协议尚未补全，请提供号源查询接口信息");
    }

    @Override
    public BookingSubmitResult confirm(PatientRef patientRef, SlotOption chosenSlot) {
        throw new UnsupportedOperationException("远程挂号协议尚未补全，请提供预约提交接口信息");
    }
}

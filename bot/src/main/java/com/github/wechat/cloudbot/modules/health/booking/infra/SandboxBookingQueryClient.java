package com.github.wechat.cloudbot.modules.health.booking.infra;

import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.common.SlotOption;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 仅供本地测试与手动演示的沙箱实现。
 * <p>不会作为 Spring 默认 Bean 注入，以免把假号源带入真实链路。</p>
 */
public class SandboxBookingQueryClient implements BookingQueryClient {

    @Override
    public List<SlotOption> recommend(PatientRef patientRef, String preferredDepartment, int limit) {
        return List.of();
    }

    @Override
    public BookingSubmitResult confirm(PatientRef patientRef, SlotOption chosenSlot) {
        return new BookingSubmitResult(false, null, null, null);
    }
}

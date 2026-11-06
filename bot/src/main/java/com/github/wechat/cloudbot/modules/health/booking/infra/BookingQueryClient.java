package com.github.wechat.cloudbot.modules.health.booking.infra;

import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.common.SlotOption;

import java.util.List;

/**
 * 医院挂号平台的远程调用边界。
 * <p>
 * 先保留接口和默认实现骨架，等 Charles 抓包信息补全后再落真实协议。
 */
public interface BookingQueryClient {

    List<SlotOption> recommend(PatientRef patientRef, String preferredDepartment, int limit);

    BookingSubmitResult confirm(PatientRef patientRef, SlotOption chosenSlot);

    record BookingSubmitResult(boolean success, String orderNo, String bookingLink, String visitTimeText) {
    }
}

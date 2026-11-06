package com.github.wechat.cloudbot.modules.health.booking.api;

import com.github.wechat.cloudbot.modules.health.common.BookingReceipt;
import com.github.wechat.cloudbot.modules.health.common.PatientRef;
import com.github.wechat.cloudbot.modules.health.common.SlotOption;
import com.github.wechat.cloudbot.modules.health.common.TriageResult;

import java.util.List;
import java.util.Optional;

/**
 * 沙箱挂号服务的最小对外契约。
 */
public interface BookingServicePort {

    List<SlotOption> recommend(String symptomText, PatientRef patientRef, TriageResult triageResult);

    Optional<BookingReceipt> confirm(String chosenSlotId, SlotOption slotOption, PatientRef patientRef);
}

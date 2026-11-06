package com.github.wechat.cloudbot.modules.health.booking.service;

import com.github.wechat.cloudbot.modules.health.booking.infra.HealthBookingProperties;
import com.github.wechat.cloudbot.modules.health.booking.infra.HealthBookingProperties.MiniProgramTarget;
import com.github.wechat.cloudbot.modules.health.common.BookingReceipt;
import com.github.wechat.cloudbot.modules.health.common.SlotOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingServiceTest {

    @Test
    @DisplayName("小程序入口应按医院单独配置并支持兜底")
    void confirm_shouldReturnMiniProgramLink() {
        HealthBookingProperties props = buildProperties();
        BookingService service = new BookingService(props, null);
        SlotOption slot = new SlotOption("杭州市余杭区第一人民医院", "口腔科门诊", LocalDateTime.of(2026, 8, 8, 9, 0), "张伟", "https://example.com/booking/1");

        Optional<BookingReceipt> receipt = service.confirm("slot-1", slot, null, true);

        assertTrue(receipt.isPresent());
        assertTrue(receipt.get().bookingLink().contains("#小程序://杭州市余杭区第一人民医院/FP0BXhWONpZpGKk"));
        assertEquals("slot-1", receipt.get().orderNo());
    }

    @Test
    @DisplayName("三家医院应命中各自独立小程序入口")
    void miniProgram_shouldResolveDedicatedEntries() {
        HealthBookingProperties props = buildProperties();
        BookingService service = new BookingService(props, null);

        assertTrue(service.buildMiniProgramLink("slot-1", sampleSlot("杭州市余杭区第一人民医院")).contains("#小程序://杭州市余杭区第一人民医院/FP0BXhWONpZpGKk"));
        assertTrue(service.buildMiniProgramLink("slot-2", sampleSlot("杭州市余杭区第三人民医院")).contains("#小程序://余杭智慧三院/hSTdw7P8PhjCJmb"));
        assertTrue(service.buildMiniProgramLink("slot-3", sampleSlot("余杭区二院")).contains("pages/booking/index"));
    }

    @Test
    @DisplayName("未命中指定医院时应走杭州市互联网医院兜底")
    void miniProgram_shouldFallbackToInternetHospital() {
        HealthBookingProperties props = buildProperties();
        BookingService service = new BookingService(props, null);

        String link = service.buildMiniProgramLink("slot-x", sampleSlot("未知医院"));
        assertTrue(link.contains("pages/booking/index") || link.contains("杭州市互联网医院"));
        assertEquals("杭州市互联网医院", service.miniProgramName("未知医院"));
    }

    private static HealthBookingProperties buildProperties() {
        HealthBookingProperties props = new HealthBookingProperties();
        props.setMiniProgramHint("请在对应医院小程序内完成最终确认和预约提交。");

        MiniProgramTarget first = new MiniProgramTarget();
        first.setName("杭州市余杭区第一人民医院");
        first.setPath("#小程序://杭州市余杭区第一人民医院/FP0BXhWONpZpGKk");
        props.setFirstHospital(first);

        MiniProgramTarget second = new MiniProgramTarget();
        second.setName("余杭区二院");
        second.setPath("");
        props.setSecondHospital(second);

        MiniProgramTarget third = new MiniProgramTarget();
        third.setName("杭州市余杭区第三人民医院");
        third.setPath("#小程序://余杭智慧三院/hSTdw7P8PhjCJmb");
        props.setThirdHospital(third);

        MiniProgramTarget fallback = new MiniProgramTarget();
        fallback.setName("杭州市互联网医院");
        fallback.setPath("");
        props.setFallbackMiniProgram(fallback);
        return props;
    }

    private static SlotOption sampleSlot(String hospitalName) {
        return new SlotOption(hospitalName, "口腔科门诊", LocalDateTime.of(2026, 8, 8, 9, 0), "普通医生", "https://example.com/booking/1");
    }
}

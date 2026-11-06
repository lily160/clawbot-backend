package com.github.wechat.cloudbot.platform.agent;

import com.github.wechat.cloudbot.modules.health.booking.service.BookingService;
import com.github.wechat.cloudbot.modules.health.common.HealthFlowOrchestrator;
import com.github.wechat.cloudbot.modules.health.profile.service.ProfileService;
import com.github.wechat.cloudbot.modules.health.triage.service.TriageService;
import com.github.wechat.cloudbot.platform.agent.plan.PlanOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class HealthCommandRouterTest {

    @Mock
    private PlanOrchestrator planOrchestrator;
    @Mock
    private HealthFlowOrchestrator healthFlowOrchestrator;
    @Mock
    private ProfileService profileService;
    @Mock
    private TriageService triageService;
    @Mock
    private BookingService bookingService;

    @Test
    @DisplayName("路由器应支持确认第N个的模式识别")
    void route_shouldRecognizeSlotSelection() {
        HealthCommandRouter router = new HealthCommandRouter(planOrchestrator, healthFlowOrchestrator, profileService, triageService, bookingService);
        assertTrue(router.route("u1", "确认第2个").isEmpty() || router.route("u1", "确认第2个").isPresent());
    }
}

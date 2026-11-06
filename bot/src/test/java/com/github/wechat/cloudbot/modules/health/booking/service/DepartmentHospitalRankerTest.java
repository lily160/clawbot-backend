package com.github.wechat.cloudbot.modules.health.booking.service;

import com.github.wechat.cloudbot.modules.health.booking.infra.HealthBookingProperties;
import com.github.wechat.cloudbot.modules.search.api.SearchGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepartmentHospitalRankerTest {

    @Test
    void rank_shouldUseSearchEvidenceAndReturnThreeHospitals() {
        HealthBookingProperties properties = new HealthBookingProperties();
        SearchGateway search = new StubSearchGateway("杭州市余杭区第一人民医院 口腔科 重点专科 主任医师 专家门诊");

        HospitalRankResult result = new DepartmentHospitalRanker(properties, search).rank("口腔科");

        assertEquals(3, result.items().size());
        assertEquals("杭州市余杭区第一人民医院", result.items().get(0).hospitalName());
        assertEquals(1, result.items().get(0).rank());
        assertFalse(result.rationale().isBlank());
    }

    @Test
    void rank_shouldUseStableFallbackWhenSearchUnavailable() {
        HealthBookingProperties properties = new HealthBookingProperties();
        SearchGateway search = new StubSearchGateway("");

        HospitalRankResult result = new DepartmentHospitalRanker(properties, search).rank("口腔科");

        assertEquals("杭州市余杭区第一人民医院", result.items().get(0).hospitalName());
        assertEquals("杭州市余杭区第三人民医院", result.items().get(1).hospitalName());
        assertEquals("余杭区二院", result.items().get(2).hospitalName());
    }

    private static class StubSearchGateway implements SearchGateway {
        private final String result;

        private StubSearchGateway(String result) {
            this.result = result;
        }

        @Override
        public boolean isAvailable() {
            return !result.isBlank();
        }

        @Override
        public String search(String query, String freshness) {
            return result;
        }
    }
}

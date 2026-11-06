package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HealthReportRepositoryTest {

    private HealthReportRepository repo;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:report_" + System.nanoTime() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        ds.setUsername("sa");
        ds.setPassword("");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(ds);
        repo = new HealthReportRepository(ds);
    }

    @Test
    @DisplayName("报告与指标同事务落库，明细行数与声明数一致")
    void saveReportWithIndicatorsPersistsBoth() {
        long reportId = repo.saveReportWithIndicators("u1", "report.pdf", sampleResult(), "{\"k\":\"v\"}");
        assertTrue(reportId > 0, "应返回自增主键");

        Integer indicatorRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM health_indicator_history WHERE report_id = ?", Integer.class, reportId);
        assertEquals(4, indicatorRows, "应保存 4 条指标明细");

        Integer declaredCount = jdbc.queryForObject(
                "SELECT indicator_count FROM health_report_record WHERE id = ?", Integer.class, reportId);
        assertEquals(4, declaredCount, "主记录 indicator_count 应与明细一致");

        assertEquals(4, repo.getLatestIndicators("u1").size());
    }

    @Test
    @DisplayName("两份报告支持「上一份」趋势对比")
    void twoReportsEnablePreviousComparison() {
        repo.saveReportWithIndicators("u1", "first.pdf", sampleResult(), "{}");
        repo.saveReportWithIndicators("u1", "second.pdf", sampleResult(), "{}");

        Map<String, HealthIndicator> previous = repo.getLastReportIndicators("u1");
        assertEquals(4, previous.size(), "上一份报告的指标应可查");
        assertTrue(previous.containsKey("空腹血糖"));
        assertEquals("7.0", previous.get("空腹血糖").value());
    }

    @Test
    @DisplayName("无报告时上一份查询返回空")
    void lastReportEmptyWhenNoHistory() {
        assertTrue(repo.getLastReportIndicators("u1").isEmpty());
    }

    private static HealthReportParseResult sampleResult() {
        List<HealthIndicator> indicators = List.of(
                new HealthIndicator("血红蛋白", "128", "g/L", "130-175", IndicatorStatus.LOW, 1, "", null),
                new HealthIndicator("空腹血糖", "7.0", "mmol/L", "3.9-6.1", IndicatorStatus.HIGH, 1, "", null),
                new HealthIndicator("血小板", "180", "10^9/L", "125-350", IndicatorStatus.NORMAL, 1, "", null),
                new HealthIndicator("尿酸", "480", "umol/L", "150-440", IndicatorStatus.HIGH, 1, "", null));
        return new HealthReportParseResult(
                "体检报告", "sample.pdf", 1, indicators, indicators,
                "本结果仅供参考，不能替代医生诊断。", "请结合原始报告并咨询专业医生复核。");
    }
}

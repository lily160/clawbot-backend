package com.github.wechat.cloudbot.modules.health.report.service;

import com.github.wechat.cloudbot.modules.health.report.domain.HealthIndicator;
import com.github.wechat.cloudbot.modules.health.report.domain.HealthReportParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 体检报告持久化仓库：存储解析结果 + 查询历史 + 趋势对比。
 */
@Repository
public class HealthReportRepository {

    private static final Logger log = LoggerFactory.getLogger(HealthReportRepository.class);

    private final JdbcTemplate jdbc;

    public HealthReportRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 保存报告主记录 + 指标明细（同一事务）。
     * <p>修复：原实现由调用方分别调用 saveReport / saveIndicators，指标保存失败会残留
     * 无指标的孤儿 report 行，被后续趋势对比误当成"上一份报告"。</p>
     */
    @Transactional
    public long saveReportWithIndicators(String userId, String sourceFilename,
                                         HealthReportParseResult result, String reportJson) {
        long reportId = saveReport(userId, sourceFilename, result, reportJson);
        saveIndicators(reportId, userId, result.indicators());
        return reportId;
    }

    /** 保存报告主记录，返回 reportId */
    public long saveReport(String userId, String sourceFilename, HealthReportParseResult result, String reportJson) {
        // 用 RETURN_GENERATED_KEYS 取自增主键，避免旧实现「INSERT 后再 SELECT LAST_INSERT_ID()」
        // 在连接池并发下取到其他连接/其他用户的自增 ID，导致指标挂错报告。
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            // 显式指定列名取回自增 id：MySQL 下行为不变，且 H2 不会把 created_at 等默认列混入生成键
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO health_report_record (user_id, source_filename, page_count, indicator_count, abnormal_count, high_count, low_count, unknown_count, report_data) VALUES (?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setString(1, userId);
            ps.setString(2, sourceFilename);
            ps.setInt(3, result.pageCount());
            ps.setInt(4, result.indicators().size());
            ps.setInt(5, result.abnormalIndicators().size());
            ps.setLong(6, result.indicators().stream().filter(i -> i.status().name().equals("HIGH")).count());
            ps.setLong(7, result.indicators().stream().filter(i -> i.status().name().equals("LOW")).count());
            ps.setLong(8, result.indicators().stream().filter(i -> i.status().name().equals("UNKNOWN")).count());
            ps.setString(9, reportJson);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    /** 批量保存指标明细 */
    public void saveIndicators(long reportId, String userId, List<HealthIndicator> indicators) {
        // value 在 DDL 中为保留字用反引号包裹，此处保持一致，兼容 H2/MySQL
        String sql = "INSERT INTO health_indicator_history (report_id, user_id, name, `value`, unit, reference_range, status, page_no) VALUES (?,?,?,?,?,?,?,?)";
        List<Object[]> batch = new ArrayList<>();
        for (HealthIndicator ind : indicators) {
            batch.add(new Object[]{reportId, userId, ind.name(), ind.value(), ind.unit(),
                    ind.referenceRange(), ind.status().name(), ind.pageNo()});
        }
        jdbc.batchUpdate(sql, batch);
    }

    /** 查询用户历史报告摘要 */
    public List<Map<String, Object>> listReports(String userId, int limit) {
        return jdbc.queryForList(
                "SELECT id, source_filename, page_count, indicator_count, abnormal_count, high_count, low_count, unknown_count, created_at FROM health_report_record WHERE user_id=? ORDER BY created_at DESC LIMIT ?",
                userId, limit);
    }

    /** 获取用户某指标的历史值（趋势用） */
    public List<Map<String, Object>> getIndicatorHistory(String userId, String indicatorName, int limit) {
        return jdbc.queryForList(
                "SELECT h.`value`, h.unit, h.reference_range, h.status, h.created_at, r.source_filename FROM health_indicator_history h JOIN health_report_record r ON h.report_id=r.id WHERE h.user_id=? AND h.name=? ORDER BY h.created_at DESC LIMIT ?",
                userId, indicatorName, limit);
    }

    /** 查询用户上一次报告中存在、本次也存在的同名指标，用于对比 */
    public Map<String, HealthIndicator> getLastReportIndicators(String userId) {
        Map<String, HealthIndicator> result = new LinkedHashMap<>();
        jdbc.query(
                "SELECT h.name, h.`value`, h.unit, h.reference_range, h.status FROM health_indicator_history h JOIN health_report_record r ON h.report_id=r.id WHERE h.user_id=? AND r.id = (SELECT MAX(id) FROM health_report_record WHERE user_id=? AND id < (SELECT MAX(id) FROM health_report_record WHERE user_id=?))",
                (ResultSet rs) -> {
                    String name = rs.getString("name");
                    result.put(name, new HealthIndicator(name,
                            rs.getString("value"), rs.getString("unit"),
                            rs.getString("reference_range"),
                            com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus.valueOf(rs.getString("status")),
                            0, "", null));
                },
                userId, userId, userId);
        return result;
    }

    /** 获取用户最近一次报告的指标 */
    public Map<String, HealthIndicator> getLatestIndicators(String userId) {
        Map<String, HealthIndicator> result = new LinkedHashMap<>();
        jdbc.query(
                "SELECT h.name, h.`value`, h.unit, h.reference_range, h.status FROM health_indicator_history h WHERE h.report_id = (SELECT MAX(id) FROM health_report_record WHERE user_id=?) AND h.user_id=?",
                (ResultSet rs) -> {
                    String name = rs.getString("name");
                    result.put(name, new HealthIndicator(name,
                            rs.getString("value"), rs.getString("unit"),
                            rs.getString("reference_range"),
                            com.github.wechat.cloudbot.modules.health.report.domain.IndicatorStatus.valueOf(rs.getString("status")),
                            0, "", null));
                },
                userId, userId);
        return result;
    }
}

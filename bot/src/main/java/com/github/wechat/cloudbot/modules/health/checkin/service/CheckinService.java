package com.github.wechat.cloudbot.modules.health.checkin.service;

import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthCheckin;
import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthReminderKind;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 健康打卡服务：喝水 / 运动 / 早睡 / 服药按天记录，同日同类累加次数。
 *
 * <p>持久化走 MySQL（Flyway {@code V4_1__health_checkin.sql}），JDBC 风格对齐
 * {@link com.github.wechat.cloudbot.modules.task.service.ReminderService}，便于 H2 单测。</p>
 */
@Service
public class CheckinService {

    /** 服药打卡类型（复用打卡表：同日一行，remark 存当日已服药品名，count 为当日服药次数）。 */
    public static final String MED_TAKE_KIND = "med_take";

    private final JdbcTemplate jdbc;

    public CheckinService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /**
     * 建表（Flyway 已建，此处幂等兜底；单元测试中手动触发）。
     */
    @PostConstruct
    public void ensureTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS health_checkin (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    wechat_user_id VARCHAR(128) NOT NULL,
                    kind VARCHAR(32) NOT NULL,
                    checkin_date DATE NOT NULL,
                    count INT NOT NULL DEFAULT 1,
                    remark VARCHAR(500) DEFAULT NULL,
                    CONSTRAINT chk_health_checkin_kind CHECK (kind IN ('drink_water','exercise','sleep_early','med_take')),
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        try {
            jdbc.execute("CREATE UNIQUE INDEX uk_user_kind_date ON health_checkin(wechat_user_id, kind, checkin_date)");
        } catch (Exception ignored) {
            // index may already exist on restart
        }
    }

    /**
     * 记录一次打卡：同一天同类型已存在则累加 count，否则插入新行。
     *
     * @return 当日的打卡记录（含累加后的 count）
     * @throws IllegalArgumentException userId 为空、类型不支持打卡或日期为空时
     */
    public HealthCheckin checkin(String userId, HealthReminderKind kind, LocalDate date, int count, String remark) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (kind == null || !kind.isCheckinKind()) {
            throw new IllegalArgumentException("该类型暂不支持打卡，仅支持喝水 / 运动 / 早睡");
        }
        LocalDate day = date == null ? LocalDate.now() : date;
        int amount = count < 1 ? 1 : count;
        String cleanRemark = remark == null || remark.isBlank() ? null : remark.trim();

        List<HealthCheckin> existing = jdbc.query(
                "SELECT * FROM health_checkin WHERE wechat_user_id = ? AND kind = ? AND checkin_date = ?",
                (rs, row) -> map(rs), userId, kind.key(), Date.valueOf(day));
        if (existing.isEmpty()) {
            try {
                jdbc.update(
                        "INSERT INTO health_checkin (wechat_user_id, kind, checkin_date, count, remark) VALUES (?, ?, ?, ?, ?)",
                        userId, kind.key(), Date.valueOf(day), amount, cleanRemark);
                return new HealthCheckin(null, userId, kind.key(), day, amount, cleanRemark);
            } catch (DuplicateKeyException ex) {
                // 并发同键插入：另一事务已建行，走下方累加路径（原子 count 自增）
            }
        }

        // 重新查询（可能刚被并发事务抢先插入），再以原子自增更新，避免 count 丢失更新
        List<HealthCheckin> rows = jdbc.query(
                "SELECT * FROM health_checkin WHERE wechat_user_id = ? AND kind = ? AND checkin_date = ?",
                (rs, row) -> map(rs), userId, kind.key(), Date.valueOf(day));
        if (rows.isEmpty()) {
            jdbc.update(
                    "INSERT INTO health_checkin (wechat_user_id, kind, checkin_date, count, remark) VALUES (?, ?, ?, ?, ?)",
                    userId, kind.key(), Date.valueOf(day), amount, cleanRemark);
            return new HealthCheckin(null, userId, kind.key(), day, amount, cleanRemark);
        }
        HealthCheckin current = rows.get(0);
        String newRemark = cleanRemark == null ? current.remark() : cleanRemark;
        jdbc.update(
                "UPDATE health_checkin SET count = count + ?, remark = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                amount, newRemark, current.id());
        int newCount = current.count() + amount;
        return new HealthCheckin(current.id(), current.userId(), current.kind(), current.checkinDate(), newCount, newRemark);
    }

    /**
     * 记录一次服药打卡：同日同药累加次数，不同药品合并进 remark（、分隔、去重）。
     *
     * @return 当日服药记录（含累加后的 count 与已服药品清单）
     * @throws IllegalArgumentException userId 为空或药品名称为空时
     */
    public HealthCheckin medTake(String userId, String drugName, LocalDate date) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        String cleanDrug = drugName == null ? "" : drugName.trim();
        if (cleanDrug.isEmpty()) {
            throw new IllegalArgumentException("药品名称不能为空");
        }
        LocalDate day = date == null ? LocalDate.now() : date;

        List<HealthCheckin> existing = jdbc.query(
                "SELECT * FROM health_checkin WHERE wechat_user_id = ? AND kind = ? AND checkin_date = ?",
                (rs, row) -> map(rs), userId, MED_TAKE_KIND, Date.valueOf(day));
        if (existing.isEmpty()) {
            try {
                jdbc.update(
                        "INSERT INTO health_checkin (wechat_user_id, kind, checkin_date, count, remark) VALUES (?, ?, ?, 1, ?)",
                        userId, MED_TAKE_KIND, Date.valueOf(day), cleanDrug);
                return new HealthCheckin(null, userId, MED_TAKE_KIND, day, 1, cleanDrug);
            } catch (DuplicateKeyException ex) {
                // 并发同键插入：另一事务已建行，走下方累加路径（原子 count 自增）
            }
        }

        // 重新查询（可能刚被并发事务抢先插入），count 用原子自增避免丢失更新
        List<HealthCheckin> rows = jdbc.query(
                "SELECT * FROM health_checkin WHERE wechat_user_id = ? AND kind = ? AND checkin_date = ?",
                (rs, row) -> map(rs), userId, MED_TAKE_KIND, Date.valueOf(day));
        if (rows.isEmpty()) {
            jdbc.update(
                    "INSERT INTO health_checkin (wechat_user_id, kind, checkin_date, count, remark) VALUES (?, ?, ?, 1, ?)",
                    userId, MED_TAKE_KIND, Date.valueOf(day), cleanDrug);
            return new HealthCheckin(null, userId, MED_TAKE_KIND, day, 1, cleanDrug);
        }
        HealthCheckin current = rows.get(0);
        String mergedRemark = mergeDrugNames(current.remark(), cleanDrug);
        jdbc.update(
                "UPDATE health_checkin SET count = count + 1, remark = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                mergedRemark, current.id());
        int newCount = current.count() + 1;
        return new HealthCheckin(current.id(), current.userId(), current.kind(),
                current.checkinDate(), newCount, mergedRemark);
    }

    /**
     * 服药连续打卡天数：从今天往回数连续有 med_take 记录的天数。
     */
    public int medTakeStreak(String userId) {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        Set<LocalDate> dates = getRecent(userId, 60).stream()
                .filter(c -> MED_TAKE_KIND.equals(c.kind()))
                .map(HealthCheckin::checkinDate)
                .collect(Collectors.toSet());
        int streak = 0;
        LocalDate cursor = LocalDate.now();
        while (dates.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    /** 把新药品名合并进当日已服清单（、分隔，去重）。 */
    private static String mergeDrugNames(String existing, String drug) {
        if (existing == null || existing.isBlank()) {
            return drug;
        }
        List<String> drugs = new ArrayList<>();
        for (String part : existing.split("、")) {
            String t = part.trim();
            if (!t.isEmpty() && !drugs.contains(t)) {
                drugs.add(t);
            }
        }
        if (!drugs.contains(drug)) {
            drugs.add(drug);
        }
        return String.join("、", drugs);
    }

    /**
     * 查询今日打卡。
     */
    public List<HealthCheckin> getToday(String userId) {
        return getRecent(userId, 1);
    }

    /**
     * 查询最近 {@code days} 天的打卡记录（含今天）。
     */
    public List<HealthCheckin> getRecent(String userId, int days) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        int n = days < 1 ? 1 : days;
        LocalDate since = LocalDate.now().minusDays(n - 1L);
        return jdbc.query(
                "SELECT * FROM health_checkin WHERE wechat_user_id = ? AND checkin_date >= ? ORDER BY checkin_date, kind",
                (rs, row) -> map(rs), userId, Date.valueOf(since));
    }

    /**
     * 将打卡记录格式化为微信回复文本（按日期分组，每类展示次数）。
     */
    public String summaryText(List<HealthCheckin> checkins, int days) {
        StringBuilder sb = new StringBuilder(days <= 1 ? "📋 今日打卡：\n" : "📋 最近 " + days + " 天打卡：\n");
        if (checkins == null || checkins.isEmpty()) {
            sb.append("还没有打卡记录，试试说「喝水打卡」吧～");
            return sb.toString();
        }
        Map<LocalDate, List<HealthCheckin>> byDate = new TreeMap<>();
        for (HealthCheckin c : checkins) {
            byDate.computeIfAbsent(c.checkinDate(), k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<LocalDate, List<HealthCheckin>> entry : byDate.entrySet()) {
            sb.append("• ").append(entry.getKey()).append("：");
            sb.append(entry.getValue().stream()
                    .map(c -> labelOf(c.kind())
                            + (MED_TAKE_KIND.equals(c.kind())
                                    && c.remark() != null && !c.remark().isBlank()
                                    ? "（" + c.remark() + "）" : "")
                            + "×" + c.count())
                    .collect(Collectors.joining("、")));
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String labelOf(String kindKey) {
        if (MED_TAKE_KIND.equals(kindKey)) {
            return "💊 服药";
        }
        try {
            HealthReminderKind kind = HealthReminderKind.parse(kindKey);
            return kind.emoji() + " " + kind.label();
        } catch (IllegalArgumentException e) {
            return kindKey;
        }
    }

    private static HealthCheckin map(ResultSet rs) throws SQLException {
        return new HealthCheckin(
                rs.getLong("id"),
                rs.getString("wechat_user_id"),
                rs.getString("kind"),
                rs.getDate("checkin_date").toLocalDate(),
                rs.getInt("count"),
                rs.getString("remark"));
    }
}

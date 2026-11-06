package com.github.wechat.cloudbot.modules.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.cloudbot.modules.task.infra.ReminderChangedEvent;
import com.github.wechat.cloudbot.modules.task.infra.ReminderProperties;
import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.platform.persistence.PersistencePaths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 定时任务模块：任务状态、SQLite 持久化及旧 JSON 数据迁移。 */
@Component
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CloudbotProperties cloudbotProperties;
    private final ReminderProperties reminderProperties;
    private final ApplicationEventPublisher eventPublisher;

    public ReminderService(
            DataSource dataSource,
            ObjectMapper objectMapper,
            CloudbotProperties cloudbotProperties,
            ReminderProperties reminderProperties,
            ApplicationEventPublisher eventPublisher) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
        this.cloudbotProperties = cloudbotProperties;
        this.reminderProperties = reminderProperties;
        this.eventPublisher = eventPublisher;
    }

    /** 建表（幂等）。拆成独立方法供测试直接构造时手动调用。 */
    public void ensureTable() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scheduled_task (
                    id VARCHAR(128) PRIMARY KEY,
                    user_id VARCHAR(128) NOT NULL,
                    content TEXT NOT NULL,
                    trigger_time VARCHAR(32) NOT NULL,
                    cron_expression VARCHAR(64),
                    advance_minutes INTEGER NOT NULL DEFAULT 0,
                    created_at VARCHAR(32) NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1
                )
                """);
        try {
            jdbc.execute("""
                    CREATE INDEX idx_scheduled_task_due
                    ON scheduled_task(enabled, trigger_time)
                    """);
        } catch (Exception e) {
            log.debug("索引 idx_scheduled_task_due 可能已存在: {}", e.getMessage());
        }
    }

    @PostConstruct
    public void loadFromDisk() {
        ensureTable();
        importLegacyJson();
    }

    public Reminder add(
            String userId,
            String content,
            LocalDateTime triggerTime,
            int advanceMinutes,
            String cronExpression) {
        requireUser(userId);
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task WHERE user_id = ?", Integer.class, userId);
        if (count != null && count >= reminderProperties.getMaxRemindersPerUser()) {
            throw new IllegalStateException(
                    "提醒数量已达上限 " + reminderProperties.getMaxRemindersPerUser() + " 条，请先删除旧提醒");
        }
        Reminder reminder = new Reminder(
                UUID.randomUUID().toString().replace("-", ""),
                userId,
                content,
                triggerTime,
                normalizeCron(cronExpression),
                Math.max(0, advanceMinutes),
                LocalDateTime.now(),
                true);
        insert(reminder, false);
        eventPublisher.publishEvent(
                new ReminderChangedEvent(ReminderChangedEvent.Action.SAVED, reminder));
        return reminder;
    }

    public boolean delete(String userId, String reminderId) {
        Reminder existing = find(userId, reminderId);
        int changed = jdbc.update(
                "DELETE FROM scheduled_task WHERE user_id = ? AND id = ?", userId, reminderId);
        if (changed > 0 && existing != null) {
            eventPublisher.publishEvent(
                    new ReminderChangedEvent(ReminderChangedEvent.Action.DELETED, existing));
        }
        return changed > 0;
    }

    /** 删除某用户的全部提醒（供演示脚本清理旧状态，防止重复运行残留）。 */
    public void deleteAll(String userId) {
        requireUser(userId);
        List<Reminder> existing = query("SELECT * FROM scheduled_task WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM scheduled_task WHERE user_id = ?", userId);
        for (Reminder reminder : existing) {
            eventPublisher.publishEvent(
                    new ReminderChangedEvent(ReminderChangedEvent.Action.DELETED, reminder));
        }
    }

    public List<Reminder> list(String userId) {
        requireUser(userId);
        return query(
                "SELECT * FROM scheduled_task WHERE user_id = ? AND enabled = 1 ORDER BY trigger_time",
                userId);
    }

    public List<Reminder> listAll(String userId) {
        requireUser(userId);
        return query(
                "SELECT * FROM scheduled_task WHERE user_id = ? ORDER BY trigger_time",
                userId);
    }

    public List<Reminder> findAllEnabled() {
        return activeReminders();
    }

    public List<Reminder> findDue(LocalDateTime now) {
        return activeReminders().stream().filter(reminder -> reminder.isDue(now)).toList();
    }

    public Reminder handleTriggered(Reminder reminder) {
        Reminder current = find(reminder.userId(), reminder.id());
        if (current == null) {
            return reminder.disable();
        }
        Reminder updated;
        if (current.isRecurring()) {
            LocalDateTime next = current.nextTriggerTime();
            updated = next == null ? current.disable() : current.reschedule(next);
        } else {
            updated = current.disable();
        }
        update(updated);
        return updated;
    }

    public List<Reminder> activeReminders() {
        return jdbc.query(
                "SELECT * FROM scheduled_task WHERE enabled = 1 ORDER BY trigger_time",
                (rs, rowNum) -> map(rs));
    }

    public List<Reminder> allReminders() {
        return jdbc.query(
                "SELECT * FROM scheduled_task ORDER BY trigger_time",
                (rs, rowNum) -> map(rs));
    }

    public boolean setEnabled(String userId, String reminderId, boolean enabled) {
        Reminder existing = find(userId, reminderId);
        if (existing == null) {
            return false;
        }
        Reminder updated = new Reminder(
                existing.id(),
                existing.userId(),
                existing.content(),
                existing.triggerTime(),
                existing.cronExpression(),
                existing.advanceMinutes(),
                existing.createdAt(),
                enabled);
        update(updated);
        if (enabled) {
            eventPublisher.publishEvent(
                    new ReminderChangedEvent(ReminderChangedEvent.Action.SAVED, updated));
        } else {
            eventPublisher.publishEvent(
                    new ReminderChangedEvent(ReminderChangedEvent.Action.DELETED, updated));
        }
        return true;
    }

    /** 仅用于启动时导入旧版 data/reminders/*.json。 */
    public Path remindersDir() {
        return PersistencePaths.root(cloudbotProperties).resolve("reminders");
    }

    private void update(Reminder reminder) {
        jdbc.update("""
                UPDATE scheduled_task
                SET content = ?, trigger_time = ?, cron_expression = ?,
                    advance_minutes = ?, created_at = ?, enabled = ?
                WHERE id = ? AND user_id = ?
                """,
                reminder.content(),
                reminder.triggerTime().toString(),
                reminder.cronExpression(),
                reminder.advanceMinutes(),
                reminder.createdAt().toString(),
                reminder.enabled() ? 1 : 0,
                reminder.id(),
                reminder.userId());
    }

    private void insert(Reminder reminder, boolean ignoreExisting) {
        String prefix = ignoreExisting ? "INSERT IGNORE" : "INSERT";
        jdbc.update(prefix + """
                 INTO scheduled_task
                (id, user_id, content, trigger_time, cron_expression,
                 advance_minutes, created_at, enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                reminder.id(),
                reminder.userId(),
                reminder.content(),
                reminder.triggerTime().toString(),
                reminder.cronExpression(),
                reminder.advanceMinutes(),
                reminder.createdAt().toString(),
                reminder.enabled() ? 1 : 0);
    }

    private Reminder find(String userId, String reminderId) {
        List<Reminder> matches = jdbc.query(
                "SELECT * FROM scheduled_task WHERE user_id = ? AND id = ?",
                (rs, rowNum) -> map(rs),
                userId,
                reminderId);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private List<Reminder> query(String sql, String userId) {
        return jdbc.query(sql, (rs, rowNum) -> map(rs), userId);
    }

    private static Reminder map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Reminder(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("content"),
                LocalDateTime.parse(rs.getString("trigger_time")),
                rs.getString("cron_expression"),
                rs.getInt("advance_minutes"),
                LocalDateTime.parse(rs.getString("created_at")),
                rs.getInt("enabled") != 0);
    }

    private void importLegacyJson() {
        Path dir = remindersDir();
        if (!Files.isDirectory(dir)) {
            return;
        }
        int imported = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : files) {
                imported += importLegacyFile(file);
            }
        } catch (Exception e) {
            log.warn("旧提醒导入 SQLite 失败: {}", e.getMessage());
        }
        if (imported > 0) {
            log.info("已将 {} 条旧提醒导入 cloudbot.db", imported);
        }
    }

    private int importLegacyFile(Path file) {
        try {
            JsonNode root = objectMapper.readTree(file.toFile());
            JsonNode nodes = root.path("reminders");
            if (!nodes.isArray()) {
                return 0;
            }
            int imported = 0;
            for (JsonNode node : nodes) {
                Reminder reminder = legacyReminder(root.path("userId").asText(), node);
                if (reminder != null) {
                    insert(reminder, true);
                    imported++;
                }
            }
            return imported;
        } catch (Exception e) {
            log.warn("跳过无法导入的旧提醒文件 {}: {}", file.getFileName(), e.getMessage());
            return 0;
        }
    }

    private Reminder legacyReminder(String fallbackUserId, JsonNode node) {
        try {
            String userId = node.path("userId").asText(fallbackUserId);
            String recurrence = node.path("recurrence").asText("");
            LocalDateTime triggerTime = LocalDateTime.parse(node.path("triggerTime").asText());
            String cron = node.path("cronExpression").isNull()
                    ? null
                    : node.path("cronExpression").asText(null);
            // 旧 JSON 里 recurrence=daily/weekly 是字面量，CronTrigger 无法解析。
            // 按 triggerTime 转成真实 cron（与 AddReminderTool 的转换逻辑一致），保证可被调度。
            if ((cron == null || cron.isBlank()) && !recurrence.isBlank()) {
                cron = switch (recurrence) {
                    case "daily" -> "0 " + triggerTime.getMinute() + " " + triggerTime.getHour() + " * * ?";
                    case "weekly" -> "0 " + triggerTime.getMinute() + " " + triggerTime.getHour()
                            + " ? * " + triggerTime.getDayOfWeek().getValue();
                    default -> null;
                };
            }
            return new Reminder(
                    node.path("id").asText(),
                    userId,
                    node.path("content").asText(),
                    triggerTime,
                    normalizeCron(cron),
                    node.path("advanceMinutes").asInt(0),
                    node.hasNonNull("createdAt")
                            ? LocalDateTime.parse(node.path("createdAt").asText())
                            : LocalDateTime.now(),
                    !node.has("enabled") || node.path("enabled").asBoolean(true));
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeCron(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()
                || "once".equalsIgnoreCase(cronExpression)) {
            return null;
        }
        return cronExpression.trim();
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
    }
}

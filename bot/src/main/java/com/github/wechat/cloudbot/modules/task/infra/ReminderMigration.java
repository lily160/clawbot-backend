package com.github.wechat.cloudbot.modules.task.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 将旧版提醒数据（recurrence=daily/weekly/once）迁移为 cronExpression 格式。
 *
 * <p>启动时自动扫描 reminders 目录，检测旧格式并转换。
 * 幂等：转换后写入新格式，后续不再重复执行。
 */
@Component
public class ReminderMigration {

    private static final Logger log = LoggerFactory.getLogger(ReminderMigration.class);

    private final ObjectMapper objectMapper;
    private final ReminderService reminderService;

    public ReminderMigration(ObjectMapper objectMapper, ReminderService reminderService) {
        this.objectMapper = objectMapper;
        this.reminderService = reminderService;
    }

    @PostConstruct
    void migrate() {
        Path dir = reminderService.remindersDir();
        if (!Files.exists(dir)) {
            return;
        }

        int migrated = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                if (migrateFile(file)) {
                    migrated++;
                }
            }
        } catch (IOException e) {
            log.warn("迁移扫描失败: {}", e.getMessage());
        }
        if (migrated > 0) {
            log.info("已迁移 {} 个用户的旧版提醒数据", migrated);
        }
    }

    private boolean migrateFile(Path file) {
        try {
            String raw = Files.readString(file);
            JsonNode root = objectMapper.readTree(raw);
            JsonNode reminders = root.get("reminders");
            if (reminders == null || !reminders.isArray()) {
                return false;
            }

            boolean changed = false;
            for (JsonNode node : reminders) {
                if (node.has("recurrence") && !node.has("cronExpression")) {
                    String recurrence = node.get("recurrence").asText("");
                    String cron = convertRecurrence(recurrence, node);
                    if (cron != null) {
                        ((ObjectNode) node).put("cronExpression", cron);
                        ((ObjectNode) node).remove("recurrence");
                        changed = true;
                    } else if ("once".equals(recurrence)) {
                        // once 任务：cronExpression 设为 null，移除 recurrence
                        ((ObjectNode) node).putNull("cronExpression");
                        ((ObjectNode) node).remove("recurrence");
                        changed = true;
                    }
                }
            }

            if (changed) {
                Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));
                log.info("迁移文件: {}", file.getFileName());
                return true;
            }
        } catch (IOException e) {
            log.warn("迁移文件失败 {}: {}", file.getFileName(), e.getMessage());
        }
        return false;
    }

    /** 将旧版 recurrence 转为 Cron 表达式。 */
    static String convertRecurrence(String recurrence, JsonNode reminderNode) {
        return switch (recurrence) {
            case "once" -> null;
            case "daily" -> {
                String triggerTime = reminderNode.has("triggerTime")
                        ? reminderNode.get("triggerTime").asText("") : "";
                if (triggerTime.length() >= 16) {
                    String hour = triggerTime.substring(11, 13);
                    String minute = triggerTime.substring(14, 16);
                    yield "0 " + minute + " " + hour + " * * ?";
                }
                yield "0 0 9 * * ?";
            }
            case "weekly" -> {
                String triggerTime = reminderNode.has("triggerTime")
                        ? reminderNode.get("triggerTime").asText("") : "";
                if (triggerTime.length() >= 16) {
                    String hour = triggerTime.substring(11, 13);
                    String minute = triggerTime.substring(14, 16);
                    String dayOfWeek = getDayOfWeekCron(triggerTime.substring(0, 10));
                    yield "0 " + minute + " " + hour + " ? * " + dayOfWeek;
                }
                yield "0 0 9 ? * MON";
            }
            default -> null;
        };
    }

    /**
     * 将日期字符串（yyyy-MM-dd）转换为 Cron 星期几数字。
     * <p>Spring CronExpression 使用 ISO 8601：1=Monday, 7=Sunday。
     * Java DayOfWeek 也是 MONDAY=1, TUESDAY=2, ..., SUNDAY=7，直接使用即可。</p>
     */
    static String getDayOfWeekCron(String dateStr) {
        try {
            java.time.LocalDate date = java.time.LocalDate.parse(dateStr);
            // Spring CronExpression 使用 ISO 8601: 1=Monday, 7=Sunday
            // Java DayOfWeek: MONDAY=1, SUNDAY=7 — 与 ISO 8601 一致，直接使用
            return String.valueOf(date.getDayOfWeek().getValue());
        } catch (Exception e) {
            return "MON";
        }
    }
}

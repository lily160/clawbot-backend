package com.github.wechat.cloudbot.modules.health.checkin.service;

import com.github.wechat.cloudbot.modules.health.checkin.infra.HealthReminderKind;
import com.github.wechat.cloudbot.modules.health.common.HealthDisclaimers;
import com.github.wechat.cloudbot.modules.task.service.Reminder;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 统一健康提醒服务：把「喝水 / 运动 / 早睡 / 复查 / 自定义」转成每日 cron 提醒，
 * 复用 {@link ReminderService} 持久化与到点推送。
 *
 * <p>提醒内容带打卡引导（前三类）与归属标记 {@code [健康打卡:{kind}]}，
 * 支持按类型查看 / 取消，风格对齐用药提醒的 ownership-marker 模式。</p>
 */
@Service
public class HealthReminderService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    /** 提醒内容归属标记前缀，用于按类型查找 / 取消。 */
    public static final String MARKER_PREFIX = "[健康打卡:";

    private final ReminderService reminderService;

    public HealthReminderService(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    /**
     * 设置一条每日重复的日常健康提醒。
     *
     * @param userId     微信用户 ID
     * @param kind       提醒类型（统一注册表枚举）
     * @param time       每日提醒时间 HH:mm；为空时使用该类型的默认时间
     * @param customText kind=custom 时的自定义文案
     * @return 已持久化的提醒
     */
    public Reminder setReminder(String userId, HealthReminderKind kind, String time, String customText) {
        if (kind == null) {
            throw new IllegalArgumentException("提醒类型不能为空");
        }
        // 设置即覆盖：先清理同类型的旧提醒，避免把 08:00 改成 09:00 后残留两条、每天重复推送
        cancelReminder(userId, kind);
        String content = buildContent(kind, customText);
        LocalTime localTime = parseTime(resolveTime(kind, time));
        String cron = dailyCron(localTime);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime triggerTime = now.withHour(localTime.getHour())
                .withMinute(localTime.getMinute())
                .withSecond(0)
                .withNano(0);
        if (triggerTime.isBefore(now)) {
            triggerTime = triggerTime.plusDays(1);
        }
        return reminderService.add(userId, content, triggerTime, 0, cron);
    }

    /**
     * 按类型取消该用户的健康提醒（匹配内容中的归属标记）。
     *
     * @return 是否删除了至少一条提醒
     */
    public boolean cancelReminder(String userId, HealthReminderKind kind) {
        if (kind == null) {
            return false;
        }
        String marker = marker(kind);
        List<Reminder> reminders = reminderService.list(userId);
        boolean removed = false;
        for (Reminder r : reminders) {
            if (r.content().contains(marker)) {
                removed |= reminderService.delete(userId, r.id());
            }
        }
        return removed;
    }

    /**
     * 列出该用户全部健康提醒（按归属标记过滤）。
     */
    public List<Reminder> listReminders(String userId) {
        return reminderService.list(userId).stream()
                .filter(r -> r.content().contains(MARKER_PREFIX))
                .toList();
    }

    /** 生成某提醒类型的归属标记，如 {@code [健康打卡:drink_water]}。 */
    public static String marker(HealthReminderKind kind) {
        return MARKER_PREFIX + kind.key() + "]";
    }

    /** 从提醒内容中解析出所属的健康提醒类型；非健康提醒返回 null。 */
    public static HealthReminderKind kindOf(Reminder reminder) {
        String content = reminder == null ? null : reminder.content();
        if (content == null) {
            return null;
        }
        int start = content.indexOf(MARKER_PREFIX);
        if (start < 0) {
            return null;
        }
        int end = content.indexOf("]", start);
        if (end < 0) {
            return null;
        }
        try {
            return HealthReminderKind.parse(content.substring(start + MARKER_PREFIX.length(), end));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 把每日提醒渲染成「每天 HH:mm」。 */
    public static String formatSchedule(Reminder reminder) {
        return "每天 " + reminder.triggerTime().format(TIME_FORMATTER);
    }

    private static String resolveTime(HealthReminderKind kind, String time) {
        if (time == null || time.isBlank()) {
            if (kind.defaultTime() == null) {
                throw new IllegalArgumentException("自定义提醒需指定时间（HH:mm）");
            }
            return kind.defaultTime();
        }
        return time.trim();
    }

    private static String buildContent(HealthReminderKind kind, String customText) {
        String body;
        if (kind == HealthReminderKind.CUSTOM) {
            if (customText == null || customText.isBlank()) {
                throw new IllegalArgumentException("自定义提醒内容（note）不能为空");
            }
            body = "📌 日常提醒：" + customText.trim();
        } else {
            body = kind.contentTemplate();
        }
        return body + marker(kind) + "\n" + HealthDisclaimers.MEDICAL_DISCLAIMER;
    }

    private static LocalTime parseTime(String time) {
        try {
            return LocalTime.parse(time, TIME_FORMATTER);
        } catch (Exception e) {
            throw new IllegalArgumentException("时间格式应为 HH:mm，例如 08:00、21:30");
        }
    }

    private static String dailyCron(LocalTime time) {
        return "0 " + time.getMinute() + " " + time.getHour() + " * * ?";
    }
}

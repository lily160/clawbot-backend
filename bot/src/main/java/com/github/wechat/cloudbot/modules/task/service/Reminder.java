package com.github.wechat.cloudbot.modules.task.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.Locale;

/**
 * 健康任务的兼容领域模型。
 *
 * <p>保留旧提醒语义的同时，补充统一的任务状态与类型判断，供调度、恢复和存储层复用。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Reminder(
        String id,
        String userId,
        String content,
        LocalDateTime triggerTime,
        String cronExpression,
        int advanceMinutes,
        LocalDateTime createdAt,
        boolean enabled
) {

    public enum TaskKind { ONE_TIME, RECURRING }

    public enum TaskState { PENDING, READY, DISABLED, COMPLETED, RECOVERING }

    public Reminder {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public TaskKind kind() {
        return isRecurring() ? TaskKind.RECURRING : TaskKind.ONE_TIME;
    }

    public TaskState state() {
        if (!enabled) {
            return isRecurring() ? TaskState.DISABLED : TaskState.COMPLETED;
        }
        return isDue(LocalDateTime.now()) ? TaskState.READY : TaskState.PENDING;
    }

    public boolean isDue(LocalDateTime now) {
        return now != null && !now.isBefore(notifyTime());
    }

    /** 计算实际发送提醒的时间（考虑提前量）。 */
    public LocalDateTime notifyTime() {
        return triggerTime.minusMinutes(Math.max(0, advanceMinutes));
    }

    /** 根据表达式计算下一次触发时间。兼容 daily/weekly 以及 Cron 表达式。 */
    public LocalDateTime nextTriggerTime() {
        if (cronExpression == null || cronExpression.isBlank()) {
            return null;
        }
        String normalized = cronExpression.trim();
        return switch (normalized.toLowerCase(Locale.ROOT)) {
            case "daily" -> triggerTime.plusDays(1);
            case "weekly" -> triggerTime.plusWeeks(1);
            case "once" -> null;
            default -> {
                if (!isCronLike(normalized)) {
                    yield null;
                }
                try {
                    CronExpression cron = CronExpression.parse(normalized);
                    yield cron.next(LocalDateTime.now());
                } catch (Exception e) {
                    yield null;
                }
            }
        };
    }

    /** 是否为周期性任务。 */
    @JsonIgnore
    public boolean isRecurring() {
        return cronExpression != null && !cronExpression.isBlank() && !"once".equalsIgnoreCase(cronExpression.trim());
    }

    /** 是否为一次性任务。 */
    @JsonIgnore
    public boolean isOneTime() {
        return !isRecurring();
    }

    public Reminder disable() {
        if (!enabled) {
            return this;
        }
        return new Reminder(id, userId, content, triggerTime, cronExpression, advanceMinutes, createdAt, false);
    }

    public Reminder reschedule(LocalDateTime nextTriggerTime) {
        return new Reminder(id, userId, content, nextTriggerTime, cronExpression, advanceMinutes, createdAt, true);
    }

    private static boolean isCronLike(String expression) {
        return expression.chars().filter(ch -> ch == ' ').count() >= 5;
    }
}

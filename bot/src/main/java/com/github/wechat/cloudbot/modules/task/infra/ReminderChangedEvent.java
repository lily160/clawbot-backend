package com.github.wechat.cloudbot.modules.task.infra;

import com.github.wechat.cloudbot.modules.task.service.Reminder;

/**
 * 提醒变更事件：用于通知调度器注册、取消或重建任务。
 */
public record ReminderChangedEvent(Action action, Reminder reminder) {

    public enum Action {
        SAVED,
        DELETED,
        TRIGGERED
    }

    public static ReminderChangedEvent saved(Reminder reminder) {
        return new ReminderChangedEvent(Action.SAVED, reminder);
    }

    public static ReminderChangedEvent deleted(Reminder reminder) {
        return new ReminderChangedEvent(Action.DELETED, reminder);
    }

    public static ReminderChangedEvent triggered(Reminder reminder) {
        return new ReminderChangedEvent(Action.TRIGGERED, reminder);
    }
}

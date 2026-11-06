package com.github.wechat.cloudbot.modules.task.infra;

import com.github.wechat.cloudbot.modules.task.service.Reminder;

/**
 * 提醒调度器对外契约：业务层只依赖这个接口，不直接绑定具体线程实现。
 */
public interface ReminderTaskSchedulerPort {

    void scheduleIfAbsent(Reminder reminder);

    boolean hasTask(Reminder reminder);

    void cancel(Reminder reminder);
}

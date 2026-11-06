package com.github.wechat.cloudbot.modules.task.infra;

import com.github.wechat.cloudbot.modules.task.service.Reminder;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.channel.wechat.OutboundGateway;
import com.github.wechat.ilink.sdk.ILinkClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提醒调度器最小实现：先把事件监听、任务登记和取消串起来。
 */
@Component
public class ReminderTaskScheduler implements ReminderTaskSchedulerPort {

    private static final Logger log = LoggerFactory.getLogger(ReminderTaskScheduler.class);

    private final ReminderService reminderService;
    private final OutboundGateway outboundGateway;
    private final ILinkClient client;
    private final Map<String, ScheduledReminder> scheduled = new ConcurrentHashMap<>();

    public ReminderTaskScheduler(ReminderService reminderService,
                                 OutboundGateway outboundGateway,
                                 ILinkClient client) {
        this.reminderService = reminderService;
        this.outboundGateway = outboundGateway;
        this.client = client;
    }

    @Override
    public void scheduleIfAbsent(Reminder reminder) {
        if (reminder == null || !reminder.enabled()) {
            return;
        }
        String key = key(reminder);
        scheduled.computeIfAbsent(key, ignored -> {
            log.info("登记提醒任务 key={}, fireAt={}", key, reminder.triggerTime());
            return new ScheduledReminder(reminder, LocalDateTime.now());
        });
    }

    @Override
    public boolean hasTask(Reminder reminder) {
        return reminder != null && scheduled.containsKey(key(reminder));
    }

    @Override
    public void cancel(Reminder reminder) {
        if (reminder == null) {
            return;
        }
        String key = key(reminder);
        if (scheduled.remove(key) != null) {
            log.info("取消提醒任务 key={}", key);
        }
    }

    private String key(Reminder reminder) {
        return reminder.userId() + ":" + reminder.id();
    }

    private record ScheduledReminder(Reminder reminder, LocalDateTime createdAt) {
        private ScheduledReminder {
            Objects.requireNonNull(reminder, "reminder");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }
}

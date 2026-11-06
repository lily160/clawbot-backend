package com.github.wechat.cloudbot.modules.task.infra;

import com.github.wechat.cloudbot.modules.task.service.Reminder;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.channel.wechat.OutboundGateway;
import com.github.wechat.ilink.sdk.ILinkClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * Cron 驱动的调度引擎。替换旧的轮询式 {@code ReminderScheduler}。
 *
 * <p>一次性任务：用 {@link TaskScheduler#schedule(Runnable, Instant)} 精确触发。
 * Cron 任务：用 {@link TaskScheduler#schedule(Runnable, CronTrigger)} 按表达式触发。
 */
@Component
public class CronScheduler implements ReminderTaskSchedulerPort {

    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);

    private final TaskScheduler taskScheduler;
    private final ReminderService reminderService;
    private final ReminderProperties reminderProperties;
    private final OutboundGateway outboundGateway;
    private final ILinkClient client;

    /** 提醒 ID → ScheduledFuture，用于取消/重调度。 */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public CronScheduler(
            TaskScheduler taskScheduler,
            ReminderService reminderService,
            ReminderProperties reminderProperties,
            OutboundGateway outboundGateway,
            ILinkClient client) {
        this.taskScheduler = taskScheduler;
        this.reminderService = reminderService;
        this.reminderProperties = reminderProperties;
        this.outboundGateway = outboundGateway;
        this.client = client;
    }

    @PostConstruct
    void init() {
        if (!reminderProperties.isEnabled()) {
            log.info("提醒模块已禁用，Cron 调度器不启动");
            return;
        }
        Collection<Reminder> all = reminderService.activeReminders();
        if (all.isEmpty()) {
            log.info("Cron 调度器启动，无待调度任务");
            return;
        }
        int scheduled = 0;
        for (Reminder reminder : all) {
            try {
                scheduleReminder(reminder);
                scheduled++;
            } catch (Exception e) {
                log.warn("Cron 调度器初始化失败 id={}: {}", reminder.id(), e.getMessage());
            }
        }
        log.info("Cron 调度器启动，已注册 {} 个任务", scheduled);
    }

    /** 注册一个提醒任务。一次性任务用延迟执行，周期性任务用 CronTrigger。 */
    public void scheduleReminder(Reminder reminder) {
        if (!reminder.enabled()) {
            log.debug("跳过已禁用的提醒 id={}", reminder.id());
            return;
        }

        cancel(reminder.id());

        if (reminder.isRecurring()) {
            scheduleCron(reminder);
        } else {
            scheduleOneTime(reminder);
        }
    }

    private void scheduleOneTime(Reminder reminder) {
        Instant fireTime = reminder.notifyTime()
                .atZone(ZoneId.systemDefault())
                .toInstant();
        Instant now = Instant.now();
        if (fireTime.isBefore(now)) {
            log.warn("一次性任务已过期 id={}, fireTime={}", reminder.id(), fireTime);
            reminderService.handleTriggered(reminder);
            return;
        }

        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> fire(reminder),
                fireTime);
        scheduledTasks.put(reminder.id(), future);
        log.info("一次性任务已注册 id={}, content={}, fireTime={}",
                reminder.id(), reminder.content(), fireTime);
    }

    private void scheduleCron(Reminder reminder) {
        CronTrigger trigger;
        try {
            trigger = new CronTrigger(reminder.cronExpression());
        } catch (IllegalArgumentException e) {
            // 非法 cron（LLM 直传或脏数据）只记录告警并跳过调度，避免在提醒已入库后抛异常
            // 让用户看到"失败"而实际已保存，造成状态不一致
            log.warn("非法 cron 表达式，跳过调度 id={}, cron={}: {}",
                    reminder.id(), reminder.cronExpression(), e.getMessage());
            return;
        }
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> fire(reminder),
                trigger);
        scheduledTasks.put(reminder.id(), future);
        log.info("Cron 任务已注册 id={}, content={}, cron={}",
                reminder.id(), reminder.content(), reminder.cronExpression());
    }

    /** 执行提醒触发。 */
    private void fire(Reminder reminder) {
        try {
            String text = buildText(reminder);
            outboundGateway.sendText(client, reminder.userId(), text);
            log.info("提醒已发送 userId={}, content={}, trigger={}",
                    reminder.userId(), reminder.content(), reminder.triggerTime());
        } catch (Exception e) {
            log.error("提醒发送失败 userId={}: {}", reminder.userId(), e.getMessage());
        }

        // 处理后事（一次性→禁用，周期性→计算下次）
        Reminder updated = reminderService.handleTriggered(reminder);

        // 如果任务被禁用，从调度器移除
        if (!updated.enabled()) {
            cancel(reminder.id());
        }
    }

    /** 取消指定提醒的调度。 */
    public void cancel(String reminderId) {
        ScheduledFuture<?> future = scheduledTasks.remove(reminderId);
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
            log.debug("已取消提醒调度 id={}", reminderId);
        }
    }

    @Override
    public void scheduleIfAbsent(Reminder reminder) {
        if (reminder == null || reminder.id() == null) {
            return;
        }
        if (!scheduledTasks.containsKey(reminder.id())) {
            scheduleReminder(reminder);
        }
    }

    @Override
    public boolean hasTask(Reminder reminder) {
        return reminder != null && scheduledTasks.containsKey(reminder.id());
    }

    @Override
    public void cancel(Reminder reminder) {
        if (reminder != null) {
            cancel(reminder.id());
        }
    }

    /** 取消并重新注册。 */
    @EventListener
    public void onReminderChanged(ReminderChangedEvent event) {
        if (event == null || event.reminder() == null) {
            return;
        }
        switch (event.action()) {
            case SAVED -> scheduleReminder(event.reminder());
            case DELETED -> cancel(event.reminder().id());
            case TRIGGERED -> scheduleReminder(event.reminder());
        }
    }

    public void reschedule(Reminder reminder) {
        cancel(reminder.id());
        if (reminder.enabled()) {
            scheduleReminder(reminder);
        }
    }

    /** 获取当前调度的任务数量。 */
    public int scheduledCount() {
        return scheduledTasks.size();
    }

    public boolean hasTask(String reminderId) {
        return reminderId != null && scheduledTasks.containsKey(reminderId);
    }

    private String buildText(Reminder r) {
        String timeStr = r.triggerTime().format(DateTimeFormatter.ofPattern("HH:mm"));
        StringBuilder sb = new StringBuilder();
        sb.append("【提醒】").append(r.content());
        if (r.advanceMinutes() > 0) {
            sb.append("（原定 ").append(timeStr).append("，提前 ").append(r.advanceMinutes()).append(" 分钟提醒）");
        } else {
            sb.append("（时间：").append(timeStr).append("）");
        }
        if (r.isRecurring()) {
            sb.append(" [Cron: ").append(r.cronExpression()).append("]");
        }
        return sb.toString();
    }
}

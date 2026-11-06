package com.github.wechat.cloudbot.modules.task.infra;

import com.github.wechat.cloudbot.modules.task.service.Reminder;
import com.github.wechat.cloudbot.modules.task.service.ReminderService;
import com.github.wechat.cloudbot.platform.channel.wechat.OutboundGateway;
import com.github.wechat.ilink.sdk.ILinkClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class ReminderRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderRecoveryScheduler.class);

    private final ReminderService reminderService;
    private final CronScheduler cronScheduler;
    private final OutboundGateway outboundGateway;
    private final ILinkClient client;

    public ReminderRecoveryScheduler(
            ReminderService reminderService,
            CronScheduler cronScheduler,
            OutboundGateway outboundGateway,
            ILinkClient client) {
        this.reminderService = reminderService;
        this.cronScheduler = cronScheduler;
        this.outboundGateway = outboundGateway;
        this.client = client;
    }

    @PostConstruct
    void init() {
        recoverMissedReminders();
    }

    @Scheduled(cron = "0 * * * * *")
    public void recoverMissedReminders() {
        LocalDateTime now = LocalDateTime.now();
        List<Reminder> due = reminderService.findDue(now);
        if (due.isEmpty()) {
            return;
        }

        int recovered = 0;
        for (Reminder reminder : due) {
            if (cronScheduler.hasTask(reminder.id())) {
                continue;
            }
            if (reminder.notifyTime().isAfter(now)) {
                continue;
            }

            try {
                outboundGateway.sendText(client, reminder.userId(), buildMissedNotice(reminder));
                reminderService.handleTriggered(reminder);
                recovered++;
            } catch (Exception e) {
                log.error("提醒补偿发送失败 userId={}, id={}: {}", reminder.userId(), reminder.id(), e.getMessage(), e);
            }
        }

        if (recovered > 0) {
            log.info("提醒补偿扫描完成 recovered={}, now={}", recovered, now);
        }
    }

    private static String buildMissedNotice(Reminder r) {
        String timeStr = r.triggerTime().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
        StringBuilder sb = new StringBuilder();
        sb.append("【提醒补偿】你有一条提醒曾在 ").append(timeStr).append(" 到期，但当前处于离线/重启补偿场景，已为你补发：");
        sb.append(r.content());
        if (r.advanceMinutes() > 0) {
            sb.append("（提前 ").append(r.advanceMinutes()).append(" 分钟提醒）");
        }
        if (r.isRecurring()) {
            sb.append(" [Cron: ").append(r.cronExpression()).append("]");
        }
        return sb.toString();
    }
}

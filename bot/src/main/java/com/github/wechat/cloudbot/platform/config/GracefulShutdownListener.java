package com.github.wechat.cloudbot.platform.config;

import com.github.wechat.cloudbot.modules.task.infra.CronScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 监听 Spring ContextClosedEvent，执行优雅关闭逻辑。
 */
@Component
public class GracefulShutdownListener {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownListener.class);

    private final CronScheduler cronScheduler;

    public GracefulShutdownListener(CronScheduler cronScheduler) {
        this.cronScheduler = cronScheduler;
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        log.info("收到 ContextClosedEvent，开始优雅关闭...");

        int scheduledCount = cronScheduler.scheduledCount();
        if (scheduledCount > 0) {
            log.info("Cron 调度器正在关闭，待取消任务数: {}", scheduledCount);
            // CronScheduler 的 Future 会在 ThreadPoolTaskScheduler shutdown 时自动取消
            // destroyMethod="shutdown" 的 ThreadPoolTaskScheduler 会等待任务完成
        }

        log.info("优雅关闭完成");
    }
}

package com.github.wechat.cloudbot.modules.task.infra;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfiguration {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler(SchedulerProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.getPoolSize());
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        scheduler.setThreadNamePrefix("cron-scheduler-");
        scheduler.setErrorHandler(t ->
                logger.error("Cron 调度任务异常: {}", t.getMessage(), t));
        return scheduler;
    }

    private static final org.slf4j.Logger logger =
            org.slf4j.LoggerFactory.getLogger(SchedulerConfiguration.class);
}

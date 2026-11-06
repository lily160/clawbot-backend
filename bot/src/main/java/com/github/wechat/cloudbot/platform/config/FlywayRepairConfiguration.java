package com.github.wechat.cloudbot.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Flyway 迁移策略：启动时先 repair 再 migrate。
 *
 * <p>⚠️ 过渡性策略：仅用于清理历史分支遗留的 {@code V2_2__normalize_reminders.sql} 条目
 * （已被升级为 H2 兼容的 Java 迁移 {@code V2_3__normalize_reminders}）。
 * 所有环境迁移收敛后应删除本类，改回严格的默认校验——全局 repair 会把真实迁移脚本
 * 被篡改/漂移（checksum 不匹配）静默改写掉，使生产安全审计失效。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FlywayRepairConfiguration {

    private static final Logger log = LoggerFactory.getLogger(FlywayRepairConfiguration.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            flyway.repair();
            log.warn("Flyway repair 已执行（过渡期策略，收敛后应移除）");
            flyway.migrate();
        };
    }
}

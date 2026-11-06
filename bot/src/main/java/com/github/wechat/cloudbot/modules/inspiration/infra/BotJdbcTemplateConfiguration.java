package com.github.wechat.cloudbot.modules.inspiration.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * bot 相关数据访问配置。
 *
 * <p>直接复用 Spring 主数据源，避免双库错配。
 */
@Configuration
public class BotJdbcTemplateConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BotJdbcTemplateConfiguration.class);

    @Bean(name = "botJdbcTemplate")
    JdbcTemplate botJdbcTemplate(DataSource dataSource) {
        log.info("botJdbcTemplate 复用主数据源（MySQL）");
        return new JdbcTemplate(dataSource);
    }
}

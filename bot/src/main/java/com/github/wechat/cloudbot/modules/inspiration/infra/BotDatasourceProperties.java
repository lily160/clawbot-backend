package com.github.wechat.cloudbot.modules.inspiration.infra;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SQLite 数据库连接配置，对应 application.yml {@code cloudbot.datasource.url}。
 *
 * <p>通过 {@code @ConfigurationPropertiesScan} 自动注册，无需 {@code @Bean} 方法。
 */
@ConfigurationProperties(prefix = "cloudbot.datasource")
public class BotDatasourceProperties {

    /**
     * SQLite 数据库 JDBC URL，默认 {@code jdbc:sqlite:cloudbot.db}。
     */
    private String url = "jdbc:sqlite:cloudbot.db";

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }
}

package com.github.wechat.cloudbot.platform.channel.wechat;

import com.github.wechat.cloudbot.platform.persistence.AuthStorage;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * SDK 客户端装配：将 yml 配置映射为 {@link ILinkConfig}，并注册登录 / 消息监听器。
 *
 * <p><b>警告：请使用微信小号扫码。Cloudbot 存在封号风险，切勿使用主号。</b>
 */
@Configuration
public class ILinkClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ILinkClientConfiguration.class);

    @Bean(destroyMethod = "close")
    public ILinkClient iLinkClient(
            ILinkSdkProperties props,
            MessageEventHandler messageEventHandler,
            AuthStorage authStorage) {

        ILinkConfig.Builder builder =
                ILinkConfig.builder()
                        .connectTimeoutMs(props.getConnectTimeoutMs())
                        .readTimeoutMs(props.getReadTimeoutMs())
                        .writeTimeoutMs(props.getWriteTimeoutMs())
                        .httpMaxRetries(props.getHttpMaxRetries())
                        .retryBaseDelayMs(props.getRetryBaseDelayMs())
                        .retryMaxDelayMs(props.getRetryMaxDelayMs())
                        .retryJitterEnabled(props.isRetryJitterEnabled())
                        .loginTimeoutMs(props.getLoginTimeoutMs())
                        .heartbeatEnabled(props.isHeartbeatEnabled())
                        .heartbeatIntervalMs(props.getHeartbeatIntervalMs())
                        .channelVersion(props.getChannelVersion());

        if (props.getRouteTag() != null && !props.getRouteTag().isBlank()) {
            builder.routeTag(props.getRouteTag());
        }

        ILinkClientBuilder clientBuilder =
                ILinkClient.builder()
                        .config(builder.build())
                        .onLogin(
                                new OnLoginListener() {
                                    @Override
                                    public void onLoginSuccess(LoginContext context) {
                                        authStorage.save(context);
                                        log.info("Login successful");
                                        log.info(
                                                "登录成功 botId={}, userId={}",
                                                context.getBotId(),
                                                context.getUserId());
                                    }

                                    @Override
                                    public void onLoginFailure(Throwable throwable) {
                                        authStorage.clear();
                                        log.error("{}登录失败: {}", ErrorCode.CHANNEL_LOGIN_ERROR.prefix(), throwable.getMessage(), throwable);
                                    }
                                })
                        .onMessage(messageEventHandler);

        Optional<LoginContext> cached = authStorage.load();
        if (cached.isPresent()) {
            clientBuilder.loginContext(cached.get());
            log.info(
                    "已注入本地登录凭证，将尝试免扫码恢复 botId={}",
                    cached.get().getBotId());
        }

        return clientBuilder.build();
    }
}

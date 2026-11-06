package com.github.wechat.cloudbot.platform.channel.wechat;

import com.github.wechat.cloudbot.platform.config.ErrorCode;
import com.github.wechat.cloudbot.platform.persistence.AuthStorage;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 应用启动后自动拉起扫码登录；若本地已有有效凭证则免扫码恢复。
 *
 * <p>注意：本项目未引入 Web 容器，且 SDK 心跳线程为 daemon。登录成功后必须阻塞住主流程，
 * 否则 JVM 会立刻退出。
 *
 * <p>可通过 {@code cloudbot.wechat.starter.enabled=false} 禁用本启动器，
 * 用于健康管家模拟等无需微信长连接的场景，避免阻塞主线程。
 *
 * <p><b>重要提醒：请使用微信【小号】扫码登录。Cloudbot 存在封号风险，切勿使用常用主号测试。</b>
 */
@Component
@ConditionalOnProperty(name = "cloudbot.wechat.starter.enabled", havingValue = "true", matchIfMissing = true)
public class WeChatBotStarter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WeChatBotStarter.class);

    private final ILinkClient client;
    private final QrCodeDisplayService qrCodeDisplayService;
    private final AuthStorage authStorage;
    private final ILinkSdkProperties sdkProperties;
    private final CountDownLatch keepAlive = new CountDownLatch(1);

    public WeChatBotStarter(
            ILinkClient client,
            QrCodeDisplayService qrCodeDisplayService,
            AuthStorage authStorage,
            ILinkSdkProperties sdkProperties) {
        this.client = client;
        this.qrCodeDisplayService = qrCodeDisplayService;
        this.authStorage = authStorage;
        this.sdkProperties = sdkProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("============================================================");
        log.info("  安全提醒：请使用微信【小号】扫码！Cloudbot 存在封号风险！");
        log.info("============================================================");

        Runtime.getRuntime().addShutdownHook(new Thread(keepAlive::countDown, "cloudbot-shutdown"));

        try {
            LoginContext resumed = client.getLoginContext();
            if (resumed != null) {
                log.info(
                        "从本地凭证恢复登录，跳过扫码 botId={}, userId={}",
                        resumed.getBotId(),
                        resumed.getUserId());
                log.info("心跳长轮询已由 SDK 启动，开始监听消息");
                log.info("Bot 持续运行中，请保持本窗口不要关闭；按 Ctrl+C 可退出");
                keepAlive.await();
                return;
            }

            // 扫码登录：超时后自动重试，刷新二维码
            int maxRetries = 3;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                log.info("扫码登录第 {}/{} 次尝试，请使用微信【小号】扫码...", attempt, maxRetries);

                String qrContent = client.executeLogin();
                qrCodeDisplayService.display(qrContent);

                long timeoutMs = sdkProperties.getLoginTimeoutMs();
                log.info("等待扫码确认（第 {} 次），超时 {} ms ...", attempt, timeoutMs);

                try {
                    LoginContext context =
                            client.getLoginFuture().get(timeoutMs, TimeUnit.MILLISECONDS);

                    log.info(
                            "扫码登录完成，botId={}, userId={}",
                            context.getBotId(),
                            context.getUserId());
                    log.info("心跳长轮询已由 SDK 启动，开始监听消息");
                    log.info("Bot 持续运行中，请保持本窗口不要关闭；按 Ctrl+C 可退出");

                    keepAlive.await();
                    return;
                } catch (TimeoutException e) {
                    authStorage.clear();
                    if (attempt < maxRetries) {
                        log.warn(
                                "第 {} 次扫码超时，即将重新生成二维码（剩余 {} 次重试）",
                                attempt,
                                maxRetries - attempt);
                        // 短暂等待，让 SDK 完成内部清理
                        Thread.sleep(2000);
                    } else {
                        log.error(
                                "{}扫码登录失败：已重试 {} 次仍超时，请稍后重启 Bot",
                                ErrorCode.CHANNEL_LOGIN_ERROR.prefix(),
                                maxRetries);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Bot 收到中断，准备退出");
        } catch (Exception e) {
            authStorage.clear();
            log.error("{}启动登录失败: {}", ErrorCode.CHANNEL_LOGIN_ERROR.prefix(), e.getMessage(), e);
        }
    }
}

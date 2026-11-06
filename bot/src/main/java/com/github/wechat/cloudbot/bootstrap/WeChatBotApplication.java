package com.github.wechat.cloudbot.bootstrap;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 微信 Cloudbot（Agent）启动入口。
 *
 * <p>扫描整个 {@code com.github.wechat.cloudbot}（platform + modules）。
 *
 * <p><b>安全提醒：请使用微信小号进行扫码测试。Cloudbot / 非官方协议机器人存在封号风险，
 * 切勿使用常用主号登录。</b>
 */
@SpringBootApplication(scanBasePackages = "com.github.wechat.cloudbot")
@ConfigurationPropertiesScan(basePackages = "com.github.wechat.cloudbot")
@EnableScheduling
@MapperScan("com.github.wechat.cloudbot.**.infra")
public class WeChatBotApplication {

    private static final Logger log = LoggerFactory.getLogger(WeChatBotApplication.class);

    private static final Path PID_FILE = Path.of("cloudbot.pid");

    public static void main(String[] args) {
        killPreviousInstance();
        writePid();
        SpringApplication.run(WeChatBotApplication.class, args);
    }

    /** 杀掉上次启动残留的旧进程（通过 cloudbot.pid 文件记录的 PID）。 */
    private static void killPreviousInstance() {
        if (!Files.exists(PID_FILE)) {
            return;
        }
        try {
            String pidStr = Files.readString(PID_FILE).trim();
            if (!pidStr.isBlank()) {
                long pid = Long.parseLong(pidStr);
                ProcessHandle.of(pid).ifPresentOrElse(
                        process -> {
                            // 校验命令行属于本应用：防止残留 PID 已被系统复用为无关进程时误杀
                            if (!looksLikeThisApp(process)) {
                                log.warn("PID={} 不是 Cloudbot 进程，跳过（避免误杀被系统复用的无关进程）", pid);
                                return;
                            }
                            log.info("发现旧 Cloudbot 进程 PID={}，正在终止...", pid);
                            process.destroy();
                            try {
                                Thread.sleep(1500);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            if (process.isAlive()) {
                                process.destroyForcibly();
                            }
                        },
                        () -> log.info("旧进程 PID={} 已不存在，跳过", pid));
            }
        } catch (Exception e) {
            log.warn("清理旧进程失败: {}", e.getMessage());
        }
    }

    /** 通过命令行匹配判断该 PID 是否属于本应用（防止 PID 被复用为无关进程后误杀）。 */
    private static boolean looksLikeThisApp(ProcessHandle process) {
        return process.info().commandLine()
                .map(cl -> cl.contains("cloudbot") || cl.contains("WeChatBotApplication"))
                .orElse(false);
    }

    /** 将当前进程 PID 写入 cloudbot.pid 文件，退出时自动删除。 */
    private static void writePid() {
        try {
            Files.writeString(PID_FILE,
                    String.valueOf(ProcessHandle.current().pid()),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { Files.deleteIfExists(PID_FILE); } catch (IOException e) {
                    // 预期的正常关机时清理
                }
            }));
            log.info("PID 已记录: {}", ProcessHandle.current().pid());
        } catch (IOException e) {
            log.error("写入 PID 文件失败: {}", e.getMessage());
        }
    }
}

package com.github.wechat.cloudbot.platform.agent;

import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.platform.config.Strings;
import com.github.wechat.cloudbot.modules.persona.service.PersonaService;
import com.github.wechat.cloudbot.modules.voice.service.ReplyModeService;
import com.github.wechat.cloudbot.modules.voice.service.VoiceCloneService;
import com.github.wechat.ilink.sdk.ILinkClient;
import jakarta.annotation.PreDestroy;
import com.github.wechat.cloudbot.platform.config.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 将同一用户短时间内连发的文字/语音合并成一条再回复，避免「补充句」被拆成两次回答。
 *
 * <p>图片/视频/语音（ASR 后）会先刷出待合并文字，再单独处理；同一用户的回复串行执行。
 */
@Service
public class InboundMessageCoalescer {

    private static final Logger log = LoggerFactory.getLogger(InboundMessageCoalescer.class);

    private final CloudbotProperties properties;
    private final AgentOrchestrator intentRouter;
    private final PersonaService personaService;
    private final ReplyModeService replyModeService;
    private final VoiceCloneService voiceCloneService;

    private final AtomicInteger replyThreadCounter = new AtomicInteger(0);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    r -> {
                        Thread t = new Thread(r, "inbound-msg-coalesce");
                        t.setDaemon(true);
                        return t;
                    });

    /** 实际调用 LLM / 发消息，避免堵死合并定时线程。
     * 使用有界线程池，防止突发流量下无限创建线程。 */
    private final ExecutorService replyExecutor =
            new ThreadPoolExecutor(
                    2,  // corePoolSize：常驻处理线程
                    8,  // maximumPoolSize：突发流量上限
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(200),  // 等待队列上限
                    r -> {
                        Thread t = new Thread(r, "inbound-msg-reply-" + replyThreadCounter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });

    /** 逃生线程：主回复池满时承接降级任务，避免 task.run() 内联阻塞唯一的 debounce scheduler 线程。 */
    private final ExecutorService escapeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "inbound-msg-reply-escape");
        t.setDaemon(true);
        return t;
    });

    private final ConcurrentHashMap<String, PendingBundle> pendingByUser = new ConcurrentHashMap<>();

    /**
     * 每用户回复串行化锁条带。
     * <p>用固定 64 条锁代替「每用户一把锁 + 任务结束即移除」的方案：旧方案在 finally 中
     * {@code remove(userId, lock)} 后，排队中的老任务仍持旧锁等待，而新任务会用
     * {@code computeIfAbsent} 新建一把锁并行执行，破坏同一用户回复串行的保证。
     * 条带锁固定大小、无移除竞态，同一用户恒映射到同一把锁（内存有界）。
     */
    private static final int LOCK_STRIPES = 64;
    private final Object[] userLockStripes = new Object[LOCK_STRIPES];

    public InboundMessageCoalescer(
            CloudbotProperties properties,
            AgentOrchestrator intentRouter,
            PersonaService personaService,
            ReplyModeService replyModeService,
            VoiceCloneService voiceCloneService) {
        this.properties = properties;
        this.intentRouter = intentRouter;
        this.personaService = personaService;
        this.replyModeService = replyModeService;
        this.voiceCloneService = voiceCloneService;
        for (int i = 0; i < userLockStripes.length; i++) {
            userLockStripes[i] = new Object();
        }
    }

    /** 投递文字/语音；短时间连发会合并。 */
    public void offerText(ILinkClient client, String userId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        // 变身 / 自定义音色 / 语音模式口令立即处理，不与普通聊天合并
        if (personaService.looksLikeSwitchCommand(text)
                || voiceCloneService.looksLikeCommand(text)
                || replyModeService.isVoiceCommand(text)) {
            flushTextNow(userId);
            submitReply(userId, () -> intentRouter.reply(client, userId, text, false));
            return;
        }

        long debounceMs = Math.max(0L, properties.getMessageCoalesceMs());
        if (debounceMs == 0L) {
            submitReply(userId, () -> intentRouter.reply(client, userId, text, false));
            return;
        }

        PendingBundle bundle =
                pendingByUser.compute(
                        userId,
                        (id, existing) -> {
                            if (existing == null) {
                                existing = new PendingBundle();
                            }
                            existing.client = client;
                            existing.parts.add(text);
                            existing.preferVoiceReply = false;
                            if (existing.future != null) {
                                existing.future.cancel(false);
                            }
                            existing.future =
                                    scheduler.schedule(
                                            () -> flushTextNow(userId),
                                            debounceMs,
                                            TimeUnit.MILLISECONDS);
                            return existing;
                        });

        log.info(
                "消息已缓冲等待合并 userId={}, parts={}, coalesceMs={}",
                userId,
                bundle.parts.size(),
                debounceMs);
    }

    /** 语音（已转写）：先刷出缓冲文字，再按语音渠道回复（可 TTS）。 */
    public void offerVoice(ILinkClient client, String userId, String transcript) {
        if (transcript == null || transcript.isBlank()) {
            return;
        }
        flushTextNow(userId);
        submitReply(userId, () -> intentRouter.reply(client, userId, transcript, true));
    }

    public void offerVoiceClone(
            ILinkClient client,
            String userId,
            byte[] audioBytes,
            int playtimeHintMs,
            int sampleRateHint) {
        flushTextNow(userId);
        submitReply(
                userId,
                () -> {
                    try {
                        client.sendText(userId, "收到语音样本，正在复刻音色，请稍候…");
                    } catch (Exception e) {
                        log.warn("发送复刻提示失败: {}", e.getMessage());
                    }
                    String msg =
                            voiceCloneService.enrollFromWeChatVoice(
                                    userId, audioBytes, playtimeHintMs, sampleRateHint);
                    try {
                        client.sendText(userId, msg);
                    } catch (Exception e) {
                        log.error("{}发送复刻结果失败: {}", ErrorCode.VOICE_CLONE_ERROR.prefix(), e.getMessage(), e);
                    }
                });
    }

    /** 图片：先刷出缓冲文字，再识图回复。 */
    public void offerImage(ILinkClient client, String userId, byte[] imageBytes) {
        flushTextNow(userId);
        submitReply(
                userId, () -> intentRouter.replyWithImage(client, userId, imageBytes));
    }

    /** 视频：先刷出缓冲文字，再识视频回复。 */
    public void offerVideo(ILinkClient client, String userId, byte[] videoBytes) {
        flushTextNow(userId);
        submitReply(
                userId, () -> intentRouter.replyWithVideo(client, userId, videoBytes));
    }

    /** 文件：先刷出缓冲文字，再解析注入对话。 */
    public void offerFile(ILinkClient client, String userId, String fileName, byte[] fileBytes) {
        flushTextNow(userId);
        submitReply(
                userId, () -> intentRouter.replyWithFile(client, userId, fileName, fileBytes));
    }

    /** 立刻合并并回复当前缓冲（无则忽略）。 */
    public void flushTextNow(String userId) {
        PendingBundle bundle = pendingByUser.remove(userId);
        if (bundle == null) {
            return;
        }
        if (bundle.future != null) {
            bundle.future.cancel(false);
        }
        if (bundle.parts.isEmpty() || bundle.client == null) {
            return;
        }

        String combined;
        if (bundle.parts.size() == 1) {
            combined = bundle.parts.get(0);
        } else {
            combined =
                    "（以下是我连续发来的内容，请合并理解后只回答一次）\n"
                            + String.join("\n", bundle.parts);
        }
        log.info(
                "合并连发消息并回复 userId={}, parts={}, content={}",
                userId,
                bundle.parts.size(),
                Strings.summarizeOneLine(combined));
        ILinkClient client = bundle.client;
        boolean preferVoice = bundle.preferVoiceReply;
        submitReply(userId, () -> intentRouter.reply(client, userId, combined, preferVoice));
    }

    private void submitReply(String userId, Runnable action) {
        Object lock = userLockStripes[lockStripe(userId)];
        Runnable task =
                () -> {
                    synchronized (lock) {
                        try {
                            action.run();
                        } catch (Exception e) {
                            log.error("{}处理用户消息失败 userId={}: {}", ErrorCode.AGENT_LOOP_ERROR.prefix(), userId, e.getMessage(), e);
                        }
                    }
                };
        try {
            replyExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            // 突发流量下主回复池队列满：投递到逃生线程执行，避免 task.run() 内联阻塞
            // 唯一的 debounce scheduler 线程（flushTextNow 在 scheduler 上触发时会被慢 LLM 卡死）。
            log.error("{}回复线程池已满，降级到逃生线程执行 userId={}: {}",
                    ErrorCode.AGENT_LOOP_ERROR.prefix(), userId, e.getMessage());
            try {
                escapeExecutor.execute(task);
            } catch (RejectedExecutionException e2) {
                // 逃生线程也拒绝（几乎不可能：单线程+无界队列），最后兜底为调用线程执行
                task.run();
            }
        }
    }

    private static int lockStripe(String userId) {
        return userId == null ? 0 : Math.floorMod(userId.hashCode(), LOCK_STRIPES);
    }

    @PreDestroy
    public void shutdown() {
        for (String userId : List.copyOf(pendingByUser.keySet())) {
            try {
                flushTextNow(userId);
            } catch (Exception e) {
                log.warn("关闭前刷新缓冲失败 userId={}: {}", userId, e.getMessage());
            }
        }
        scheduler.shutdownNow();
        replyExecutor.shutdown();
        escapeExecutor.shutdown();
        try {
            replyExecutor.awaitTermination(3, TimeUnit.SECONDS);
            escapeExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            replyExecutor.shutdownNow();
            escapeExecutor.shutdownNow();
        }
    }

    private static final class PendingBundle {
        private final List<String> parts = new ArrayList<>();
        private ILinkClient client;
        private ScheduledFuture<?> future;
        private boolean preferVoiceReply;
    }
}

package com.github.wechat.cloudbot.platform.agent;

import com.github.wechat.cloudbot.platform.config.CloudbotProperties;
import com.github.wechat.cloudbot.modules.persona.service.PersonaService;
import com.github.wechat.cloudbot.modules.voice.service.ReplyModeService;
import com.github.wechat.cloudbot.modules.voice.service.VoiceCloneService;
import com.github.wechat.ilink.sdk.ILinkClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * InboundMessageCoalescer 单元测试：聚焦消息合并与「同一用户回复串行化」两个关键点。
 */
class InboundMessageCoalescerTest {

    private InboundMessageCoalescer coalescer;
    private AgentOrchestrator orchestrator;
    private ILinkClient client;

    private void build(long coalesceMs) {
        CloudbotProperties props = new CloudbotProperties();
        props.setMessageCoalesceMs(coalesceMs);
        orchestrator = mock(AgentOrchestrator.class);
        PersonaService personaService = mock(PersonaService.class);
        when(personaService.looksLikeSwitchCommand(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        VoiceCloneService voiceCloneService = mock(VoiceCloneService.class);
        when(voiceCloneService.looksLikeCommand(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        ReplyModeService replyModeService = mock(ReplyModeService.class);
        when(replyModeService.isVoiceCommand(org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
        client = mock(ILinkClient.class);
        coalescer = new InboundMessageCoalescer(props, orchestrator, personaService, replyModeService, voiceCloneService);
    }

    @AfterEach
    void tearDown() {
        if (coalescer != null) {
            coalescer.shutdown();
        }
    }

    @Test
    @DisplayName("窗口内连发两条文本应合并成一次回复")
    void offerTextCoalescesWithinWindow() throws Exception {
        build(150L);
        ConcurrentLinkedQueue<String> received = new ConcurrentLinkedQueue<>();
        CountDownLatch replied = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            received.add(inv.getArgument(2, String.class));
            replied.countDown();
            return null;
        }).when(orchestrator).reply(org.mockito.ArgumentMatchers.eq(client),
                org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(false));

        coalescer.offerText(client, "u1", "你好");
        Thread.sleep(50);
        coalescer.offerText(client, "u1", "在吗");

        assertTrue(replied.await(5, TimeUnit.SECONDS), "合并回复应在超时内到达");
        assertEquals(1, received.size(), "窗口内连发应合并为一次回复");
        assertTrue(received.peek().contains("你好"), "合并内容应包含第一条");
        assertTrue(received.peek().contains("在吗"), "合并内容应包含第二条");
    }

    @Test
    @DisplayName("超过合并窗口的第二次文本应触发第二次回复")
    void offerTextBeyondWindowRepliesSeparately() throws Exception {
        build(100L);
        CountDownLatch replied = new CountDownLatch(2);
        org.mockito.Mockito.doAnswer(inv -> {
            replied.countDown();
            return null;
        }).when(orchestrator).reply(org.mockito.ArgumentMatchers.eq(client),
                org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(false));

        coalescer.offerText(client, "u1", "第一条");
        Thread.sleep(300); // 超过 100ms 窗口，前一条已触发
        coalescer.offerText(client, "u1", "第二条");

        assertTrue(replied.await(5, TimeUnit.SECONDS), "两次回复应在超时内到达");
        assertEquals(0, replied.getCount(), "两条文本应分别触发回复");
    }

    @Test
    @DisplayName("同一用户回复严格串行（条带锁），并发下最大并发数=1")
    void sameUserRepliesAreSerialized() throws Exception {
        build(0L); // 关闭合并，直接投递回复任务
        int messages = 10;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch replied = new CountDownLatch(messages);
        org.mockito.Mockito.doAnswer(inv -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            Thread.sleep(30); // 模拟慢 LLM，拉长持锁时间以暴露串行问题
            active.decrementAndGet();
            replied.countDown();
            return null;
        }).when(orchestrator).reply(org.mockito.ArgumentMatchers.eq(client),
                org.mockito.ArgumentMatchers.eq("u1"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(false));

        ExecutorService feeder = Executors.newFixedThreadPool(messages);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < messages; i++) {
            final String text = "msg" + i;
            feeder.submit(() -> {
                try {
                    start.await();
                    coalescer.offerText(client, "u1", text);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        feeder.shutdown();

        assertTrue(replied.await(15, TimeUnit.SECONDS), "全部回复应在超时内完成");
        assertTrue(feeder.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(1, maxActive.get(), "同一用户回复必须串行执行，实际最大并发=" + maxActive.get());
        assertEquals(0, replied.getCount(), "全部消息都应得到回复");
    }

    @Test
    @DisplayName("不同用户可并行回复（互不阻塞）")
    void differentUsersRunInParallel() throws Exception {
        build(0L);
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger entered = new AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            entered.incrementAndGet();
            bothEntered.countDown();
            release.await();
            return null;
        }).when(orchestrator).reply(org.mockito.ArgumentMatchers.eq(client),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(false));

        coalescer.offerText(client, "uA", "a");
        coalescer.offerText(client, "uB", "b");

        assertTrue(bothEntered.await(5, TimeUnit.SECONDS), "两个不同用户应能同时进入回复逻辑");
        assertEquals(2, entered.get());
        release.countDown();
    }
}

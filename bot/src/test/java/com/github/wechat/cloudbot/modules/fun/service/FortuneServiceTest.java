package com.github.wechat.cloudbot.modules.fun.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import static org.junit.jupiter.api.Assertions.*;

class FortuneServiceTest {

    private final FortuneService fortuneService = new FortuneService();

    @RepeatedTest(10)
    void testRollDice() {
        int result = fortuneService.rollDice();
        assertTrue(result >= 1 && result <= 6,
                "Dice roll should be between 1 and 6, got: " + result);
    }

    @RepeatedTest(10)
    void testDrawLottery() {
        String result = fortuneService.drawLottery();
        assertTrue(result.contains("抽到了"));
        assertTrue(result.contains("大吉") || result.contains("中吉") || result.contains("小吉")
                || result.contains("吉") || result.contains("末吉") || result.contains("凶")
                || result.contains("大凶"));
    }

    @RepeatedTest(10)
    void testTodayFortune() {
        String result = fortuneService.todayFortune();
        assertTrue(result.contains("今日运势"));
        assertTrue(result.contains("⭐") || result.contains("("));
        // Should have star rating and a fortune message
        assertTrue(result.contains("/5"));

        // Check the fortune part
        assertTrue(result.contains("好消息") || result.contains("出门走走") || result.contains("coding")
                || result.contains("加班") || result.contains("回家"));
    }

    @RepeatedTest(100)
    void testDiceDistribution() {
        // Verify dice stays within range over many rolls
        for (int i = 0; i < 100; i++) {
            int r = fortuneService.rollDice();
            assertTrue(r >= 1 && r <= 6);
        }
    }

    @Test
    void testAllLotteryResults() {
        // Run lots of draws to make sure no exceptions and all results are valid
        for (int i = 0; i < 100; i++) {
            String result = fortuneService.drawLottery();
            assertNotNull(result);
            assertFalse(result.isBlank());
        }
    }
}

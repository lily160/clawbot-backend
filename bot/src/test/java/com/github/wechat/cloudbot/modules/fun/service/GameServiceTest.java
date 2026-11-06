package com.github.wechat.cloudbot.modules.fun.service;

import com.github.wechat.cloudbot.modules.fun.infra.FunProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock
    private FunProperties funProperties;

    private GameService createGameService(int timeoutMinutes) {
        lenient().when(funProperties.getGameTimeoutMinutes()).thenReturn(timeoutMinutes);
        return new GameService(funProperties);
    }

    @Test
    void testStartGame() {
        GameService gameService = createGameService(30);
        String msg = gameService.startGuessNumber("user1");
        assertTrue(msg.contains("猜数字"));
        assertTrue(msg.contains("1-100"));
    }

    @Test
    void testGuessWithoutGame() {
        GameService gameService = new GameService(funProperties);
        String msg = gameService.guess("user1", 50);
        assertTrue(msg.contains("没有进行中的游戏"));
    }

    @Test
    void testGuessTooLow() {
        GameService gameService = createGameService(30);
        gameService.startGuessNumber("user1");
        String msg = gameService.guess("user1", 1);
        assertTrue(msg.contains("小了") || msg.contains("猜对了"));
    }

    @Test
    void testGuessTooHigh() {
        GameService gameService = createGameService(30);
        gameService.startGuessNumber("user1");
        String msg = gameService.guess("user1", 100);
        assertTrue(msg.contains("大了") || msg.contains("猜对了"));
    }

    @Test
    void testExhaustAttempts() {
        GameService gameService = createGameService(30);
        gameService.startGuessNumber("user1");
        String lastMsg = "";
        for (int i = 0; i < 10; i++) {
            lastMsg = gameService.guess("user1", -1);
        }
        assertTrue(lastMsg.contains("机会用完了") || lastMsg.contains("猜对了"));
    }

    @Test
    void testMultipleUsers() {
        GameService gameService = createGameService(30);
        gameService.startGuessNumber("user1");
        gameService.startGuessNumber("user2");
        assertNotNull(gameService.guess("user1", 50));
        assertNotNull(gameService.guess("user2", 30));
    }

    @Test
    void testGameTimeout() {
        GameService gameService = createGameService(0);
        gameService.startGuessNumber("user1");
        String msg = gameService.guess("user1", 50);
        assertTrue(msg.contains("超时") || msg.contains("没有进行中的游戏"));
    }
}

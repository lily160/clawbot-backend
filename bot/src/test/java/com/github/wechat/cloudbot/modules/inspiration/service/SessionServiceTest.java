package com.github.wechat.cloudbot.modules.inspiration.service;

import com.github.wechat.cloudbot.modules.inspiration.model.BotSession;
import com.github.wechat.cloudbot.modules.inspiration.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock
    private SessionRepository repository;

    @InjectMocks
    private SessionService sessionService;

    @Test
    void testSaveSession() {
        when(repository.save(any(BotSession.class))).thenReturn(1);

        boolean result = sessionService.saveSession("sid-1", "user1", "测试", "{}");
        assertTrue(result);
        verify(repository).save(any(BotSession.class));
    }

    @Test
    void testSaveSessionFailure() {
        when(repository.save(any(BotSession.class))).thenReturn(0);

        boolean result = sessionService.saveSession("sid-2", "user2", null, null);
        assertFalse(result);
    }

    @Test
    void testLoadSession() {
        BotSession session = new BotSession();
        session.setSessionId("sid-1");
        session.setUserId("user1");
        when(repository.findBySessionId("sid-1")).thenReturn(Optional.of(session));

        var result = sessionService.loadSession("sid-1");
        assertTrue(result.isPresent());
        assertEquals("sid-1", result.get().getSessionId());
    }

    @Test
    void testLoadSessionNotFound() {
        when(repository.findBySessionId("missing")).thenReturn(Optional.empty());

        var result = sessionService.loadSession("missing");
        assertFalse(result.isPresent());
    }

    @Test
    void testListSessions() {
        when(repository.findAll()).thenReturn(List.of(new BotSession(), new BotSession()));

        var result = sessionService.listSessions();
        assertEquals(2, result.size());
    }

    @Test
    void testUpdateSession() {
        when(repository.update("sid-1", "新标题", "新数据")).thenReturn(1);

        boolean result = sessionService.updateSession("sid-1", "新标题", "新数据");
        assertTrue(result);
    }

    @Test
    void testUpdateSessionNotFound() {
        when(repository.update("missing", "标题", "数据")).thenReturn(0);

        boolean result = sessionService.updateSession("missing", "标题", "数据");
        assertFalse(result);
    }

    @Test
    void testDeleteSession() {
        when(repository.deleteBySessionId("sid-1")).thenReturn(1);

        boolean result = sessionService.deleteSession("sid-1");
        assertTrue(result);
    }

    @Test
    void testDeleteSessionNotFound() {
        when(repository.deleteBySessionId("missing")).thenReturn(0);

        boolean result = sessionService.deleteSession("missing");
        assertFalse(result);
    }

    @Test
    void testCleanupOldSessions() {
        when(repository.deleteOlderThanDays(30)).thenReturn(5);

        int deleted = sessionService.cleanupOldSessions(30);
        assertEquals(5, deleted);
    }
}

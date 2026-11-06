package com.github.wechat.cloudbot.modules.inspiration.service;

import com.github.wechat.cloudbot.modules.inspiration.model.BotSession;
import com.github.wechat.cloudbot.modules.inspiration.repository.SessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Bot 会话管理服务（数据库持久化）。
 *
 * <p>提供会话的保存、加载、更新、删除及过期清理功能。</p>
 */
@Service
public class SessionService {

    private final SessionRepository repository;

    public SessionService(SessionRepository repository) {
        this.repository = repository;
    }

    /**
     * 保存新会话。
     */
    public boolean saveSession(String sessionId, String userId, String title, String stateData) {
        BotSession session = new BotSession();
        session.setSessionId(sessionId);
        session.setUserId(userId != null ? userId : "");
        session.setTitle(title);
        session.setStateData(stateData);
        return repository.save(session) > 0;
    }

    /**
     * 按 sessionId 加载会话。
     */
    public Optional<BotSession> loadSession(String sessionId) {
        return repository.findBySessionId(sessionId);
    }

    /**
     * 查询所有会话。
     */
    public List<BotSession> listSessions() {
        return repository.findAll();
    }

    /**
     * 更新会话标题和状态数据。
     *
     * @return true 如果更新成功
     */
    public boolean updateSession(String sessionId, String title, String stateData) {
        return repository.update(sessionId, title, stateData) > 0;
    }

    /**
     * 按 sessionId 删除会话。
     *
     * @return true 如果删除成功
     */
    public boolean deleteSession(String sessionId) {
        return repository.deleteBySessionId(sessionId) > 0;
    }

    /**
     * 清理 N 天前的旧会话。
     */
    public int cleanupOldSessions(int daysOld) {
        return repository.deleteOlderThanDays(daysOld);
    }
}

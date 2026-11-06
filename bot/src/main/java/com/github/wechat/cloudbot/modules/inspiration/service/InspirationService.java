package com.github.wechat.cloudbot.modules.inspiration.service;

import com.github.wechat.cloudbot.modules.inspiration.infra.GistClient;
import com.github.wechat.cloudbot.modules.inspiration.model.Inspiration;
import com.github.wechat.cloudbot.modules.inspiration.repository.InspirationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class InspirationService {

    private static final Logger log = LoggerFactory.getLogger(InspirationService.class);

    private final GistClient gistClient;
    private final InspirationRepository repository;

    public InspirationService(GistClient gistClient, InspirationRepository repository) {
        this.gistClient = gistClient;
        this.repository = repository;
    }

    /**
     * 保存灵感笔记 — 双写策略：同时写入 GitHub Gist + 数据库。
     * <p>Gist 失败不影响本地保存，反之亦然。</p>
     *
     * @param topic   大模型总结的核心主题
     * @param content 具体内容
     */
    public String saveNote(String topic, String content) {
        // 生成规范且带时间戳的文件名 (确保在 GitHub 上展示美观)
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        String safeTopic = topic.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_");
        String filename = "Idea_" + safeTopic + "_" + timestamp + ".md";

        // 组装文件开头的元信息
        String formattedContent = String.format(
                "# 💡 %s\n> 记录时间: %s\n\n---\n\n%s",
                topic,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                content
        );

        // 1️⃣ 写 Gist（云端）
        String gistUrl = null;
        try {
            GistClient.GistResponse response = gistClient.createGist(filename, formattedContent,
                    "来自微信机器人的灵感记录: " + topic);
            if (response != null) {
                gistUrl = response.getHtmlUrl();
            }
        } catch (Exception e) {
            log.warn("Gist 保存失败（不影响本地存储）: {}", e.getMessage());
        }

        // 2️⃣ 写数据库（本地）
        Inspiration local = new Inspiration();
        local.setTopic(topic);
        local.setContent(formattedContent);
        local.setFilename(filename);
        local.setGistUrl(gistUrl);
        long localId = repository.save(local);

        // 组装结果消息
        StringBuilder msg = new StringBuilder();
        if (localId > 0) {
            msg.append("✅ 灵感已保存到本地 (ID: ").append(localId).append(")");
        } else {
            msg.append("⚠️ 本地保存失败");
        }
        if (gistUrl != null) {
            msg.append("\n☁️ Gist 链接：").append(gistUrl);
        } else {
            msg.append("\n☁️ Gist 同步不可用（网络问题或未配置 Token）");
        }
        return msg.toString();
    }

    /**
     * 查询最近 N 条灵感笔记（来自数据库）。
     */
    public List<Inspiration> listRecent(int limit) {
        return repository.findRecent(limit);
    }

    /**
     * 按 ID 删除本地灵感笔记。
     *
     * @return true 如果删除成功
     */
    public boolean deleteById(long id) {
        return repository.deleteById(id) > 0;
    }
}
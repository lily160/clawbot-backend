package com.github.wechat.cloudbot.modules.inspiration.service;

import com.github.wechat.cloudbot.modules.inspiration.infra.GistClient;
import com.github.wechat.cloudbot.modules.inspiration.model.Inspiration;
import com.github.wechat.cloudbot.modules.inspiration.repository.InspirationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InspirationServiceTest {

    @Mock
    private GistClient gistClient;

    @Mock
    private InspirationRepository repository;

    @InjectMocks
    private InspirationService inspirationService;

    @Captor
    private ArgumentCaptor<Inspiration> inspirationCaptor;

    @Test
    void testSaveNote_GistSuccess() {
        // Gist 返回成功
        GistClient.GistResponse response = new GistClient.GistResponse();
        response.setHtmlUrl("https://gist.github.com/test/abc123");
        when(gistClient.createGist(anyString(), anyString(), anyString())).thenReturn(response);

        // 数据库保存成功
        when(repository.save(any(Inspiration.class))).thenReturn(42L);

        String result = inspirationService.saveNote("测试主题", "测试内容");

        assertTrue(result.contains("✅"));
        assertTrue(result.contains("ID: 42"));
        assertTrue(result.contains("Gist"));
        assertTrue(result.contains("https://gist.github.com/test/abc123"));

        // 验证 gistUrl 被传到 repository
        verify(repository).save(inspirationCaptor.capture());
        assertEquals("https://gist.github.com/test/abc123", inspirationCaptor.getValue().getGistUrl());
    }

    @Test
    void testSaveNote_GistFails() {
        // Gist 抛异常
        when(gistClient.createGist(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("网络超时"));

        // SQLite 保存成功
        when(repository.save(any(Inspiration.class))).thenReturn(7L);

        String result = inspirationService.saveNote("测试主题", "失败测试");

        assertTrue(result.contains("ID: 7"));
        assertTrue(result.contains("Gist 同步不可用"));
        assertFalse(result.contains("❌")); // 整体不应失败

        // Gist 失败时 gistUrl 应为 null
        verify(repository).save(inspirationCaptor.capture());
        assertNull(inspirationCaptor.getValue().getGistUrl());
    }

    @Test
    void testSaveNote_GistReturnsNull() {
        GistClient.GistResponse response = new GistClient.GistResponse();
        response.setHtmlUrl(null);
        when(gistClient.createGist(anyString(), anyString(), anyString())).thenReturn(response);
        when(repository.save(any(Inspiration.class))).thenReturn(1L);

        String result = inspirationService.saveNote("主题", "内容");

        assertTrue(result.contains("Gist 同步不可用"));
        verify(repository).save(inspirationCaptor.capture());
        assertNull(inspirationCaptor.getValue().getGistUrl());
    }

    @Test
    void testSaveNote_LocalFails() {
        GistClient.GistResponse response = new GistClient.GistResponse();
        response.setHtmlUrl("https://gist.github.com/test/def456");
        when(gistClient.createGist(anyString(), anyString(), anyString())).thenReturn(response);

        // 数据库保存失败（返回 -1）
        when(repository.save(any(Inspiration.class))).thenReturn(-1L);

        String result = inspirationService.saveNote("主题", "内容");

        assertTrue(result.contains("本地保存失败"));
        assertTrue(result.contains("Gist 链接"));
    }

    @Test
    void testSaveNote_TopicWithSpecialChars() {
        when(gistClient.createGist(anyString(), anyString(), anyString())).thenReturn(null);
        when(repository.save(any(Inspiration.class))).thenReturn(1L);

        // 含特殊字符的主题，文件名应该安全
        String result = inspirationService.saveNote("A/B\\C:D*?", "内容");

        assertNotNull(result);
        // 验证传给 repository 的 filename 不含特殊字符
        verify(repository).save(inspirationCaptor.capture());
        String filename = inspirationCaptor.getValue().getFilename();
        assertTrue(filename.startsWith("Idea_A_B_C_D_"));
        assertTrue(filename.endsWith(".md"));
    }

    @Test
    void testListRecent() {
        Inspiration insp1 = new Inspiration();
        insp1.setTopic("主题1");
        Inspiration insp2 = new Inspiration();
        insp2.setTopic("主题2");
        when(repository.findRecent(10)).thenReturn(List.of(insp1, insp2));

        var result = inspirationService.listRecent(10);
        assertEquals(2, result.size());
        assertEquals("主题1", result.get(0).getTopic());
    }

    @Test
    void testDeleteById() {
        when(repository.deleteById(5)).thenReturn(1);
        assertTrue(inspirationService.deleteById(5));

        when(repository.deleteById(999)).thenReturn(0);
        assertFalse(inspirationService.deleteById(999));
    }
}

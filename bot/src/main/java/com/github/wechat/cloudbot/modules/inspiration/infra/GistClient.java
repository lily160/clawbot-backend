package com.github.wechat.cloudbot.modules.inspiration.infra;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class GistClient {

    private final GistProperties properties;
    private final RestClient restClient;
    private static final String GITHUB_API_URL = "https://api.github.com/gists";

    @SuppressWarnings("null")
    public GistClient(GistProperties properties) {
        this.properties = properties;
        var http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 将内容推送到 GitHub Gist
     */
    public GistResponse createGist(String filename, String content, String description) {
        // 未配置 token 时直接失败，避免发送 "Bearer null" 做一次必失败的网络往返
        String token = properties.getToken();
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("未配置 GITHUB_GIST_TOKEN，无法同步到 GitHub");
        }

        // 构建 GitHub 要求的 JSON 结构
        GistRequest request = new GistRequest();
        request.setDescription(description);
        request.setPublicFlag(false); // 强制设为私密，保护个人隐私

        Map<String, GistRequest.FileContent> files = new HashMap<>();
        files.put(filename, new GistRequest.FileContent(content));
        request.setFiles(files);

        try {
            GistResponse response = restClient
                    .post()
                    .uri(GITHUB_API_URL)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .body(request)
                    .retrieve()
                    .body(GistResponse.class);
            return response;
        } catch (Exception e) {
            throw new RuntimeException("向 GitHub 推送 Gist 失败: " + e.getMessage(), e);
        }
    }

    // --- DTO: 请求体 ---
    public static class GistRequest {
        private String description;
        private boolean isPublic; // 注意：JSON 中字段名为 "public"
        private Map<String, FileContent> files;

        @JsonProperty("public")
        public boolean isPublicFlag() { return isPublic; }
        public void setPublicFlag(boolean isPublic) { this.isPublic = isPublic; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Map<String, FileContent> getFiles() { return files; }
        public void setFiles(Map<String, FileContent> files) { this.files = files; }

        public static class FileContent {
            private String content;
            public FileContent(String content) { this.content = content; }
            public String getContent() { return content; }
            public void setContent(String content) { this.content = content; }
        }
    }

    // --- DTO: 响应体 ---
    public static class GistResponse {
        private String id;
        private String htmlUrl; // Gist 的网页访问地址

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        @JsonProperty("html_url")
        public String getHtmlUrl() { return htmlUrl; }
        public void setHtmlUrl(String htmlUrl) { this.htmlUrl = htmlUrl; }
    }
}

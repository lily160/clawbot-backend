package com.github.wechat.cloudbot.platform.config;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 统一装配 JDK {@link RestClient}，消除各模块 Configuration 样板。
 */
public final class RestClients {

    private RestClients() {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseUrl;
        private String defaultBaseUrl;
        private long connectTimeoutMs = 10_000L;
        private long readTimeoutMs = 30_000L;
        private boolean followRedirects;
        private String bearerToken;
        private String userAgent;
        private HttpClient httpClient;

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public Builder baseUrl(String baseUrl, String defaultIfBlank) {
            this.baseUrl = baseUrl;
            this.defaultBaseUrl = defaultIfBlank;
            return this;
        }

        public Builder connectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
            return this;
        }

        public Builder readTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
            return this;
        }

        public Builder followRedirects(boolean followRedirects) {
            this.followRedirects = followRedirects;
            return this;
        }

        public Builder bearerToken(String bearerToken) {
            this.bearerToken = bearerToken;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        /**
         * 构建 JDK {@link JdkClientHttpRequestFactory}，供 RestTemplate 等使用。
         * <p>注意：返回的 factory 不含 baseUrl / bearerToken / userAgent（这些是 RestClient 层概念）。
         */
        @SuppressWarnings("null")
        public JdkClientHttpRequestFactory buildRequestFactory() {
            HttpClient client = httpClient;
            if (client == null) {
                HttpClient.Builder httpBuilder =
                        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectTimeoutMs));
                if (followRedirects) {
                    httpBuilder.followRedirects(HttpClient.Redirect.NORMAL);
                }
                client = httpBuilder.build();
            }
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
            factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
            return factory;
        }

        /**
         * 构建完整的 {@link RestClient}（含 baseUrl / bearerToken / userAgent）。
         */
        public RestClient build() {
            JdkClientHttpRequestFactory requestFactory = buildRequestFactory();
            RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
            if (defaultBaseUrl != null || (baseUrl != null && !baseUrl.isBlank())) {
                builder.baseUrl(Strings.trimTrailingSlash(baseUrl, defaultBaseUrl));
            }
            if (bearerToken != null && !bearerToken.isBlank()) {
                builder.defaultHeader("Authorization", "Bearer " + bearerToken);
            }
            if (userAgent != null && !userAgent.isBlank()) {
                builder.defaultHeader("User-Agent", userAgent);
            }
            return builder.build();
        }
    }
}

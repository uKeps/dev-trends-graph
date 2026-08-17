package com.dev.trends.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Centralised {@link HttpClient} bean. The same instance is reused across the
 * ingestion pipeline (HN, Dev.to, Lobsters, Stack Exchange) and the live-source
 * lookup, so a single connection pool backs the whole fan-out.
 *
 * <p>Timeouts are conservative: 15s connect, 8s per request. The per-source
 * call sites may override the request timeout for endpoints known to be slow
 * (e.g. the HN top-stories list) without rebuilding the client.
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }
}

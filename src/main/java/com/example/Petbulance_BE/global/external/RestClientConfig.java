package com.example.Petbulance_BE.global.external;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 연동별 RestClient. 호출 성격(로그인 / 조회 / AI)마다 타임아웃과 풀을 따로 둔다.
 *
 * <p>HttpClient5 를 코드로 직접 구성하는 이유:
 * <ul>
 *   <li>풀 크기: 기본값(경로당 5, 전체 25)이면 같은 API 로 동시 호출이 5건에서 막힌다</li>
 *   <li>자동 재시도 끄기: HttpClient5 는 기본 재시도 전략이 켜져 있어 Resilience4j 재시도와 겹치면 시도 횟수가 곱해진다.
 *       재시도 계층은 Resilience4j 한 곳으로 통일한다</li>
 *   <li>유휴 연결 정리/재검증: 상대나 LB 가 먼저 끊은 연결을 재사용해 나는 Connection reset 방지</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(ExternalClientProperties.class)
public class RestClientConfig {

    public static final String OAUTH = "oauth";
    public static final String VWORLD = "vworld";
    public static final String CLAUDE = "claude";

    // ---- OAuth (로그인 경로, 사용자 대기) ----
    @Bean(destroyMethod = "close")
    public CloseableHttpClient oauthHttpClient(ExternalClientProperties props) {
        return httpClient(props.get(OAUTH));
    }

    @Bean
    public RestClient oauthRestClient(@Qualifier("oauthHttpClient") CloseableHttpClient http,
                                      ExternalClientProperties props, RestClient.Builder builder) {
        return restClient(OAUTH, http, props.get(OAUTH), builder);
    }

    // ---- VWorld (조회형) ----
    @Bean(destroyMethod = "close")
    public CloseableHttpClient vworldHttpClient(ExternalClientProperties props) {
        return httpClient(props.get(VWORLD));
    }

    @Bean
    public RestClient vworldRestClient(@Qualifier("vworldHttpClient") CloseableHttpClient http,
                                       ExternalClientProperties props, RestClient.Builder builder) {
        return restClient(VWORLD, http, props.get(VWORLD), builder);
    }

    // ---- Claude (AI 진단 스트리밍, 영수증 추출) ----
    // 스트리밍은 이 HttpClient 를 직접 쓰고(즉시 취소), 일반 호출은 RestClient 를 쓴다. 연결 풀은 하나를 공유한다
    @Bean(destroyMethod = "close")
    public CloseableHttpClient claudeHttpClient(ExternalClientProperties props) {
        return httpClient(props.get(CLAUDE));
    }

    @Bean
    public RestClient claudeRestClient(@Qualifier("claudeHttpClient") CloseableHttpClient http,
                                       ExternalClientProperties props, RestClient.Builder builder) {
        return restClient(CLAUDE, http, props.get(CLAUDE), builder);
    }

    /** 테스트에서도 같은 구성을 쓰도록 static 으로 공개한다 */
    public static CloseableHttpClient httpClient(ExternalClientProperties.Client c) {
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(c.connectTimeout()))
                .setSocketTimeout(Timeout.of(c.responseTimeout()))
                .setValidateAfterInactivity(TimeValue.ofSeconds(2))
                .setTimeToLive(TimeValue.ofMinutes(5))
                .build();

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnPerRoute(c.maxPerRoute())
                .setMaxConnTotal(c.maxTotal())
                .setDefaultConnectionConfig(connectionConfig)
                .build();

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.of(c.poolWaitTimeout()))
                .setResponseTimeout(Timeout.of(c.responseTimeout()))
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries()
                .evictExpiredConnections()
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .build();
    }

    public static RestClient restClient(String name, CloseableHttpClient http,
                                        ExternalClientProperties.Client c, RestClient.Builder builder) {
        RestClient.Builder b = builder
                .requestFactory(new HttpComponentsClientHttpRequestFactory(http))
                .requestInterceptor(new ClientLoggingInterceptor(name));
        if (c.baseUrl() != null && !c.baseUrl().isBlank()) {
            b.baseUrl(c.baseUrl());
        }
        return b.build();
    }
}

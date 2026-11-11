package com.example.Petbulance_BE.global.external;

import com.example.Petbulance_BE.global.external.exception.UpstreamBadResponseException;
import com.example.Petbulance_BE.global.external.exception.UpstreamRateLimitedException;
import com.example.Petbulance_BE.global.external.exception.UpstreamRejectedException;
import com.example.Petbulance_BE.global.external.exception.UpstreamTimeoutException;
import com.example.Petbulance_BE.global.external.exception.UpstreamUnavailableException;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.net.ServerSocket;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HTTP 계층(RestClient + HttpClient5) 단독 검증.
 * 타임아웃이 실제로 동작하는지, 상태/예외가 올바른 타입으로 분류되는지,
 * HttpClient5 자동 재시도가 꺼져 있는지(Resilience4j 재시도와 곱해지지 않는지)를 확인한다.
 */
class ExternalHttpBehaviorTest {

    private static final String SECRET = "TOP-SECRET-KEY";

    private static WireMockServer wireMock;
    private static CloseableHttpClient http;
    private static RestClient restClient;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();

        ExternalClientProperties.Client config = client(wireMock.baseUrl());
        http = RestClientConfig.httpClient(config);
        restClient = RestClientConfig.restClient("test", http, config, RestClient.builder());
    }

    @AfterAll
    static void stop() throws Exception {
        http.close();
        wireMock.stop();
    }

    @BeforeEach
    void reset() {
        wireMock.resetAll();
    }

    private static ExternalClientProperties.Client client(String baseUrl) {
        return new ExternalClientProperties.Client(baseUrl, null, null, null,
                Duration.ofMillis(500), Duration.ofMillis(400), Duration.ofMillis(100), 5, 10);
    }

    private String call(RestClient client, String path) {
        return ExternalCalls.execute("test", () ->
                ExternalCalls.mapErrors("test", client.get().uri(path + "?key={key}", SECRET).retrieve())
                        .body(String.class));
    }

    @Test
    @DisplayName("응답이 느리면 응답 타임아웃으로 끊고 UpstreamTimeoutException 으로 분류한다")
    void readTimeout() {
        wireMock.stubFor(get(urlPathEqualTo("/slow")).willReturn(aResponse().withStatus(200).withFixedDelay(2000)));

        long start = System.nanoTime();
        assertThatThrownBy(() -> call(restClient, "/slow")).isInstanceOf(UpstreamTimeoutException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("타임아웃(400ms) 근처에서 끊겨야 한다").isLessThan(1500);
        // WireMock 이 요청을 기록하는 시점이 응답 지연과 겹칠 수 있어 기록될 때까지 잠깐 기다린 뒤 횟수를 본다
        awaitRequests("/slow");
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/slow")));
    }

    @Test
    @DisplayName("연결 자체가 안 되면 UpstreamUnavailableException 으로 분류한다")
    void connectFailure() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ExternalClientProperties.Client config = client("http://localhost:" + closedPort);
        try (CloseableHttpClient closedHttp = RestClientConfig.httpClient(config)) {
            RestClient closedClient = RestClientConfig.restClient("test", closedHttp, config, RestClient.builder());
            assertThatThrownBy(() -> call(closedClient, "/any")).isInstanceOf(UpstreamUnavailableException.class);
        }
    }

    @Test
    @DisplayName("503 은 Unavailable 로 분류하고, HttpClient5 가 몰래 재시도하지 않는다 (Retry-After 가 있어도)")
    void serverErrorIsNotRetriedByHttpClient() {
        wireMock.stubFor(get(urlPathEqualTo("/down"))
                .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "0")));

        assertThatThrownBy(() -> call(restClient, "/down")).isInstanceOf(UpstreamUnavailableException.class);

        // 재시도 계층은 Resilience4j 한 곳뿐이어야 한다. 자동 재시도가 켜져 있으면 2번 이상 호출된다.
        wireMock.verify(1, getRequestedFor(urlPathEqualTo("/down")));
    }

    @Test
    @DisplayName("429 는 RateLimited, 401/403 은 인증 오류로 표시된 Rejected, 400 은 Rejected")
    void statusClassification() {
        wireMock.stubFor(get(urlPathEqualTo("/limited")).willReturn(aResponse().withStatus(429)));
        wireMock.stubFor(get(urlPathEqualTo("/auth")).willReturn(aResponse().withStatus(403)));
        wireMock.stubFor(get(urlPathEqualTo("/bad")).willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> call(restClient, "/limited")).isInstanceOf(UpstreamRateLimitedException.class);
        assertThatThrownBy(() -> call(restClient, "/auth"))
                .isInstanceOfSatisfying(UpstreamRejectedException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(403);
                    assertThat(e.isAuthError()).isTrue();
                });
        assertThatThrownBy(() -> call(restClient, "/bad"))
                .isInstanceOfSatisfying(UpstreamRejectedException.class, e -> assertThat(e.isAuthError()).isFalse());
    }

    @Test
    @DisplayName("해석할 수 없는 본문은 BadResponse 로 분류한다")
    void undecodableBody() {
        wireMock.stubFor(get(urlPathEqualTo("/text")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/plain").withBody("not json")));

        assertThatThrownBy(() -> ExternalCalls.execute("test", () ->
                ExternalCalls.mapErrors("test", restClient.get().uri("/text").retrieve())
                        .body(java.util.Map.class)))
                .isInstanceOf(UpstreamBadResponseException.class);
    }

    @Test
    @DisplayName("예외 메시지에 요청 URL(쿼리스트링의 API 키)이 새어 나가지 않는다")
    void exceptionDoesNotLeakApiKey() {
        wireMock.stubFor(get(urlPathEqualTo("/slow2")).willReturn(aResponse().withFixedDelay(2000)));
        wireMock.stubFor(get(urlPathEqualTo("/down2")).willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> call(restClient, "/slow2"))
                .satisfies(e -> assertThat(fullMessage(e)).doesNotContain(SECRET));
        assertThatThrownBy(() -> call(restClient, "/down2"))
                .satisfies(e -> assertThat(fullMessage(e)).doesNotContain(SECRET));
    }

    private static void awaitRequests(String path) {
        long until = System.currentTimeMillis() + 3000;
        while (wireMock.findAll(getRequestedFor(urlPathEqualTo(path))).isEmpty()
                && System.currentTimeMillis() < until) {
            Thread.onSpinWait();
        }
    }

    /** 예외 자신의 메시지만 본다 (원인 예외 체인은 서비스 로그에 직접 남기지 않는 정책) */
    private static String fullMessage(Throwable e) {
        return String.valueOf(e.getMessage());
    }
}

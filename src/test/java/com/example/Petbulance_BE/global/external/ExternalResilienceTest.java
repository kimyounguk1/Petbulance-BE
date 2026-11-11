package com.example.Petbulance_BE.global.external;

import com.example.Petbulance_BE.domain.review.dto.GeoDto;
import com.example.Petbulance_BE.global.common.error.exception.CustomException;
import com.example.Petbulance_BE.global.common.error.exception.ErrorCode;
import com.example.Petbulance_BE.global.external.exception.UpstreamBadResponseException;
import com.example.Petbulance_BE.global.external.exception.UpstreamRejectedException;
import com.example.Petbulance_BE.global.external.exception.UpstreamTimeoutException;
import com.example.Petbulance_BE.global.external.claude.AiOutputException;
import com.example.Petbulance_BE.global.external.claude.ClaudeClient;
import com.example.Petbulance_BE.global.external.claude.ClaudeResult;
import com.example.Petbulance_BE.global.external.claude.ClaudeStream;
import com.example.Petbulance_BE.global.external.claude.StreamCancelledException;
import com.example.Petbulance_BE.global.external.exception.UpstreamUnavailableException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import com.example.Petbulance_BE.global.external.oauth.GoogleUserInfoClient;
import com.example.Petbulance_BE.global.external.oauth.KakaoUserInfoClient;
import com.example.Petbulance_BE.global.external.oauth.NaverUserInfoClient;
import com.example.Petbulance_BE.global.external.oauth.OAuthEmailResolver;
import com.example.Petbulance_BE.global.external.vworld.GeoResult;
import com.example.Petbulance_BE.global.external.vworld.VworldClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.resilience4j.springboot3.bulkhead.autoconfigure.BulkheadAutoConfiguration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 호출 성격별 장애 대응 정책이 "설정대로 실제로 동작하는지"를 WireMock 으로 검증한다.
 * (Resilience4j 어노테이션은 프록시를 거쳐야 하므로 실제 Spring 컨텍스트에서 확인한다)
 */
@SpringBootTest(classes = ExternalResilienceTest.TestConfig.class,
        properties = "spring.config.import=optional:classpath:application-external.yml")
class ExternalResilienceTest {

    static final WireMockServer WIRE_MOCK = new WireMockServer(options().dynamicPort());

    static {
        WIRE_MOCK.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = WIRE_MOCK.baseUrl();
        registry.add("external.clients.vworld.base-url", () -> base);
        registry.add("external.clients.claude.base-url", () -> base);
        registry.add("external.clients.claude.api-key", () -> "test-key");
        registry.add("external.oauth.kakao-user-info-uri", () -> base + "/kakao/me");

        // 테스트 속도를 위해 타임아웃/대기를 줄인다 (정책 구조는 application.yml 과 동일)
        registry.add("external.clients.vworld.response-timeout", () -> "500ms");
        registry.add("external.clients.claude.response-timeout", () -> "1s");
        registry.add("external.clients.oauth.response-timeout", () -> "500ms");
        registry.add("resilience4j.retry.instances.vworld.waitDuration", () -> "10ms");
        registry.add("resilience4j.retry.instances.claude.waitDuration", () -> "10ms");
        registry.add("resilience4j.circuitbreaker.instances.vworld.slidingWindowSize", () -> "4");
        registry.add("resilience4j.circuitbreaker.instances.vworld.minimumNumberOfCalls", () -> "4");
        registry.add("resilience4j.bulkhead.instances.claude-receipt.maxConcurrentCalls", () -> "2");
    }

    @Configuration
    @ImportAutoConfiguration({
            AopAutoConfiguration.class, JacksonAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, RestClientAutoConfiguration.class,
            CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class, BulkheadAutoConfiguration.class
    })
    @Import({RestClientConfig.class, VworldClient.class, ClaudeClient.class,
            KakaoUserInfoClient.class, NaverUserInfoClient.class, GoogleUserInfoClient.class, OAuthEmailResolver.class})
    static class TestConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired VworldClient vworldClient;
    @Autowired ClaudeClient claudeClient;
    @Autowired ObjectMapper objectMapper;
    @Autowired OAuthEmailResolver oauthEmailResolver;
    @Autowired CircuitBreakerRegistry circuitBreakerRegistry;


    @BeforeEach
    void reset() {
        WIRE_MOCK.resetAll();
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private ObjectNode request() {
        return claudeClient.buildRequest("system", List.of(), "hi", objectMapper.createObjectNode().put("type", "object"));
    }

    // ------------------------------------------------------------------ VWorld (조회형)

    private static String vworldOk() {
        return "{\"response\":{\"status\":\"OK\",\"result\":{\"point\":{\"x\":127.1,\"y\":37.5}}}}";
    }

    @Test
    @DisplayName("[VWorld] 503 후 성공하면 1회 재시도해서 성공한다")
    void vworldRetriesOnServerError() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/req/address")).inScenario("s")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/req/address")).inScenario("s")
                .whenScenarioStateIs("recovered").willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(vworldOk())));

        GeoResult result = vworldClient.geocode("서울 강남구 테헤란로 1", "road");

        assertThat(result.found()).isTrue();
        assertThat(result.point()).isEqualTo(new GeoDto.PointDTO(127.1, 37.5));
        WIRE_MOCK.verify(2, getRequestedFor(urlPathEqualTo("/req/address")));
    }

    @Test
    @DisplayName("[VWorld] 읽기 타임아웃도 재시도한다 (조회형은 싸고 멱등)")
    void vworldRetriesOnReadTimeout() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/req/address")).inScenario("t")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(200).withFixedDelay(2000))
                .willSetStateTo("fast"));
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/req/address")).inScenario("t")
                .whenScenarioStateIs("fast").willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(vworldOk())));

        assertThat(vworldClient.geocode("주소", "road").found()).isTrue();
        WIRE_MOCK.verify(2, getRequestedFor(urlPathEqualTo("/req/address")));
    }

    @Test
    @DisplayName("[VWorld] NOT_FOUND 는 정상 결과다: 재시도하지 않고 서킷에도 집계하지 않는다")
    void vworldNotFoundIsNotAFailure() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/req/address")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"response\":{\"status\":\"NOT_FOUND\"}}")));

        for (int i = 0; i < 6; i++) {
            assertThat(vworldClient.geocode("없는 주소", "road").found()).isFalse();
        }

        WIRE_MOCK.verify(6, getRequestedFor(urlPathEqualTo("/req/address")));
        assertThat(circuitBreakerRegistry.circuitBreaker("vworld").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuitBreakerRegistry.circuitBreaker("vworld").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("[VWorld] 본문 status=ERROR 는 실패로 집계되어 서킷이 열리고, 열린 뒤에는 상대를 호출하지 않는다")
    void vworldBodyErrorOpensCircuit() {
        WIRE_MOCK.stubFor(get(urlPathEqualTo("/req/address")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"response\":{\"status\":\"ERROR\"}}")));

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> vworldClient.geocode("주소", "road"))
                    .isInstanceOf(UpstreamBadResponseException.class);
        }
        assertThat(circuitBreakerRegistry.circuitBreaker("vworld").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        assertThatThrownBy(() -> vworldClient.geocode("주소", "road"))
                .isInstanceOf(CallNotPermittedException.class);

        // 본문 오류는 재시도하지 않으므로 4번 + 차단된 5번째는 나가지 않음 = 4건
        WIRE_MOCK.verify(4, getRequestedFor(urlPathEqualTo("/req/address")));
    }

    // ------------------------------------------------------------------ Claude (AI)

    private static final String MESSAGES = "/v1/messages";
    private static final String OK_CLAUDE = """
            {"content":[{"type":"text","text":"{\\"ok\\":true}"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":3,"output_tokens":2}}""";

    /** Claude SSE 본문 만들기: data JSON 의 type 을 event 이름으로 쓴다 */
    private static String sse(String... dataJsons) {
        StringBuilder body = new StringBuilder();
        for (String data : dataJsons) {
            String type = data.replaceAll("^\\{\"type\":\"([a-z_]+)\".*$", "$1");
            body.append("event: ").append(type).append('\n').append("data: ").append(data).append("\n\n");
        }
        return body.toString();
    }

    private static String delta(String text) {
        return "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"" + text + "\"}}";
    }

    @Test
    @DisplayName("[Claude] 429 후 성공하면 재시도하고, 키/버전 헤더와 JSON 스키마 출력 설정을 보낸다")
    void claudeRetriesOn429AndSendsHeaders() {
        WIRE_MOCK.stubFor(post(urlEqualTo(MESSAGES)).inScenario("c")
                .whenScenarioStateIs(Scenario.STARTED).willReturn(aResponse().withStatus(429))
                .willSetStateTo("ok"));
        WIRE_MOCK.stubFor(post(urlEqualTo(MESSAGES)).inScenario("c")
                .whenScenarioStateIs("ok").willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(OK_CLAUDE)));

        ClaudeResult result = claudeClient.createForReceipt(request());

        assertThat(result.text()).isEqualTo("{\"ok\":true}");
        WIRE_MOCK.verify(2, postRequestedFor(urlEqualTo(MESSAGES))
                .withHeader("x-api-key", matching(".+"))
                .withHeader("anthropic-version", equalTo("2026-10-21"))
                .withRequestBody(matchingJsonPath("$.output_config.format.type", equalTo("json_schema")))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("false"))));
    }

    @Test
    @DisplayName("[Claude] 읽기 타임아웃은 재시도하지 않는다 (비용과 대기가 2배가 되므로)")
    void claudeDoesNotRetryReadTimeout() {
        WIRE_MOCK.stubFor(post(urlEqualTo(MESSAGES)).willReturn(aResponse().withStatus(200).withFixedDelay(2500)));

        assertThatThrownBy(() -> claudeClient.createForReceipt(request()))
                .isInstanceOf(UpstreamTimeoutException.class);
        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(MESSAGES)));
    }

    @Test
    @DisplayName("[Claude] 401 은 재시도하지 않고 서킷에도 집계하지 않는다 (키 문제)")
    void claudeRejectedIsNotRetriedNorCounted() {
        WIRE_MOCK.stubFor(post(urlEqualTo(MESSAGES)).willReturn(aResponse().withStatus(401)));

        assertThatThrownBy(() -> claudeClient.createForReceipt(request()))
                .isInstanceOfSatisfying(UpstreamRejectedException.class, e -> assertThat(e.isAuthError()).isTrue());
        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(MESSAGES)));
        assertThat(circuitBreakerRegistry.circuitBreaker("claude").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    @DisplayName("[Claude] 200 이어도 거절(refusal)이면 쓸 수 없는 출력으로 구분하고, 서킷 실패로 세지 않는다")
    void claudeRefusalIsNotAFailure() {
        WIRE_MOCK.stubFor(post(urlEqualTo(MESSAGES)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"content\":[{\"type\":\"text\",\"text\":\"\"}],\"stop_reason\":\"refusal\"}")));

        assertThatThrownBy(() -> claudeClient.createForReceipt(request()))
                .isInstanceOfSatisfying(AiOutputException.class,
                        e -> assertThat(e.getReason()).isEqualTo(AiOutputException.Reason.BLOCKED));
        assertThat(circuitBreakerRegistry.circuitBreaker("claude").getMetrics().getNumberOfFailedCalls()).isZero();
        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(MESSAGES)));
    }

    @Test
    @DisplayName("[Claude] 영수증 동시 호출 상한을 넘으면 기다리지 않고 즉시 거절한다 (스레드 점유 제한)")
    void claudeReceiptBulkheadRejectsImmediately() throws Exception {
        WIRE_MOCK.stubFor(post(urlEqualTo(MESSAGES)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(OK_CLAUDE).withFixedDelay(600)));

        int callers = 4; // 상한은 2
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            Callable<Long> task = () -> {
                startGate.await();
                long start = System.nanoTime();
                try {
                    claudeClient.createForReceipt(request());
                    return -1L;
                } catch (BulkheadFullException e) {
                    return (System.nanoTime() - start) / 1_000_000;
                }
            };
            futures.add(pool.submit(task));
        }
        startGate.countDown();

        int succeeded = 0;
        int rejected = 0;
        for (Future<Long> future : futures) {
            long result = future.get();
            if (result < 0) {
                succeeded++;
            } else {
                rejected++;
                assertThat(result).as("거절은 대기 없이 즉시 일어나야 한다").isLessThan(300);
            }
        }
        pool.shutdownNow();

        assertThat(succeeded).isEqualTo(2);
        assertThat(rejected).isEqualTo(2);
        WIRE_MOCK.verify(2, postRequestedFor(urlEqualTo(MESSAGES)));
    }

    @Test
    @DisplayName("[Claude 스트림] text_delta 조각을 순서대로 받고, 끝에서 stop_reason 과 토큰 사용량을 얻는다")
    void streamDeliversTextDeltas() {
        WIRE_MOCK.stubFor(post(urlEqualTo(MESSAGES)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse(
                        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}",
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                        "{\"type\":\"ping\"}",
                        delta("{\\\"emergency"),
                        delta("Level\\\":\\\"high\\\"}"),
                        "{\"type\":\"content_block_stop\",\"index\":0}",
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":7}}",
                        "{\"type\":\"message_stop\"}"))));

        List<String> chunks = new ArrayList<>();
        ClaudeStream.StreamSummary summary = claudeClient.openStream("diagnosis", request()).read(chunks::add);

        assertThat(String.join("", chunks)).isEqualTo("{\"emergencyLevel\":\"high\"}");
        assertThat(chunks).hasSize(2);
        assertThat(summary.stopReason()).isEqualTo("end_turn");
        assertThat(summary.inputTokens()).isEqualTo(10);
        assertThat(summary.outputTokens()).isEqualTo(7);
        WIRE_MOCK.verify(postRequestedFor(urlEqualTo(MESSAGES))
                .withHeader("Accept", equalTo("text/event-stream"))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true"))));
    }

    @Test
    @DisplayName("[Claude 스트림] 200 으로 시작한 뒤 중간에 온 error 이벤트(과부하)도 상대 장애로 분류한다")
    void streamMidErrorIsClassified() {
        WIRE_MOCK.stubFor(post(urlEqualTo(MESSAGES)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse(
                        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}",
                        delta("{\\\"a\\\""),
                        "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"))));

        List<String> chunks = new ArrayList<>();
        assertThatThrownBy(() -> claudeClient.openStream("diagnosis", request()).read(chunks::add))
                .isInstanceOf(UpstreamUnavailableException.class);
        assertThat(chunks).containsExactly("{\"a\"");
    }

    @Test
    @DisplayName("[Claude 스트림] 스트림을 열 때 529 면 바로 분류된 예외 (재시도는 호출하는 쪽에서 한 번만)")
    void streamOpenFailsWithStatus() {
        WIRE_MOCK.stubFor(post(urlEqualTo(MESSAGES)).willReturn(aResponse().withStatus(529)
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")));

        assertThatThrownBy(() -> claudeClient.openStream("diagnosis", request()))
                .isInstanceOf(UpstreamUnavailableException.class);
        WIRE_MOCK.verify(1, postRequestedFor(urlEqualTo(MESSAGES)));
    }

    @Test
    @DisplayName("[Claude 스트림] 끊으면 남은 응답을 기다리지 않고 바로 빠져나온다 (사용자 이탈 시 토큰 낭비 방지)")
    void streamCloseDoesNotWaitForRemainingBody() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            many.append(delta("x"));
            many.append('\n');
        }
        String[] events = many.toString().split("\n");
        WIRE_MOCK.stubFor(post(urlEqualTo(MESSAGES)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sse(events))
                .withChunkedDribbleDelay(40, 4000))); // 남은 본문이 4초에 걸쳐 천천히 온다

        ClaudeStream stream = claudeClient.openStream("diagnosis", request());
        long start = System.nanoTime();
        assertThatThrownBy(() -> stream.read(text -> stream.close())) // 첫 조각을 받자마자 끊는다
                .isInstanceOf(StreamCancelledException.class);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("남은 본문(약 4초)을 다 읽지 않아야 한다").isLessThan(2000);
    }

    // ------------------------------------------------------------------ OAuth (로그인 경로)

    private static void kakao(int status, String body, int delayMs) {
        WIRE_MOCK.stubFor(post(urlPathEqualTo("/kakao/me")).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body).withFixedDelay(delayMs)));
    }

    @Test
    @DisplayName("[로그인] 정상 조회")
    void oauthSuccess() {
        kakao(200, "{\"kakao_account\":{\"email\":\"a@b.com\"}}", 0);

        assertThat(oauthEmailResolver.resolveEmail("KAKAO", "token")).isEqualTo("a@b.com");
        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo("/kakao/me")).withHeader("Authorization", equalTo("Bearer token")));
    }

    @Test
    @DisplayName("[로그인] 토큰이 거부되면(401) 업체별 로그인 실패 코드, 재시도 없음")
    void oauthRejectedToken() {
        kakao(401, "{}", 0);

        assertThatThrownBy(() -> oauthEmailResolver.resolveEmail("KAKAO", "bad"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FAIL_KAKAO_LOGIN));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo("/kakao/me")));
    }

    @Test
    @DisplayName("[로그인] 소셜 서버가 느리거나(타임아웃) 5xx 면 503 코드, 재시도 없음")
    void oauthUnavailable() {
        kakao(200, "{}", 2000);
        assertThatThrownBy(() -> oauthEmailResolver.resolveEmail("KAKAO", "t"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SOCIAL_LOGIN_UNAVAILABLE));
        WIRE_MOCK.verify(1, postRequestedFor(urlPathEqualTo("/kakao/me")));

        kakao(500, "{}", 0);
        assertThatThrownBy(() -> oauthEmailResolver.resolveEmail("KAKAO", "t"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SOCIAL_LOGIN_UNAVAILABLE));
    }

    @Test
    @DisplayName("[로그인] 이메일이 비어 있으면 거부한다 (빈 문자열로 사용자가 합쳐지는 것을 방지)")
    void oauthEmptyEmail() {
        kakao(200, "{\"kakao_account\":{}}", 0);

        assertThatThrownBy(() -> oauthEmailResolver.resolveEmail("KAKAO", "t"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SOCIAL_EMAIL_NOT_PROVIDED));
    }

    @Test
    @DisplayName("[로그인] 알 수 없는 provider")
    void oauthUnknownProvider() {
        assertThatThrownBy(() -> oauthEmailResolver.resolveEmail("FACEBOOK", "t"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PROVIDER));
    }

    @Test
    @DisplayName("[로그인] 업체별로 서킷이 독립이다: Kakao 서킷이 열려도 Naver 는 영향이 없다")
    void oauthCircuitsAreIndependent() {
        kakao(500, "{}", 0);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> oauthEmailResolver.resolveEmail("KAKAO", "t")).isInstanceOf(CustomException.class);
        }
        assertThat(circuitBreakerRegistry.circuitBreaker("oauth-kakao").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(circuitBreakerRegistry.circuitBreaker("oauth-naver").getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // 열린 뒤에는 상대 서버로 나가지 않고 즉시 503 코드
        WIRE_MOCK.resetRequests();
        assertThatThrownBy(() -> oauthEmailResolver.resolveEmail("KAKAO", "t"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SOCIAL_LOGIN_UNAVAILABLE));
        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo("/kakao/me")));
    }
}

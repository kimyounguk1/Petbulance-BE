package com.example.Petbulance_BE.global.external.claude;

import com.example.Petbulance_BE.global.external.ExternalCalls;
import com.example.Petbulance_BE.global.external.ExternalClientProperties;
import com.example.Petbulance_BE.global.external.RestClientConfig;
import com.example.Petbulance_BE.global.external.exception.UpstreamBadResponseException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;

/**
 * Claude Messages API 클라이언트 (Anthropic 공식 문서 기준, raw HTTP).
 *
 * <p>SDK 대신 직접 호출하는 이유: 다른 연동과 같은 연결 풀(HttpClient5), 같은 오류 분류,
 * 같은 재시도 계층(Resilience4j)을 쓰기 위해서다. SDK 는 기본으로 2회 재시도하므로 함께 쓰면 재시도가 겹친다.
 *
 * <ul>
 *   <li>{@link #createForReceipt}: 응답을 한 번에 받는 호출. 어노테이션으로 재시도/서킷/벌크헤드 적용</li>
 *   <li>{@link #openStream}: SSE 스트리밍. 수명이 길어 호출하는 쪽에서 서킷을 직접 다룬다</li>
 * </ul>
 * 출력은 JSON 스키마(output_config.format)로 고정해 형식 오류를 원천적으로 줄인다.
 */
@Slf4j
@Component
public class ClaudeClient {

    private static final String CLIENT = RestClientConfig.CLAUDE;
    private static final String MESSAGES_PATH = "/v1/messages";

    private final RestClient restClient;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final String apiVersion;
    private final String thinking;

    public ClaudeClient(@Qualifier("claudeRestClient") RestClient restClient,
                        @Qualifier("claudeHttpClient") CloseableHttpClient httpClient,
                        ExternalClientProperties props,
                        ObjectMapper objectMapper,
                        MeterRegistry meterRegistry,
                        @Value("${external.claude.api-version:2026-10-21}") String apiVersion,
                        @Value("${external.claude.thinking:disabled}") String thinking) {
        ExternalClientProperties.Client c = props.get(CLIENT);
        this.restClient = restClient;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.baseUrl = c.baseUrl();
        this.apiKey = c.apiKey();
        this.model = c.model();
        this.maxTokens = c.maxOutputTokens() == null ? 2048 : c.maxOutputTokens();
        this.apiVersion = apiVersion;
        this.thinking = thinking;
    }

    /**
     * 요청 본문: 시스템 프롬프트 + 사용자 메시지(이미지들 + 텍스트) + 출력 JSON 스키마.
     * 이미지를 텍스트보다 앞에 둔다.
     */
    public ObjectNode buildRequest(String system, List<ClaudeImage> images, String userText, JsonNode outputSchema) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("system", system);
        if (thinking != null && !thinking.isBlank()) {
            // 빠른 응답을 위해 사고(thinking)를 끈다. 모델마다 허용 값이 달라 설정으로 둔다
            body.putObject("thinking").put("type", thinking);
        }
        ArrayNode content = body.putArray("messages").addObject().put("role", "user").putArray("content");
        for (ClaudeImage image : images) {
            ObjectNode block = content.addObject();
            block.put("type", "image");
            ObjectNode source = block.putObject("source");
            source.put("type", "base64");
            source.put("media_type", image.mediaType());
            source.put("data", image.base64Data());
        }
        content.addObject().put("type", "text").put("text", userText);
        ObjectNode format = body.putObject("output_config").putObject("format");
        format.put("type", "json_schema");
        format.set("schema", outputSchema);
        return body;
    }

    // ------------------------------------------------------------------ 한 번에 받는 호출

    @Retry(name = "claude")
    @CircuitBreaker(name = "claude")
    @Bulkhead(name = "claude-receipt")
    public ClaudeResult createForReceipt(ObjectNode body) {
        return create("receipt", body);
    }

    public ClaudeResult create(String feature, ObjectNode body) {
        byte[] json = toBytes(body, false);
        JsonNode response = ExternalCalls.execute(CLIENT, () ->
                ExternalCalls.mapErrors(CLIENT, restClient.post()
                                .uri(MESSAGES_PATH)
                                .headers(this::authHeaders)
                                .contentType(MediaType.APPLICATION_JSON)
                                .body(json)
                                .retrieve())
                        .body(JsonNode.class));
        if (response == null) {
            throw new UpstreamBadResponseException(CLIENT, "빈 응답");
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode block : response.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        String stopReason = response.path("stop_reason").asText(null);
        Integer in = intOrNull(response.path("usage").path("input_tokens"));
        Integer out = intOrNull(response.path("usage").path("output_tokens"));
        recordTokens(feature, in, out);

        AiOutputException.checkStopReason(stopReason, text);
        return new ClaudeResult(text.toString(), stopReason, in, out);
    }

    // ------------------------------------------------------------------ 스트리밍

    /**
     * 스트림을 연다. 첫 이벤트를 받기 전 단계(연결, HTTP 상태)의 실패만 여기서 예외가 된다.
     * 이 단계는 아직 사용자에게 아무것도 보내지 않았으므로 재시도해도 안전하다.
     */
    public ClaudeStream openStream(String feature, ObjectNode body) {
        HttpPost post = new HttpPost(baseUrl + MESSAGES_PATH);
        post.setHeader("x-api-key", apiKey);
        post.setHeader("anthropic-version", apiVersion);
        post.setHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE);
        post.setEntity(new ByteArrayEntity(toBytes(body, true), ContentType.APPLICATION_JSON));

        long start = System.nanoTime();
        CloseableHttpResponse response;
        try {
            response = httpClient.execute(post);
        } catch (IOException e) {
            log.warn("external-call client={} target={} stream=true failed={} elapsedMs={}",
                    CLIENT, MESSAGES_PATH, e.getClass().getSimpleName(), elapsedMs(start));
            throw ExternalCalls.translateIo(CLIENT, e);
        }

        int status = response.getCode();
        log.info("external-call client={} target={} stream=true status={} elapsedMs={}",
                CLIENT, MESSAGES_PATH, status, elapsedMs(start));
        if (status >= 400) {
            logErrorBody(response, status);
            post.cancel();
            closeQuietly(response);
            throw ExternalCalls.fromStatus(CLIENT, status);
        }
        return new HttpClaudeStream(post, response, objectMapper, (in, out) -> recordTokens(feature, in, out));
    }

    // ------------------------------------------------------------------ 공통

    private void authHeaders(HttpHeaders headers) {
        headers.set("x-api-key", apiKey);
        headers.set("anthropic-version", apiVersion);
    }

    private byte[] toBytes(ObjectNode body, boolean stream) {
        ObjectNode copy = body.deepCopy();
        copy.put("stream", stream);
        try {
            return objectMapper.writeValueAsBytes(copy);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("요청 직렬화 실패", e);
        }
    }

    /** 오류 본문에서 type 과 요청 ID 만 남긴다 (문의할 때 요청을 정확히 찾기 위해) */
    private void logErrorBody(CloseableHttpResponse response, int status) {
        try {
            String type = objectMapper.readTree(EntityUtils.toString(response.getEntity()))
                    .path("error").path("type").asText("unknown");
            String requestId = response.getFirstHeader("request-id") == null
                    ? null : response.getFirstHeader("request-id").getValue();
            log.warn("Claude 오류 응답 status={} type={} requestId={}", status, type, requestId);
        } catch (Exception ignored) {
            log.warn("Claude 오류 응답 status={} (본문 해석 불가)", status);
        }
    }

    /** 토큰 사용량을 기능별로 쌓는다. 한도와 비용은 조직 단위라 직접 쌓지 않으면 어디서 쓰는지 모른다 */
    private void recordTokens(String feature, Integer in, Integer out) {
        if (in != null && in > 0) {
            meterRegistry.counter("ai.tokens", "client", CLIENT, "feature", feature, "type", "input").increment(in);
        }
        if (out != null && out > 0) {
            meterRegistry.counter("ai.tokens", "client", CLIENT, "feature", feature, "type", "output").increment(out);
        }
    }

    private static void closeQuietly(CloseableHttpResponse response) {
        try {
            response.close();
        } catch (IOException ignored) {
            // 정리 단계 오류는 무시
        }
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

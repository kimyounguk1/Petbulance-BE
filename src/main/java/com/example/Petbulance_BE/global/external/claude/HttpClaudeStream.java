package com.example.Petbulance_BE.global.external.claude;

import com.example.Petbulance_BE.global.external.ExternalCalls;
import com.example.Petbulance_BE.global.external.exception.UpstreamBadResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.EntityUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Claude SSE 응답을 한 줄씩 읽는 스트림.
 *
 * <p>왜 Spring RestClient 가 아니라 HttpClient5 를 직접 쓰나:
 * Spring 의 HttpComponentsClientHttpResponse.close() 는 연결 재사용을 위해 남은 본문을 끝까지 읽는다
 * (EntityUtils.consume). 사용자가 떠났을 때 Claude 생성이 끝날 때까지 기다리게 되므로,
 * 요청 객체의 cancel() 로 연결을 즉시 끊을 수 있도록 원래 요청을 쥐고 있는다.
 */
final class HttpClaudeStream implements ClaudeStream {

    private static final String CLIENT = "claude";

    private final HttpPost request;
    private final CloseableHttpResponse response;
    private final ObjectMapper objectMapper;
    private final BiConsumer<Integer, Integer> usageRecorder;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    HttpClaudeStream(HttpPost request, CloseableHttpResponse response, ObjectMapper objectMapper,
                     BiConsumer<Integer, Integer> usageRecorder) {
        this.request = request;
        this.response = response;
        this.objectMapper = objectMapper;
        this.usageRecorder = usageRecorder;
    }

    @Override
    public StreamSummary read(TextHandler handler) {
        boolean completed = false;
        String stopReason = null;
        Integer inputTokens = null;
        Integer outputTokens = null;
        try {
            // try-with-resources 를 쓰지 않는다: reader 를 닫으면 남은 본문을 끝까지 읽어 버린다
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.getEntity().getContent(), StandardCharsets.UTF_8));
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    data.append(line.substring(5).trim());
                    continue;
                }
                if (!line.isEmpty() || data.isEmpty()) {
                    continue; // "event:" 줄은 무시한다. 이벤트 종류는 data 안의 type 에도 들어 있다
                }
                JsonNode event = objectMapper.readTree(data.toString());
                data.setLength(0);

                switch (event.path("type").asText()) {
                    case "message_start" ->
                            inputTokens = intOrNull(event.path("message").path("usage").path("input_tokens"));
                    case "content_block_delta" -> {
                        JsonNode delta = event.path("delta");
                        if ("text_delta".equals(delta.path("type").asText())) {
                            handler.onText(delta.path("text").asText());
                        }
                    }
                    case "message_delta" -> {
                        stopReason = event.path("delta").path("stop_reason").asText(null);
                        outputTokens = intOrNull(event.path("usage").path("output_tokens"));
                    }
                    case "message_stop" -> {
                        completed = true;
                        usageRecorder.accept(inputTokens, outputTokens);
                        return new StreamSummary(stopReason, inputTokens, outputTokens);
                    }
                    // 200 으로 시작한 뒤에 오는 오류 (예: overloaded_error). 일반 HTTP 오류 경로를 타지 않는다
                    case "error" -> throw ExternalCalls.fromStreamError(CLIENT,
                            event.path("error").path("type").asText("unknown"));
                    default -> {
                        // ping, content_block_start, content_block_stop
                    }
                }
            }
            if (cancelled.get()) {
                throw new StreamCancelledException();
            }
            throw new UpstreamBadResponseException(CLIENT, "스트림이 message_stop 없이 끝났습니다.");
        } catch (IOException e) {
            if (cancelled.get()) {
                throw new StreamCancelledException();
            }
            // 바이트 사이 무응답이 응답 타임아웃을 넘으면 SocketTimeoutException
            throw ExternalCalls.translateIo(CLIENT, e);
        } finally {
            if (completed) {
                finishNormally();
            } else {
                close();
            }
        }
    }

    /** 남은 응답을 읽지 않고 연결을 끊는다. 다른 스레드에서 불러도 된다 */
    @Override
    public void close() {
        if (cancelled.compareAndSet(false, true)) {
            request.cancel();
            closeQuietly();
        }
    }

    /** 정상 종료: message_stop 뒤 남은 몇 바이트만 읽고 닫아 연결을 풀에 돌려준다 */
    private void finishNormally() {
        if (cancelled.compareAndSet(false, true)) {
            try {
                EntityUtils.consume(response.getEntity());
            } catch (IOException ignored) {
                // 정리 단계 오류는 무시
            }
            closeQuietly();
        }
    }

    private void closeQuietly() {
        try {
            response.close();
        } catch (IOException ignored) {
            // 정리 단계 오류는 무시
        }
    }

    private static Integer intOrNull(JsonNode node) {
        return node.isNumber() ? node.asInt() : null;
    }
}

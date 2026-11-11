package com.example.Petbulance_BE.global.external.claude;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.util.TokenBuffer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 조각조각 도착하는 JSON 객체 텍스트를 읽으면서, 최상위 필드가 "완성되는 즉시" 알려준다.
 *
 * <p>Claude 는 JSON 을 몇 글자씩 나눠 보낸다. 전체가 끝날 때까지 기다리지 않고
 * 응급도 같은 앞쪽 필드를 먼저 사용자에게 보여주기 위해 쓴다.
 * 지정한 배열 필드(예: steps)는 원소 하나가 완성될 때마다 알려준다.
 *
 * <p>Jackson 의 논블로킹 파서를 써서 문자열 안의 중괄호, 이스케이프, 여러 조각에 걸친 글자도 안전하게 처리한다.
 * 스레드 하나에서만 사용한다.
 */
public class StreamingJsonFieldParser {

    public interface Handler {
        /** 최상위 필드 하나가 완성됨 */
        void onField(String name, JsonNode value);

        /** 지정한 배열 필드의 원소 하나가 완성됨 */
        void onArrayElement(String arrayName, int index, JsonNode value);
    }

    private final ObjectMapper objectMapper;
    private final Set<String> streamedArrays;
    private final Handler handler;
    private final JsonParser parser;
    private final ByteArrayFeeder feeder;

    /** 0: 루트 시작 전, 1: 루트 객체 안, 2: 원소 단위로 알려주는 배열 안 */
    private int level;
    private String currentField;
    private int arrayIndex;
    private TokenBuffer capture;
    private int captureDepth;

    public StreamingJsonFieldParser(ObjectMapper objectMapper, Set<String> streamedArrays, Handler handler) {
        this.objectMapper = objectMapper;
        this.streamedArrays = streamedArrays;
        this.handler = handler;
        try {
            this.parser = objectMapper.getFactory().createNonBlockingByteArrayParser();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        this.feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
    }

    /** 새로 도착한 텍스트 조각을 넣는다. 형식이 깨졌으면 {@link AiOutputException}(INVALID_FORMAT) */
    public void feed(String chunk) {
        try {
            byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
            feeder.feedInput(bytes, 0, bytes.length);
            JsonToken token;
            while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
                handle(token);
            }
        } catch (IOException e) {
            throw new AiOutputException(AiOutputException.Reason.INVALID_FORMAT, "스트림 JSON 해석 실패", e);
        }
    }

    private void handle(JsonToken token) throws IOException {
        // 객체/배열 값을 모으는 중이면 끝날 때까지 토큰을 복사한다
        if (capture != null) {
            capture.copyCurrentEvent(parser);
            if (token.isStructStart()) {
                captureDepth++;
            } else if (token.isStructEnd() && --captureDepth == 0) {
                JsonNode node = objectMapper.readTree(capture.asParser());
                capture = null;
                emit(node);
            }
            return;
        }

        switch (token) {
            case START_OBJECT -> {
                if (level == 0) {
                    level = 1; // 루트 객체
                } else {
                    startCapture();
                }
            }
            case START_ARRAY -> {
                if (level == 1 && streamedArrays.contains(currentField)) {
                    level = 2;
                    arrayIndex = 0;
                } else {
                    startCapture();
                }
            }
            case END_ARRAY -> {
                if (level == 2) {
                    level = 1;
                }
            }
            case END_OBJECT -> {
                if (level == 1) {
                    level = 0;
                }
            }
            case FIELD_NAME -> {
                if (level == 1) {
                    currentField = parser.currentName();
                }
            }
            default -> {
                // 문자열, 숫자, true/false/null: 논블로킹 파서는 값이 다 도착해야 토큰을 준다
                TokenBuffer single = new TokenBuffer(parser);
                single.copyCurrentEvent(parser);
                emit(objectMapper.readTree(single.asParser()));
            }
        }
    }

    private void startCapture() throws IOException {
        capture = new TokenBuffer(parser);
        capture.copyCurrentEvent(parser);
        captureDepth = 1;
    }

    private void emit(JsonNode node) {
        if (level == 2) {
            handler.onArrayElement(currentField, arrayIndex++, node);
        } else if (level == 1) {
            handler.onField(currentField, node);
        }
    }
}

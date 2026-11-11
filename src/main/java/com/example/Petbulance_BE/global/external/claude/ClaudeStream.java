package com.example.Petbulance_BE.global.external.claude;

/**
 * 열려 있는 Claude 스트림. {@link #read} 로 텍스트 조각을 받고, {@link #close} 로 언제든 끊는다.
 *
 * <p>close() 는 다른 스레드(클라이언트 연결 종료를 감지한 컨테이너 스레드)에서 불릴 수 있다.
 * 그때 read() 중인 스레드는 {@link StreamCancelledException} 으로 빠져나온다.
 */
public interface ClaudeStream extends AutoCloseable {

    /**
     * 스트림을 끝까지 읽는다. text_delta 가 올 때마다 handler 를 부르고, 끝나면 요약을 돌려준다.
     * handler 가 예외를 던지면 즉시 업스트림을 끊고 그 예외를 그대로 던진다 (조기 중단).
     */
    StreamSummary read(TextHandler handler);

    /** 남은 응답을 읽지 않고 바로 끊는다 (생성 중단 유도, 토큰 낭비 방지) */
    @Override
    void close();

    @FunctionalInterface
    interface TextHandler {
        void onText(String text);
    }

    record StreamSummary(String stopReason, Integer inputTokens, Integer outputTokens) {
    }
}

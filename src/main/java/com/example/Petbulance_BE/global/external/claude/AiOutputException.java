package com.example.Petbulance_BE.global.external.claude;

import lombok.Getter;

/**
 * HTTP 호출은 성공했지만 모델 출력을 쓸 수 없는 경우 (거절, 빈 응답, 잘림, 형식 오류).
 *
 * <p>서비스 장애가 아니라 출력 품질/안전 문제이므로 {@code ExternalApiException} 이 아니다.
 * 서킷브레이커에 집계하지 않는다(yml 의 ignoreExceptions).
 */
@Getter
public class AiOutputException extends RuntimeException {

    public enum Reason {
        /** 모델이 거절함 (stop_reason=refusal). 같은 입력으로 다시 해도 같은 결과 */
        BLOCKED,
        /** 텍스트가 없음 */
        EMPTY,
        /** 출력 토큰 상한에 걸려 잘림 (stop_reason=max_tokens) */
        TRUNCATED,
        /** JSON 형식/값 검증 실패 */
        INVALID_FORMAT
    }

    private final Reason reason;

    public AiOutputException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AiOutputException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    /**
     * HTTP 200 이라도 결과를 쓸 수 있는지 stop_reason 으로 판별한다 (Anthropic 문서 기준:
     * 거절은 refusal, 잘림은 max_tokens 이며 둘 다 스키마를 만족하지 않을 수 있다).
     */
    public static void checkStopReason(String stopReason, CharSequence text) {
        if ("refusal".equals(stopReason)) {
            throw new AiOutputException(Reason.BLOCKED, "모델이 요청을 거절했습니다.");
        }
        if ("max_tokens".equals(stopReason)) {
            throw new AiOutputException(Reason.TRUNCATED, "출력 토큰 상한으로 응답이 잘렸습니다.");
        }
        if (text == null || text.toString().isBlank()) {
            throw new AiOutputException(Reason.EMPTY, "응답 텍스트가 없습니다.");
        }
    }
}

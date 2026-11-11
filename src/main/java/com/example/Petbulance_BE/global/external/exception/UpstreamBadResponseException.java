package com.example.Petbulance_BE.global.external.exception;

/**
 * HTTP 200 이지만 본문이 오류 상태(예: VWorld status=ERROR)이거나 해석할 수 없다.
 * Resilience4j 는 예외가 던져져야 실패로 세므로, 본문 오류를 이 예외로 바꿔 서킷이 집계하게 한다.
 */
public class UpstreamBadResponseException extends ExternalApiException {

    public UpstreamBadResponseException(String client, String message) {
        super(client, message, null);
    }

    public UpstreamBadResponseException(String client, String message, Throwable cause) {
        super(client, message, cause);
    }
}

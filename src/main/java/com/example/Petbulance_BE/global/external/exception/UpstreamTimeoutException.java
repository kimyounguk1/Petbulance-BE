package com.example.Petbulance_BE.global.external.exception;

/**
 * 연결은 됐지만 응답이 제한 시간 안에 오지 않았다.
 * 결과를 모르는 상태이므로, 멱등하고 저렴한 조회(VWorld)만 재시도하고 비용이 드는 AI 호출은 재시도하지 않는다.
 */
public class UpstreamTimeoutException extends ExternalApiException {

    public UpstreamTimeoutException(String client, String message, Throwable cause) {
        super(client, message, cause);
    }
}

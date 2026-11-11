package com.example.Petbulance_BE.global.external.exception;

/** 연결 실패, 풀 대기 초과, 5xx, 408. 상대 서버(또는 네트워크) 문제이며 재시도 후보다. */
public class UpstreamUnavailableException extends ExternalApiException {

    public UpstreamUnavailableException(String client, String message, Throwable cause) {
        super(client, message, cause);
    }
}

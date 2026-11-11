package com.example.Petbulance_BE.global.external.exception;

/** 429. 상대의 한도 초과. 백오프+지터로 재시도할 수 있고, 반복되면 서킷이 막아 상대를 더 두드리지 않는다. */
public class UpstreamRateLimitedException extends ExternalApiException {

    public UpstreamRateLimitedException(String client, String message) {
        super(client, message, null);
    }
}

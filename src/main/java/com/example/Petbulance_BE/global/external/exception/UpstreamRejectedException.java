package com.example.Petbulance_BE.global.external.exception;

import lombok.Getter;

/**
 * 429 를 제외한 4xx. 요청 형식, 인증, 결제/권한 문제처럼 재시도해도 같은 결과가 나오는 오류.
 * 재시도하지 않고 서킷에도 집계하지 않는다(상대는 정상이다). 대신 401/403 은 운영 알림 대상이다.
 */
@Getter
public class UpstreamRejectedException extends ExternalApiException {

    private final int status;

    public UpstreamRejectedException(String client, int status) {
        super(client, "상대 서버가 요청을 거부했습니다. status=" + status, null);
        this.status = status;
    }

    /** 401/403: 키 만료, 키 유출 차단, 권한 문제 등 설정 점검이 필요한 경우 */
    public boolean isAuthError() {
        return status == 401 || status == 403;
    }
}

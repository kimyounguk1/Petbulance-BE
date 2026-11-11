package com.example.Petbulance_BE.global.external.exception;

import lombok.Getter;

/**
 * 외부 API 호출 실패의 공통 부모.
 *
 * <p>오류의 "성격"을 타입으로 구분하는 것이 이 설계의 핵심이다.
 * Resilience4j(재시도/서킷브레이커)는 예외 타입으로 정책을 갈라 적용하므로,
 * 어떤 오류는 재시도하고 어떤 오류는 서킷에 집계하지 않을지를 yml 에서 타입 이름으로 선언할 수 있다.
 *
 * <ul>
 *   <li>{@link UpstreamUnavailableException}: 연결 실패, 5xx, 408 - 상대 서버 문제, 재시도 가능</li>
 *   <li>{@link UpstreamTimeoutException}: 응답 대기 초과 - 호출 성격에 따라 재시도 여부가 갈린다</li>
 *   <li>{@link UpstreamRateLimitedException}: 429 - 백오프 후 재시도 가능</li>
 *   <li>{@link UpstreamRejectedException}: 4xx - 우리 요청/설정 문제, 재시도 금지, 서킷 제외</li>
 *   <li>{@link UpstreamBadResponseException}: 200 이지만 본문이 오류/해석 불가</li>
 * </ul>
 *
 * 메시지에는 URL(쿼리스트링의 API 키 포함 가능)을 절대 넣지 않는다.
 */
@Getter
public abstract class ExternalApiException extends RuntimeException {

    private final String client;

    protected ExternalApiException(String client, String message, Throwable cause) {
        super("[" + client + "] " + message, cause);
        this.client = client;
    }
}

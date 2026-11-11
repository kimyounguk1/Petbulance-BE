package com.example.Petbulance_BE.global.external;

import com.example.Petbulance_BE.global.external.exception.ExternalApiException;
import com.example.Petbulance_BE.global.external.exception.UpstreamRejectedException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

/**
 * 서비스 계층이 "외부 때문에 지금 못 한다"를 한 번에 판별하기 위한 도우미.
 * 서킷이 열렸거나(CallNotPermitted) 벌크헤드가 찬 경우도 사용자에게는 같은 "일시 불가"다.
 */
public final class ExternalFailures {

    private ExternalFailures() {
    }

    /** 상대/네트워크/보호장치 때문에 일시적으로 실패한 경우 (503 으로 응답할 대상) */
    public static boolean isUnavailable(Throwable e) {
        if (e instanceof CallNotPermittedException || e instanceof BulkheadFullException) {
            return true;
        }
        return e instanceof ExternalApiException && !(e instanceof UpstreamRejectedException);
    }
}

package com.example.Petbulance_BE.global.external;

import com.example.Petbulance_BE.global.external.exception.ExternalApiException;
import com.example.Petbulance_BE.global.external.exception.UpstreamBadResponseException;
import com.example.Petbulance_BE.global.external.exception.UpstreamRateLimitedException;
import com.example.Petbulance_BE.global.external.exception.UpstreamRejectedException;
import com.example.Petbulance_BE.global.external.exception.UpstreamTimeoutException;
import com.example.Petbulance_BE.global.external.exception.UpstreamUnavailableException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.SocketTimeoutException;
import java.util.function.Supplier;

/**
 * 모든 외부 호출이 공유하는 오류 번역기.
 * HTTP 상태와 I/O 예외를 {@link ExternalApiException} 계열로 바꿔 Resilience4j 정책이 타입으로 판단하게 한다.
 */
public final class ExternalCalls {

    private ExternalCalls() {
    }

    /** {@code retrieve()} 뒤에 붙여 4xx/5xx 를 분류된 예외로 바꾼다. */
    public static RestClient.ResponseSpec mapErrors(String client, RestClient.ResponseSpec spec) {
        return spec.onStatus(HttpStatusCode::isError, (request, response) -> {
            throw fromStatus(client, response.getStatusCode().value());
        });
    }

    public static ExternalApiException fromStatus(String client, int status) {
        if (status == 429) {
            return new UpstreamRateLimitedException(client, "상대 서버 한도 초과 status=429");
        }
        if (status == 408 || status >= 500) {
            return new UpstreamUnavailableException(client, "상대 서버 오류 status=" + status, null);
        }
        return new UpstreamRejectedException(client, status);
    }

    /**
     * 호출 전체를 감싸 I/O 예외와 변환 오류를 분류한다.
     * 예외 메시지에는 요청 URL(API 키가 쿼리스트링에 있을 수 있음)이 들어가므로 원문 메시지를 그대로 옮기지 않는다.
     */
    public static <T> T execute(String client, Supplier<T> call) {
        try {
            return call.get();
        } catch (ExternalApiException e) {
            throw e;
        } catch (ResourceAccessException e) {
            throw translateIo(client, e);
        } catch (RestClientException e) {
            throw new UpstreamBadResponseException(client,
                    "응답을 해석하지 못했습니다. " + e.getClass().getSimpleName(), e);
        }
    }

    private static ExternalApiException translateIo(String client, ResourceAccessException e) {
        return translateIo(client, e.getCause() == null ? e : e.getCause());
    }

    /** I/O 예외 분류. 스트리밍처럼 RestClient 를 거치지 않는 호출도 같은 기준을 쓰도록 공개한다 */
    public static ExternalApiException translateIo(String client, Throwable cause) {
        if (cause instanceof SocketTimeoutException) {
            return new UpstreamTimeoutException(client, "응답 대기 시간 초과", cause);
        }
        return new UpstreamUnavailableException(client, "연결 실패 " + cause.getClass().getSimpleName(), cause);
    }

    /** HTTP 200 으로 시작한 스트림 도중에 온 error 이벤트의 type 을 같은 오류 분류로 바꾼다 */
    public static ExternalApiException fromStreamError(String client, String errorType) {
        return switch (errorType) {
            case "rate_limit_error" -> new UpstreamRateLimitedException(client, "스트림 중 한도 초과");
            case "overloaded_error", "api_error", "timeout_error" ->
                    new UpstreamUnavailableException(client, "스트림 중 상대 오류 type=" + errorType, null);
            default -> new UpstreamBadResponseException(client, "스트림 중 오류 type=" + errorType);
        };
    }
}

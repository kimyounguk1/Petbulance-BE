package com.example.Petbulance_BE.global.external;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * 외부 호출 한 건당 한 줄 로그. 호스트와 경로만 남기고 쿼리스트링은 절대 남기지 않는다
 * (VWorld 는 API 키가 쿼리스트링으로 나간다).
 */
@Slf4j
public class ClientLoggingInterceptor implements ClientHttpRequestInterceptor {

    private final String client;

    public ClientLoggingInterceptor(String client) {
        this.client = client;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        long start = System.nanoTime();
        String target = request.getURI().getHost() + request.getURI().getPath();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            log.info("external-call client={} method={} target={} status={} elapsedMs={}",
                    client, request.getMethod(), target, response.getStatusCode().value(), elapsedMs(start));
            return response;
        } catch (IOException e) {
            log.warn("external-call client={} method={} target={} failed={} elapsedMs={}",
                    client, request.getMethod(), target, e.getClass().getSimpleName(), elapsedMs(start));
            throw e;
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}

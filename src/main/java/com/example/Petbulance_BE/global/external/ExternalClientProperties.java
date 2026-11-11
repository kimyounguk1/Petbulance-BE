package com.example.Petbulance_BE.global.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * 연동별 HTTP 클라이언트 설정. {@code external.clients.<이름>} 으로 바인딩된다.
 * 호출 성격(로그인 / 조회 / AI)마다 타임아웃과 풀 크기를 다르게 두기 위해 연동마다 따로 설정한다.
 *
 * <ul>
 *   <li>connectTimeout: 연결을 맺는 시간</li>
 *   <li>responseTimeout: 마지막 바이트 이후 무응답 시간 (전체 시간이 아니다. 전체 상한은 Deadline 으로 따로 관리)</li>
 *   <li>poolWaitTimeout: 풀에서 빈 연결을 기다리는 시간. 짧게 두어 풀이 찼을 때 스레드가 오래 묶이지 않게 한다</li>
 *   <li>maxPerRoute / maxTotal: 풀 크기. HttpClient5 기본값(경로당 5, 전체 25)은 너무 작다</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "external")
public record ExternalClientProperties(Map<String, Client> clients) {

    public ExternalClientProperties {
        clients = clients == null ? Map.of() : clients;
    }

    public Client get(String name) {
        Client client = clients.get(name);
        if (client == null) {
            throw new IllegalStateException("external.clients." + name + " 설정이 없습니다.");
        }
        return client;
    }

    public record Client(
            String baseUrl,
            String apiKey,
            String model,
            Integer maxOutputTokens,
            Duration connectTimeout,
            Duration responseTimeout,
            Duration poolWaitTimeout,
            Integer maxPerRoute,
            Integer maxTotal
    ) {
        public Client {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
            responseTimeout = responseTimeout == null ? Duration.ofSeconds(5) : responseTimeout;
            poolWaitTimeout = poolWaitTimeout == null ? Duration.ofMillis(500) : poolWaitTimeout;
            maxPerRoute = maxPerRoute == null ? 20 : maxPerRoute;
            maxTotal = maxTotal == null ? Math.max(maxPerRoute, 50) : maxTotal;
        }
    }
}

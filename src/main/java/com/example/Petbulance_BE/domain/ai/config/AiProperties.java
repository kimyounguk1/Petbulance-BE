package com.example.Petbulance_BE.domain.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AI 기능 제어 설정 ({@code external.ai.*}).
 *
 * <ul>
 *   <li>enabled: 킬스위치. 비용 폭주나 장애 시 환경변수(EXTERNAL_AI_ENABLED=false) 변경과 재시작만으로 기능을 끈다
 *       (설정을 시작 시점에 바인딩하므로 런타임 토글은 아니다). 진단과 영수증 모두에 적용</li>
 *   <li>maxImages / maxImageBytes / maxSymptomLength: 입력 크기 제한. 입력이 곧 토큰 비용이다</li>
 *   <li>maxConcurrentStreams: AI 진단 전용 스레드 수 = 동시에 진행되는 진단 수. 넘으면 대기 없이 503</li>
 *   <li>flowDeadline: 진단 하나가 쓸 수 있는 전체 시간</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "external.ai")
public record AiProperties(
        Boolean enabled,
        Integer maxImages,
        Long maxImageBytes,
        Integer maxSymptomLength,
        Integer maxConcurrentStreams,
        Duration flowDeadline
) {
    public AiProperties {
        enabled = enabled == null || enabled;
        maxImages = maxImages == null ? 3 : maxImages;
        maxImageBytes = maxImageBytes == null ? 5L * 1024 * 1024 : maxImageBytes;
        maxSymptomLength = maxSymptomLength == null ? 1000 : maxSymptomLength;
        maxConcurrentStreams = maxConcurrentStreams == null ? 10 : maxConcurrentStreams;
        flowDeadline = flowDeadline == null ? Duration.ofSeconds(50) : flowDeadline;
    }

    public boolean isEnabled() {
        return enabled;
    }
}

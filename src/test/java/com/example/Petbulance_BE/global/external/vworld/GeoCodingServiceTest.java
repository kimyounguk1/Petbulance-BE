package com.example.Petbulance_BE.global.external.vworld;

import com.example.Petbulance_BE.domain.review.dto.GeoDto;
import com.example.Petbulance_BE.global.common.error.exception.CustomException;
import com.example.Petbulance_BE.global.common.error.exception.ErrorCode;
import com.example.Petbulance_BE.global.external.exception.UpstreamTimeoutException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GeoCodingServiceTest {

    @Mock VworldClient vworldClient;
    @Mock StringRedisTemplate redisTemplate;
    @Mock ValueOperations<String, String> valueOps;

    GeoCodingService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new GeoCodingService(vworldClient, redisTemplate, true, Duration.ofDays(30), Duration.ofMinutes(10));
    }

    private static GeoResult found() {
        return new GeoResult(new GeoDto.PointDTO(127.1, 37.5));
    }

    @Test
    @DisplayName("캐시에 있으면 외부를 호출하지 않는다")
    void cacheHit() {
        when(valueOps.get("geo:road:서울 강남구 테헤란로 1")).thenReturn("127.1,37.5");

        GeoDto.PointDTO point = service.geocode("  서울   강남구 테헤란로 1 ", "road");

        assertThat(point).isEqualTo(new GeoDto.PointDTO(127.1, 37.5));
        verify(vworldClient, never()).geocode(anyString(), anyString());
    }

    @Test
    @DisplayName("캐시 미스면 호출하고 결과를 저장한다")
    void cacheMissStoresResult() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(vworldClient.geocode("주소", "road")).thenReturn(found());

        service.geocode("주소", "road");

        verify(valueOps).set(eq("geo:road:주소"), eq("127.1,37.5"), eq(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("도로명으로 못 찾으면 반대 유형(지번)으로 1회 보정한다")
    void fallbackToOtherAddressType() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(vworldClient.geocode("주소", "road")).thenReturn(GeoResult.NOT_FOUND);
        when(vworldClient.geocode("주소", "parcel")).thenReturn(found());

        assertThat(service.geocode("주소", "road")).isEqualTo(new GeoDto.PointDTO(127.1, 37.5));
        verify(vworldClient, times(2)).geocode(anyString(), anyString());
    }

    @Test
    @DisplayName("둘 다 못 찾으면 FAIL_GEOCODING, NOT_FOUND 는 짧게 캐시한다")
    void notFoundIsCachedBriefly() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(vworldClient.geocode(anyString(), anyString())).thenReturn(GeoResult.NOT_FOUND);

        assertThatThrownBy(() -> service.geocode("없는주소", "road"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FAIL_GEOCODING));
        verify(valueOps).set(eq("geo:road:없는주소"), eq("NF"), eq(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("NOT_FOUND 캐시가 있으면 외부를 호출하지 않고 바로 실패한다")
    void cachedNotFound() {
        when(valueOps.get("geo:road:없는주소")).thenReturn("NF");

        assertThatThrownBy(() -> service.geocode("없는주소", "road")).isInstanceOf(CustomException.class);
        verify(vworldClient, never()).geocode(anyString(), anyString());
    }

    @Test
    @DisplayName("외부 장애(타임아웃, 벌크헤드 거절 등)는 GEO_UNAVAILABLE(503)로 변환한다")
    void unavailableIsTranslated() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(vworldClient.geocode(anyString(), anyString())).thenThrow(new UpstreamTimeoutException("vworld", "t", null));

        assertThatThrownBy(() -> service.geocode("주소", "road"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GEO_UNAVAILABLE));

        Bulkhead bulkhead = Bulkhead.of("x", BulkheadConfig.custom().maxConcurrentCalls(1).build());
        org.mockito.Mockito.doThrow(BulkheadFullException.createBulkheadFullException(bulkhead))
                .when(vworldClient).geocode(anyString(), anyString());
        assertThatThrownBy(() -> service.geocode("주소", "road"))
                .isInstanceOfSatisfying(CustomException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.GEO_UNAVAILABLE));
    }

    @Test
    @DisplayName("Redis 가 죽어도 기능은 동작한다 (캐시 fail-open)")
    void redisFailureIsIgnored() {
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));
        when(valueOps.get(any())).thenThrow(new RuntimeException("redis down"));
        org.mockito.Mockito.doThrow(new RuntimeException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(vworldClient.geocode("주소", "road")).thenReturn(found());

        assertThat(service.geocode("주소", "road")).isEqualTo(new GeoDto.PointDTO(127.1, 37.5));
    }
}

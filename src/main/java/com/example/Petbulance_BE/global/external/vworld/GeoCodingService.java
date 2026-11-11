package com.example.Petbulance_BE.global.external.vworld;

import com.example.Petbulance_BE.domain.review.dto.GeoDto;
import com.example.Petbulance_BE.global.common.error.exception.CustomException;
import com.example.Petbulance_BE.global.common.error.exception.ErrorCode;
import com.example.Petbulance_BE.global.external.ExternalFailures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 주소 -> 좌표. 호출 순서: 캐시 -> (벌크헤드 -> 서킷 -> 호출) -> 캐시 저장.
 *
 * <ul>
 *   <li>같은 주소는 같은 결과이므로 Redis 에 캐시한다 (서버가 여러 대여도 공유). NOT_FOUND 는 짧게 캐시한다</li>
 *   <li>Redis 가 죽어도 기능은 동작해야 하므로 캐시 오류는 무시하고 호출로 넘어간다 (fail-open)</li>
 *   <li>주소 유형(도로명/지번)은 AI(Claude)가 영수증에서 추정한 값이라 틀릴 수 있다. NOT_FOUND 면 반대 유형으로 1회 보정한다.
 *       이것은 재시도가 아니라 도메인 로직이다</li>
 *   <li>DB 트랜잭션 밖에서 호출해야 한다</li>
 * </ul>
 */
@Slf4j
@Service
public class GeoCodingService {

    private static final String NOT_FOUND_MARK = "NF";

    private final VworldClient vworldClient;
    private final StringRedisTemplate redisTemplate;
    private final boolean cacheEnabled;
    private final Duration cacheTtl;
    private final Duration notFoundTtl;

    public GeoCodingService(VworldClient vworldClient,
                            StringRedisTemplate redisTemplate,
                            @Value("${external.geo.cache-enabled:true}") boolean cacheEnabled,
                            @Value("${external.geo.cache-ttl:30d}") Duration cacheTtl,
                            @Value("${external.geo.not-found-ttl:10m}") Duration notFoundTtl) {
        this.vworldClient = vworldClient;
        this.redisTemplate = redisTemplate;
        this.cacheEnabled = cacheEnabled;
        this.cacheTtl = cacheTtl;
        this.notFoundTtl = notFoundTtl;
    }

    public GeoDto.PointDTO geocode(String address, String addressType) {
        String normalized = normalize(address);
        String type = "parcel".equalsIgnoreCase(addressType) ? "parcel" : "road";
        String cacheKey = "geo:" + type + ":" + normalized;

        String cached = readCache(cacheKey);
        if (cached != null) {
            if (NOT_FOUND_MARK.equals(cached)) {
                throw new CustomException(ErrorCode.FAIL_GEOCODING);
            }
            GeoDto.PointDTO point = parsePoint(cached);
            if (point != null) {
                return point;
            }
        }

        GeoResult result;
        try {
            result = vworldClient.geocode(normalized, type);
            if (!result.found()) {
                result = vworldClient.geocode(normalized, "road".equals(type) ? "parcel" : "road");
            }
        } catch (RuntimeException e) {
            if (ExternalFailures.isUnavailable(e)) {
                log.warn("지오코딩 일시 불가 cause={}", e.getClass().getSimpleName());
                throw new CustomException(ErrorCode.GEO_UNAVAILABLE);
            }
            throw e;
        }

        if (!result.found()) {
            writeCache(cacheKey, NOT_FOUND_MARK, notFoundTtl);
            throw new CustomException(ErrorCode.FAIL_GEOCODING);
        }

        GeoDto.PointDTO point = result.point();
        writeCache(cacheKey, point.x() + "," + point.y(), cacheTtl);
        return point;
    }

    /** 캐시 키가 같은 요청은 같은 키가 되도록 공백만 정리한다 */
    static String normalize(String address) {
        return address == null ? "" : address.trim().replaceAll("\\s+", " ");
    }

    private String readCache(String key) {
        if (!cacheEnabled) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("지오코딩 캐시 조회 실패(무시하고 호출) cause={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private void writeCache(String key, String value, Duration ttl) {
        if (!cacheEnabled) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("지오코딩 캐시 저장 실패(무시) cause={}", e.getClass().getSimpleName());
        }
    }

    private static GeoDto.PointDTO parsePoint(String value) {
        try {
            String[] parts = value.split(",");
            return new GeoDto.PointDTO(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        } catch (RuntimeException e) {
            return null;
        }
    }
}

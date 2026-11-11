package com.example.Petbulance_BE.global.external.vworld;

import com.example.Petbulance_BE.domain.review.dto.GeoDto;

/**
 * 지오코딩 결과. "주소를 못 찾음"은 정상적인 도메인 결과이지 외부 장애가 아니다.
 * 장애(통신 실패, 5xx, 본문 status=ERROR)는 {@code ExternalApiException} 으로 던져 서킷이 집계하게 한다.
 */
public record GeoResult(GeoDto.PointDTO point) {

    public static final GeoResult NOT_FOUND = new GeoResult(null);

    public boolean found() {
        return point != null;
    }
}

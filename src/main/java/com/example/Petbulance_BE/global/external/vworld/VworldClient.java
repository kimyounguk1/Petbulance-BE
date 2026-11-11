package com.example.Petbulance_BE.global.external.vworld;

import com.example.Petbulance_BE.domain.review.dto.GeoDto;
import com.example.Petbulance_BE.global.external.ExternalCalls;
import com.example.Petbulance_BE.global.external.ExternalClientProperties;
import com.example.Petbulance_BE.global.external.RestClientConfig;
import com.example.Petbulance_BE.global.external.exception.UpstreamBadResponseException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * VWorld 지오코딩 HTTP 호출 + 장애 대응 정책만 담당한다 (캐시/보정은 {@link GeoCodingService}).
 * Resilience4j 어노테이션은 프록시를 거쳐야 동작하므로 서비스 로직과 다른 빈으로 분리했다.
 *
 * <p>적용 순서(바깥 -> 안쪽): Retry -> CircuitBreaker -> Bulkhead -> 호출
 * <ul>
 *   <li>조회형(GET, 멱등, 무료)이라 읽기 타임아웃도 1회 재시도한다</li>
 *   <li>본문 status=ERROR 는 HTTP 200 이라 예외로 바꿔 서킷이 집계하게 한다 (키 오류/쿼터 소진도 호출을 멈추는 게 맞다)</li>
 *   <li>NOT_FOUND 는 정상 결과이므로 서킷에 집계하지 않는다</li>
 * </ul>
 */
@Component
public class VworldClient {

    private static final String CLIENT = RestClientConfig.VWORLD;

    private final RestClient restClient;
    private final String apiKey;

    public VworldClient(@Qualifier("vworldRestClient") RestClient restClient, ExternalClientProperties props) {
        this.restClient = restClient;
        this.apiKey = props.get(CLIENT).apiKey();
    }

    @Retry(name = "vworld")
    @CircuitBreaker(name = "vworld")
    @Bulkhead(name = "vworld")
    public GeoResult geocode(String address, String type) {
        // URI 템플릿 변수로 넘겨 값 인코딩을 엄격하게 하고, 메트릭/로그의 URI 에 키 값이 노출되지 않게 한다
        GeoDto.GeoRootDTO root = ExternalCalls.execute(CLIENT, () ->
                ExternalCalls.mapErrors(CLIENT, restClient.get()
                        .uri("/req/address?service=address&request=getCoord&format=json&crs=epsg:4326"
                                + "&key={key}&type={type}&address={address}", apiKey, type, address)
                        .retrieve())
                        .body(GeoDto.GeoRootDTO.class));

        if (root == null || root.response() == null) {
            throw new UpstreamBadResponseException(CLIENT, "응답 본문이 비어 있습니다.");
        }

        String status = root.response().status();
        if ("NOT_FOUND".equals(status)) {
            return GeoResult.NOT_FOUND;
        }
        if ("OK".equals(status)
                && root.response().result() != null
                && root.response().result().point() != null) {
            return new GeoResult(root.response().result().point());
        }
        throw new UpstreamBadResponseException(CLIENT, "본문 status=" + status);
    }
}

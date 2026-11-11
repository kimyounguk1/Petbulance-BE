package com.example.Petbulance_BE.global.external.oauth;

import com.example.Petbulance_BE.global.common.error.exception.ErrorCode;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KakaoUserInfoClient extends AbstractOAuthUserInfoClient {

    public KakaoUserInfoClient(
            @Qualifier("oauthRestClient") RestClient restClient,
            @Value("${external.oauth.kakao-user-info-uri:https://kapi.kakao.com/v2/user/me}") String endpoint) {
        super(restClient, endpoint);
    }

    @Override
    public String provider() {
        return "KAKAO";
    }

    @Override
    public ErrorCode loginFailureCode() {
        return ErrorCode.FAIL_KAKAO_LOGIN;
    }

    @Override
    @Bulkhead(name = "oauth-kakao")
    @CircuitBreaker(name = "oauth-kakao")
    public String fetchEmail(String accessToken) {
        return call(HttpMethod.POST, accessToken, true)
                .path("kakao_account").path("email").asText("");
    }
}

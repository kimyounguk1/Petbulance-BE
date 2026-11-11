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
public class NaverUserInfoClient extends AbstractOAuthUserInfoClient {

    public NaverUserInfoClient(
            @Qualifier("oauthRestClient") RestClient restClient,
            @Value("${external.oauth.naver-user-info-uri:https://openapi.naver.com/v1/nid/me}") String endpoint) {
        super(restClient, endpoint);
    }

    @Override
    public String provider() {
        return "NAVER";
    }

    @Override
    public ErrorCode loginFailureCode() {
        return ErrorCode.FAIL_NAVER_LOGIN;
    }

    @Override
    @Bulkhead(name = "oauth-naver")
    @CircuitBreaker(name = "oauth-naver")
    public String fetchEmail(String accessToken) {
        return call(HttpMethod.GET, accessToken, false)
                .path("response").path("email").asText("");
    }
}

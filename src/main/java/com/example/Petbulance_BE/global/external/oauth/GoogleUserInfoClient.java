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
public class GoogleUserInfoClient extends AbstractOAuthUserInfoClient {

    public GoogleUserInfoClient(
            @Qualifier("oauthRestClient") RestClient restClient,
            @Value("${external.oauth.google-user-info-uri:https://www.googleapis.com/oauth2/v2/userinfo}") String endpoint) {
        super(restClient, endpoint);
    }

    @Override
    public String provider() {
        return "GOOGLE";
    }

    @Override
    public ErrorCode loginFailureCode() {
        return ErrorCode.FAIL_GOOGLE_LOGIN;
    }

    @Override
    @Bulkhead(name = "oauth-google")
    @CircuitBreaker(name = "oauth-google")
    public String fetchEmail(String accessToken) {
        return call(HttpMethod.GET, accessToken, false)
                .path("email").asText("");
    }
}

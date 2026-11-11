package com.example.Petbulance_BE.global.external.oauth;

import com.example.Petbulance_BE.global.external.ExternalCalls;
import com.example.Petbulance_BE.global.external.RestClientConfig;
import com.example.Petbulance_BE.global.external.exception.UpstreamBadResponseException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;

abstract class AbstractOAuthUserInfoClient implements OAuthUserInfoClient {

    private final RestClient restClient;
    private final String endpoint;

    protected AbstractOAuthUserInfoClient(RestClient restClient, String endpoint) {
        this.restClient = restClient;
        this.endpoint = endpoint;
    }

    protected JsonNode call(HttpMethod method, String accessToken, boolean formContentType) {
        return ExternalCalls.execute(RestClientConfig.OAUTH, () -> {
            RestClient.RequestBodySpec spec = restClient.method(method)
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            if (formContentType) {
                spec.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded;charset=utf-8");
            }
            JsonNode body = ExternalCalls.mapErrors(RestClientConfig.OAUTH, spec.retrieve()).body(JsonNode.class);
            if (body == null) {
                throw new UpstreamBadResponseException(RestClientConfig.OAUTH, "빈 응답");
            }
            return body;
        });
    }
}

package com.example.Petbulance_BE.global.external.oauth;

import com.example.Petbulance_BE.global.common.error.exception.CustomException;
import com.example.Petbulance_BE.global.common.error.exception.ErrorCode;
import com.example.Petbulance_BE.global.external.ExternalFailures;
import com.example.Petbulance_BE.global.external.exception.UpstreamRejectedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 로그인/계정연동 서비스가 공유하는 진입점. 외부 오류를 사용자 응답(CustomException)으로 번역한다.
 *
 * <p>호출하는 쪽은 반드시 DB 트랜잭션 밖에서 이 메서드를 부른다.
 * 소셜 서버가 느릴 때 트랜잭션이 DB 커넥션을 쥔 채 대기하면 풀이 말라 다른 기능까지 멈추기 때문이다.
 */
@Slf4j
@Component
public class OAuthEmailResolver {

    private final Map<String, OAuthUserInfoClient> clients = new HashMap<>();

    public OAuthEmailResolver(List<OAuthUserInfoClient> clientList) {
        clientList.forEach(c -> clients.put(c.provider(), c));
    }

    public String resolveEmail(String provider, String accessToken) {
        OAuthUserInfoClient client = clients.get(provider);
        if (client == null) {
            throw new CustomException(ErrorCode.INVALID_PROVIDER);
        }

        String email;
        try {
            email = client.fetchEmail(accessToken);
        } catch (RuntimeException e) {
            if (ExternalFailures.isUnavailable(e)) {
                // 토큰/개인정보 없이 원인 종류만 남긴다
                log.warn("소셜 로그인 서버 일시 장애 provider={} cause={}", provider, e.getClass().getSimpleName());
                throw new CustomException(ErrorCode.SOCIAL_LOGIN_UNAVAILABLE);
            }
            if (e instanceof UpstreamRejectedException rejected) {
                log.warn("소셜 로그인 토큰 거부 provider={} status={}", provider, rejected.getStatus());
                throw new CustomException(client.loginFailureCode());
            }
            throw e;
        }

        // 이메일 미동의 시 빈 문자열로 사용자 매칭이 되면 서로 다른 사용자가 한 계정으로 합쳐진다
        if (email == null || email.isBlank()) {
            log.warn("소셜 이메일 없음 provider={}", provider);
            throw new CustomException(ErrorCode.SOCIAL_EMAIL_NOT_PROVIDED);
        }
        return email;
    }
}

package com.example.Petbulance_BE.global.external.oauth;

import com.example.Petbulance_BE.global.common.error.exception.ErrorCode;

/**
 * 소셜 로그인 업체의 사용자 정보 조회 클라이언트.
 * 업체마다 URL, HTTP 메서드, 응답 모양이 달라 구현체를 나누고(전략 패턴),
 * 업체별로 서킷브레이커/벌크헤드를 따로 둔다 (Kakao 장애가 Naver 로그인에 번지지 않게).
 */
public interface OAuthUserInfoClient {

    /** "KAKAO", "NAVER", "GOOGLE" */
    String provider();

    /** 토큰이 거부(401/403 등)됐을 때 사용자에게 돌려줄 오류 코드 */
    ErrorCode loginFailureCode();

    /**
     * 액세스 토큰으로 이메일을 조회한다. 이메일이 없으면 빈 문자열을 돌려줄 수 있다(검증은 호출하는 쪽).
     *
     * @throws com.example.Petbulance_BE.global.external.exception.ExternalApiException 외부 호출 실패
     */
    String fetchEmail(String accessToken);
}

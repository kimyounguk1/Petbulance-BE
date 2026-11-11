package com.example.Petbulance_BE.global.common.auth.service;

import com.example.Petbulance_BE.domain.dashboard.service.DashboardMetricRedisService;
import com.example.Petbulance_BE.domain.user.entity.Users;
import com.example.Petbulance_BE.domain.user.repository.UsersJpaRepository;
import com.example.Petbulance_BE.domain.userEmail.entity.UserEmails;
import com.example.Petbulance_BE.domain.userEmail.repository.UserEmailsJpaRepository;
import com.example.Petbulance_BE.domain.userSetting.entity.UserAuthority;
import com.example.Petbulance_BE.domain.userSetting.entity.UserSetting;
import com.example.Petbulance_BE.global.common.auth.component.RandomNicknameGenerator;
import com.example.Petbulance_BE.global.common.dto.LoginRequestDto;
import com.example.Petbulance_BE.global.common.dto.LoginResponseDto;
import com.example.Petbulance_BE.global.common.error.exception.CustomException;
import com.example.Petbulance_BE.global.common.error.exception.ErrorCode;
import com.example.Petbulance_BE.global.common.redisEntity.RefreshEntity;
import com.example.Petbulance_BE.global.common.redisRepository.RefreshTokenRepository;
import com.example.Petbulance_BE.global.common.type.Role;
import com.example.Petbulance_BE.global.external.oauth.OAuthEmailResolver;
import com.example.Petbulance_BE.global.firebase.FirebaseTokenService;
import com.example.Petbulance_BE.global.util.JWTUtil;
import com.google.firebase.auth.FirebaseAuthException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UsersJpaRepository usersJpaRepository;
    private final UserEmailsJpaRepository userEmailsJpaRepository;
    private final JWTUtil jwtUtil;
    private final RefreshTokenRepository refreshTokenRepository;
    private final FirebaseTokenService firebaseTokenService;
    private final OAuthEmailResolver oauthEmailResolver;
    private final TransactionTemplate transactionTemplate;
    private final RandomNicknameGenerator randomNicknameGenerator;
    private final DashboardMetricRedisService dashboardMetricRedisService;

    /**
     * 클래스 전체에 트랜잭션이 걸려 있어서, 예전에는 소셜 서버 호출(외부 HTTP)이 DB 커넥션을 쥔 채 진행됐다.
     * 소셜 서버가 느려지면 Hikari 풀이 말라 로그인과 무관한 기능까지 멈추므로 다음 순서로 나눈다.
     * <ol>
     *   <li>소셜 서버에서 이메일 조회 (트랜잭션 없음, 타임아웃/벌크헤드/서킷브레이커 적용)</li>
     *   <li>사용자 조회/생성 (짧은 트랜잭션)</li>
     *   <li>토큰 발급, Redis 저장, 가입 지표 (트랜잭션 없음, 커밋 이후)</li>
     * </ol>
     */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public LoginResponseDto loginService(LoginRequestDto dto) {
        String provider = dto.getProvider().toUpperCase();

        String email = oauthEmailResolver.resolveEmail(provider, dto.getAuthCode());

        Authenticated authenticated = transactionTemplate.execute(status -> authenticate(provider, email));

        if (authenticated.newUser()) {
            dashboardMetricRedisService.incrementTodaySignup();
        }

        LoginResponseDto response = createLoginResponse(authenticated.user(), provider);
        response.setIsNewUser(authenticated.newUser());
        return response;
    }

    private record Authenticated(Users user, boolean newUser) {
    }

    private Authenticated authenticate(String provider, String email) {
        final boolean[] isNewUser = {false};

        Users user = userEmailsJpaRepository.findByEmailByProvider(provider, email)
                .map(u -> {
                    switch (provider) {
                        case "KAKAO" -> { if (!u.getKakaoConnected()) u.setKakaoConnected(true); }
                        case "NAVER" -> { if (!u.getNaverConnected()) u.setNaverConnected(true); }
                        case "GOOGLE" -> { if (!u.getGoogleConnected()) u.setGoogleConnected(true); }
                    }
                    return u;
                })
                .orElseGet(() -> {
                    isNewUser[0] = true;
                    return createNewUser(provider, email);
                });

        return new Authenticated(user, isNewUser[0]);
    }

    private Users createNewUser(String provider, String email) {

        String nickname;
        do{
            nickname = randomNicknameGenerator.generateNickname();
        } while (usersJpaRepository.existsByNickname(nickname));

        UserSetting setting = UserSetting.builder()
                .totalPush(false)
                .eventPush(false)
                .marketingPush(false)
                .build();

        UserAuthority authority = UserAuthority.builder()
                .locationService(false)
                .marketing(false)
                .camera(false)
                .build();

        Users newUser = Users.builder()
                .nickname(nickname)
                .firstLogin(provider)
                .role(Role.ROLE_TEMPORAL)
                .profileImage("default_image/159833.png")
                .userSetting(new ArrayList<>(List.of(setting)))
                .createdAt(LocalDateTime.now())
                .userAuthority(authority)
                .build();

        setting.setUser(newUser);


        usersJpaRepository.save(newUser);

        UserEmails emails = UserEmails.builder()
                .user(newUser)
                .build();

        switch (provider) {
            case "KAKAO" -> emails.setKakaoEmail(email);
            case "NAVER" -> emails.setNaverEmail(email);
            case "GOOGLE" -> emails.setGoogleEmail(email);
        }

        userEmailsJpaRepository.save(emails);


        switch (provider) {
            case "KAKAO" -> newUser.setKakaoConnected(true);
            case "NAVER" -> newUser.setNaverConnected(true);
            case "GOOGLE" -> newUser.setGoogleConnected(true);
        }

        return newUser;
    }

    private LoginResponseDto createLoginResponse(Users user, String provider) {
        String userId = user.getId();
        String role = user.getRole().name();

        String access = jwtUtil.createJwt(userId, "access", role, provider);
        String refresh = jwtUtil.createJwt(userId, "refresh", role, provider);

        refreshTokenRepository.save(new RefreshEntity(userId, refresh, 8640000000L));

        String firebaseCustomToken;
        try {
            firebaseCustomToken = firebaseTokenService.createCustomToken(userId);
        } catch (FirebaseAuthException e) {
            throw new CustomException(ErrorCode.FirebaseToken_Fail);
        }

        return new LoginResponseDto(false, firebaseCustomToken, access, refresh);
    }
}

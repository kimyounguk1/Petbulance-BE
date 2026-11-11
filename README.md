# Petbulance-BE

특수동물 보호자를 위한 동물병원 탐색, 영수증 기반 리뷰, AI 응급 진단, 커뮤니티, 관리자 기능을 제공하는 Petbulance 백엔드 서버입니다.
팀 프로젝트에서 백엔드 개발자로 참여했으며, 병원 검색/매칭, 리뷰 도메인, 인증/권한, 관리자 기능, **외부 API 연동 설계**, 운영 모니터링 영역을 중심으로 구현과 리팩터링을 진행했습니다.

## 프로젝트 개요

Petbulance는 특수동물 보호자가 진료 가능한 병원을 찾고, 응급 상황을 빠르게 판단하고, 실제 진료 경험을 리뷰로 남길 수 있도록 돕는 서비스입니다.

- 위치 기반 동물병원 검색 및 매칭
- 영수증 이미지 기반 리뷰 작성 (AI 추출 → 지오코딩 → 병원 매칭)
- AI 응급 진단 (사진 + 증상, 스트리밍 응답)
- 커뮤니티 게시글, 댓글, 좋아요
- 소셜 로그인과 JWT 인증
- 관리자용 회원/병원/리뷰/공지/문의 관리
- FCM 알림, S3 이미지 업로드

## 담당 역할

| 영역 | 작업 내용 |
|---|---|
| 병원 도메인 | 병원 검색, 상세 조회, 카드 조회, 위치 기반 매칭 API 개선 |
| 리뷰 도메인 | 영수증 리뷰, 리뷰 검색/필터, 병원별 리뷰 커서 페이지네이션, 리뷰 좋아요 |
| 인증/권한 | JWT 만료 처리, 토큰 예외 처리, 소셜 로그인 연동 흐름 개선 |
| **외부 연동** | 소셜 로그인, VWorld, Claude 연동을 성격별로 재설계 (타임아웃, 재시도, 서킷브레이커, 동시 호출 제한, AI 스트리밍) |
| 관리자 기능 | 회원 제재, 병원 관리, 리뷰 활성/비활성, 약관 적용 기능 개선 |
| 운영 품질 | Actuator, Prometheus, Sentry 기반 관측 포인트 추가 |
| 테스트 | 리뷰 서비스 단위 테스트, WireMock 기반 외부 연동 장애 재현 테스트 |

## 외부 API 연동 설계

외부 API는 성격이 모두 달라서 같은 정책을 쓰면 안 된다고 보고, 연동마다 장애 대응 정책을 다르게 설계했습니다.

| 연동 | 성격 | 설계 |
|---|---|---|
| 소셜 로그인 (Kakao/Naver/Google) | 사용자가 기다리는 경로, 작은 응답 | 짧은 타임아웃(연결 1s/응답 3s), 재시도 없음, 업체별 서킷 격리, **외부 호출을 DB 트랜잭션 밖으로 분리** |
| VWorld 지오코딩 | 조회형, 같은 입력은 같은 결과 | Redis 캐시, 읽기 타임아웃 포함 1회 재시도, "주소 없음"과 장애 구분, 도로명/지번 보정 |
| Claude (AI 진단, 영수증 추출) | 느리고 호출마다 비용 발생 | SSE 스트리밍, JSON 스키마 출력 + 서버 재검증, 사용자 이탈 시 즉시 중단, 읽기 타임아웃 미재시도, 전용 스레드풀 |

### 공통 구조

```text
Adapter   (도메인 변환)  GeoCodingService, AiService, ReceiptAiExtractor, OAuthEmailResolver
Policy    (장애 대응)    Resilience4j: Retry → CircuitBreaker → Bulkhead
Transport (HTTP)        RestClient + Apache HttpClient5 (연동별 타임아웃, 연결 풀)
```

- **연결 풀 직접 구성**: HttpClient5 기본값(경로당 5개)은 동시 호출 상한보다 작아서, 경로당 연결 수를 그 서버를 쓰는 호출 합 이상으로 설정했습니다. 자동 재시도는 꺼서 재시도가 한 곳(Resilience4j)에서만 일어나게 했습니다.
- **오류를 타입으로 분류**: 연결 실패·5xx, 타임아웃, 429, 4xx, 본문 오류, AI 출력 문제로 나누고, 재시도 대상과 서킷 집계 대상을 이 타입으로 정했습니다.
- **스레드 점유 제한**: 벌크헤드와 AI 전용 스레드풀로 느린 외부 호출이 요청 스레드를 모두 차지하지 못하게 했습니다. 상한을 넘으면 대기 없이 즉시 503을 응답합니다.

### AI 진단 스트리밍

- `POST /ai/diagnosis/sse`: 사진(최대 3장)과 증상을 Claude에 한 번에 보내고, 응급도 → 의심 질환 → 응급처치 단계를 **완성되는 즉시** SSE 이벤트(`field`, `step`, `result`, `error`)로 보냅니다.
- 요청 스레드는 입력 검사 후 바로 반환되고, Claude 응답은 AI 전용 스레드에서 읽습니다.
- 사용자가 연결을 끊거나 "동물 사진 아님" 판정이 나오면 Claude 연결을 바로 끊어 토큰 낭비를 막습니다.
- 출력은 JSON 스키마로 형식을 고정하고, 응급도·신뢰도·단계 같은 값은 서버에서 다시 검증합니다.
- 기존 `POST /ai/diagnosis`(JSON 한 번에 반환)도 같은 로직으로 유지합니다.

### 검증

WireMock으로 외부 서버 장애를 재현해 다음을 테스트합니다.

- 타임아웃 분류, 재시도 횟수, HttpClient5 자동 재시도가 꺼져 있는지
- 서킷 열림 후 상대를 호출하지 않는지, 업체별 서킷이 독립적인지
- 동시 호출 상한을 넘으면 즉시 거절되는지
- 스트림 조각 수신, 스트림 중간 오류, 연결을 끊으면 남은 응답을 기다리지 않는지

## 기술 스택

| 영역 | 기술 |
|---|---|
| Backend | Java 17, Spring Boot 3.5, Spring Web, Spring Security |
| Persistence | Spring Data JPA, QueryDSL, MySQL, H2 Test DB |
| Cache | Redis |
| Auth | JWT, OAuth2 Client, Kakao/Naver/Google Login |
| External / Resilience | RestClient, Apache HttpClient5, Resilience4j |
| AI | Claude API (Messages API, SSE 스트리밍, 구조화 출력) |
| Geo | VWorld API, Hibernate Spatial, JTS, Lucene Spatial Extras |
| File / Cloud | AWS S3, AWS Lambda SDK |
| Notification | Firebase Admin SDK, FCM |
| Test | JUnit 5, Mockito, WireMock |
| Docs / Ops | Springdoc OpenAPI, Actuator, Prometheus, Sentry |
| Deployment | Docker Compose, Blue/Green compose files |

## 주요 기능

### 병원 검색 및 매칭

- 병원 목록 검색: `GET /hospitals`
- 병원 상세 조회: `GET /hospitals/{hospitalId}`
- 병원 카드 조회: `GET /hospitals/card/{hospitalId}`
- 위치 기반 병원 매칭: `GET /hospitals/matching`
- 병원 상세 매칭 정보: `GET /hospitals/{hospitalId}/matching`

### 영수증 리뷰

- 영수증 이미지 인식: `POST /receipts` (Claude 추출 → VWorld 지오코딩 → 가까운 병원 매칭)
- 병원명 검색: `GET /receipts/{hospitalName}`
- 리뷰 검색/필터: `GET /receipts/search/{value}`, `GET /receipts/filter`
- 병원별 리뷰 커서 페이지네이션: `GET /receipts/reviews/{hospitalId}`
- 리뷰 작성/수정/삭제, 좋아요/취소, 내 리뷰 조회

### AI 응급 진단

- 스트리밍 진단: `POST /ai/diagnosis/sse`
- 일반 진단(JSON): `POST /ai/diagnosis`

### 인증과 사용자 기능

- JWT access token 발급/검증, refresh token Redis 저장
- Kakao, Naver, Google 로그인, 소셜 계정 연결/해제
- 닉네임, 프로필, 알림 설정, 회원 탈퇴

### 커뮤니티 / 관리자

- 게시글, 댓글, 좋아요, 검색, 신고
- 회원·병원·리뷰·공지·문의 관리, 대시보드 지표

## 프로젝트 구조

```text
src/main/java/com/example/Petbulance_BE
├── global
│   ├── common          # 공통 응답, 예외, S3, 인증 공통 로직
│   ├── config          # Redis, QueryDSL 설정
│   ├── external        # 외부 연동 (HTTP 클라이언트 구성, 오류 분류)
│   │   ├── claude      # Claude 클라이언트, SSE 스트림, 스트리밍 JSON 파서
│   │   ├── oauth       # 소셜 로그인 업체별 사용자 조회
│   │   ├── vworld      # VWorld 지오코딩, 캐시
│   │   └── exception   # 외부 오류 분류
│   ├── filter          # JWT / Logout filter
│   ├── firebase        # FCM 설정 및 발송
│   ├── security        # Spring Security 설정
│   └── util
└── domain
    ├── admin / ai / hospital / review / post / comment / user / notification / dashboard

src/main/resources
├── application-external.yml   # 외부 연동 설정 (타임아웃, 재시도, 서킷브레이커 등, 비밀값 없음)
└── ai/                        # AI 프롬프트와 출력 JSON 스키마
```

## 실행 방법

### 사전 요구사항

- Java 17, MySQL, Redis
- 환경변수 `ANTHROPIC_API_KEY` (AI 진단, 영수증 인식)
- 로컬 `src/main/resources/application.yml` (DB, OAuth 등 비밀값 포함, 저장소 미포함)에 아래 두 가지를 추가합니다.

```yaml
spring.config.import: optional:classpath:application-external.yml

external:
  clients:
    vworld:
      api-key: ${VWORLD_API_KEY}
```

### 로컬 실행 / 테스트

```bash
./gradlew bootRun
./gradlew test
```

### 배포 compose

```bash
docker compose -f docker-compose-blue.yml up -d
docker compose -f docker-compose-green.yml up -d
```

## 운영 관점

- Actuator와 Prometheus로 애플리케이션 상태를 수집합니다.
- `/actuator/circuitbreakers`, `/actuator/bulkheads`로 외부 연동별 서킷과 동시 호출 상태를 확인합니다.
- AI 토큰 사용량을 `ai.tokens` 지표(기능별, 입력/출력)로 기록합니다.
- Sentry로 런타임 예외를 추적합니다.
- Blue/Green compose 파일을 분리해 배포 전환 구조를 구성했습니다.

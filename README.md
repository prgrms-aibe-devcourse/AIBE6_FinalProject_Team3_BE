# 알고계약 (algogyeyak) — Backend

사회초년생과 대학생을 위한 부동산 계약 안전 확인 서비스의 API 서버입니다.

## Stack

- Java 21
- Spring Boot 4.1.0 (Gradle Kotlin DSL, wrapper Gradle 9.5.1)
- Spring Web MVC
- Spring Data JPA — H2(로컬/dev), MySQL(runtime)
- Spring Security + OAuth2 Client + JWT (jjwt)
- springdoc-openapi (Swagger UI)
- Spring Boot Actuator + Micrometer(Prometheus) — `/actuator/prometheus`는 `MetricsScrapeTokenFilter`로 공유 토큰 검사, `/actuator/health` 상세는 `ADMIN` role만 조회 가능
- Lombok

## Architecture

- **웹 레이어**: `spring-boot-starter-webmvc`(서블릿 MVC, WebFlux 아님). 컨트롤러는 `@RestController` + 공통 응답 포맷(`ApiResponse`)을 사용합니다.
- **영속성**: `spring-boot-starter-data-jpa`. 로컬/dev/test는 인메모리 H2(`spring-boot-h2console`)로 기동되고, 운영은 MySQL(`mysql-connector-j`, RDS)입니다 — 자세한 연결 방식은 아래 "Getting Started"와 "Deployment" 참고. HikariCP `maximum-pool-size`는 20(RDS `max_connections=60` 기준, 인스턴스 1대 운영 가정).
- **Redis**: `spring-boot-starter-data-redis`(Lettuce). 1) auth의 access token 블랙리스트/refresh token 저장(`AccessTokenRevocationService`/`RefreshTokenService`) — 장애 시 fail-closed로 로그인/토큰 재발급을 거부합니다. 2) market-data 시세비교 캐시(`RedisCacheConfig`, `marketComparison`/`marketSaleComparison`) — 장애 시 fail-open으로 원본 로직을 그대로 탑니다. 실제 Redis가 필요한 테스트는 Testcontainers(`redis:7-alpine`)를 쓰며 로컬에 Docker가 필요합니다.
- **인증**: OAuth2 로그인(구글/카카오)은 `spring-boot-starter-security` + `-security-oauth2-client`, JWT access token 발급/검증은 `jjwt`(0.12.6). Stateless — HTTP 세션 없이 OAuth2 인가 요청은 쿠키에 저장합니다(`com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository`). 전체 배선은 `com.algogyeyak.auth.config.SecurityConfig` 참고 — `/admin/**`은 `hasRole("ADMIN")`, 회원가입/로그인/토큰재발급/이메일인증/비밀번호재설정 등 일부 API는 `permitAll`, 나머지는 `anyRequest().authenticated()`입니다. 각 API의 인증 요구사항은 아래 "Domains" 섹션에 정리했습니다.
- **API 문서**: `springdoc-openapi-starter-webmvc-ui` — 아래 "API 문서" 섹션 참고.
- **관측성**: `spring-boot-starter-actuator` + `micrometer-registry-prometheus`. `/actuator/prometheus`는 `MetricsScrapeTokenFilter`로 공유 토큰을 검사하고, `/actuator/health` 상세 정보는 `ADMIN` role만 조회할 수 있습니다. `RequestIdLoggingFilter`가 요청마다 상관관계 ID를 MDC에 심어 로그 라인에 붙입니다.
- **Lombok**: `compileOnly` + `annotationProcessor`(테스트 소스에도 동일하게 적용)로 활성화 — 엔티티/DTO에 사용합니다.
- **Config profiles**: 아래 "Config profiles" 섹션 참고.

## Getting Started

```bash
./gradlew.bat bootRun
```

`application.yml`/`application-{dev,test}.yml`에는 별도 데이터소스 설정이 없어 로컬은 기본적으로 인메모리 H2로 기동됩니다. 운영(RDS/MySQL) 연결은 `application-{profile}.yml`이 아니라 `docker-compose.yml`(base)에 `SPRING_DATASOURCE_URL: jdbc:mysql://${RDS_ENDPOINT}:3306/${DB_NAME}` 형태로 구성되어 있고, 배포 워크플로우(`.github/workflows/deploy.yml`)가 `docker-compose.yml -f docker-compose.prod.yml -f docker-compose.monitoring.yml`로 함께 띄웁니다 — `RDS_ENDPOINT`/`DB_NAME`/`DB_USERNAME`/`DB_PASSWORD`는 EC2의 `.env`에서 채워집니다.

Redis는 두 가지 용도로 쓰입니다. 1) **access token blacklist/refresh token 저장소**(`com.algogyeyak.auth`)는 이미 실제로 Redis를 사용하며, Redis가 없으면 로그인/토큰 재발급이 fail-closed로 거부됩니다. 2) **market-data 시세비교 캐싱**(`MarketComparisonService`/`MarketSaleComparisonService`, `marketComparison`/`marketSaleComparison` 캐시)도 `RedisCacheConfig`에 실제로 붙어 있습니다 — TTL은 `market-data.comparison.cache-ttl-minutes`(기본 30분)이며, 캐시는 성능 최적화 목적이라 Redis 장애 시 fail-open으로 원본 로직을 그대로 탑니다(1의 fail-closed와 반대).

로컬에서 Redis를 띄우려면 `docker compose up -d redis`를 실행하면 됩니다(`spring.data.redis.host`/`port`가 기본값 `localhost:6379`를 가리키고 있어 별도 설정 없이 바로 연결됩니다). Redis 없이도 `bootRun` 자체는 되지만, 로그인/토큰 재발급은 fail-closed라 실제로는 못 씁니다.

구글/카카오 로그인을 실제로 붙여보려면 `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`/`KAKAO_CLIENT_ID`/`KAKAO_CLIENT_SECRET`을 채워야 합니다 — 안 채우면 더미 값으로 기동은 되지만 소셜 로그인만 실패합니다(이메일/비밀번호 로그인은 영향 없음). `.env.example`을 `backend/.env`로 복사해 값을 채우면 `bootRun` 시 자동으로 읽힙니다(별도 export 불필요, BOM 없는 UTF-8로 저장할 것).

### 환경변수

로컬 개발에서 자주 필요한 핵심 환경변수입니다(전부 `.env.example`에 기본값/더미값과 함께 있음).

| 환경변수                                       | 설명                                                                                                                                                                                                         |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`    | Google Cloud Console에서 발급                                                                                                                                                                                |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET`      | Kakao Developers에서 발급                                                                                                                                                                                    |
| `JWT_SECRET`                                   | HS256 서명용 시크릿 (최소 32바이트 랜덤 문자열)                                                                                                                                                              |
| `OAUTH2_STATE_SIGNING_KEY`                     | OAuth2 인가 요청을 담는 쿠키(`oauth2_auth_request`)의 HMAC 서명 키 (최소 32바이트 랜덤 문자열, `JWT_SECRET`과는 다른 값 권장)                                                                                |
| `OAUTH2_REDIRECT_URI`                          | 로그인 성공 후 리다이렉트할 프론트엔드 콜백 URL (dev 기본값 `http://localhost:3000/oauth/callback`, prod는 기본값 없이 fail-fast — 빠뜨리면 로그인 성공 후 localhost로 조용히 리다이렉트되는 것을 막기 위함) |
| `CORS_ALLOWED_ORIGINS`                         | 허용할 프론트엔드 origin (기본값 `http://localhost:3000`)                                                                                                                                                    |
| `COOKIE_SECURE`                                | `access_token` 등 쿠키의 Secure 속성 (dev 기본값 `false`, prod 기본값 `true`)                                                                                                                                |
| `COOKIE_SAME_SITE`                             | 쿠키의 SameSite 속성 (dev 기본값 `Lax`, prod는 기본값 없이 fail-fast — 배포 시나리오에 맞게 반드시 명시. 아래 Deployment 섹션 참고)                                                                          |
| `COOKIE_DOMAIN`                                | 쿠키의 Domain 속성 — 커스텀 서브도메인 배포 시에만 `.example.com`처럼 지정 (dev/prod 기본값 모두 비어있음=host-only)                                                                                         |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | access token blacklist/refresh token 저장소용 Redis 연결 정보 (dev 기본값 `localhost:6379`/비밀번호 없음, prod는 기본값 없이 fail-fast)                                                                      |
| `RDS_ENDPOINT` / `DB_NAME` / `DB_USERNAME` / `DB_PASSWORD` | 운영 MySQL(RDS) 접속 정보 — `docker-compose.yml`이 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`로 주입(로컬 `bootRun`에는 영향 없음, EC2의 `.env`에서만 채움)                                          |
| `MAIL_HOST`/`MAIL_PORT`/`MAIL_USERNAME`/`MAIL_PASSWORD`/`MAIL_FROM` | 이메일 인증(회원가입)/비밀번호 재설정 메일 발송용 SMTP 계정 (안 채우면 기동은 되나 실제 발송 시점에 실패)                                                                                     |
| `CLOVA_OCR_INVOKE_URL` / `CLOVA_OCR_SECRET_KEY` | contract-analysis OCR용 Naver Clova OCR 연동 정보                                                                                                                                                          |
| `GEMINI_API_KEY`                               | contract-analysis AI 계약 분석/챗봇용 Gemini API 키                                                                                                                                                          |
| `KAKAO_REST_API_KEY`                           | 카카오 로컬 API(주소검색, 좌표→법정동코드) — 매물 등록/시세비교에 필수                                                                                                                                       |
| `MOLIT_SERVICE_KEY`                            | 국토교통부 실거래가 API(공공데이터포털) 키. 없어도 매물 등록/조회는 되고 시세비교만 항상 UNAVAILABLE로 나옴                                                                                                  |
| `AWS_S3_BUCKET` / `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` | 프로필/매물 이미지, contract-analysis 이미지 업로드용 S3 접속 정보                                                                                                                                     |
| `DEV_LOGIN_ENABLED` / `DEV_LOGIN_SECRET`       | `POST /auth/dev-login`(개발용 원클릭 로그인) 스위치와 공유 비밀값 (기본 `false`; prod는 `true`로 켜려면 secret 필수)                                                                                        |
| `METRICS_SCRAPE_TOKEN`                         | `/actuator/prometheus` 스크래핑 전용 공유 비밀(`MetricsScrapeTokenFilter`) — Prometheus의 `bearer_token_file`과 값이 같아야 함                                                                              |

Redis fail-closed 동작, `.env` 인식 경로(working directory) 문제, 비밀번호 정책 등 더 상세한 로컬 개발 트러블슈팅은 [`CURRENT_STATE.md`](./CURRENT_STATE.md)의 "로컬 개발 환경" 섹션을 참고하세요.

## Scripts

```bash
./gradlew.bat build      # 빌드
./gradlew.bat bootRun    # 로컬 실행
./gradlew.bat test       # 전체 테스트

# 단일 테스트 클래스
./gradlew.bat test --tests "com.algogyeyak.AlgogyeyakApplicationTests"

# 단일 테스트 메서드
./gradlew.bat test --tests "com.algogyeyak.AlgogyeyakApplicationTests.contextLoads"
```

Windows 환경이므로 `gradlew.bat`을 사용합니다.

## Testing

### 단위/통합 테스트

`src/test/java`에 도메인별 패키지(`auth`/`user`/`property`/`checklist`/`marketdata`/`riskanalysis`/`contractanalysis`/`admin`/`global`)로 나뉜 테스트 클래스가 100개 이상 있습니다. 대부분은 Mockito 기반 순수 단위 테스트이고, 실제 Redis가 있어야만 검증 가능한 동작은 Testcontainers(`org.testcontainers:testcontainers-bom:1.20.4`)로 실제 Redis 컨테이너를 띄워 검증합니다 — `@Testcontainers`를 쓰는 클래스는 3개입니다: `RefreshTokenServiceRedisIntegrationTest`(`RefreshTokenService`의 Lua 스크립트 원자성/TTL 자연만료 확인), `AuthControllerTest`, `SecurityRoleEnforcementIntegrationTest`(`@SpringBootTest`로 전체 시큐리티 설정을 띄워 역할별 접근 제어 확인) — 로컬에 Docker가 필요합니다. `riskanalysis`의 `RepeatableReadVisibilityExperimentTest`는 Testcontainers 없이 H2에서 isolation을 명시적으로 `REPEATABLE_READ`로 강제해(H2 기본값은 `READ_COMMITTED`) 운영 MySQL의 기본 격리수준과 같은 조건에서 트랜잭션 스냅샷 가시성을 재현하고, `FakeListingSignalServiceConcurrencyTest`도 Redis 없이 mock으로 동시 요청 시나리오를 검증합니다.

### k6 부하 테스트 (`k6/`)

실제 서버(로컬 또는 `-e BASE_URL=...`로 지정한 배포 서버)를 대상으로 도는 시나리오 스크립트입니다. 로그인은 `k6/common/auth.js`가 공용으로 처리하고, `CSRF_HEADERS`(`k6/common/config.js`)로 `CsrfHeaderFilter`가 요구하는 헤더를 붙입니다.

| 파일 | 목적 |
| --- | --- |
| `01-read-endpoints.js` | 스모크 테스트 — VU 20명, 30초 동안 주요 조회 엔드포인트 조합이 정상 동작하는지 빠르게 확인(관리자 전용 항목은 관리자 계정 정보를 넘겨줬을 때만 함께 확인) |
| `02-auth-session.js` | 인증/세션 동시성 테스트 — 동시 refresh 요청 중 정확히 1건만 성공하는지(`RefreshTokenService.rotate()`의 원자성), `JwtAuthenticationFilter`의 Redis 블랙리스트 조회 오버헤드, 재로그인 시 이전 refresh token 무효화를 시간대를 나눠 확인 |
| `03-race-conditions.js` | 동시성 경쟁 조건 테스트 — 체크리스트 생성(`createOrGetChecklist`) 동시 요청 시 `REQUIRES_NEW`+예외 복구 패턴이 항상 200/201로 정상 처리되는지 확인(VU 20명, HikariCP 풀 용량 고려) |
| `04-load-baseline.js` | 베이스라인 부하 테스트 — 예상 실사용 트래픽(VU 60명)을 5분 유지했을 때 응답시간(p95<500ms)/에러율(<1%)이 정상 범위인지 확인 |
| `05-stress.js` | 스트레스 테스트 — VU를 100→200→300→400까지 단계적으로 올려 t3.small 인스턴스에서 언제부터 성능이 무너지는지(HikariCP 풀, JVM 힙, Tomcat 기본 최대 스레드 200 등) 관찰 |
| `06-soak.js` | 소크(내구성) 테스트 — 중간 부하(VU 150명)를 기본 30분(`-e SOAK_DURATION=2h` 등으로 조절 가능) 유지해 커넥션/메모리 누수나 시간에 따른 응답시간 저하를 확인 |
| `07-spike.js` | 스파이크 테스트 — 평소(VU 10명)에서 순간적으로 500명까지 튀었다가 다시 10명으로 돌아왔을 때, 스파이크 구간 내구성과 종료 후 회복 여부를 확인 |

## API 문서

로컬에서 `bootRun` 후 `http://localhost:8080/swagger-ui/index.html`에서 Swagger UI로 전체 API를 확인할 수 있습니다(`springdoc-openapi`, 커스텀 경로 설정 없이 기본값 그대로). 운영에서는 `application-prod.yml`이 `springdoc.api-docs.enabled`/`springdoc.swagger-ui.enabled`를 둘 다 `false`로 꺼서 엔드포인트 구조가 노출되지 않습니다.

## Config profiles

`application.yml` + `application-{dev,prod,test}.yml` 4개 파일이 있습니다. 공통 설정(OAuth2 client id/secret, JWT, CORS, 쿠키, dev-login 등)은 `application.yml`(기본 프로필)에 이미 채워져 있고, `application-prod.yml`은 운영 전용 오버라이드(H2 콘솔/Swagger 비활성화, dummy 기본값 제거로 fail-fast, `DEV_LOGIN_ENABLED`를 환경변수와 무관하게 고정 false 등)를 담고 있습니다. `application-dev.yml`/`application-test.yml`은 아직 `spring.application.name`만 설정된 상태입니다.

## 알아둘 점

패키지 컨벤션은 `com.algogyeyak`(`AlgogyeyakApplication.java`)로 확정되었습니다. Gradle `group`(`com.ll`)과는 무관하니 새 클래스는 전부 `com.algogyeyak` 하위에 작성하세요.

## Current state

8개 도메인(auth/user/property/checklist/market-data/risk-analysis/contract-analysis/admin)이 구현되어 있으며, 진행 정도는 도메인마다 다릅니다 — auth/market-data/risk-analysis/contract-analysis는 거의 완전 구현이고, user/property/admin은 부분 구현입니다. 자세한 남은 이슈·트러블슈팅은 [`CURRENT_STATE.md`](./CURRENT_STATE.md)와 각 `docs/specs/{도메인}-design.md`를 참고하세요.

## Domains

아래 엔드포인트 목록은 각 도메인 컨트롤러(`*Controller.java`)를 직접 읽어 정리했습니다. "인증"은 `SecurityConfig`의 `permitAll`/`hasRole("ADMIN")`/`anyRequest().authenticated()` 배선 기준입니다 — 별다른 표시가 없으면 로그인(access token) 필요입니다.

### auth — 구글/카카오 OAuth2 + 이메일/비밀번호 로그인, Redis 기반 Access/Refresh Token, 로그아웃 시 즉시 무효화(jti 블랙리스트)까지 구현됨. → [`auth-design.md`](./docs/specs/auth-design.md)

`AuthController` (`/auth`):
- `GET /auth/password-policy` — 인증 불필요
- `POST /auth/email-verification/request`, `POST /auth/email-verification/confirm` — 인증 불필요(회원가입 전)
- `POST /auth/password-reset/request`, `POST /auth/password-reset/confirm` — 인증 불필요
- `POST /auth/signup` — 인증 불필요, 성공 시 access/refresh 쿠키 즉시 발급
- `POST /auth/login` — 인증 불필요
- `GET /auth/me` — 인증 필요
- `PATCH /auth/password` — 인증 필요(비밀번호 설정/변경)
- `POST /auth/dev-login` — 인증 불필요(개발용, `DEV_LOGIN_ENABLED`+헤더 공유 비밀 필요, `@Hidden`으로 Swagger 미노출)
- `POST /auth/logout` — 인증 불필요(refresh/access 쿠키가 있으면 서버가 각각 무효화)
- `POST /auth/refresh` — 인증 불필요(refresh token 쿠키 자체로 검증)

### user — 프로필 등록/조회/수정, 닉네임 중복확인, S3 presign 방식 프로필 이미지 업로드/삭제, 탈퇴 시 연관 데이터 정리까지 구현됨. → [`user-design.md`](./docs/specs/user-design.md)

`UserController` (`/users`):
- `GET /users/nickname-policy`, `GET /users/nickname-check` — 인증 불필요
- `GET /users/me` — 인증 필요
- `POST /users/me/profile` — 인증 필요(프로필 등록)
- `POST /users/me/profile-image/presign`, `POST /users/me/profile-image/confirm`, `DELETE /users/me/profile-image` — 인증 필요
- `PATCH /users/me` — 인증 필요(프로필 수정)
- `DELETE /users/me` — 인증 필요(회원 탈퇴)
- `GET /users/me/contract-history`, `GET /users/me/contract-history/{id}`, `DELETE /users/me/contract-history/{id}` — 인증 필요(contract-analysis 히스토리 조회/삭제 — `ContractAnalysisHistoryService`를 여기서 호출)

### property — 매물 CRUD, 지역/면적/가격/거래유형 검색, 매물 신고, market-data 시세비교·체크리스트 진행률 연동까지 구현됨. 매매(SALE)는 아직 미지원(전월세만). → [`property-design.md`](./docs/specs/property-design.md)

`PropertyController` (`/properties`, 전부 인증 필요, 본인 소유 매물만 대상):
- `POST /properties` — 등록
- `GET /properties` — 본인 소유 매물 목록(지역/면적/가격/거래유형/신호 유무 검색 + 페이지네이션)
- `GET /properties/{propertyId}` — 상세
- `PATCH /properties/{propertyId}` — 수정(가격/면적/설명만)
- `DELETE /properties/{propertyId}` — 삭제(soft delete)

`PropertyImageUploadController` (`/properties/images`, 인증 필요):
- `POST /properties/images/upload-url` — presigned URL 발급
- `POST /properties/images/confirm` — 업로드 확정

`PropertyReportController` (인증 필요):
- `POST /properties/{propertyId}/reports` — 매물 신고

### market-data — 국토부 실거래가 API 연동, 반경 기반(300m→600m) 시세비교, Redis 캐싱(TTL 30분)까지 구현됨. → [`market-data-design.md`](./docs/specs/market-data-design.md)

별도 REST 컨트롤러가 없는 내부 서비스 도메인입니다 — `MarketComparisonService`/`MarketSaleComparisonService`를 property/risk-analysis가 직접 호출해서 씁니다(공개 엔드포인트 없음).

### checklist — 체크리스트 생성/조회/항목 확인/결과 확인, 관리자용 문항 템플릿 CRUD, 목록 페이지네이션까지 구현됨. → [`checklist-design.md`](./docs/specs/checklist-design.md)

`ChecklistController` (인증 필요):
- `POST /properties/{propertyId}/checklists` — 생성(이미 있으면 기존 반환, 멱등)
- `GET /properties/{propertyId}/checklists` — 문항 포함 조회
- `PATCH /checklists/{checklistId}/items/{itemId}` — 항목 하나 갱신
- `GET /checklists/{checklistId}/result` — 결과(완료도/누락/주의 항목 수) 조회
- `GET /checklists` — 내 매물 전체 체크리스트 현황 페이지네이션 조회

### risk-analysis — 허위매물 의심 신호 탐지 4종, 전세가율 계산, market-data 연동, 매물 수정 시 자동 재계산까지 구현됨. → [`risk-analysis-design.md`](./docs/specs/risk-analysis-design.md)

`RiskAnalysisController` (인증 필요):
- `POST /properties/{propertyId}/risk-analysis` — 신호 판정·저장 + 요약 반환(멱등, upsert)
- `GET /properties/{propertyId}/risk-signals` — 신호 4종 현재 상태 조회

`DepositSafetyController` (인증 필요):
- `GET /properties/{propertyId}/deposit-safety` — 전세가율(보증금 안전성) 조회
- `POST /properties/{propertyId}/deposit-safety/recalculate` — 선순위보증금 입력값으로 정밀 재계산

### contract-analysis — 입력(이미지/텍스트) → Clova OCR → 개인정보 마스킹 → AI 계약 분석(Gemini) → 챗봇 → 히스토리 저장까지 파이프라인 전체가 동작함. 매물 소유권 검증 연결만 TODO로 남음. → [`contract-analysis-design.md`](./docs/specs/contract-analysis-design.md)

`ContractAnalysisController` (`/contract-analysis`, 전부 인증 필요):
- `POST /contract-analysis/inputs` — 입력 등록(이미지/텍스트, `multipart/form-data`)
- `POST /contract-analysis/ocr` — Clova OCR 텍스트 인식
- `POST /contract-analysis/masking` — 개인정보(전화번호/주민번호/계좌/성명) 마스킹
- `POST /contract-analysis/analyze` — Gemini 기반 AI 계약 분석(`GeminiRateLimiterService`로 분당/일 한도 선체크)
- `POST /contract-analysis/chat` — 분석 결과 기반 챗봇(동일하게 rate limit 적용)

히스토리 조회/삭제 엔드포인트(`/users/me/contract-history*`)는 `UserController`에 있습니다 — 위 "user" 항목 참고.

### admin — 별도 도메인 패키지 없이 기능별로 분산(대시보드 통계/유저 관리/매물 신고 검토/문항 템플릿 CRUD), 전부 `/admin/**` + `ROLE_ADMIN`. → [`admin-design.md`](./docs/specs/admin-design.md)

`AdminStatsController` (`/admin/stats`):
- `GET /admin/stats/dashboard` — 기간별 대시보드 통계(기간 생략 시 최근 14일)

`AdminUserController` (`/admin/users`):
- `GET /admin/users` — 유저 목록(이메일/닉네임/권한/상태 검색 + 페이지네이션)
- `PATCH /admin/users/{userId}/role` — 권한 변경
- `PATCH /admin/users/{userId}/status` — 상태 변경
- `PATCH /admin/users/bulk-status` — 상태 일괄 변경

`AdminPropertyReportController` (`/admin/property-reports`):
- `GET /admin/property-reports` — 매물 신고 목록
- `GET /admin/property-reports/{reportId}` — 신고 상세
- `PATCH /admin/property-reports/{reportId}/review` — 신고 검토(RESOLVED/REJECTED)
- `PATCH /admin/property-reports/bulk-review` — 신고 일괄 검토

`AdminChecklistTemplateController` (`/admin/checklist-templates`):
- `GET /admin/checklist-templates` — 문항 템플릿 목록
- `POST /admin/checklist-templates` — 문항 템플릿 생성
- `PATCH /admin/checklist-templates/{templateId}` — 문항 템플릿 수정
- `DELETE /admin/checklist-templates/{templateId}` — 문항 템플릿 삭제
- `GET /admin/checklist-templates/{templateId}/images`, `POST /admin/checklist-templates/{templateId}/images`, `DELETE /admin/checklist-templates/{templateId}/images/{imageId}` — 문항 예시 이미지 관리(URL만 등록, 파일 업로드는 미지원)

## Deployment

### 배포 파이프라인

`main` 브랜치에 push되면(또는 `workflow_dispatch`로 수동 실행하면) `.github/workflows/deploy.yml`이 실행됩니다.
1. `test` job — `./gradlew test`로 전체 테스트를 통과해야 다음 job으로 진행됩니다.
2. `deploy` job — Docker 이미지를 빌드해 GHCR(`ghcr.io/<repository_owner>/algogyeyak-backend`)에 `latest`와 커밋 SHA 두 태그로 push합니다.
3. `appleboy/ssh-action`으로 EC2에 접속해: GHCR 재로그인 → (커밋되지 않는) `monitoring/metrics_scrape_token` 되돌리기 → `git pull origin main` → `algogyeyak-network`(external) 없으면 생성 → `docker compose -f docker-compose.yml -f docker-compose.prod.yml -f docker-compose.monitoring.yml pull backend && up -d` → 안 쓰는 이미지 정리(`docker image prune -af`) → `.env`의 `METRICS_SCRAPE_TOKEN`으로 `monitoring/metrics_scrape_token` 덮어쓰기 → `nginx -s reload`(NPM이 backend 컨테이너의 새 내부 IP를 다시 잡도록)까지 수행합니다.

`.github/workflows/test-ghcr-push.yml`은 위 2번(GHCR push)만 `workflow_dispatch`로 수동 실행해볼 수 있는 테스트용 워크플로우입니다 — EC2 배포 단계는 없습니다.

Docker Compose 파일은 역할이 나뉘어 있고, 배포 시 여러 개를 `-f`로 함께 지정해 합성합니다:

- **`docker-compose.yml`(base)** — Redis(`requirepass` 필수)와 backend 컨테이너를 정의합니다. backend는 `.env`를 `env_file`로 읽고, `SPRING_PROFILES_ACTIVE=prod`, RDS(MySQL) 접속 정보(`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, `${RDS_ENDPOINT}`/`${DB_NAME}`/`${DB_USERNAME}`/`${DB_PASSWORD}`로 조합), Redis 접속 정보, JVM 힙 옵션(`-Xms512m -Xmx768m`)을 환경변수로 주입합니다.
- **`docker-compose.prod.yml`** — backend에 `SERVER_FORWARD_HEADERS_STRATEGY=framework`를 추가하고, nginx-proxy-manager(리버스 프록시/TLS 종료) 컨테이너를 함께 띄웁니다.
- **`docker-compose.monitoring.yml`** — Prometheus(`monitoring/prometheus.yml` 설정으로 `/actuator/prometheus`를 15초 간격 스크래핑, bearer token은 `monitoring/metrics_scrape_token` 파일, 7일 보관), Grafana(`GRAFANA_ADMIN_PASSWORD` 필수), InfluxDB(k6 부하테스트 결과 저장용, `127.0.0.1:8086`만 노출)를 띄웁니다. Prometheus/Grafana는 기본적으로 포트를 외부에 노출하지 않고 내부 네트워크 + nginx-proxy-manager를 통해서만 접근합니다.
- **`docker-compose.override.yml`** — 로컬 전용 오버라이드입니다(자동 병합). Redis 포트를 로컬에 노출하고, `full-container` 프로필로 backend까지 컨테이너로 띄울 때는 H2 인메모리(`jdbc:h2:mem:testdb`)로 접속하도록 지정합니다.
- **`docker-compose.monitoring.override.yml`** — 로컬에서 Grafana/Prometheus에 브라우저로 직접 접속해볼 때만 쓰는 포트 노출용 오버라이드입니다. 파일명이 Compose가 자동으로 얹어주는 이름(`docker-compose.override.yml`)과 다르기 때문에 `-f`로 명시해야만 적용됩니다.

### 운영 배포 시 쿠키 설정 (`COOKIE_SECURE` / `COOKIE_SAME_SITE` / `COOKIE_DOMAIN`)

프론트(Vercel)와 백엔드(EC2)가 배포에서 브라우저 기준 "같은 site"로 보이는지에 따라 값이 완전히 달라집니다. 배포 도메인 전략이 정해지면 아래 표에서 해당하는 행의 값으로 설정하세요.

| 시나리오                                                                           | `COOKIE_SECURE` | `COOKIE_SAME_SITE` | `COOKIE_DOMAIN`                   | 비고                                                                                                                                                                                                                                                |
| ---------------------------------------------------------------------------------- | --------------- | ------------------ | --------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **A. 커스텀 서브도메인** (`app.example.com` + `api.example.com`, 같은 등록 도메인) | `true`          | `Lax` (기본값)     | `.example.com`                    | 지금 코드가 이 시나리오를 전제로 만들어져 있음 — 로컬의 `.localhost` 설정과 동일한 구조                                                                                                                                                             |
| **B. Vercel rewrite** (`/api/*`를 EC2로 프록시, 브라우저는 Vercel origin만 봄)     | `true`          | `Lax` (기본값)     | 비워둠(host-only)                 | 브라우저 입장에서 이미 같은 origin이라 `Domain` 지정이 필요 없음. 단, `proxy.ts`/`oauth/callback`이 지금처럼 EC2를 직접 부르는 구조라 rewrite에 맞춰 손봐야 함 (검토 예정)                                                                          |
| **C. 도메인 공유 없음** (완전히 별개 호스트, 위 둘 다 아닌 경우)                   | `true`          | `None`             | 비워둠(host-only, 어차피 못 맞춤) | `SameSite=None`만으로는 프론트 서버가 쿠키를 볼 수 없다(Domain이 EC2 호스트로 고정) — 프론트가 `NEXT_PUBLIC_CROSS_ORIGIN_AUTH=true`로 배포돼야 로그인 게이트/데이터 조회가 브라우저 크로스오리진 fetch 방식으로 동작한다(아래 2026-08-04 항목 참고) |

시나리오 A/B는 둘 다 `SameSite=Lax`로 충분하고, `SameSite=None`은 시나리오 C에서만, 그것도 부분적으로만 문제를 해결합니다.

**(2026-08-04 확정)** AWS 배포는 시나리오 C(프론트 Vercel, 백엔드 EC2 — 도메인 공유 없음)로 결정됐습니다. `application-prod.yml`의 `COOKIE_SAME_SITE`는 `JWT_SECRET`/`OAUTH2_STATE_SIGNING_KEY`/`CORS_ALLOWED_ORIGINS`와 같은 패턴으로 **기본값을 아예 두지 않습니다** — `Lax`를 기본값으로 두면 시나리오 C에 이 값을 빠뜨렸을 때 로그인이 조용히 깨지고, `None`을 기본값으로 두면 반대로 시나리오 A/B 배포 때 불필요하게 느슨해지기 때문입니다. **배포 시 `COOKIE_SAME_SITE` 환경변수를 반드시 명시**하세요(시나리오 C라면 `None`) — 빠뜨리면 기동 자체가 실패해 바로 드러납니다. `CookieUtils`는 기동 시점에 `same-site=None`인데 `secure=false`면 즉시 실패하도록도 막아둬서, `COOKIE_SECURE`를 켜지 않은 채 조용히 뜨는 상황(브라우저가 쿠키를 버려 로그인이 전부 깨지는데 원인이 안 보임)을 방지합니다.

**(2026-08-04 완료)** 프론트 쪽도 이 시나리오에 맞춰 전환 완료했습니다. `proxy.ts`의 쿠키 기반 게이트, `(main)/layout.tsx`, 그리고 로그인 상태가 필요한 나머지 보호 페이지·OAuth 콜백·랜딩 페이지까지 전부 서버에서 쿠키를 포워딩하는 방식 대신 브라우저가 직접 `credentials:'include'`로 백엔드 `/auth/me` 등을 호출해 확인하는 방식으로 바뀌었습니다(`NEXT_PUBLIC_CROSS_ORIGIN_AUTH=true` 필요 — frontend/README.md 참고). 로컬/서브도메인 공유 배포(시나리오 A/B)는 이 플래그 없이 기존 서버측 게이트를 그대로 씁니다.

## Docs

- [CURRENT_STATE.md](./CURRENT_STATE.md) — 도메인별 구현 현황 요약
- [CLAUDE.md](./CLAUDE.md) — AI 코딩 에이전트용 아키텍처/명령어 가이드
- [AGENTS.md](./AGENTS.md) — CLAUDE.md를 가리키는 포인터 (Claude Code 외 다른 AI 코딩 툴용)
- `docs/specs/` — 도메인별 설계/구현 이력 상세 문서
  - [`auth-design.md`](./docs/specs/auth-design.md)
  - [`user-design.md`](./docs/specs/user-design.md)
  - [`property-design.md`](./docs/specs/property-design.md)
  - [`market-data-design.md`](./docs/specs/market-data-design.md)
  - [`checklist-design.md`](./docs/specs/checklist-design.md)
  - [`risk-analysis-design.md`](./docs/specs/risk-analysis-design.md)
  - [`contract-analysis-design.md`](./docs/specs/contract-analysis-design.md)
  - [`admin-design.md`](./docs/specs/admin-design.md)
  - [`cross-domain-summary.md`](./docs/specs/cross-domain-summary.md) — 도메인 간 반복적으로 나타나는 패턴 정리

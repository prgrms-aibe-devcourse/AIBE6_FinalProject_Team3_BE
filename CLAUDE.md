# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```
# build
./gradlew.bat build

# run the app locally
./gradlew.bat bootRun

# run all tests
./gradlew.bat test

# run a single test class
./gradlew.bat test --tests "com.algogyeyak.AlgogyeyakApplicationTests"

# run a single test method
./gradlew.bat test --tests "com.algogyeyak.AlgogyeyakApplicationTests.contextLoads"
```

Use `gradlew.bat` (Windows). On the wrapper, Gradle 9.5.1 is pinned via `gradle/wrapper/gradle-wrapper.properties`.

## Architecture

- **Framework**: Spring Boot 4.1.0, Java 21 toolchain, group `com.ll`, artifact `algogyeyak`.
- **Web layer**: `spring-boot-starter-webmvc` (servlet MVC, not WebFlux).
- **Persistence**: `spring-boot-starter-data-jpa`, with H2 (`spring-boot-h2console`) for local/dev and MySQL (`mysql-connector-j`) as the runtime driver — expect environment-specific datasource config to live in the `application-{profile}.yml` files.
- **Redis**: `spring-boot-starter-data-redis` (Lettuce), used for access token blacklist + refresh token storage (`com.algogyeyak.auth.jwt.AccessTokenRevocationService` / `com.algogyeyak.auth.token.RefreshTokenService`) — see "Current state" below. Fail-closed on Redis outage: see those classes' javadoc. Tests that need a real Redis use Testcontainers (`redis:7-alpine`), requiring Docker locally.
- **Auth**: `spring-boot-starter-security` + `spring-boot-starter-security-oauth2-client` for OAuth2 login (Google/Kakao), plus `jjwt` (api/impl/jackson, 0.12.6) for issuing/validating JWT access tokens. Stateless: no HTTP session, OAuth2 authorization requests are stored in a cookie (`com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository`) instead. See `com.algogyeyak.auth.config.SecurityConfig` for the full wiring.
- **API docs**: `springdoc-openapi-starter-webmvc-ui` — Swagger UI is available once controllers exist.
- **Observability**: `spring-boot-starter-actuator` + `micrometer-registry-prometheus` for metrics/health endpoints.
- **Lombok**: enabled via `compileOnly` + `annotationProcessor` (and test equivalents) — expected for entities/DTOs.
- **Config profiles**: `application.yml` plus `application-{dev,prod,test}.yml`, selected via Spring profiles. Common config (OAuth2 client id/secret, JWT, CORS, cookies, dev-login) already lives in `application.yml`; `application-prod.yml` overrides it for production (disables H2 console/Swagger, drops dummy defaults so missing env vars fail fast, hard-codes `DEV_LOGIN_ENABLED=false`). `application-dev.yml`/`application-test.yml` still only set `spring.application.name`.

### Package convention

Confirmed: all classes live under `com.algogyeyak` (matching `AlgogyeyakApplication.java`), not `com.ll.algogyeyak` (the Gradle `group`, `com.ll`, is unrelated to the source package). The previously mismatched test package has been moved to match.

## Current state

Google/Kakao OAuth2 login and local email/password login, both with refresh tokens, are implemented:
- `com.algogyeyak.user` — `User` entity, `AuthProvider`/`Role` enums, `UserRepository`, `UserSocialAccount`(`user_social_accounts`)/`UserSocialAccountRepository` (다중 소셜 연동 — 아래 참고)
- `com.algogyeyak.auth.jwt` — `JwtProvider` (issue/validate access tokens, 매 토큰마다 `jti` 발급), `JwtUserPrincipal`, `JwtAuthenticationFilter` (헤더/쿠키에서 토큰을 찾아 검증하고, 실패 사유를 `AUTH_TOKEN_MISSING`/`AUTH_TOKEN_INVALID`/`AUTH_TOKEN_EXPIRED`로 구분해 요청 속성에 남김 — `SecurityConfig`의 `authenticationEntryPoint`가 읽어서 401 응답 코드를 정함), `AccessTokenRevocationService` (**2026-08-03부터 Redis** — 로그아웃 시 jti를 만료 시각까지의 TTL로 Redis에 등록해 access token을 즉시 무효화. 이전엔 `RevokedAccessToken` DB 테이블. Redis 장애 시 fail-closed로 "무효화됨"으로 간주해 인증 거부)
- `com.algogyeyak.auth.oauth` — per-provider attribute parsing (`GoogleOAuth2UserInfo`, `KakaoOAuth2UserInfo`), `CustomOAuth2UserService`, cookie-based `AuthorizationRequestRepository`
- `com.algogyeyak.auth.util.EmailNormalizer` — 이메일 trim+lowercase 정규화를 로컬/OAuth 양쪽이 공유한다. 저장하거나 `findByEmail`로 조회하는 모든 지점에서 반드시 거쳐야 함 — 안 그러면 대소문자만 다른 이메일이 다른 계정으로 취급돼 자동 연동이 조용히 실패할 수 있다.
- `com.algogyeyak.auth.service.LocalAuthService` — email/password 가입·로그인. 비밀번호는 `PasswordEncoder`(BCrypt, `SecurityConfig`에 빈 등록)로 해시한다. `setPassword`로 로그인된 사용자 본인이 비밀번호를 설정/변경할 수 있다 — 구글/카카오 전용 계정도 이걸로 로컬 로그인을 추가로 확보할 수 있음(OAuth가 이미 이메일을 검증했으므로 안전)
- **계정 자동 연동 및 다중 소셜 연동 (2026-07-30 갱신)**: `CustomOAuth2UserService.findOrCreateUser`는 `UserSocialAccount`(user_id+provider+provider_id, `(provider, provider_id)`/`(user_id, provider)` 유니크 제약)를 유일한 소스로 쓴다 — 1) `(provider, providerId)`로 `UserSocialAccount`를 먼저 조회해 있으면 그 유저를 재사용하고, 2) 없으면 검증된 이메일로 기존 계정(로컬 또는 다른 소셜)을 찾아 **기존 연동을 유지한 채 새 `UserSocialAccount`를 추가**하며, 3) 그것도 없으면 신규 `User`+첫 `UserSocialAccount`를 같은 트랜잭션에서 함께 생성한다. 이메일 검증(`findVerifiedEmailMatch`, `OAuth2UserInfo.isEmailVerified()` — Google `email_verified`, Kakao `kakao_account.is_email_verified`)을 거쳐야만 자동 연동되는 건 이전과 동일하다 — 검증 안 된 이메일은 `users.email`에 아예 저장하지 않고 `null`로 둔다(계정 탈취 방지, `CustomOAuth2UserService.createUser` 참고). `User`는 더 이상 `provider`/`providerId` 컬럼을 갖지 않는다(2026-07-30 제거) — 예전에는 "가장 최근에 로그인에 쓴 수단"만 가리키는 캐시로 남겨뒀었지만, 실제 조회/판단 로직 어디에서도 쓰이지 않아 정리했다. "이 유저가 실제로 연동해둔 모든 소셜 계정 목록"은 `UserSocialAccount`가 유일한 소스다. 한 유저가 구글+카카오를 동시에 연동해도 둘 다 유효하며, 이전 provider로 다시 로그인해도 재연동 없이 그 계정을 곧바로 찾는다.
- `com.algogyeyak.auth.token` — `RefreshTokenService`: **Redis-backed since 2026-08-03** (previously DB — see `docs/specs/auth-design.md` for the reversal rationale), single session per user — a new login or refresh immediately invalidates the previous raw token. Two Redis keys per session (`by-hash:{tokenHash}`→userId, `by-user:{userId}`→tokenHash reverse index), both TTL'd to the refresh token validity so Redis handles natural expiry — no more `expiresAt` column/manual expiry check, and the old `AUTH_REFRESH_TOKEN_EXPIRED` error code was removed (collapsed into `AUTH_REFRESH_TOKEN_INVALID`, which the frontend already treated the same). `issue`/`rotate`/`revoke` each run as a single atomic Lua script (`RedisTemplate.execute(RedisScript, ...)`) — not separate GET/SET/DELETE calls — so concurrent issue() calls for the same user, concurrent rotate() of the same raw token, and rotate() racing against a stale/orphaned key all resolve to exactly one live session (see the class javadoc and `RefreshTokenServiceRedisIntegrationTest` for the concurrency regression tests). Raw tokens are never stored, only a SHA-256 hash. Redis failures are fail-closed (`AUTH_TOKEN_STORE_UNAVAILABLE`, 503).
- `com.algogyeyak.auth.handler` + `com.algogyeyak.auth.config.SecurityConfig` — OAuth2 login wiring, access/refresh JWT delivered via httpOnly cookies (both path `/` — the frontend middleware needs to read the refresh cookie on protected-page requests, which a narrower path would block)
- `com.algogyeyak.auth.controller.AuthController` — `GET /auth/me`, `POST /auth/logout`, `POST /auth/refresh`, `POST /auth/signup`, `POST /auth/login`, `GET /auth/password-policy`(인증 불필요) (엔드포인트는 `/api` 프리픽스 없이 작성하는 것으로 팀 컨벤션 확정). `signup`/`login` 모두 소셜 로그인과 동일하게 성공 시 access/refresh 쿠키를 즉시 발급해 자동 로그인 상태로 만든다. `logout`은 refresh token을 Redis에서 즉시 삭제하고, access token도 `Authorization: Bearer` 헤더/쿠키 어느 쪽으로 인증했든 그 jti를 Redis 블랙리스트에 등록해 즉시 무효화한다.

비밀번호 정책: 영문+숫자를 포함한 8~72자의 ASCII 출력 가능 문자(공백 제외) — `PasswordPolicy`(`com.algogyeyak.auth.dto`)가 유일한 소스다. BCrypt가 72바이트를 넘는 부분을 조용히 잘라버리는 문제 때문에 멀티바이트 문자를 막아 문자 수와 바이트 수를 일치시켰다. `GET /auth/password-policy`로 frontend에 이 정책(정규식/안내 문구)을 그대로 내려줘서, frontend가 하드코딩으로 따로 들고 있지 않게 한다.

See [README.md](./README.md) for stack overview and getting-started instructions.

## contract-analysis 도메인 (진행 중 — 담당: 송민혁)

- 패키지: `com.algogyeyak.contractanalysis`
- 파이프라인: 계약문구입력 → OCR → 마스킹 → AI분석 (4개 엔드포인트, 순차 진행)
  - POST /contract-analysis/inputs
  - POST /contract-analysis/ocr
  - POST /contract-analysis/masking
  - POST /contract-analysis/analyze
- 외부 API: Clova OCR(NCP), Gemini(gemini-2.5-flash) — 키는 환경변수로 분리, .env에 절대 커밋 금지
- 마스킹 완료 전(`userConfirmed=true` 아니면) AI 분석 요청 절대 차단
- 위험 신호는 등급(LOW/MEDIUM/HIGH) 없이 riskFlag(true/false) + 설명 방식 — 팀 전체 정책
- 계약 문구 원본/마스킹 전 텍스트는 DB·로그에 영구 저장하지 않음 (개인정보 정책)
- 분석 결과는 이력 조회 목적 저장 안 함 (MVP 범위 제외)

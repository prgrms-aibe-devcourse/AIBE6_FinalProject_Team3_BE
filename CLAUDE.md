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
- **Auth**: `spring-boot-starter-security` + `spring-boot-starter-security-oauth2-client` for OAuth2 login (Google/Kakao), plus `jjwt` (api/impl/jackson, 0.12.6) for issuing/validating JWT access tokens. Stateless: no HTTP session, OAuth2 authorization requests are stored in a cookie (`com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository`) instead. See `com.algogyeyak.auth.config.SecurityConfig` for the full wiring.
- **API docs**: `springdoc-openapi-starter-webmvc-ui` — Swagger UI is available once controllers exist.
- **Observability**: `spring-boot-starter-actuator` + `micrometer-registry-prometheus` for metrics/health endpoints.
- **Lombok**: enabled via `compileOnly` + `annotationProcessor` (and test equivalents) — expected for entities/DTOs.
- **Config profiles**: `application.yml` plus `application-{dev,prod,test}.yml`, selected via Spring profiles. All four currently only set `spring.application.name`; profile-specific values (datasource, OAuth2 client secrets, etc.) have not been added yet.

### Package convention

Confirmed: all classes live under `com.algogyeyak` (matching `AlgogyeyakApplication.java`), not `com.ll.algogyeyak` (the Gradle `group`, `com.ll`, is unrelated to the source package). The previously mismatched test package has been moved to match.

## Current state

Google/Kakao OAuth2 login and local email/password login, both with refresh tokens, are implemented:
- `com.algogyeyak.user` — `User` entity, `AuthProvider`/`Role` enums, `UserRepository`
- `com.algogyeyak.auth.jwt` — `JwtProvider` (issue/validate access tokens), `JwtUserPrincipal`, `JwtAuthenticationFilter`
- `com.algogyeyak.auth.oauth` — per-provider attribute parsing (`GoogleOAuth2UserInfo`, `KakaoOAuth2UserInfo`), `CustomOAuth2UserService`, cookie-based `AuthorizationRequestRepository`
- `com.algogyeyak.auth.util.EmailNormalizer` — 이메일 trim+lowercase 정규화를 로컬/OAuth 양쪽이 공유한다. 저장하거나 `findByEmail`로 조회하는 모든 지점에서 반드시 거쳐야 함 — 안 그러면 대소문자만 다른 이메일이 다른 계정으로 취급돼 자동 연동이 조용히 실패할 수 있다.
- `com.algogyeyak.auth.service.LocalAuthService` — email/password 가입·로그인. 비밀번호는 `PasswordEncoder`(BCrypt, `SecurityConfig`에 빈 등록)로 해시한다. `provider_id`는 소셜 로그인과 달리 별도 식별자가 없어 email 값을 그대로 재사용한다(`User.createLocalUser`). `setPassword`로 로그인된 사용자 본인이 비밀번호를 설정/변경할 수 있다 — 구글/카카오 전용 계정도 이걸로 로컬 로그인을 추가로 확보할 수 있음(OAuth가 이미 이메일을 검증했으므로 안전)
- **계정 자동 연동 및 한계**: `CustomOAuth2UserService.findOrCreateUser`는 provider+providerId로 못 찾으면 같은 이메일의 기존 계정(로컬 또는 다른 소셜)을 찾아 `User.linkProvider`로 연결한다. 이 연동은 `findVerifiedEmailMatch`를 거쳐 **OAuth 제공자가 이메일 소유권을 검증해준 경우에만**(`OAuth2UserInfo.isEmailVerified()` — Google `email_verified`, Kakao `kakao_account.is_email_verified`) 이뤄진다. 검증 안 된 이메일은 `users.email`에 아예 저장하지 않고 `null`로 둔다 — 저장해두면 나중에 그 이메일의 실제 소유자가 검증된 OAuth로 로그인할 때 이 row를 "기존 계정"으로 착각해 연동해버려, 검증 안 된 이메일로 미리 만들어둔 계정에 진짜 소유자가 합쳐지는 계정 탈취로 이어질 수 있기 때문이다(`CustomOAuth2UserService.createUser`). `User`는 provider/provider_id를 **한 쌍만** 저장하므로, 진짜 다중 소셜 연동은 아니다 — 예를 들어 구글로 연동된 계정에 카카오로 로그인하면 provider_id가 카카오 것으로 덮이고, 이후 구글 로그인은 이메일 재조회로만 복구된다(검증된 이메일이 없거나 바뀌면 복구 불가). MVP 범위에서는 이 "검증된 이메일 기반 자동 재연동"만 지원하며, 여러 소셜 계정을 동시에 안정적으로 유지하려면 `user_id`+`provider`+`provider_id`를 따로 저장하는 `user_socials` 같은 연동 테이블이 필요하다(아직 미구현).
- `com.algogyeyak.auth.token` — `RefreshTokenService`/`RefreshTokenRepository`: DB-backed (no Redis), single session per user — a new login or refresh rotates/overwrites the one row for that `user_id`. Raw tokens are never stored, only a SHA-256 hash.
- `com.algogyeyak.auth.handler` + `com.algogyeyak.auth.config.SecurityConfig` — OAuth2 login wiring, access/refresh JWT delivered via httpOnly cookies (both path `/` — the frontend middleware needs to read the refresh cookie on protected-page requests, which a narrower path would block)
- `com.algogyeyak.auth.controller.AuthController` — `GET /auth/me`, `POST /auth/logout`, `POST /auth/refresh`, `POST /auth/signup`, `POST /auth/login` (엔드포인트는 `/api` 프리픽스 없이 작성하는 것으로 팀 컨벤션 확정). `signup`/`login` 모두 소셜 로그인과 동일하게 성공 시 access/refresh 쿠키를 즉시 발급해 자동 로그인 상태로 만든다.

비밀번호 정책: 영문+숫자를 포함한 8~72자의 ASCII 출력 가능 문자(공백 제외) — `SignupRequest`의 `@Pattern` 참고. BCrypt가 72바이트를 넘는 부분을 조용히 잘라버리는 문제 때문에 멀티바이트 문자를 막아 문자 수와 바이트 수를 일치시켰다.

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

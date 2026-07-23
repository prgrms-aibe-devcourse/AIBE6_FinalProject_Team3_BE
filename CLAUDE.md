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

Google/Kakao OAuth2 login with refresh tokens is implemented:
- `com.algogyeyak.user` — `User` entity, `AuthProvider`/`Role` enums, `UserRepository`
- `com.algogyeyak.auth.jwt` — `JwtProvider` (issue/validate access tokens), `JwtUserPrincipal`, `JwtAuthenticationFilter`
- `com.algogyeyak.auth.oauth` — per-provider attribute parsing (`GoogleOAuth2UserInfo`, `KakaoOAuth2UserInfo`), `CustomOAuth2UserService`, cookie-based `AuthorizationRequestRepository`
- `com.algogyeyak.auth.token` — `RefreshTokenService`/`RefreshTokenRepository`: DB-backed (no Redis), single session per user — a new login or refresh rotates/overwrites the one row for that `user_id`. Raw tokens are never stored, only a SHA-256 hash.
- `com.algogyeyak.auth.handler` + `com.algogyeyak.auth.config.SecurityConfig` — OAuth2 login wiring, access/refresh JWT delivered via httpOnly cookies (both path `/` — the frontend middleware needs to read the refresh cookie on protected-page requests, which a narrower path would block)
- `com.algogyeyak.auth.controller.AuthController` — `GET /auth/me`, `POST /auth/logout`, `POST /auth/refresh` (엔드포인트는 `/api` 프리픽스 없이 작성하는 것으로 팀 컨벤션 확정)

Local email/password login is not implemented yet — planned as a follow-up.

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

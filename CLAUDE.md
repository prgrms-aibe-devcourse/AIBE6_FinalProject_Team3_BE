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

Google/Kakao OAuth2 login (access-token issuance only, no refresh token yet) is implemented:
- `com.algogyeyak.user` — `User` entity, `AuthProvider`/`Role` enums, `UserRepository`
- `com.algogyeyak.auth.jwt` — `JwtProvider` (issue/validate), `JwtUserPrincipal`, `JwtAuthenticationFilter`
- `com.algogyeyak.auth.oauth` — per-provider attribute parsing (`GoogleOAuth2UserInfo`, `KakaoOAuth2UserInfo`), `CustomOAuth2UserService`, cookie-based `AuthorizationRequestRepository`
- `com.algogyeyak.auth.handler` + `com.algogyeyak.auth.config.SecurityConfig` — OAuth2 login wiring, JWT delivered via httpOnly cookie
- `com.algogyeyak.auth.controller.AuthController` — `GET /api/auth/me`, `POST /api/auth/logout`

Local email/password login and Redis-backed refresh tokens are not implemented yet — planned as a follow-up.

See [README.md](./README.md) for stack overview and getting-started instructions.

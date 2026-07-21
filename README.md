# 알고계약 (algogyeyak) — Backend

사회초년생과 대학생을 위한 부동산 계약 안전 확인 서비스의 API 서버입니다.

## Stack

- Java 21
- Spring Boot 4.1.0 (Gradle Kotlin DSL, wrapper Gradle 9.5.1)
- Spring Web MVC
- Spring Data JPA — H2(로컬/dev), MySQL(runtime)
- Spring Security + OAuth2 Client + JWT (jjwt)
- springdoc-openapi (Swagger UI)
- Spring Boot Actuator + Micrometer(Prometheus)
- Lombok

## Getting Started

```bash
./gradlew.bat bootRun
```

`application.yml`에 별도 데이터소스 설정이 없어 기본적으로 인메모리 H2로 기동됩니다. MySQL 등 실제 데이터소스 연결은 아직 `application-{dev,prod,test}.yml`에 구성되어 있지 않습니다.

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

## Config profiles

`application.yml` + `application-{dev,prod,test}.yml` 4개 파일이 있으며, 현재는 전부 `spring.application.name`만 설정되어 있습니다. 데이터소스, OAuth2 client secret 등 프로필별 값은 아직 채워지지 않았습니다.

## 알아둘 점

패키지 컨벤션은 `com.algogyeyak`(`AlgogyeyakApplication.java`)로 확정되었습니다. Gradle `group`(`com.ll`)과는 무관하니 새 클래스는 전부 `com.algogyeyak` 하위에 작성하세요.

## Current state

구글/카카오 OAuth2 로그인(Access Token 발급까지, Refresh Token은 아직 미구현)이 구현되어 있습니다: `com.algogyeyak.user`(엔티티/리포지토리), `com.algogyeyak.auth.jwt`(JWT 발급/검증/필터), `com.algogyeyak.auth.oauth`(구글/카카오 속성 파싱, 커스텀 OAuth2 유저 서비스), `com.algogyeyak.auth.handler` + `com.algogyeyak.auth.config.SecurityConfig`(로그인 성공 시 JWT를 httpOnly 쿠키로 전달), `com.algogyeyak.auth.controller.AuthController`(`GET /api/auth/me`, `POST /api/auth/logout`). 로컬 이메일/비밀번호 로그인과 Redis 기반 Refresh Token은 다음 단계로 남아 있습니다.

실제 구글/카카오 로그인을 로컬에서 테스트하려면 아래 환경변수가 필요합니다 (미설정 시 더미 값으로 기동은 되지만 실제 소셜 로그인은 동작하지 않습니다):

| 환경변수 | 설명 |
| --- | --- |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google Cloud Console에서 발급 |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | Kakao Developers에서 발급 |
| `JWT_SECRET` | HS256 서명용 시크릿 (최소 32바이트 랜덤 문자열) |
| `OAUTH2_REDIRECT_URI` | 로그인 성공 후 리다이렉트할 프론트엔드 콜백 URL (기본값 `http://localhost:3000/oauth/callback`) |
| `CORS_ALLOWED_ORIGINS` | 허용할 프론트엔드 origin (기본값 `http://localhost:3000`) |

## Docs

- [CLAUDE.md](./CLAUDE.md) — AI 코딩 에이전트용 아키텍처/명령어 가이드
- [AGENTS.md](./AGENTS.md) — CLAUDE.md를 가리키는 포인터 (Claude Code 외 다른 AI 코딩 툴용)

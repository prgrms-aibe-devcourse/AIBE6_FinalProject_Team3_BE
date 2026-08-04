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

Redis는 두 가지 용도로 쓰입니다. 1) **access token blacklist/refresh token 저장소**(`com.algogyeyak.auth` — 아래 "Current state" 참고)는 이미 실제로 Redis를 사용하며, Redis가 없으면 로그인/토큰 재발급이 fail-closed로 거부됩니다(아래 문단 참고). 2) 그 외 도메인(실거래가 비교 등)의 **캐싱**은 아직 연결 준비만 되어 있고 실제 캐시 로직(`@Cacheable` 대상/TTL 설계)은 붙어있지 않습니다 — 이쪽은 Redis가 없어도 영향받지 않습니다(Lettuce가 연결을 지연 생성).

로컬에서 Redis를 띄우려면 `docker compose up -d redis`를 실행하면 됩니다(`spring.data.redis.host`/`port`가 기본값 `localhost:6379`를 가리키고 있어 별도 설정 없이 바로 연결됩니다).

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

`application.yml` + `application-{dev,prod,test}.yml` 4개 파일이 있습니다. 공통 설정(OAuth2 client id/secret, JWT, CORS, 쿠키, dev-login 등)은 `application.yml`(기본 프로필)에 이미 채워져 있고, `application-prod.yml`은 운영 전용 오버라이드(H2 콘솔/Swagger 비활성화, dummy 기본값 제거로 fail-fast, `DEV_LOGIN_ENABLED`를 환경변수와 무관하게 고정 false 등)를 담고 있습니다. `application-dev.yml`/`application-test.yml`은 아직 `spring.application.name`만 설정된 상태입니다.

## 알아둘 점

패키지 컨벤션은 `com.algogyeyak`(`AlgogyeyakApplication.java`)로 확정되었습니다. Gradle `group`(`com.ll`)과는 무관하니 새 클래스는 전부 `com.algogyeyak` 하위에 작성하세요.

## Current state

구글/카카오 OAuth2 로그인과 로컬 이메일/비밀번호 로그인 모두 Refresh Token과 함께 구현되어 있습니다: `com.algogyeyak.user`(엔티티/리포지토리), `com.algogyeyak.auth.jwt`(Access Token 발급/검증/필터 — 로그아웃된 access token의 jti 블랙리스트는 Redis로 관리), `com.algogyeyak.auth.oauth`(구글/카카오 속성 파싱, 커스텀 OAuth2 유저 서비스), `com.algogyeyak.auth.service.LocalAuthService`(이메일/비밀번호 가입·로그인 — 비밀번호는 BCrypt로 해시, 이메일은 trim+lowercase로 정규화해 저장/조회), `com.algogyeyak.auth.token`(`RefreshTokenService` — **Redis**로 관리, 유저당 1개 세션만 유지하며 재로그인/재발급 시 이전 토큰을 즉시 무효화), `com.algogyeyak.auth.handler` + `com.algogyeyak.auth.config.SecurityConfig`(로그인 성공 시 Access/Refresh Token을 httpOnly 쿠키로 전달, 둘 다 path `/` — 프론트 미들웨어가 보호 페이지 요청에서도 Refresh 쿠키를 읽어야 해서 path를 좁히지 않음), `com.algogyeyak.auth.controller.AuthController`(`GET /auth/me`, `POST /auth/logout`, `POST /auth/refresh`, `POST /auth/signup`, `POST /auth/login`). 회원가입/로컬 로그인도 소셜 로그인과 동일하게 성공 시 즉시 쿠키를 발급해 자동 로그인 상태로 만듭니다.

Access token blacklist와 refresh token 저장소는 Redis가 있어야 동작합니다 (`REDIS_HOST`/`REDIS_PORT`/`REDIS_PASSWORD`, 로컬 기본값은 `localhost:6379`/비밀번호 없음). 로컬에서 가장 간단한 실행 방법:

```bash
docker run --name algogyeyak-redis -p 6379:6379 -d redis:7-alpine
```

Redis 연결에 실패하면 **fail-closed**로 처리됩니다 — access token 인증은 거부되고(401), refresh token 발급/재발급/로그아웃은 503(`AUTH_TOKEN_STORE_UNAVAILABLE`)으로 실패합니다. 가용성보다 "장애 중 무효화된 토큰이 재사용되지 않는 것"을 우선한 결정입니다.

로컬 비밀번호 정책은 영문+숫자를 포함한 8~72자의 ASCII 출력 가능 문자(공백 제외)입니다 — BCrypt가 72바이트를 넘는 부분을 조용히 잘라버리는 문제 때문에 멀티바이트 문자를 막아 문자 수와 바이트 수를 일치시켰습니다 (`SignupRequest`의 `@Pattern` 참고).

실제 구글/카카오 로그인을 로컬에서 테스트하려면 아래 환경변수가 필요합니다 (미설정 시 더미 값으로 기동은 되지만 실제 소셜 로그인은 동작하지 않습니다). `.env.example`을 `backend/.env`로 복사해 값을 채워두면 `me.paulschwarz:springboot4-dotenv`(개발 전용 의존성)가 `bootRun` 시 자동으로 읽어들입니다 — 별도로 셸에 export하거나 IDE Run Configuration에 등록할 필요가 없습니다. **`.env`는 반드시 BOM 없는 UTF-8로 저장하세요** — 메모장 등으로 저장하면 파일 앞에 보이지 않는 BOM이 붙어 dotenv 파서가 `Malformed entry` 에러를 내며 기동에 실패합니다.

dotenv는 원래 JVM의 working directory 기준으로 `.env`를 찾습니다. 이 프로젝트를 루트 폴더(`aibe6_team3_final_project`)로 열었다면 IntelliJ가 생성하는 Run Configuration의 working directory가 `backend`가 아니라 그 루트로 잡혀 `.env`를 못 찾는 경우가 있는데, `com.algogyeyak.global.config.DotenvDirectoryEnvironmentPostProcessor`가 기동 시 working directory에 `.env`가 없으면 `backend/.env`가 있는지 확인해 자동으로 그 위치를 알려주므로 — working directory를 `backend`로 직접 맞추거나 Run Configuration에 환경변수를 등록하는 등 팀원별 IDE 설정이 필요 없습니다.

| 환경변수 | 설명 |
| --- | --- |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | Google Cloud Console에서 발급 |
| `KAKAO_CLIENT_ID` / `KAKAO_CLIENT_SECRET` | Kakao Developers에서 발급 |
| `JWT_SECRET` | HS256 서명용 시크릿 (최소 32바이트 랜덤 문자열) |
| `OAUTH2_STATE_SIGNING_KEY` | OAuth2 인가 요청을 담는 쿠키(`oauth2_auth_request`)의 HMAC 서명 키 (최소 32바이트 랜덤 문자열, `JWT_SECRET`과는 다른 값 권장) |
| `OAUTH2_REDIRECT_URI` | 로그인 성공 후 리다이렉트할 프론트엔드 콜백 URL (기본값 `http://localhost:3000/oauth/callback`) |
| `CORS_ALLOWED_ORIGINS` | 허용할 프론트엔드 origin (기본값 `http://localhost:3000`) |
| `COOKIE_SECURE` | `access_token` 등 쿠키의 Secure 속성 (dev 기본값 `false`, prod 기본값 `true`) |
| `COOKIE_SAME_SITE` | 쿠키의 SameSite 속성 (기본값 `Lax`) |
| `COOKIE_DOMAIN` | 쿠키의 Domain 속성 — 커스텀 서브도메인 배포 시에만 `.example.com`처럼 지정 (dev/prod 기본값 모두 비어있음=host-only) |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | access token blacklist/refresh token 저장소용 Redis 연결 정보 (dev 기본값 `localhost:6379`/비밀번호 없음, prod는 기본값 없이 fail-fast) |

로컬은 프론트/백엔드 모두 `http://localhost:3000` / `http://localhost:8080`을 그대로 씁니다. 한때 `app.localhost`/`api.localhost` + `Domain=.localhost`로 커스텀 서브도메인 배포 구조를 로컬에서부터 검증해보려 했으나, **크롬이 `localhost`를 public-suffix처럼 취급해 서브도메인이 `Domain=.localhost` 쿠키를 설정하는 것 자체를 거부**한다는 걸 확인해 되돌렸습니다 (`api.localhost`에서 심은 OAuth2 state 쿠키를 콜백 때 못 찾아 `authorization_request_not_found`로 로그인 자체가 실패했음). 커스텀 서브도메인 + 공유 쿠키가 실제로 동작하는지는 진짜 도메인이 생기거나 `lvh.me`/`nip.io` 같은 실제 등록된 서브도메인 지원 도메인을 쓸 때 검증하면 됩니다.

### 운영 배포 시 쿠키 설정 (`COOKIE_SECURE` / `COOKIE_SAME_SITE` / `COOKIE_DOMAIN`)

프론트(Vercel)와 백엔드(EC2)가 배포에서 브라우저 기준 "같은 site"로 보이는지에 따라 값이 완전히 달라집니다. 배포 도메인 전략이 정해지면 아래 표에서 해당하는 행의 값으로 설정하세요.

| 시나리오 | `COOKIE_SECURE` | `COOKIE_SAME_SITE` | `COOKIE_DOMAIN` | 비고 |
| --- | --- | --- | --- | --- |
| **A. 커스텀 서브도메인** (`app.example.com` + `api.example.com`, 같은 등록 도메인) | `true` | `Lax` (기본값) | `.example.com` | 지금 코드가 이 시나리오를 전제로 만들어져 있음 — 로컬의 `.localhost` 설정과 동일한 구조 |
| **B. Vercel rewrite** (`/api/*`를 EC2로 프록시, 브라우저는 Vercel origin만 봄) | `true` | `Lax` (기본값) | 비워둠(host-only) | 브라우저 입장에서 이미 같은 origin이라 `Domain` 지정이 필요 없음. 단, `proxy.ts`/`oauth/callback`이 지금처럼 EC2를 직접 부르는 구조라 rewrite에 맞춰 손봐야 함 (검토 예정) |
| **C. 도메인 공유 없음** (완전히 별개 호스트, 위 둘 다 아닌 경우) | `true` | `None` | 비워둠(host-only, 어차피 못 맞춤) | `SameSite=None`만으로는 프론트 서버가 쿠키를 볼 수 없다(Domain이 EC2 호스트로 고정) — 프론트가 `NEXT_PUBLIC_CROSS_ORIGIN_AUTH=true`로 배포돼야 로그인 게이트/데이터 조회가 브라우저 크로스오리진 fetch 방식으로 동작한다(아래 2026-08-04 항목 참고) |

시나리오 A/B는 둘 다 `SameSite=Lax`로 충분하고, `SameSite=None`은 시나리오 C에서만, 그것도 부분적으로만 문제를 해결합니다.

**(2026-08-04 확정)** AWS 배포는 시나리오 C(프론트 Vercel, 백엔드 EC2 — 도메인 공유 없음)로 결정됐습니다. 다만 `application-prod.yml`의 `COOKIE_SAME_SITE` 기본값은 보수적으로 `Lax`를 유지합니다 — 시나리오 A/B로 배포하는 경우까지 기본값이 조용히 `None`으로 느슨해지는 걸 막기 위함입니다. **실제(시나리오 C) 배포 시에는 `COOKIE_SAME_SITE=None` 환경변수를 반드시 명시**하세요. `CookieUtils`는 기동 시점에 `same-site=None`인데 `secure=false`면 즉시 실패하도록 막아둬서, `COOKIE_SECURE`를 켜지 않은 채 조용히 뜨는 상황(브라우저가 쿠키를 버려 로그인이 전부 깨지는데 원인이 안 보임)을 방지합니다.

**(2026-08-04 완료)** 프론트 쪽도 이 시나리오에 맞춰 전환 완료했습니다. `proxy.ts`의 쿠키 기반 게이트, `(main)/layout.tsx`, 그리고 로그인 상태가 필요한 나머지 보호 페이지·OAuth 콜백·랜딩 페이지까지 전부 서버에서 쿠키를 포워딩하는 방식 대신 브라우저가 직접 `credentials:'include'`로 백엔드 `/auth/me` 등을 호출해 확인하는 방식으로 바뀌었습니다(`NEXT_PUBLIC_CROSS_ORIGIN_AUTH=true` 필요 — frontend/README.md 참고). 로컬/서브도메인 공유 배포(시나리오 A/B)는 이 플래그 없이 기존 서버측 게이트를 그대로 씁니다.

## Docs

- [CLAUDE.md](./CLAUDE.md) — AI 코딩 에이전트용 아키텍처/명령어 가이드
- [AGENTS.md](./AGENTS.md) — CLAUDE.md를 가리키는 포인터 (Claude Code 외 다른 AI 코딩 툴용)

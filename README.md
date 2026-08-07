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

`application.yml`에 별도 데이터소스 설정이 없어 기본적으로 인메모리 H2로 기동됩니다. MySQL 등 실제 데이터소스 연결은 아직 `application-{dev,prod,test}.yml`/`docker-compose.prod.yml`/배포 워크플로우 어디에도 명시적으로 구성되어 있지 않습니다.

Redis는 두 가지 용도로 쓰입니다. 1) **access token blacklist/refresh token 저장소**(`com.algogyeyak.auth`)는 이미 실제로 Redis를 사용하며, Redis가 없으면 로그인/토큰 재발급이 fail-closed로 거부됩니다. 2) 그 외 도메인(실거래가 비교 등)의 **캐싱**은 아직 연결 준비만 되어 있고 실제 캐시 로직(`@Cacheable` 대상/TTL 설계)은 붙어있지 않습니다 — 이쪽은 Redis가 없어도 영향받지 않습니다(Lettuce가 연결을 지연 생성).

로컬에서 Redis를 띄우려면 `docker compose up -d redis`를 실행하면 됩니다(`spring.data.redis.host`/`port`가 기본값 `localhost:6379`를 가리키고 있어 별도 설정 없이 바로 연결됩니다). Redis fail-closed 동작, 환경변수 전체 목록, 소셜 로그인 로컬 설정 등은 [`CURRENT_STATE.md`](./CURRENT_STATE.md)의 "로컬 개발 환경" 섹션을 참고하세요.

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

8개 도메인(auth/user/property/checklist/market-data/risk-analysis/contract-analysis/admin)이 구현되어 있으며, 진행 정도는 도메인마다 다릅니다 — auth/market-data/risk-analysis는 거의 완전 구현, contract-analysis는 진행 중입니다. 도메인별 요약, Redis/환경변수/소셜 로그인 로컬 설정 등 자세한 내용은 [`CURRENT_STATE.md`](./CURRENT_STATE.md)를 참고하세요.

## Deployment

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
  - [`cross-domain-summary.md`](./docs/specs/cross-domain-summary.md) — 도메인 간 반복적으로 나타나는 패턴 정리

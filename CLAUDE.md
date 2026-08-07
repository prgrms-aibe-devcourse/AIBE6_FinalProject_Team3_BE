# CLAUDE.md

이 문서는 이 저장소에서 작업할 때 Claude Code(claude.ai/code)에게 안내를 제공합니다.

## 명령어

```
# 빌드
./gradlew.bat build

# 로컬 실행
./gradlew.bat bootRun

# 전체 테스트
./gradlew.bat test

# 단일 테스트 클래스
./gradlew.bat test --tests "com.algogyeyak.AlgogyeyakApplicationTests"

# 단일 테스트 메서드
./gradlew.bat test --tests "com.algogyeyak.AlgogyeyakApplicationTests.contextLoads"
```

Windows 환경이므로 `gradlew.bat`을 사용합니다. wrapper 기준 Gradle 9.5.1로 고정되어 있습니다(`gradle/wrapper/gradle-wrapper.properties`).

## 아키텍처

- **프레임워크**: Spring Boot 4.1.0, Java 21 toolchain, group `com.ll`, artifact `algogyeyak`.
- **웹 레이어**: `spring-boot-starter-webmvc`(서블릿 MVC, WebFlux 아님).
- **영속성**: `spring-boot-starter-data-jpa`, 로컬/dev는 H2(`spring-boot-h2console`), 운영 드라이버는 MySQL(`mysql-connector-j`) — 환경별 데이터소스 설정은 `application-{profile}.yml`에 있어야 함(현재는 명시적으로 구성돼 있지 않음, `CURRENT_STATE.md` 참고).
- **Redis**: `spring-boot-starter-data-redis`(Lettuce), access token 블랙리스트 + refresh token 저장용(`com.algogyeyak.auth.jwt.AccessTokenRevocationService` / `com.algogyeyak.auth.token.RefreshTokenService`) — 아래 "현재 상태" 참고. Redis 장애 시 fail-closed(해당 클래스들의 javadoc 참고). 실제 Redis가 필요한 테스트는 Testcontainers(`redis:7-alpine`)를 쓰며 로컬에 Docker가 필요함.
- **인증**: OAuth2 로그인(구글/카카오)은 `spring-boot-starter-security` + `spring-boot-starter-security-oauth2-client`, JWT access token 발급/검증은 `jjwt`(api/impl/jackson, 0.12.6). Stateless — HTTP 세션 없이 OAuth2 인가 요청은 쿠키에 저장(`com.algogyeyak.auth.oauth.CookieAuthorizationRequestRepository`). 전체 배선은 `com.algogyeyak.auth.config.SecurityConfig` 참고.
- **API 문서**: `springdoc-openapi-starter-webmvc-ui` — 컨트롤러가 있으면 Swagger UI 사용 가능.
- **관측성**: `spring-boot-starter-actuator` + `micrometer-registry-prometheus`(메트릭/헬스 엔드포인트).
- **Lombok**: `compileOnly` + `annotationProcessor`(테스트도 동일)로 활성화 — 엔티티/DTO에 사용.
- **Config profiles**: `application.yml` + `application-{dev,prod,test}.yml`, Spring profile로 선택. 공통 설정(OAuth2 client id/secret, JWT, CORS, 쿠키, dev-login)은 `application.yml`(기본 프로필)에 이미 있고, `application-prod.yml`이 운영 전용 오버라이드를 담당(H2 콘솔/Swagger 비활성화, dummy 기본값 제거로 fail-fast, `DEV_LOGIN_ENABLED=false` 고정). `application-dev.yml`/`application-test.yml`은 아직 `spring.application.name`만 설정.

### 패키지 컨벤션

확정: 모든 클래스는 `com.algogyeyak`(`AlgogyeyakApplication.java`와 일치) 하위에 있으며, `com.ll.algogyeyak`이 아닙니다(Gradle `group`인 `com.ll`은 소스 패키지와 무관). 예전에 불일치했던 테스트 패키지도 이미 맞춰졌습니다.

## 현재 상태

8개 도메인(auth/user/property/checklist/market-data/risk-analysis/contract-analysis/admin)이 구현되어 있습니다. 도메인별 구현 현황·남은 이슈는 [CURRENT_STATE.md](./CURRENT_STATE.md)를, 각 도메인의 상세 설계/구현 이력은 `docs/specs/{도메인}-design.md`를 참고하세요(README.md의 "Docs" 섹션에 전체 목록).

패키지 위치만 빠르게 참고할 때:

- `com.algogyeyak.user` — `User` 엔티티, `AuthProvider`/`Role`, `UserSocialAccount`(다중 소셜 연동)
- `com.algogyeyak.auth.jwt` — `JwtProvider`, `JwtAuthenticationFilter`, `AccessTokenRevocationService`(Redis 블랙리스트)
- `com.algogyeyak.auth.oauth` — 구글/카카오 속성 파싱, `CustomOAuth2UserService`
- `com.algogyeyak.auth.service.LocalAuthService` — 이메일/비밀번호 가입·로그인
- `com.algogyeyak.auth.token.RefreshTokenService` — Redis 기반, 유저당 세션 1개
- `com.algogyeyak.auth.controller.AuthController` — `GET /auth/me`, `POST /auth/{signup,login,logout,refresh}`, `GET /auth/password-policy`

비밀번호 정책(영문+숫자 포함 8~72자)의 유일한 소스는 `PasswordPolicy`(`com.algogyeyak.auth.dto`)입니다 — `GET /auth/password-policy`로 frontend에 그대로 내려줍니다.

스택 개요와 시작 방법은 [README.md](./README.md)를 참고하세요.

## PR 작성 규칙

사용자가 "PR 작성해줘"라고 요청하면 아래 형식을 그대로 따르세요. `gh pr create`로 직접 열지 말고, 채팅에 복사 가능한 마크다운 텍스트로만 작성합니다 — 실제 PR 생성은 사용자가 합니다.

**PR 제목**

```
[타입] #이슈번호 제목
```

예시: `[feat] #10 방 생성 API 구현`

**PR 본문** — `<!-- -->` 안내 주석은 실제 작성 시 빼고, "관련 이슈"/"작업 내용"/"리뷰 포인트"는 현재 브랜치의 실제 커밋·diff를 근거로 채웁니다(`git log`, `git diff`로 확인 — 추측 금지). 체크리스트는 체크하지 않은 채로 두고 사용자가 직접 확인 후 체크합니다.

```markdown
## 📌 관련 이슈
- close #이슈번호

## ✨ 작업 내용
<!-- 어떤 변경 사항이 있었는지 주요 내용을 적어주세요. -->
- 

## 📸 스크린샷 / 테스트 결과
<!-- API 응답 결과(Postman/Swagger)나 실행 결과 스크린샷을 첨부해주세요. -->
- 

## 🔍 리뷰 포인트
<!-- 리뷰어가 집중해서 봐주었으면 하는 부분이 있다면 적어주세요. -->
- 

## ✅ 체크리스트
- [ ] 커밋 메시지 컨벤션을 준수했는가?
- [ ] 로컬에서 빌드 및 테스트가 성공했는가?
- [ ] 불필요한 주석이나 console.log를 제거했는가?
- [ ] 관련 문서를 함께 수정했는가?
```

## PR 리뷰 규칙

- 최소 **1명 이상 리뷰 후 merge**
- 리뷰 코멘트 반영 후 merge 진행
- 리뷰 반영 완료 후 재요청 코멘트 작성
- 충돌이 발생하면 작업자가 직접 해결 후 다시 확인 요청

## Merge 기준

- 리뷰 승인 완료
- 빌드 / 테스트 통과
- 충돌 없음
- PR 체크리스트 완료

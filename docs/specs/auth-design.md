# 인증(auth) 도메인 — 구현 현황 정리

## 배경 / 성격

이 문서는 **회고성(retroactive) 문서**입니다. 다른 문서들(checklist 등)은 구현 전에 브레인스토밍하며 함께 썼지만, 이 문서는 **원본 요구사항 명세서와 이미 완성된 실제 코드를 대조**해서 작성했습니다. 담당자(Auth 도메인 개발자) 없이 코드만 보고 정리한 부분이 있어, **⚠️ 확인 필요**로 표시한 항목은 실제 작업자의 확인이 필요합니다.

**범위**: `com.algogyeyak.auth.*`(인증/토큰/OAuth2)만 다룹니다. `com.algogyeyak.user.*`(프로필 등록/수정/닉네임 등 프로필 관리)는 별도 도메인이라 이 문서에서 제외합니다.

## 주요 Entity — 요구사항 대비 실제

| 요구사항 | 실제 구현 |
|---|---|
| `User`(id, email, passwordHash, nickname, status) | 동일 + `role`, `profileImageUrl`, `createdAt`/`updatedAt` 추가 (`provider`/`providerId`는 2026-07-30 제거 — 아래 참고) |
| `OAuthAccount`(별도 엔티티: id, userId, provider, providerUserId) | ✅ **(2026-07-28 구현 완료)** `UserSocialAccount`(`user_social_accounts`)로 구현. 아래 참고 |

**(2026-07-28) 다중 소셜 연동 구현** — 요구사항의 `OAuthAccount`를 `UserSocialAccount` 엔티티로 구현했다.

- **역할 분리**: "이 유저가 실제로 연동해둔 모든 소셜 계정 목록"의 유일한 소스는 `UserSocialAccount`다. LOCAL(이메일/비밀번호)은 이 테이블에 포함하지 않는다 — `User` 자체가 이미 email+passwordHash로 충분히 표현하고, 외부 provider_id 같은 게 애초에 없기 때문
- **제약**: `(provider, provider_id)` 전역 유니크(같은 소셜 계정이 두 User에게 동시에 연결 불가), `(user_id, provider)` 유니크(한 User가 같은 provider를 두 개 연동 불가)
- **`CustomOAuth2UserService.findOrCreateUser` 흐름**:
  1. `UserSocialAccount`에서 `(provider, providerId)`로 먼저 조회 → 있으면 그 User 반환(이미 연동된 provider로의 재로그인)
  2. 없으면 기존과 동일하게 검증된 이메일로 기존 계정(로컬 또는 다른 소셜) 매칭 시도 → 매치되면 새 `UserSocialAccount`를 추가(기존 연동은 그대로 유지 — 한 계정에 구글+카카오가 동시에 남는다)
  3. 둘 다 없으면 신규 User 생성 + 그 User의 첫 `UserSocialAccount` 생성(같은 REQUIRES_NEW 트랜잭션에서 함께 커밋 — 항상 짝으로 존재해야 함)
- **동시성**: 신규 생성 시 유니크 제약 충돌 복구는 기존 패턴(REQUIRES_NEW 격리 + 재조회)을 그대로 따름. 기존 계정에 새 provider를 연동할 때의 레이스(같은 계정에 동시에 같은 provider 연동 시도, 극히 드묾)도 같은 패턴으로 방어
- **마이그레이션**: 이 프로젝트는 Flyway/Liquibase 없이 Hibernate `ddl-auto`로 스키마를 관리하고 있어(다른 테이블들과 동일), 새 테이블은 별도 마이그레이션 스크립트 없이 자동 생성됨. 기존에 쌓인 실사용자 데이터가 없다는 전제(H2는 인메모리, 실 운영 배포 전 단계)라 과거 `users.provider`/`provider_id`를 `user_social_accounts`로 백필하는 마이그레이션은 별도로 작성하지 않음 — 만약 이미 운영 데이터가 있다면 배포 전에 백필 스크립트가 필요함
- 테스트: `CustomOAuth2UserServiceTest`에 다중 연동 시나리오 2개 추가(이미 연동된 provider로 재로그인 시 재연동 안 함, 두 번째 provider 연동 후에도 첫 provider가 여전히 유효), `CustomOAuth2UserServiceConcurrentLoginIntegrationTest`도 `UserSocialAccount` 기준으로 갱신

**(2026-07-30) `User.provider`/`providerId` 컬럼 제거** — 다중 소셜 연동 도입 이후 "가장 최근에 로그인에 쓴 수단"만 가리키는 캐시로 남겨뒀었지만, 실제 조회/판단 로직 어디에서도 읽지 않는 죽은 필드였다(연동 판단은 전부 `UserSocialAccount` 기준). `User` 엔티티와 `uk_user_provider_provider_id` 유니크 제약을 제거했고, `AdminAccountSeeder`의 "실제 소셜 사용자 이메일과 겹침" 가드도 `user.getProvider() != LOCAL` 대신 `userSocialAccountRepository.existsByUserId(user.getId())`(소셜 계정이 하나라도 연동돼 있으면 건드리지 않음)로 대체했다. 이 프로젝트엔 아직 Flyway/Liquibase가 없어 운영 DB 스키마는 수동 관리 대상이다 — 이미 배포된 환경이 있다면 `ALTER TABLE users DROP COLUMN provider, DROP COLUMN provider_id`(+ 유니크 제약 드롭)를 별도로 실행해야 한다.

## 이메일 회원가입 — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 이메일 중복 확인 | ✅ 동일 (정규화(trim+lowercase) 후 비교) |
| 비밀번호 정책: 8자 이상, 영문+숫자 조합 | 부분 일치 — 실제론 **8~72자**(상한 있음) + 영문·숫자 필수 + **공백 제외 ASCII 출력가능 문자만 허용**(기호는 허용, 한글 등 멀티바이트 불가). 72자 상한과 ASCII 제한은 BCrypt가 72바이트 초과 시 조용히 잘라버리는 문제 때문 |
| 비밀번호 해시 저장 | ✅ BCrypt |
| Access/Refresh Token 발급 | ✅ 동일, 회원가입 성공 시 즉시 쿠키로 발급 (자동 로그인) |
| 실패: 이메일 중복 | ✅ `AUTH_EMAIL_ALREADY_EXISTS`(409) |
| 실패: 비밀번호 정책 미충족 | ✅ 400 (필드 검증 에러) |
| 실패: 필수 입력값 누락 | ✅ 400 |
| *(요구사항에 없음)* | **닉네임 중복도 검증함** — `AUTH_NICKNAME_ALREADY_EXISTS`(409). 요구사항엔 닉네임 중복 실패 케이스가 없었는데 실제론 있습니다 |

## 이메일 로그인 — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 이메일로 User 존재 확인 | ✅ (탈퇴 계정 제외) |
| 비밀번호 대조 | ✅ |
| 성공 시 토큰 발급 | ✅ |
| 실패: 존재하지 않는 이메일 | ✅ — 단, **"소셜 로그인으로만 가입된 계정"과 완전히 동일한 에러 코드/메시지**(`AUTH_INVALID_CREDENTIALS`)를 반환함. 계정 존재 여부가 새어나가지 않도록 의도적으로 구분 안 함 |
| 실패: 비밀번호 불일치 | ✅ 위와 동일 코드로 합쳐짐 |
| 실패: 소셜 전용 계정으로 이메일 로그인 시도 | ✅ — 위와 동일하게 구분 없이 같은 에러 |

전체적으로 요구사항의 3가지 실패 사유를 **의도적으로 하나의 에러로 통합**한 형태입니다 (보안상 계정 존재 여부 비노출).

## 소셜 로그인 (구글/카카오) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 인증 코드 유효성 확인 | ✅ (Spring Security OAuth2 표준 흐름) |
| 사용자 식별 정보 조회 | ✅ (구글: `sub`/`email`/`name`/`picture`, 카카오: `id`/`kakao_account.*`) |
| `OAuthAccount` 기준 기존 가입 확인 | ✅ **(2026-07-28)** `UserSocialAccount`의 `(provider, providerId)` 기준 (위 "주요 Entity" 섹션 참고) |
| 신규 시 User+OAuthAccount 생성 | ✅ **(2026-07-28)** User + 그 첫 `UserSocialAccount`를 같은 트랜잭션에서 함께 생성 |
| 동일 이메일 기존 계정 연동 | ✅ — 단, **"검증된 이메일"일 때만** 연동함 (구글 `email_verified`, 카카오 `is_email_verified`). 검증 안 된 이메일은 아예 저장 안 하고 `null`로 둠 (계정 탈취 방지 목적) |
| 성공: 신규→온보딩, 기존→홈 | ✅ **(2026-07-28 확인 완료)** 백엔드는 신규/기존을 구분해 다른 곳으로 보내지 않지만, **프론트엔드가 "프로필 등록 여부"를 기준으로 사실상 동일한 결과를 만든다.** `frontend/app/oauth/callback/route.ts`가 로그인 성공 후 프로필을 조회해, 프로필이 없으면 `/mypage/profile`(온보딩)로, 있으면 `/home`으로 보낸다. 신규 가입자는 프로필이 없는 게 당연하니 자연스럽게 온보딩으로 가고, 기존 유저는 홈으로 간다 — "신규 계정 여부" 대신 "프로필 완료 여부"라는 대리 신호(proxy)를 쓰는 것뿐, 요구사항이 원하던 분기는 실질적으로 만족됨 |
| 실패: 제공자 인증/사용자 정보 조회 실패 | ✅ — 단, 세부 사유 구분 없이 전부 `?error=oauth_login_failed` 하나로 통합 |
| 실패: 사용자/연동 정보 생성 실패 | ✅ 동일 |

⚠️ **확인 필요**: 카카오는 현재 `profile_nickname` 스코프만 요청하고 있어서, **카카오 로그인 시 이메일 자체를 못 받아옵니다** (email 동의항목이 카카오 비즈니스 앱 검수 후에나 활성화 가능 — 코드 주석에 명시됨). 즉 지금은 카카오로는 기존 이메일 계정과 연동이 안 되고 항상 새 계정이 만들어질 가능성이 높습니다.

**(2026-07-28 조사)** 카카오 공식 문서 기준, 사업자등록번호 없는 **개인 개발자도 비즈 앱 전환이 가능**하며 조건은 (1) 앱 소유자(Owner) 본인인증 완료, (2) 카카오비즈니스 통합 서비스 약관 동의 — **서비스가 정식 출시(런칭)돼 있어야 한다는 요구사항은 명시돼 있지 않음.** 다만 그다음 단계인 "개인정보 동의항목(email) 심사" 신청 시에는 **실제 동작하는 회원가입 페이지 캡처본**을 제출해 신청 항목과 대조하므로, 최소한 동작하는 회원가입 화면은 있어야 함 — 저희는 로컬 이메일 가입 폼이 이미 있으니 이 조건은 충족한 상태로 보임. 즉 "정식 출시 후에만 가능"은 부정확하고, **본인인증 + 약관동의만 되면 지금도 비즈 앱 전환 신청이 가능해 보임**(심사 기간 영업일 3~5일). 다만 개인 개발자 비즈 앱은 사업자 정보가 없어 **비즈니스 채널 연결은 불가**하다는 제약이 있음. 정확한 절차는 카카오 디벨로퍼스 @app_review 문의로 재확인 권장.

## 토큰 검증 — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 요청 헤더의 Access Token 확인 | 헤더(`Authorization: Bearer`) **또는 쿠키**(`access_token`) — 헤더 우선, 없으면 쿠키 사용 |
| 서명/만료/사용자 식별 검증 | ✅ |
| 사용자 정상 상태 확인 | ✅ — `/auth/me` 호출 시 DB를 다시 조회해서 탈퇴 여부까지 재확인 (JWT 자체가 유효해도 탈퇴한 유저면 401) |
| 실패 사유 구분 제공 | ✅ **(2026-07-28 구현 완료, 3/4 구분)** 아래 참고 |

**(2026-07-28) 토큰 검증 실패 사유 구분 — 3가지 구현, "비활성 사용자"는 보류**

토큰 없음/무효/만료 3가지는 코드를 구분하도록 구현했다. `ErrorCode`에 `AUTH_TOKEN_MISSING`/`AUTH_TOKEN_INVALID`/`AUTH_TOKEN_EXPIRED`(전부 HTTP 401)를 추가하고, `JwtAuthenticationFilter`가 `parseClaims()`를 직접 호출해 `ExpiredJwtException`(만료)과 그 외 `JwtException`/`IllegalArgumentException`(형식 오류·서명 위조 등 무효)을 구분한다.

- 구조적으로 중요한 지점: 이 필터는 인증에 실패해도 예외를 던지지 않고 `SecurityContext`를 비운 채 다음 필터로 그냥 넘긴다. 그래서 "왜" 실패했는지를 이후 단계(Spring Security의 `authenticationEntryPoint`, 실제 401 응답을 만드는 지점)까지 전달할 방법이 없었다 — 이번에 필터가 실패 사유를 `request.setAttribute(JwtAuthenticationFilter.AUTH_FAILURE_REASON_ATTRIBUTE, errorCode)`로 남기고, `SecurityConfig`의 `authenticationEntryPoint`가 이 값을 읽어 응답 코드를 정하도록 연결했다(속성이 없으면 기본값 `UNAUTHORIZED`).
- 로그아웃으로 jti가 블랙리스트에 오른(revoke된) 토큰도 `AUTH_TOKEN_INVALID`로 분류한다 — "더 이상 쓸 수 없는 토큰"이라는 점에서 형식 오류/서명 위조와 같은 취급.
- **"비활성 사용자"(탈퇴 등)는 구분하지 않고 보류함.** 이유: 필터는 무상태 검증 원칙상 매 요청마다 DB를 조회하지 않는다(`com.algogyeyak.user` 조회는 `/auth/me`만 예외적으로 함). 비활성 사용자를 필터 레벨에서 구분하려면 모든 요청에 DB 조회를 추가해야 하는데, 이는 "Access Token 검증 시 외부 API/DB 미호출"이라는 비기능 요구사항과 충돌한다. 실제로 이 기능(계정 비활성화)이 아직 구현되어 있지 않기도 해서, 필요해지면 그때 다시 설계하기로 함. 지금은 `/auth/me`가 이미 하던 대로 컨트롤러 레벨에서 DB 재조회 후 `ErrorCode.UNAUTHORIZED`(코드 구분 없음, 메시지만 "존재하지 않거나 탈퇴한 사용자입니다")로 응답한다.
- **(2026-07-28 갱신)** 프론트엔드가 `isSessionInvalidErrorCode()`(`app/lib/api/http.ts`)로 이 세 코드(+`UNAUTHORIZED`)를 전부 인식하도록 갱신함(`fix/auth-modify_according_docs` 브랜치, frontend `dev` 머지 대기 중) — 갱신 전에는 `PasswordUpdateFormClient`가 `UNAUTHORIZED` 하나만 체크하고 있어서, 이 코드 세분화가 먼저 머지되면 만료/무효 케이스에서 재로그인 유도가 실제로 깨지는 상태였음(리뷰로 발견). 다만 화면에 보여줄 문구 자체는 여전히 하나로 통합 — "재로그인 필요 여부" 판단에만 네 코드를 씀.

## 토큰 재발급 — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| Refresh Token 서명/만료 검증 (별도 저장소 조회 없음) | ⚠️ **다름** — 실제론 **저장소에 저장하고 조회**합니다(해시값으로). "별도 저장소 조회 없음"이라는 요구사항과 반대로, Refresh Token은 별도 저장소에서 관리됩니다 (2026-08-03부터 **Redis** — 그 전엔 DB `refresh_tokens` 테이블. 아래 "재결정" 문단 참고) |
| 유효 시 새 Access Token 발급 | ✅ — 추가로 **Refresh Token도 매번 새로 발급(rotate)** 됩니다 (요구사항엔 없던 동작) |
| 실패 시 재로그인 요청 | ✅ |
| 실패 사유: 없음/유효하지 않음/만료됨 | ✅ 메시지는 구분되지만, 에러 코드는 전부 `UNAUTHORIZED`로 동일 |

🔁 **(2026-08-03 재결정)**: 2026-07-29 결정("DB 유지")을 뒤집고 **Redis로 이전**함. 코드 리뷰에서 access token blacklist/refresh token/유저 role·status 캐시/관리자 통계 캐시를 한 PR에 다 넣으면 장애 원인 분리가 어렵다는 지적이 있었고, 이에 따라 blast radius가 작은 것부터 단계적으로 도입하기로 함 — 1) access token blacklist, 2) refresh token 저장소(이번 범위), 3) 유저 role/status 캐시, 4) 관리자 통계 캐시 순. Refresh Token은 이제 **Redis**(`RefreshTokenService` — `by-hash:{tokenHash}`→userId, `by-user:{userId}`→tokenHash 역인덱스, 두 키 모두 TTL=refresh token 유효기간)에 저장한다. DB `refresh_tokens` 테이블/`RefreshToken`/`RefreshTokenRepository`는 삭제했다. Redis TTL이 만료를 자동으로 처리하므로 더 이상 `expiresAt` 컬럼/수동 만료 검사가 없고, 그 대신 자연 만료와 "애초에 모르는 토큰"이 둘 다 "키 없음"으로만 관측되어 `AUTH_REFRESH_TOKEN_EXPIRED`는 더 이상 던져지지 않는다(전부 `AUTH_REFRESH_TOKEN_INVALID`) — frontend는 애초에 이 둘을 구분하지 않았으므로(`SESSION_INVALID_ERROR_CODES`에 둘 다 없음) 영향 없음. **Redis 장애 시 정책은 fail-closed** — access token blacklist 확인/refresh token 발급·회전·폐기 어느 쪽이든 Redis에 연결할 수 없으면 인증을 통과시키지 않고 명시적으로 실패(blacklist 확인은 401, 그 외는 `AUTH_TOKEN_STORE_UNAVAILABLE`/503)시킨다 — 가용성보다 "무효화된 토큰이 장애 상황에서 재사용되지 않는 것"을 우선한 것.

<details><summary>이전 결정 (2026-07-29, 취소됨)</summary>

✅ **(2026-07-29 확정, 2026-08-03 취소)**: 요구사항 문서의 "별도 저장소 없이 서명/만료만 검증"은 "Redis 같은 별도 인메모리 캐시 없음"을 의미했던 것으로 확정. Refresh Token은 계속 **DB 테이블(`refresh_tokens`)**에 저장하는 현재 방식을 그대로 유지하기로 결정함 (즉시 무효화/유저당 1세션/탈퇴 차단 요구사항 때문에 저장소 자체는 불가피 — 아래 설명 참고). (참고: 유저당 1개 세션만 유지하는 방식이라, 이 저장소는 "다중 로그인 방지" 용도도 겸하고 있습니다.)

</details>

**(2026-07-28) 왜 어떤 형태로든 저장소가 필요한가** — Refresh Token을 "서명/만료만 보고 저장소 조회 없이" 검증하려면, JWT처럼 그 자체로 서명 검증이 가능한 형태여야 한다. 하지만 지금 방식은 로그아웃 시 즉시 무효화(row 삭제), 유저당 1세션 강제(재로그인 시 이전 토큰 자동 무효화), 탈퇴 유저 차단을 전부 만족해야 하는데, 이건 전부 "발급된 토큰 하나하나의 현재 상태"를 서버가 언제든 뒤집을 수 있어야 가능한 요구사항이다 — 서명 검증만으로는 절대 못 만든다(서명은 발급 시점에 고정되고, 이미 서명된 토큰을 나중에 "무효"로 만들 방법이 서명 자체엔 없음). 그래서 "요청/재시작에 걸쳐 공유되는 조회 가능한 저장소"가 사실상 필수이고, Redis와 DB 둘 중 하나를 골라야 하는 문제였지 "아예 안 쓴다"는 선택지는 없었다. 게다가 이 앱은 `User`/`Property`/`Checklist` 등 이미 DB 없이는 동작할 수 없는 서비스라, refresh token만을 위해 저장소를 새로 들이는 것도 아니고 **이미 있는 DB에 테이블 하나(`refresh_tokens`) 더 추가한 것뿐**이다. Redis를 새로 도입했다면 만료 자동 삭제(TTL) 정도의 이점은 있었겠지만, 별도 인프라를 하나 더 운영해야 하는 비용 대비 이 규모에서는 이득이 크지 않다고 판단한 것으로 보임 — "별도 저장소 없음"이 원래 "Redis 같은 새 인프라 없음"을 뜻했을 가능성이 높은 이유이기도 하다.

## 로그아웃 — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 클라이언트 토큰 제거 | ✅ 쿠키 삭제 |
| *(요구사항에 없음)* | Refresh Token은 **서버에서도 즉시 삭제(DB row 삭제)**됩니다. Access Token도 **jti 블랙리스트로 즉시 무효화**됩니다(아래 참고) — 더 이상 "남은 유효시간 동안 계속 유효"가 아님 |

**(2026-07-28 갱신) jti 블랙리스트 구현 완료** — 위에 남아있던 "Swagger 문서(jti 블랙리스트)와 실제 코드 불일치" 이슈는 해결됨:
- `JwtProvider.createAccessToken()`이 access token 발급 시 `jti`(UUID) 클레임을 심음
- 로그아웃 시 `AccessTokenRevocationService.revoke(jti, expiresAt)`가 그 jti를 Redis에 등록(**2026-08-03부터 Redis** — 이전엔 `revoked_access_tokens` DB 테이블. 키 TTL을 만료 시각과 동일하게 맞춰 자연 만료를 Redis가 자동 처리하므로 별도 청소 로직이 없다)
- `JwtAuthenticationFilter`가 매 요청마다 `isRevoked(jti)`를 확인해, 서명/만료가 유효해도 블랙리스트에 있으면 인증 거부. Redis 장애 시에는 fail-closed로 "무효화된 것으로 간주"해 인증을 거부한다(필터가 예외를 던지지 않는 설계이므로 이 판단만 뒤집는다)
- 최초 구현 때는 `logout()`이 access token을 `access_token` 쿠키에서만 찾아, `Authorization: Bearer` 헤더로만 인증하는 클라이언트(Swagger/Postman 등)는 로그아웃해도 무효화가 안 되는 버그가 있었음 — `JwtAuthenticationFilter.resolveToken`(헤더 우선, 쿠키 폴백)과 동일한 규칙을 `logout()`도 쓰도록 고쳐서 해결
- jti는 **토큰 단위** 무효화라 유저 단위가 아님 — 같은 유저가 두 브라우저에서 각각 로그인했다면 한쪽 로그아웃이 다른 쪽 access token까지 무효화하지는 않음(각자 다른 jti)
- 현재 `fix/auth-access_token&refresh_token` 브랜치에 구현되어 있고, 아직 `dev`에 머지되지 않음

## 비기능 요구사항 — 대조

| 항목 | 요구사항 | 실제 |
|---|---|---|
| Access/Refresh 만료시간 분리 | O | ✅ Access 30분 / Refresh 14일 |
| Refresh Token: HttpOnly/Secure/SameSite 쿠키 | O | ✅ (`Secure`는 dev=false, prod=true 기본값) |
| Local Storage 미사용 | O | ✅ 쿠키만 사용 |
| 시크릿 키 소스코드 미포함 | O | ✅ 환경변수, prod는 기본값 없이 fail-fast |
| 실패 사유 과도한 노출 금지 | O | ✅ (계정 존재 여부 비노출 등, 위 참고) |
| HTTPS(운영) | O | ⚠️ 코드에서 강제하는 부분은 못 찾음 — 인프라(로드밸런서 등) 레벨에서 처리하는 것으로 추정, 확인 필요 |
| 동일 소셜 계정 중복 생성 방지 | O | ✅ `(provider, providerId)` 유니크 제약 + 동시성 레이스 처리(재조회 후 재시도) |
| Access Token 검증 시 외부 API 미호출 | O | ✅ 로컬 서명 검증만 함 |

## 요구사항에 없던 추가 구현

- `PATCH /auth/password` — 로그인 후 비밀번호 설정/변경 (소셜 전용 계정이 로컬 로그인 수단을 추가하는 용도 포함)
- **(2026-07-28)** `GET /auth/password-policy` — frontend가 회원가입/비밀번호 변경 폼의 `<input pattern="...">`/안내 문구를 하드코딩하는 대신 이 엔드포인트로 런타임에 받아오도록 만든 것. `PasswordPolicy`(SignupRequest/PasswordUpdateRequest가 실제 검증에 쓰는 바로 그 상수)가 유일한 소스가 되어, 여기만 바꾸면 백엔드 검증과 프론트 폼 힌트가 항상 같이 바뀐다. 인증 불필요(로그인 전 회원가입 폼에서도 호출)
- `POST /auth/dev-login` — 개발/데모용 관리자 로그인 백도어 (`app.dev-login.enabled`일 때만 동작, 평소엔 404). 관리자 계정은 앱 기동 시 자동 시딩/복구됨
- 이메일 정규화(trim+lowercase)를 모든 저장/조회 지점에 일관 적용
- 회원가입 INSERT를 별도 트랜잭션으로 분리해 Hibernate 세션 오염 방지 (동시 가입 레이스 대응)

## 남은 이슈 / 확인 필요 총정리

1. ~~`OAuthAccount` 별도 엔티티 없이 `User`가 provider 1개만 갖는 구조 — 다중 소셜 연동 필요 여부~~ — ✅ 2026-07-28 구현 완료. `UserSocialAccount` 연동 테이블 추가, 한 유저가 구글/카카오를 동시에 연동 가능(위 "주요 Entity"/"소셜 로그인" 섹션 참고). `User.provider`/`providerId`는 제거하지 않고 "가장 최근 로그인 수단" 캐시로 의미만 재정의함 — 기존 데이터/코드(AdminAccountSeeder 등)를 건드리지 않아 마이그레이션 스크립트가 필요 없었음
2. 카카오 email 스코프 미승인 상태 — **(2026-07-29 확정) 보류/후순위.** 카카오 계정 연동이 사실상 항상 신규 생성되는 현재 동작은 알려진 상태이며, 지금 당장 처리하지 않기로 함.
   **⚠️ 코드 작업이 아님 — 별도 도메인/트랙으로 분리.** 카카오 디벨로퍼스 콘솔에서 앱 소유자 본인인증 → 비즈 앱 전환 → 개인정보(email) 동의항목 심사 신청까지 전부 **카카오 개발자 콘솔 운영 업무**이지 백엔드/프론트 코드 변경이 아니다. `com.algogyeyak.auth`(코드) 관점에서 할 일은 승인이 완료된 뒤 `application.yml`의 `scope: profile_nickname` → `scope: profile_nickname, account_email`로 한 줄 바꾸는 것뿐. 신청 자체는 팀의 운영/기획 쪽에서 여유 있을 때 트래킹하고, 승인 완료 시점에 이 코드 한 줄만 바꾸면 되는 후속 작업으로 남겨둠
3. ~~신규/기존 사용자 리다이렉트 분기가 백엔드가 아닌 프론트 담당으로 보이는데, 실제로 그렇게 구현되어 있는지~~ — ✅ 2026-07-28 확인 완료. `frontend/app/oauth/callback/route.ts`가 프로필 등록 여부로 분기 처리함(위 소셜 로그인 섹션 참고)
4. ~~"Refresh Token 별도 저장소 없음"(요구사항) vs 실제 DB 저장 — 원래 의도 확인~~ — ✅ 2026-07-29 DB 유지로 확정했다가, 🔁 2026-08-03 Redis로 재전환(위 "토큰 재발급" 섹션 참고). PR 범위는 access token blacklist + refresh token 저장소까지만 — 유저 role/status 캐시와 관리자 통계 캐시는 별도 단계로 후순위.
5. ~~`/auth/logout`의 Swagger 설명(jti 블랙리스트)과 실제 코드 불일치~~ — ✅ 2026-07-28 해결. jti 블랙리스트 구현 완료(로그아웃 섹션 참고), `fix/auth-access_token&refresh_token` 브랜치, `dev` 머지 대기 중
6. ~~토큰 검증 실패 사유가 전부 401로 뭉뚱그려지는 게 의도인지 (요구사항은 사유 구분을 요구함)~~ — ✅ 2026-07-28 3/4 해결. `AUTH_TOKEN_MISSING`/`AUTH_TOKEN_INVALID`/`AUTH_TOKEN_EXPIRED` 구현 완료(토큰 검증 섹션 참고), `fix/auth-access_token&refresh_token` 브랜치, `dev` 머지 대기 중. "비활성 사용자" 구분은 무상태 검증 원칙과 충돌하고 실제 기능도 아직 없어 보류

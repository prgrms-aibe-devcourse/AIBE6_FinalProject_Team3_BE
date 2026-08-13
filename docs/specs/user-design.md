# 사용자(user) 도메인 — 구현 현황 정리

## 배경 / 성격

`auth-design.md`와 같은 방식의 **회고성(retroactive) 문서**입니다. 원본 요구사항 명세서와 실제 코드를 대조했고, 담당자 확인이 필요한 부분은 **⚠️ 확인 필요**로 표시했습니다.

**범위**: `com.algogyeyak.user.*`(프로필 등록/조회/수정, 회원 탈퇴, 닉네임 중복 확인)만 다룹니다. 인증(로그인/토큰)은 `auth-design.md` 참고.

## 주요 Entity — 요구사항 대비 실제

요구사항과 거의 동일합니다.

| Entity | 요구사항 | 실제 |
|---|---|---|
| `User` | id, nickname, email, profileImageUrl, status | 동일 (+ auth 관련 필드는 auth-design.md 참고) |
| `UserPreference` | id, userId, interestRegion, transactionType, currentStage | 동일 — `User`와 `@OneToOne`(userId 유니크) |

## 프로필 등록 (`POST /users/me/profile`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 생성형 AI 기반 운용 사전 고지 | ❌ **백엔드에 관련 로직/응답 필드가 전혀 없습니다.** 프론트엔드가 화면에서만 고지 문구를 보여주는 것으로 추정 — 확인 필요 |
| 입력값 검증 | 부분 구현 — 관심지역 필수(`@NotBlank`), 거래유형 필수(`@NotNull`), 자취/취업여부(currentStage)는 선택. **(2026-08-12 변경)** 닉네임은 더 이상 이 API의 입력값이 아님 — 아래 참고 |
| UserPreference 저장 | ✅ |
| currentStage에 따른 홈 위젯 우선순위 결정 | ❌ **백엔드는 currentStage 값을 그대로 저장만 하고, 우선순위를 계산하거나 응답에 담는 로직이 없습니다.** 위젯 우선순위 자체가 프론트엔드 책임으로 보임 — 확인 필요 |
| 성공: 온보딩 분기 반영된 홈으로 이동 | 백엔드는 이동 경로를 결정하지 않고 저장된 `UserProfileResponse`만 반환 — 라우팅은 프론트 담당으로 추정 |
| 실패: 필수 입력값 누락 | ✅ 400 |
| 실패: 인증되지 않은 사용자 | ✅ 401 (JWT 필터) |
| *(요구사항에 없음)* | **이미 프로필이 등록된 경우** 실패 처리됨 — **(2026-07-31)** 409 `USER_PROFILE_ALREADY_EXISTS`("이미 프로필이 등록되어 있습니다."). 요구사항 실패 목록엔 없던 케이스 |

**(2026-08-12 변경, 닉네임 처리 제거)** 이 API는 더 이상 `nickname`을 받지 않습니다 — `ProfileRegisterRequest.nickname` 필드와 `UserService.registerProfile()`의 닉네임 변경 분기(중복 검사 + 원자적 커밋)를 제거했습니다(백엔드 커밋 `7a5666c`). "실패: 중복된 닉네임"/"실패: 허용되지 않는 닉네임"/"닉네임 길이 초과"는 더 이상 이 API의 실패 케이스가 아니며, 닉네임 변경은 이제 `PATCH /users/me`(아래 "프로필 수정" 절)로만 가능합니다.

원래부터 죽어있던 코드는 아니었습니다 — 히스토리를 보면:
- **2026-07-21** User 도메인 최초 구현 시점부터 온보딩 화면에 닉네임 입력 UI가 있었고, 요청에도 `nickname`이 포함됐습니다(프론트/백엔드 둘 다 이 시점에 생성).
- **2026-07-31** 프론트 커밋(`fa834ab`, "마이페이지, 홈 화면, 회원가입 화면 프론트 수정 작업")에서 온보딩 화면의 닉네임 입력 UI를 `mode === 'edit'` 조건부로 숨겨, 온보딩에서는 더 이상 노출되지 않게 됐습니다. 이때는 UI만 숨겼을 뿐 요청 body에서 필드 자체를 뺀 건 아니라, 백엔드 코드(닉네임 처리 분기)는 그대로 남아 죽은 코드가 됐습니다.
- **2026-08-12** 프론트 커밋(`05b9426`, "프로필 등록 시 닉네임 관련 로직 제거")에서 요청 body에서 `nickname` 필드 자체를 제거했고, 같은 날 백엔드도 대응 제거(`7a5666c`)했습니다.

## 프로필 조회 (`GET /users/me`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 인증된 사용자 정보 확인 | ✅ |
| 프로필+관심정보 조회 | ✅ (`UserPreference`가 없으면 관련 필드는 `null`로 반환 — 아직 등록 안 한 상태도 에러 없이 조회 가능) |
| 실패: 인증 실패 | ✅ 401 |
| 실패: 존재하지 않는 사용자 | ✅ 404 `NOT_FOUND` |
| 실패: 탈퇴/비활성 사용자 | ✅ **(2026-08-11)** 상태 코드는 여전히 404로 동일하지만, `code`/`message`는 구분됨 — 탈퇴는 `USER_WITHDRAWN`("이미 탈퇴한 사용자입니다."), 정지는 `USER_SUSPENDED`("정지된 사용자입니다.") |

## 프로필 수정 (`PATCH /users/me`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 본인 프로필인지 확인 | ✅ (경로에 별도 id 없이 JWT의 userId만 사용 — 애초에 남의 프로필 지정 자체가 불가능한 구조) |
| 길이/형식 검증 | **(2026-08-11, `feat/user-nickname-format` 브랜치 — 아직 `dev` 미머지)** 닉네임은 길이+형식(한글/영문/숫자, `NicknamePolicy`) 둘 다 검증하지만 본인 기존 닉네임과 실제로 다를 때만 — DTO의 `@Pattern`이 아니라 `UserService`가 "바뀔 때만" 검사(아래 참고). **머지 전까지는 `dev`/배포판에서 여전히 닉네임 길이만 검증됩니다.** 나머지 필드는 형식 검증 없음 |
| 닉네임 중복 확인 | ✅ (변경 시에만) |
| 변경 저장 | ✅ — 요청에 담긴 필드만 부분 업데이트 (null이 아닌 것만 반영) |
| 실패: 인증 실패 | ✅ 401 |
| 실패: 중복된 닉네임 | ✅ **(2026-07-31)** 409 `USER_NICKNAME_ALREADY_EXISTS` |
| 실패: 잘못된 입력값 | **(2026-08-11, 미머지 브랜치)** 길이+형식(한글/영문/숫자) — 단, 욕설/금칙어는 아직 안 걸러짐(위 "프로필 등록" 표 참고) |

**(2026-08-11 설계 결정, `feat/user-nickname-format` 브랜치 — 아직 `dev` 미머지)** 닉네임 형식(`NicknamePolicy`) 검증을 `ProfileRegisterRequest`/`ProfileUpdateRequest`의 `@Pattern`으로 걸면 안 되는 이유가 있었습니다 — 카카오/구글 OAuth 가입자는 이 정책을 거치지 않고 만들어진 닉네임(소셜 제공자가 내려준 값 그대로, 공백·특수문자 포함 가능)을 가질 수 있는데, 프론트는 닉네임을 안 바꿔도 현재 값을 매 요청 그대로 재전송합니다. DTO에 무조건 `@Pattern`을 걸면 그런 사용자는 닉네임을 건드리지도 않았는데 프로필 저장 자체가 막힙니다. 그래서 `UserService.registerProfile()`/`updateMyProfile()`이 이미 갖고 있던 "본인 기존 닉네임과 실제로 다를 때만 중복 검사" 분기 안에 형식 검사(`validateNicknameFormat()`)도 같이 넣는 방식으로 해결했습니다 — `SignupRequest`(로컬 회원가입)는 항상 새로 정하는 값이라 이 문제가 없어 그대로 DTO `@Pattern`을 유지합니다.

**(2026-08-03 변경)** `ProfileUpdateRequest`에서 `profileImageUrl` 필드가 제거되어, 이 API로는 더 이상 프로필 이미지를 바꿀 수 없습니다. 이미지 형식/크기 검증을 포함한 프로필 이미지 변경은 아래 "프로필 이미지 업로드/초기화" 절의 전용 엔드포인트로 이관됐습니다.

## 프로필 이미지 업로드/초기화 (`POST /users/me/profile-image/presign` → `POST /users/me/profile-image/confirm`, `DELETE /users/me/profile-image`) — 요구사항 대비

**(2026-08-03~04 신규 구현)** 클라이언트가 URL 문자열을 직접 넘기던 이전 방식을 걷어내고, S3 presign/confirm 2단계 플로우로 전환했습니다.

1. `POST /users/me/profile-image/presign` — `contentType`/`fileSize`/`fileExtension`을 받아 검증 후, S3에 직접 PUT할 수 있는 presigned URL(5분 유효)과 `key`를 반환. 클라이언트는 이 URL로 S3에 직접 업로드.
2. `POST /users/me/profile-image/confirm` — presign에서 받은 `key`로 실제 업로드가 끝났는지 S3에 `HeadObject`로 재확인하고, 통과하면 `User.profileImageUrl`을 영구 공개 URL로 갱신. 이전 이미지가 우리 버킷 소유였다면 best-effort로 삭제(실패해도 요청 자체는 성공 처리, 로그만 남김).
3. `DELETE /users/me/profile-image` — `profileImageUrl`을 `null`(기본 이미지)로 되돌림. 이미 기본 상태여도 에러 없이 성공하는 멱등 동작. 이전 이미지가 있었다면 마찬가지로 best-effort 삭제.

**(2026-08-04 추가)** presign이 발급될 때마다 업로드 대상 객체에 `status=pending` 태그를 서명에 포함시켜 걸어두고(`S3PresignService.PENDING_UPLOAD_TAG`), presign 응답(`PresignedUploadResponse`)에도 이 태그 값을 `tagging` 필드로 함께 내려줍니다 — 클라이언트는 S3에 PUT할 때 `x-amz-tagging` 헤더로 이 값을 그대로 실어 보내야 하며, 값이 다르면 서명 불일치로 S3가 403을 반환합니다. 버킷 Lifecycle 규칙(AWS 콘솔에서 설정, **만료 기간 1일로 확인됨**)이 이 태그가 붙은 채로 1일 넘게 남아있는 객체를 자동 삭제하도록 되어 있어, presign만 받고 실제 업로드까지 했지만 `confirm`을 끝내 호출하지 않은 채 이탈한 경우에도 애플리케이션이 직접 정리하지 않고 S3가 최대 1일 내로 자동 청소합니다. `confirm` 성공 시에는 이 태그를 지워(`clearPendingTag`) 정상 확정된 이미지가 나중에 Lifecycle 규칙에 의해 지워지는 일이 없도록 합니다.

**(2026-08-04 추가, 작업 중)** confirm/reset이 교체·초기화된 **이전** 이미지를 지울 때도 같은 안전망을 적용하도록 `deleteObject` 대신 `S3PresignService.deleteReplacedObject`를 쓰게 바뀌었습니다. 즉시 삭제를 시도하기 전에 그 객체를 먼저 다시 `status=pending`으로 태깅해두고(`tagAsPending`), 그 다음 즉시 삭제를 시도합니다 — 태깅이 성공한 뒤라면 즉시 삭제가 권한/네트워크 문제로 실패해도 위 Lifecycle 규칙이 나중에 대신 정리해주므로, 아래 "남은 이슈" 8번이 우려했던 "best-effort 삭제 실패 시 영원한 고아 객체"가 더 이상 아니라 "Lifecycle 만료 기간까지는 남아있다가 결국 정리되는 객체"가 됩니다. 다만 태깅 자체도 실패하고 즉시 삭제도 실패하는 이중 실패의 경우는 여전히 안전망 밖입니다.

| 요구사항 | 실제 구현 |
|---|---|
| 본인 프로필인지 확인 | ✅ JWT의 userId 기준 |
| 지원 형식 제한 | ✅ jpg/jpeg/png만 허용(`S3ImagePurpose.PROFILE`) — presign 단계에서 `contentType` 화이트리스트 검증 + confirm 단계에서 실제 업로드된 객체의 `Content-Type`을 재검증 |
| 크기 제한 | ✅ 5MB — presign 시 서명에 `Content-Length`를 포함시켜 S3가 다른 크기의 PUT을 403으로 거부하게 하고, confirm 시 실제 업로드된 객체 크기도 재검증 |
| 실패: 지원하지 않는 이미지 형식 | ✅ 400 `FILE_CONTENT_TYPE_NOT_ALLOWED` |
| 실패: 이미지 크기 초과 | ✅ 400 `FILE_TOO_LARGE` (confirm 단계에서 초과가 발견되면 해당 객체를 즉시 삭제 후 에러) |
| 실패: 인증 실패 | ✅ 401 |
| *(요구사항에 없음)* | confirm 시점에 아직 S3에 실제 업로드가 안 된 key라면 404 `FILE_UPLOAD_NOT_COMPLETED` (presigned URL만 발급받고 PUT을 안 했거나, 5분 만료 후 뒤늦게 confirm한 경우) |
| *(요구사항에 없음)* | confirm 요청의 `key`가 본인 소유 prefix(`profile-images/{userId}/`)가 아니면 403 `FILE_KEY_ACCESS_DENIED` — 다른 사용자나 다른 도메인(매물/계약서 이미지)의 key를 그대로 넘겨 확정시키는 것을 차단 |

## 회원 탈퇴 (`DELETE /users/me`) — 요구사항 대비

| 요구사항 | 실제 구현 |
|---|---|
| 본인 여부 확인 | ✅ |
| 상태를 탈퇴로 변경 | ✅ |
| 개인정보 삭제/익명화 | ✅ **(2026-08-10)** `User`의 nickname/email/passwordHash/profileImageUrl은 복원 불가능한 값으로 치환되고 `withdrawnAt`에 탈퇴 시각도 기록됩니다. `UserSocialAccount`(OAuth 연동정보)·`UserPreference`(관심지역/거래유형/자취여부)·본인 소유 `Checklist`는 하드 삭제되고, 본인 소유 `Property`는 기존 soft-delete(`Property.delete()`)를 재사용해 상태만 `DELETED`로 바꿉니다(다른 유저의 체크리스트·risk-analysis 기록이 이 매물을 참조하고 있어 row 자체는 보존) |
| 클라이언트 인증정보 제거 | ✅ **(2026-08-11)** `AuthController.logout()`에서 뽑아낸 `SessionLogoutService`를 재사용해, 탈퇴 커밋 후 refresh token 무효화(Redis)·access token jti 블랙리스트 등록·access/refresh 쿠키 삭제까지 처리합니다. 단, Redis 장애로 이 세션 무효화가 실패해도 탈퇴 자체는 이미 커밋된 뒤라 되돌리지 않고, `UserController.withdraw()`가 그 예외를 로그만 남기고 삼켜 응답은 항상 200으로 나갑니다 — 이 경우 쿠키가 자연 만료 전까지 남아있을 수 있지만, 이후 요청은 `JwtAuthenticationFilter`가 탈퇴 상태를 다시 확인해 차단하므로 보안 구멍은 아닙니다 |
| 실패: 인증 실패 | ✅ 401 |
| 실패: 이미 탈퇴한 사용자 | ✅ **(2026-08-11)** 도달 불가능했던 `User.withdraw()` 내부 가드는 제거했습니다 — 유일한 호출부인 `UserService.withdraw()`가 그보다 먼저 `getActiveUserOrThrow()`로 활성 사용자만 걸러내기 때문에 애초에 실행될 수 없는 코드였습니다. 대신 `getActiveUserOrThrow()` 자체가 탈퇴한 사용자를 404 `USER_WITHDRAWN`("이미 탈퇴한 사용자입니다.")으로, 정지된 사용자를 404 `USER_SUSPENDED`("정지된 사용자입니다.")로 구분해서 던지도록 변경해 실제로 구분되는 실패 사유를 응답합니다 |
| 실패: 사용자 데이터 처리 실패 | 전용 처리 없음 (일반 500) |
| *(요구사항에 없음)* | **(2026-08-10 해결됨)** 탈퇴 시 연관 데이터 처리 정책을 확정하고 구현했습니다 — OAuth 연동정보(`UserSocialAccount`)는 하드 삭제(안 지우면 `(provider, provider_id)` unique 제약 때문에 같은 소셜 계정으로 재가입이 영구히 막힘), 본인 소유 `Checklist`는 하드 삭제, 본인 소유 `Property`는 기존 soft-delete를 재사용(다른 유저의 체크리스트·risk-analysis 기록이 이 매물을 참조하므로 row는 보존). `ContractAnalysis`는 영속성 계층(`@Entity`/`@Repository`) 자체가 없는 stateless 도메인이라 별도 처리가 필요 없음을 확인했습니다. (참고: **전수조사 결과 재확인** — `CustomOAuth2UserService.rejectIfBlocked()`(재로그인 시점인 `findOrCreateUser`의 기존 `UserSocialAccount` 매칭 분기와, 이메일로 기존 계정에 연동하는 `linkToExistingAccountByEmail` 분기 둘 다에서 호출됨)가 `isWithdrawn() || isSuspended()`를 확인해 거부하므로, 탈퇴 계정은 OAuth 재로그인으로도 로그인할 수 없습니다(로컬/refresh/JWT 필터와 동일) — ~~`account_blocked`로 거부~~ **(2026-08-12 정정)** 이후 계정 존재 비노출 원칙에 맞춰 일반 실패 코드(`oauth_login_failed`)로 통일됨(auth-design.md 전수조사 결과 참고) — 아래 "전수조사 결과" 참고) |

## 비기능 요구사항 — 대조

| 항목 | 요구사항 | 실제 |
|---|---|---|
| 본인만 조회/수정 | O | ✅ 모든 엔드포인트가 JWT의 userId 기준으로만 동작 (다른 유저 id를 지정할 방법 자체가 없음) |
| 이메일/소셜 식별정보 비공개 | O | ✅ 본인 데이터만 반환하는 구조라 타인에게 노출될 경로 없음 |
| 이미지 형식/크기 검증 | O | ✅ **(2026-08-03 구현)** presign/confirm 이중 검증 — 위 "프로필 이미지 업로드/초기화" 절 참고 |
| 불필요한 개인정보 미수집 | O | 판단 어려움 — 확인 필요 |
| 탈퇴 시 삭제/익명화 | O | ✅ **(2026-08-10)** `User` 익명화 + `UserSocialAccount`/`UserPreference`/본인 `Checklist` 하드 삭제 + 본인 `Property` soft-delete |
| OAuth 연동정보 탈퇴 처리 기준 | O(별도 정의) | ✅ **(2026-08-10)** 하드 삭제로 확정 — 재가입 시 동일 소셜 계정 재연동이 가능해야 하므로 |
| 선택 정보는 건너뛰기 가능 | O | ✅ `currentStage` 등 선택 필드는 실제로 선택 |
| 오류 항목/수정 방법 명확 표시 | O | ✅ Bean Validation 메시지가 필드별로 내려감 |
| 불필요한 필수 입력 강제 안 함 | O | ✅ |
| 닉네임 중복: 앱 검증 + DB 제약 동시 적용 | O | ✅ `existsByNicknameAndIdNot` + `User.nickname` DB unique 제약 |
| 탈퇴 식별자 재사용 방지 | O | ✅ (IDENTITY PK 재사용 없음, 익명화 값도 id 기반이라 유일함) |
| 프로필 조회 시 매물/계약서 상세 미조회 | O | ✅ `UserService`는 `User`/`UserPreference`만 다룸 |
| 이미지 적정 크기 제공 | O | 부분 — 형식/용량 제한(jpg/jpeg/png, 5MB 이하)만 있고, 리사이징/최적화 로직은 없음(원본을 그대로 저장) |

## 요구사항에 없던 추가 구현

- `GET /users/nickname-check?nickname=` — 닉네임 중복을 사전에 확인할 수 있는 별도 엔드포인트 (요구사항엔 명시 안 됐지만 프로필 등록/수정 UX상 필요했을 것으로 추정). **(2026-08-06 회원가입 화면 인증 오류 수정)** 회원가입 화면(로그인 전)에서도 이 엔드포인트를 호출하는데, 원래 인증을 요구하도록 되어 있어 비로그인 상태로는 호출 자체가 막혀 있었음. `SecurityConfig`에 `permitAll`을 추가해 로그인 여부와 무관하게 호출 가능하도록 열었고, `UserController.checkNickname`은 비로그인 요청에서 `@AuthenticationPrincipal`이 `null`로 주입되는 것에 대응해 `userId`를 null-safe하게 꺼내도록, `UserService.checkNicknameAvailable`은 `userId`가 null이면(비로그인) 본인 제외 없이 `existsByNickname`으로 전체 중복만 검사하고 `userId`가 있으면(로그인 후 프로필 수정 화면) 기존처럼 `existsByNicknameAndIdNot`으로 본인을 제외하고 검사하도록 분기 처리했다 — 이 분기가 없던 이전 코드는 비로그인 호출 시 `userDetails.userId()`에서 NPE가 나거나, null을 그대로 `existsByNicknameAndIdNot`에 넘기면 SQL의 `id <> NULL`이 항상 거짓이 되어 중복이어도 무조건 `available=true`로 잘못 판정되는 문제가 있었음.
- `UserProfileResponse`에 `hasPassword`(boolean) 필드 — 소셜 전용 계정인지 로컬 비밀번호가 있는지 프론트가 판단할 수 있게 해줌 (auth 쪽 기능과 연결됨)
- 프로필 이미지 변경이 presign/confirm 2단계 API로 분리됨 — 요구사항엔 "이미지 업로드" 정도로만 명시돼 있고, 이 기술적 플로우(presigned URL 발급 → 클라이언트 직접 업로드 → 서버 확정) 자체는 구현 세부사항
- `DELETE /users/me/profile-image` — 기본 이미지로 초기화하는 엔드포인트도 요구사항에 명시된 케이스는 아님
- **(2026-08-11, `feat/user-nickname-format` 브랜치 — 아직 `dev` 미머지)** `GET /users/nickname-policy` — `GET /auth/password-policy`와 동일한 이유(정책값을 프론트가 하드코딩하지 않고 서버를 유일한 소스로 삼음)로 신설. 회원가입 화면(로그인 전)에서도 필요해 `nickname-check`와 동일하게 `permitAll`

## 남은 이슈 / 확인 필요 총정리

1. "생성형 AI 서비스 사전 고지"가 백엔드에 전혀 없음 — 프론트 전담인지 확인
2. `currentStage` 기반 홈 위젯 우선순위 로직이 백엔드에 없음 — 프론트가 currentStage 값만 받아서 직접 계산하는 구조인지 확인
3. **(2026-08-10 해결됨)** 탈퇴 시 `UserPreference`(관심지역/거래유형/자취여부) 하드 삭제 처리 완료
4. **(2026-08-10 해결됨)** 탈퇴 시 OAuth 연동 정보(하드 삭제) / `Checklist`(하드 삭제) / `Property`(soft-delete 재사용) 처리 완료. `ContractAnalysis`는 stateless라 처리 대상 자체가 없음을 확인
5. **(2026-08-11 해결됨)** 탈퇴 시 쿠키를 지우지 않던 문제 — `SessionLogoutService`(auth 도메인에서 로그아웃 로직을 공용화) 도입 후 `UserController.withdraw()`에서 탈퇴 커밋 뒤 호출하도록 통합 완료. Redis 장애 시의 처리 방식(예외를 삼키고 200 응답)은 `SessionLogoutService` 클래스 주석과 위 표의 "클라이언트 인증정보 제거" 항목 참고
6. **(2026-08-11 해결됨)** "이미 탈퇴한 사용자" 실패 사유가 "존재하지 않는 사용자"와 구분 없이 같은 404로 나가던 문제 해결 — 도달 불가능했던 `User.withdraw()` 내부 가드는 제거하고, 대신 `UserService.getActiveUserOrThrow()`에서 탈퇴(`USER_WITHDRAWN`)/정지(`USER_SUSPENDED`)를 서로 다른 `ErrorCode`로 구분해서 던지도록 변경(상태 코드는 여전히 404로 동일, `code`/`message`만 구분)
7. **(2026-07-31 해결됨, 문서 반영 누락 상태였음)** 프로필 등록 시 "이미 등록됨" 실패가 400으로 처리되던 문제 — 커밋 `9daefca`("User 프로필/닉네임 중복을 409 CONFLICT로 정리")에서 `USER_PROFILE_ALREADY_EXISTS`/`USER_NICKNAME_ALREADY_EXISTS`(둘 다 409)를 신설해 이미 해결되어 있었습니다. `docs/specs/auth-design.md`의 `AUTH_EMAIL_ALREADY_EXISTS`/`AUTH_NICKNAME_ALREADY_EXISTS`(둘 다 409)와 동일한 패턴으로 정리된 것으로, 코드는 맞았는데 이 문서(위 "프로필 등록"/"프로필 수정" 표)만 갱신되지 않고 400으로 잘못 남아있었습니다 — 위 표도 함께 수정했습니다. **(2026-08-12, 전수조사로 재검증됨)** `UserServiceTest`의 여러 테스트가 `assertEquals(HttpStatus.CONFLICT, exception.getStatus())`로 이를 명시적으로 검증하고 있음을 확인 — 아래 "전수조사 결과" 코드 품질 1번 참고
8. **(2026-08-04, `deleteReplacedObject`로 완화됨)** confirm/reset 시 이전 S3 이미지 삭제는 여전히 best-effort지만, 즉시 삭제 전에 `status=pending`으로 다시 태깅해두는 안전망이 추가됨 — 즉시 삭제가 실패해도 버킷 Lifecycle 규칙(만료 기간 1일, AWS 콘솔에서 설정 확인함)이 최대 1일 내로 정리해줌. 태깅 자체와 즉시 삭제가 둘 다 실패하는 이중 실패 케이스만 여전히 영구 고아로 남을 수 있음 — 발생 확률이 낮아 별도 정리 배치까지는 아직 논의 안 됨
9. **닉네임 욕설/금칙어 필터링 미구현 (2026-08-11 방향만 결정, 코드 없음)** — `NicknamePolicy`(그 자체도 `feat/user-nickname-format` 브랜치에만 있고 아직 `dev` 미머지 — 위 "프로필 수정" 표 참고. **(2026-08-12)** "프로필 등록"은 더 이상 닉네임을 받지 않아 대상에서 제외)는 문자 형식(한글/영문/숫자)만 막고 욕설은 못 거른다. 검토한 방향: ① 외부 검열 API/라이브러리는 이 프로젝트 규모엔 과함 → 제외 ② 어근 목록 + 정규화(공백·특수문자 제거, 자모 단독 표기 별도 등록) 방식으로 결정 ③ 목록은 오픈소스 한국어 비속어 리소스(예: `badwords-ko`, `korean-profanity-resources`)에서 가져오되 직접 작성하지 않음 ④ 저장은 DB 관리형 블록리스트 대신 정적 리소스 파일로 시작(수백 개 규모면 조회 성능상 DB가 필수는 아니고, 배포 없이 오탐을 바로 고쳐야 하는 운영 요구가 생기면 그때 DB로 전환) ⑤ 적용 지점은 형식 검증과 동일 — `checkNicknameAvailable`과 `UserService`의 "바뀔 때만 검사" 분기 둘 다. **아직 실제 구현은 시작 안 함**

## 전수조사 결과 (2026-08-12)

### 버그/정확성

1. `UserPreference.interestRegion`에는 애플리케이션 레벨 길이 검증이 전혀 없다. `ProfileRegisterRequest.interestRegion`(`@NotBlank`만 있고 `@Size` 없음, `dto/ProfileRegisterRequest.java:18-19`)과 `ProfileUpdateRequest.interestRegion`(제약 없음, `dto/ProfileUpdateRequest.java:16`) 모두 닉네임과 달리 길이 제약이 없고, 엔티티 컬럼(`entity/UserPreference.java:30`)도 `@Column(length=...)` 지정 없이 Hibernate 기본값(VARCHAR(255))으로 생성된다. 실제 프런트(`ProfileClient.tsx`)는 3단계 select로만 값을 구성해 실무상 짧은 문자열만 만들어지지만, API를 직접 호출하면(Swagger/Postman 등) 255자를 넘는 문자열도 검증 없이 그대로 받아들여져 저장 시점에 DB 컬럼 길이 초과로 500(DataException)이 날 수 있다 — 닉네임처럼 400으로 정리되게 막히는 다른 실패 케이스들과 일관성이 없다. `interestRegion`에도 합리적인 `@Size(max = ...)`를 추가하는 것을 권장.
2. **(신규 발견, 팀 자체 테스트가 이미 증명하고 있음)** `User.changeRole()`/`suspend()`/`activate()`의 "탈퇴한 사용자는 대상에서 제외" 가드(`entity/User.java:116-139`)는 해당 트랜잭션이 그 행을 읽은 시점의 메모리 스냅샷을 기준으로 판단하기 때문에, 사용자 본인의 탈퇴 커밋과 경쟁하면 TOCTOU로 우회될 수 있다. `UserWithdrawFieldOverwriteIntegrationTest.concurrentRoleChangeDoesNotResurrectFieldsAnonymizedByWithdraw`(`src/test/java/com/algogyeyak/user/service/UserWithdrawFieldOverwriteIntegrationTest.java`)가 정확히 이 경쟁 상황(관리자의 `changeRole` 트랜잭션이 탈퇴 전 상태를 먼저 읽고, 탈퇴가 커밋된 뒤에 `changeRole`이 커밋)을 재현하는데, 이 테스트의 목적은 "PII 필드가 되살아나지 않는다"를 확인하는 것이지만 마지막 assertion에서 `assertThat(persisted.getRole()).isEqualTo(Role.ADMIN)`을 그대로 "정상 동작"으로 단언한다. 즉 결과적으로 **WITHDRAWN 상태이면서 Role=ADMIN인 계정**이 DB에 남을 수 있다는 사실을 테스트 스스로 증명하면서도, 그 함의는 별도로 짚어지지 않은 것으로 보인다. `changeNickname()`이 동일한 성격의 레이스에 대해 REQUIRES_NEW 재확인(최신 커밋 상태 재조회)을 두는 것과 달리, `changeRole`/`suspend`/`activate`에는 그런 안전망이 없다. 코드 주석의 판단은 "탈퇴 계정은 로그인 자체가 안 되니 role이 잘못 남아도 무해하다"는 것인데, 위 "회원 탈퇴" 표에서 확인했듯 `CustomOAuth2UserService.rejectIfBlocked()`가 탈퇴/정지 계정의 OAuth 재로그인을 이미 차단하고 있어 이 경로로 ADMIN 권한이 악용될 가능성은 낮다고 판단됨 — 다만 로컬 로그인/refresh 경로까지 전부 재확인한 것은 아니라 auth 담당자와 함께 한 번 더 확인 권장.

### 보안

1. `confirmProfileImageUpload`가 호출하는 `S3KeyGenerator.isProfileImageOwnedBy(userId, key)`(`global/s3/util/S3KeyGenerator.java:24`)를 직접 확인했다. prefix 검사에 구분자 `/`까지 포함시켜(`"profile-images/" + userId + "/"`) 비교하므로, userId=1과 userId=12처럼 숫자 표현이 서로의 접두어가 되는 경우에도 다른 사용자의 key를 자기 것으로 오인하지 않는다 — 문서가 주장하는 IDOR 방어가 실제로 유효함을 코드 레벨에서 재확인했다.
2. 그 외 이번 조사에서 새로 발견된 IDOR/권한 우회는 없다. `UserController`의 `/users/me*` 엔드포인트 전부가 예외 없이 `@AuthenticationPrincipal`의 `userId`만 사용하고, 경로/바디 어디에도 대상 사용자를 지정하는 파라미터가 없어 다른 사용자 리소스를 겨냥할 방법 자체가 없음을 코드로 재확인했다(기존 문서의 판단과 일치).

### 코드 품질 (중복/구조/일관성)

1. **문서-코드 불일치 정정**: 이 문서의 "프로필 등록"/"프로필 수정" 표와 "남은 이슈" 7번은 "실패: 중복된 닉네임"과 "이미 프로필이 등록된 경우"를 모두 **400**으로 서술하고 있었으나(이후 회원탈퇴 작업 중 위 표는 이미 409로 정정됨), 실제 `ErrorCode.USER_NICKNAME_ALREADY_EXISTS`/`USER_PROFILE_ALREADY_EXISTS`(`global/error/ErrorCode.java:52-53`)는 `HttpStatus.CONFLICT`(**409**)로 정의되어 있고, `UserServiceTest`(`registerProfileThrowsWhenProfileAlreadyExists`/`registerProfileThrowsWhenNicknameAlreadyExists`/`updateMyProfileThrowsWhenNicknameAlreadyExists`/`updateMyProfileRecoversWithNicknameConflictWhenConcurrentChangeWinsTheRace`, 각각 `assertEquals(HttpStatus.CONFLICT, exception.getStatus())`)도 이를 명시적으로 검증한다. **(2026-08-12 추가 변경)** `registerProfileThrowsWhenNicknameAlreadyExists`는 프로필 등록에서 닉네임 처리 자체가 제거되면서 함께 삭제됨 — 아래 2번 참고.
2. ~~`registerProfile()`(`service/UserService.java:80-85`)과 `updateMyProfile()`(`service/UserService.java:177-180`)이 "닉네임이 실제로 바뀌는지"를 판단하는 조건(`StringUtils.hasText(request.getNickname()) && !request.getNickname().equals(user.getNickname())`)을 각자 인라인으로 거의 동일하게 중복 구현하고 있다.~~ **(2026-08-12 해결됨)** 프로필 등록 화면에는 애초에 닉네임 입력 UI가 없었고(2026-07-31 프론트에서 숨김 처리), 백엔드의 `registerProfile()` 닉네임 분기는 실제로 호출될 수 없는 죽은 코드였음이 확인되어 제거했다(`registerProfile()`은 더 이상 닉네임을 다루지 않음). 이 판단 조건은 이제 `updateMyProfile()`에만 남아 중복이 아니다.
3. `getActiveUserOrThrow()`가 던지는 "존재하지 않거나 탈퇴한 사용자입니다." 메시지 문자열이 `UserService.java` 안에 2곳(`getActiveUserOrThrow`, `changeNickname`)에서 각각 리터럴로 반복돼 있다. **(2026-08-12)** 이전에는 `registerProfileAtomically`도 이 메시지를 갖고 있어 3곳이었으나, 닉네임 처리 제거와 함께 해당 메서드가 `savePreferenceOrThrowIfAlreadyRegistered()`로 단순화되며 2곳으로 줄었다. 상수로 추출하면 이후 문구를 바꿀 때 일부만 놓치는 실수를 줄일 수 있다(기능적 문제는 아님).
